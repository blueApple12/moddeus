package dev.moddeus.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.moddeus.Moddeus;
import dev.moddeus.live.OnReload;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;

/**
 * Local HTTP API that lets Claude Code see and act in the running world (used by `node tools/mc.mjs game ...`).
 * POST /<endpoint>?t=token with a JSON body. Port and token are in run/moddeus/game.json. Localhost only.
 * Endpoints: context, command, blocks, screenshot, say. All logic is in static methods, so it hot-swaps.
 */
public final class GameApi {
	public static volatile boolean capturing;

	@OnReload
	public static void start() {
		if (System.getProperties().putIfAbsent("moddeus.gameapi", Boolean.TRUE) != null) return;
		try {
			String token = HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(16));
			HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			http.createContext("/", ex -> {
				byte[] out;
				int code = 200;
				try {
					String q = ex.getRequestURI().getQuery();
					if (q == null || !q.contains("t=" + token)) { ex.sendResponseHeaders(403, -1); ex.close(); return; }
					String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
					JsonObject in = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
					out = handle(ex.getRequestURI().getPath().substring(1), in).toString().getBytes(StandardCharsets.UTF_8);
				} catch (Throwable t) {
					code = 500;
					JsonObject e = new JsonObject();
					e.addProperty("error", String.valueOf(t));
					out = e.toString().getBytes(StandardCharsets.UTF_8);
				}
				ex.getResponseHeaders().add("content-type", "application/json");
				ex.sendResponseHeaders(code, out.length);
				ex.getResponseBody().write(out);
				ex.close();
			});
			http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "Moddeus-GameApi"); t.setDaemon(true); return t; }));
			Thread st = new Thread(http::start); // its dispatcher thread inherits daemon: a non-daemon one kept the JVM alive after quitting (shutdown watchdog crash)
			st.setDaemon(true);
			st.start();
			st.join();
			if (server != null) try { server.stop(0); } catch (Throwable ignored) {} // reclaim: drop the old server
			server = http;
			Path dir = root().resolve("run/moddeus");
			Files.createDirectories(dir);
			JsonObject info = new JsonObject();
			info.addProperty("port", http.getAddress().getPort());
			info.addProperty("token", token);
			Files.writeString(dir.resolve("game.json"), info.toString());
			ownerOnly(dir.resolve("game.json"));
			Moddeus.LOG.info("Game API on port {}", http.getAddress().getPort());
			FastRun.warm();
			Thread sb = new Thread(SourceBuild::baseline, "Moddeus-SourceBuild-baseline");
			sb.setDaemon(true);
			sb.start();
		} catch (Throwable t) {
			Moddeus.LOG.error("Game API failed to start", t);
		}
	}

	static HttpServer server;

	/** The token lets anyone run code in the game: on Linux/macOS make the file owner-only (Windows profiles are already private). */
	static void ownerOnly(Path f) {
		try { Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | java.io.IOException ignored) {}
	}

	/** Two game copies running: the one with a world loaded takes over game.json (new server, new token). */
	@OnReload
	public static void reclaim() {
		if (net.minecraft.client.Minecraft.getInstance().level == null) return;
		try { // already ours? (a one-shot guard here left a world-less duplicate owning game.json)
			var j = JsonParser.parseString(Files.readString(root().resolve("run/moddeus/game.json"))).getAsJsonObject();
			if (server != null && j.get("port").getAsInt() == server.getAddress().getPort()) return;
		} catch (Exception ignored) {}
		System.getProperties().remove("moddeus.gameapi");
		start();
	}

	public static Path projectDir() { return root(); }

	static Path root() { return Path.of(System.getProperty("moddeus.project", "..")).toAbsolutePath(); }

	static JsonElement handle(String path, JsonObject in) throws Exception {
		return switch (path) {
			case "context" -> onServer(GameApi::context);
			case "command" -> {
				if (in.toString().contains("reload")) dev.moddeus.live.DataReload.prepare(server()); // file scan off the server thread
				yield onServer(() -> commands(in));
			}
			case "blocks" -> onServer(() -> blocks(in));
			case "screenshot" -> screenshot(in.has("ui") && in.get("ui").getAsBoolean(), in.has("pov") && in.get("pov").getAsBoolean());
			case "buddy", "orb" -> onClient(() -> new com.google.gson.JsonPrimitive(dev.moddeus.client.content.ClaudeOrb.api(in)));
			case "lag" -> onClient(dev.moddeus.client.LagGuard::report);
			case "quick" -> quick(in);
			case "run" -> run(in);
			case "build" -> dev.moddeus.client.HotReloader.build(!in.has("java") || in.get("java").getAsBoolean()); // incremental in-game build, swapped on the reloader thread
			case "say" -> onServer(() -> {
				player().sendSystemMessage(Component.literal("§d[Moddeus]§r " + in.get("text").getAsString()));
				return ok();
			});
			default -> { JsonObject e = new JsonObject(); e.addProperty("error", "unknown endpoint " + path + " (context, command, blocks, screenshot, say)"); yield e; }
		};
	}

	/** Instant prebuilt action (dev.moddeus.live.Quick), no compile. */
	static JsonElement quick(JsonObject in) throws Exception {
		String action = in.has("action") ? in.get("action").getAsString() : "";
		if (action.isEmpty()) return new com.google.gson.JsonPrimitive(dev.moddeus.live.Quick.HELP);
		List<String> args = new ArrayList<>();
		if (in.has("args")) for (JsonElement e : in.getAsJsonArray("args")) args.add(e.getAsString());
		return onServer(() -> new com.google.gson.JsonPrimitive(dev.moddeus.live.Quick.run(action, args, player())));
	}

	/** Java snippet compiled in memory (FastRun) and run on the server thread: no Gradle, no hotswap. */
	static JsonElement run(JsonObject in) throws Exception {
		Class<?> c = FastRun.compile(in.get("name").getAsString(), in.get("src").getAsString());
		java.lang.reflect.Method m = c.getDeclaredMethod("run");
		return onServer(() -> new com.google.gson.JsonPrimitive(String.valueOf(m.invoke(null))));
	}

	interface Work { JsonElement run() throws Exception; }

	static JsonElement onClient(Work w) throws Exception {
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> { try { f.complete(w.run()); } catch (Throwable t) { f.completeExceptionally(t); } });
		return f.get(10, TimeUnit.SECONDS);
	}

	static JsonElement onServer(Work w) throws Exception {
		MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
		if (s == null) throw new IllegalStateException("no world loaded");
		CompletableFuture<JsonElement> f = new CompletableFuture<>();
		s.execute(() -> { try { f.complete(w.run()); } catch (Throwable t) { f.completeExceptionally(t); } });
		return f.get(20, TimeUnit.SECONDS);
	}

	static MinecraftServer server() { return Minecraft.getInstance().getSingleplayerServer(); }

	static ServerPlayer player() {
		List<ServerPlayer> ps = server().getPlayerList().getPlayers();
		if (ps.isEmpty()) throw new IllegalStateException("no player");
		return ps.get(0);
	}

	static JsonObject ok() { JsonObject o = new JsonObject(); o.addProperty("ok", true); return o; }

	static String id(Object registryValue) {
		if (registryValue instanceof net.minecraft.world.level.block.Block b) return BuiltInRegistries.BLOCK.getKey(b).toString();
		if (registryValue instanceof net.minecraft.world.item.Item i) return BuiltInRegistries.ITEM.getKey(i).toString();
		if (registryValue instanceof net.minecraft.world.entity.EntityType<?> t) return BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
		return String.valueOf(registryValue);
	}

	static String stack(ItemStack s) {
		String r = s.getCount() + "x " + id(s.getItem());
		if (s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) r += " \"" + s.getHoverName().getString() + "\"";
		if (s.isEnchanted()) r += " (enchanted)";
		return r;
	}

	static JsonElement context() {
		ServerPlayer p = player();
		ServerLevel level = (ServerLevel) p.level();
		JsonObject o = new JsonObject();
		o.addProperty("player", p.getName().getString());
		o.addProperty("pos", String.format("%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()));
		o.addProperty("block_pos", p.blockPosition().toShortString());
		o.addProperty("facing", p.getDirection().toString() + String.format(" (yaw %.0f, pitch %.0f)", p.getYRot(), p.getXRot()));
		o.addProperty("dimension", level.dimension().toString());
		o.addProperty("gamemode", p.gameMode.getGameModeForPlayer().toString());
		o.addProperty("health", p.getHealth() + "/" + p.getMaxHealth());
		o.addProperty("food", p.getFoodData().getFoodLevel());
		try { o.addProperty("biome", level.getBiome(p.blockPosition()).unwrapKey().map(Object::toString).orElse("?")); } catch (Throwable ignored) {}
		o.addProperty("time", level.getOverworldClockTime() % 24000);
		o.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
		o.addProperty("main_hand", stack(p.getMainHandItem()));

		JsonArray inv = new JsonArray();
		var items = p.getInventory();
		for (int i = 0; i < items.getContainerSize(); i++) {
			ItemStack s = items.getItem(i);
			if (!s.isEmpty()) inv.add(i + ": " + stack(s));
		}
		o.add("inventory", inv);

		HitResult hr = Minecraft.getInstance().hitResult;
		if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
			BlockState st = level.getBlockState(bh.getBlockPos());
			o.addProperty("looking_at_block", st.toString().replace("Block{", "").replace("}", "") + " @ " + bh.getBlockPos().toShortString() + " face " + bh.getDirection());
		} else if (hr instanceof EntityHitResult eh) {
			o.addProperty("looking_at_entity", id(eh.getEntity().getType()) + " " + eh.getEntity().getUUID());
		}

		JsonArray near = new JsonArray();
		for (Entity e : level.getEntities(p, new AABB(p.blockPosition()).inflate(32), e -> true)) {
			if (near.size() >= 40) break;
			String n = id(e.getType()) + " @ " + e.blockPosition().toShortString() + String.format(" (%.0fm)", e.distanceTo(p));
			if (e.hasCustomName()) n += " \"" + e.getCustomName().getString() + "\"";
			near.add(n);
		}
		o.add("nearby_entities", near);
		return o;
	}

	/** Runs commands as the player's position/rotation with full permissions; returns what they printed. */
	static JsonElement commands(JsonObject in) {
		List<String> cmds = new ArrayList<>();
		if (in.has("commands")) for (JsonElement e : in.getAsJsonArray("commands")) cmds.add(e.getAsString());
		if (in.has("command")) cmds.add(in.get("command").getAsString());
		ServerPlayer p = player();
		JsonArray results = new JsonArray();
		for (String c : cmds) {
			List<String> lines = new ArrayList<>();
			CommandSource capture = new CommandSource() {
				@Override public void sendSystemMessage(Component m) { lines.add(m.getString()); }
				@Override public boolean acceptsSuccess() { return true; }
				@Override public boolean acceptsFailure() { return true; }
				@Override public boolean shouldInformAdmins() { return false; }
			};
			CommandSourceStack src = server().createCommandSourceStack().withSource(capture).withEntity(p).withLevel((ServerLevel) p.level())
				.withPosition(p.position()).withRotation(new Vec2(p.getXRot(), p.getYRot()));
			String cmd = c.startsWith("/") ? c.substring(1) : c;
			try {
				if (cmd.strip().equals("reload")) lines.add(dev.moddeus.live.DataReload.reload(server())); // instant when only functions changed
				else server().getCommands().performPrefixedCommand(src, cmd);
			} catch (Throwable t) {
				lines.add("ERROR " + t);
			}
			JsonObject r = new JsonObject();
			r.addProperty("command", cmd);
			r.addProperty("output", String.join("\n", lines));
			results.add(r);
		}
		return results;
	}

	/** Block palette and (optionally) every non-air block in a box. Max 64^3. */
	static JsonElement blocks(JsonObject in) {
		ServerPlayer p = player();
		ServerLevel level = (ServerLevel) p.level();
		JsonArray a = in.getAsJsonArray("from"), b = in.getAsJsonArray("to");
		BlockPos from = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
		BlockPos to = new BlockPos(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt());
		long vol = (long) (Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getY() - to.getY()) + 1) * (Math.abs(from.getZ() - to.getZ()) + 1);
		if (vol > 262144) throw new IllegalArgumentException("box too big (" + vol + " blocks, max 64^3)");
		boolean list = !in.has("list") || in.get("list").getAsBoolean();
		Map<String, Integer> counts = new TreeMap<>();
		JsonArray out = new JsonArray();
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			BlockState st = level.getBlockState(pos);
			if (st.isAir()) continue;
			String s = st.toString().replace("Block{", "").replace("}", "");
			counts.merge(s, 1, Integer::sum);
			if (list && out.size() < 4000) out.add(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + s);
		}
		JsonObject o = new JsonObject();
		o.addProperty("volume", vol);
		JsonObject c = new JsonObject();
		counts.forEach(c::addProperty);
		o.add("counts", c);
		if (list) o.add("blocks", out);
		return o;
	}

	/** What the player sees (the Claude screen is hidden for the shot). Saved under run/moddeus/shots. */
	static JsonElement screenshot(boolean ui) throws Exception { return screenshot(ui, false); }

	/** pov is gone with the avatar body: same as a normal shot. */
	static JsonElement screenshot(boolean ui, boolean pov) throws Exception { return capture(ui); }

	static JsonElement capture(boolean ui) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		Path dir = root().resolve("run/moddeus/shots");
		Files.createDirectories(dir);
		Path file = dir.resolve("shot-" + System.currentTimeMillis() + ".png");
		capturing = !ui;
		try {
			Thread.sleep(250); // a few frames without our UI
			CompletableFuture<Void> done = new CompletableFuture<>();
			mc.execute(() -> Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> {
				ShotWriter.save(img, file, done); // PNG encode off the render thread (~200 ms freeze per shot before)
			}));
			done.get(10, TimeUnit.SECONDS);
		} finally {
			capturing = false;
		}
		JsonObject o = new JsonObject();
		o.addProperty("path", file.toString());
		return o;
	}
}
