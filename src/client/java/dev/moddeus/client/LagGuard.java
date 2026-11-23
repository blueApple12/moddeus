package dev.moddeus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.moddeus.Moddeus;
import dev.moddeus.live.LagStats;
import dev.moddeus.live.OnReload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;

/**
 * Lag guard: the game must never lag because of something Claude built. Every server tick is timed; datapack tick
 * functions are run (and timed) one by one; a watchdog samples the server and render threads during slow ticks/frames
 * to see which mod's code is running. If the game stays slow (server > 60 ms per tick, or < 25 FPS, for 3 s) and one
 * mod or datapack causes most of it, that mod is switched OFF (same as G > Mods > Off), the player is told, and
 * run/moddeus/lag.json records why. Never switched back on automatically. node tools/mc.mjs game lag = live report.
 */
public final class LagGuard {
	static boolean enabled = true;
	static volatile Thread serverThread;
	static volatile long tickStart; // 0 = not in a tick
	static List<CommandFunction<CommandSourceStack>> tickFns;
	static List<Object> marker; // our empty list in ServerFunctionManager.ticking (lazy: a static added by a swap starts null)
	public static List<Object> marker() { if (marker == null) marker = java.util.Collections.unmodifiableList(new ArrayList<>()); return marker; }
	static Map<String, Long> javaSamples, renderSamples; // culprit -> sample count (5 ms each)
	static long windowStart, windowTicks, windowNanos, maxTick;
	static int slowSeconds, lowFpsSeconds;
	public static volatile long ignoreUntil; // hot-swap pauses and reloads are ours, not a mod's

	public static void quiet(long ms) { ignoreUntil = Math.max(ignoreUntil, System.currentTimeMillis() + ms); }

	static void reloaded(MinecraftServer s, net.minecraft.server.packs.resources.CloseableResourceManager rm, boolean ok) { quiet(10_000); }
	static String last = "";
	static JsonObject lastWindow = new JsonObject();

	static Map<String, Long> js() { if (javaSamples == null) javaSamples = new java.util.concurrent.ConcurrentHashMap<>(); return javaSamples; }
	static Map<String, Long> rs() { if (renderSamples == null) renderSamples = new java.util.concurrent.ConcurrentHashMap<>(); return renderSamples; }

	// ---------------------------------------------------------------- server tick timing

	public static void start(MinecraftServer s) {
		serverThread = Thread.currentThread();
		tickStart = System.nanoTime();
		if (!enabled) return;
		// take over the datapack tick functions so each one is timed (vanilla re-fills the list on /reload)
		try {
			ServerFunctionManager fm = s.getFunctions();
			var f = ServerFunctionManager.class.getDeclaredField("ticking");
			f.setAccessible(true);
			// vanilla re-assigns 'ticking' on every /reload (immutable list): take whatever it set, leave our empty marker
			Object cur = f.get(fm);
			if (cur != marker()) {
				@SuppressWarnings("unchecked") List<CommandFunction<CommandSourceStack>> ticking = (List<CommandFunction<CommandSourceStack>>) cur;
				tickFns = new ArrayList<>(ticking);
				f.set(fm, marker());
			}
			if (tickFns != null) for (var fn : tickFns) {
				long t = System.nanoTime();
				fm.execute(fn, fm.getGameLoopSender());
				if (!LagStats.mixin) LagStats.add(fn.id().toString(), System.nanoTime() - t);
			}
		} catch (Throwable t) {
			if (last.isEmpty()) Moddeus.LOG.warn("lag guard: tick functions", t);
			last = "tick functions: " + t;
		}
	}

	public static void end(MinecraftServer s) {
		long now = System.nanoTime(), dt = now - tickStart;
		tickStart = 0;
		windowTicks++;
		windowNanos += dt;
		maxTick = Math.max(maxTick, dt);
		if (windowStart == 0) windowStart = now;
		if (now - windowStart < 1_000_000_000L) return;
		try { evaluate(s, now); } catch (Throwable t) { Moddeus.LOG.warn("lag guard", t); }
		windowStart = now; windowTicks = 0; windowNanos = 0; maxTick = 0;
		LagStats.fn().clear(); js().clear(); rs().clear();
	}

	/** Once a second: who used the time, and act on sustained lag. */
	static void evaluate(MinecraftServer s, long now) {
		double secs = (now - windowStart) / 1e9;
		double mspt = windowTicks == 0 ? 0 : windowNanos / 1e6 / windowTicks;
		Map<String, Double> cost = new HashMap<>(); // culprit (mod id / datapack / vanilla) -> ms per second
		Map<String, Long> fns = new HashMap<>(LagStats.fn());
		Map<String, String> packOf = packsByNamespace(s);
		for (var e : fns.entrySet()) {
			String ns = e.getKey().contains(":") ? e.getKey().substring(0, e.getKey().indexOf(':')) : e.getKey();
			cost.merge(packOf.getOrDefault(ns, "datapack " + ns), e.getValue() / 1e6 / secs, Double::sum);
		}
		for (var e : js().entrySet()) cost.merge(e.getKey(), e.getValue() * 5.0 / secs, Double::sum);
		int fps = Minecraft.getInstance().getFps();
		JsonObject w = new JsonObject();
		w.addProperty("mspt", Math.round(mspt * 10) / 10.0);
		w.addProperty("maxTickMs", maxTick / 1_000_000);
		w.addProperty("fps", fps);
		JsonArray top = new JsonArray();
		cost.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(6).forEach(e -> {
			JsonObject o = new JsonObject(); o.addProperty("what", e.getKey()); o.addProperty("msPerSecond", Math.round(e.getValue())); top.add(o);
		});
		w.add("server", top);
		JsonArray rtop = new JsonArray();
		rs().entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(4).forEach(e -> {
			JsonObject o = new JsonObject(); o.addProperty("what", e.getKey()); o.addProperty("msPerSecond", e.getValue() * 5); rtop.add(o);
		});
		w.add("render", rtop);
		JsonArray fnTop = new JsonArray();
		fns.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(5).forEach(e -> {
			JsonObject o = new JsonObject(); o.addProperty("function", e.getKey()); o.addProperty("msPerSecond", Math.round(e.getValue() / 1e6 / secs)); fnTop.add(o);
		});
		w.add("functions", fnTop);
		lastWindow = w;

		boolean swapping = System.currentTimeMillis() < ignoreUntil || s.tickRateManager().isFrozen();
		slowSeconds = !swapping && mspt > 60 ? slowSeconds + 1 : 0;
		long renderTotal = rs().values().stream().mapToLong(Long::longValue).sum();
		lowFpsSeconds = !swapping && fps > 0 && fps < 25 && renderTotal > 0 ? lowFpsSeconds + 1 : 0;
		if (slowSeconds >= 5) {
			var worst = cost.entrySet().stream().filter(e -> !e.getKey().equals("vanilla")).max(Map.Entry.comparingByValue()).orElse(null);
			if (worst != null && worst.getValue() > 0.4 * mspt * windowTicks / secs) { switchOff(worst.getKey(), "the server ran at " + Math.round(mspt) + " ms per tick (normal is under 50)"); slowSeconds = 0; }
		}
		if (lowFpsSeconds >= 5) {
			var worst = rs().entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
			if (worst != null && worst.getValue() > 0.4 * renderTotal) { switchOff(worst.getKey(), "the game ran at " + fps + " FPS"); lowFpsSeconds = 0; }
		}
	}

	/** Datapack namespace -> its mod project id (folder <Id>-DP), from the world's datapacks folder. */
	static Map<String, String> nsCache;
	static long nsAt;
	static Map<String, String> packsByNamespace(MinecraftServer s) {
		if (nsCache != null && System.currentTimeMillis() - nsAt < 10_000) return nsCache;
		Map<String, String> m = new HashMap<>();
		try (Stream<Path> packs = Files.list(s.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR))) {
			for (Path p : packs.toList()) {
				String id = p.getFileName().toString().replaceAll("-(DP|dp)$", "");
				try (Stream<Path> ns = Files.list(p.resolve("data"))) { for (Path n : ns.toList()) if (!n.getFileName().toString().equals("minecraft")) m.put(n.getFileName().toString(), id); }
				catch (Exception ignored) {}
			}
		} catch (Exception ignored) {}
		nsCache = m; nsAt = System.currentTimeMillis();
		return m;
	}

	static Map<String, Long> reportedAt;
	static Map<String, Long> frames; // "Mod  Class.method:line" -> samples (where a Java mod spends the time)

	/** Never switches anything off: tells the mod's own chat what lagged, the numbers, and how to fix it. */
	static void switchOff(String culprit, String why) {
		if (reportedAt == null) reportedAt = new java.util.concurrent.ConcurrentHashMap<>();
		Long prev = reportedAt.get(culprit);
		if (prev != null && System.currentTimeMillis() - prev < 180_000) return; // once per 3 min per mod
		reportedAt.put(culprit, System.currentTimeMillis());
		boolean dp = culprit.startsWith("datapack ");
		String id = dp ? culprit.substring(9) : culprit;
		boolean isDatapack = dp || !Projects.datapacks(id).isEmpty();
		StringBuilder costs = new StringBuilder();
		for (var e : lastWindow.getAsJsonArray("functions")) { var o = e.getAsJsonObject(); costs.append(o.get("function").getAsString()).append(" ").append(o.get("msPerSecond").getAsInt()).append(" ms/s; "); }
		if (frames != null) frames.entrySet().stream().filter(e -> e.getKey().startsWith(id + " ")).sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(4)
			.forEach(e -> costs.append(e.getKey().substring(id.length() + 1)).append(" ~").append(e.getValue() * 5).append(" ms; "));
		String fix = isDatapack
			? "Tick functions run 20x per second: do not run execute as/at @e (or every block) each tick, filter early with tags + limit, keep raycast/recursion loops short (the 65536-command limit means a runaway loop), spread heavy work over ticks (schedule function, or a scoreboard timer that runs it every Nth tick), and summon/particle less per tick."
			: "Move heavy work off the per-tick/per-frame path, cache lookups, no world-wide entity or block scans each tick, cap particles and spawned entities, and do expensive math once per ability use instead of every tick.";
		String text = "[Lag guard, automatic message] Your mod " + id + " is making the game lag: " + why + " for 5+ seconds. "
			+ "Where the time goes: " + (costs.length() == 0 ? "(no single function stood out)" : costs) + "What to do: " + fix
			+ " Fix it now without removing or weakening any ability, then check with node tools/mc.mjs game lag (this mod should stay under ~5 ms per tick) and tell the player in one line what was wrong.";
		Moddeus.LOG.warn("Lag guard: {} lagging ({}), sent to its chat", id, why);
		try {
			JsonObject rec = lastWindow.deepCopy();
			rec.addProperty("mod", id);
			rec.addProperty("why", why);
			rec.addProperty("at", java.time.LocalDateTime.now().toString());
			Files.writeString(Projects.root().toPath().resolve("run/moddeus/lag.json"), rec.toString());
		} catch (Exception ignored) {}
		Minecraft.getInstance().execute(() -> {
			HotReloader.say("§6⚠ Lag§r: §e" + id + "§r slowed the game (" + why + "). Sent what happened + how to fix it to its chat.");
			dev.moddeus.client.ui.Bridge.send("forward", "project", id, "text", text, "quiet", true);
		});
	}

	// ---------------------------------------------------------------- watchdog: who is running during slow ticks/frames

	static void watch() {
		Thread render = null;
		while (true) {
			try {
				Thread.sleep(5);
				if (!enabled) continue;
				long ts = tickStart;
				Thread st = serverThread;
				if (st != null && ts != 0 && System.nanoTime() - ts > 30_000_000L) {
					String who = culprit(st.getStackTrace());
					if (who != null) js().merge(who, 1L, Long::sum);
				}
				if (render == null) for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("Render thread")) render = t;
				int fps = Minecraft.getInstance().getFps();
				if (render != null && fps > 0 && fps < 40) {
					String who = culprit(render.getStackTrace());
					if (who != null && !who.equals("vanilla")) rs().merge(who, 1L, Long::sum);
				}
			} catch (Throwable ignored) {}
		}
	}

	/** First Moddeus content class on the stack -> its mod (by class prefix); datapack functions; else vanilla. */
	static String culprit(StackTraceElement[] st) {
		boolean fn = false;
		for (StackTraceElement e : st) {
			String c = e.getClassName();
			if (c.startsWith("dev.moddeus.content.") || c.startsWith("dev.moddeus.client.content.")) {
				String simple = c.substring(c.lastIndexOf('.') + 1).replaceAll("\\$.*", "");
				for (var p : Projects.state().keySet()) if (simple.startsWith(p)) {
					if (frames == null) frames = new java.util.concurrent.ConcurrentHashMap<>();
					frames.merge(p + " " + simple.replaceAll("__g\\d+", "") + "." + e.getMethodName() + ":" + e.getLineNumber(), 1L, Long::sum);
					return p;
				}
				return null; // core system (Vfx, Physics...): not a mod to switch off
			}
			if (c.startsWith("dev.moddeus.client.LagGuard")) fn = true; // inside our timed tick-function loop
			if (c.contains("ServerFunctionManager") || c.contains("commands.execution")) fn = true;
		}
		return fn ? null : "vanilla"; // function time is measured exactly (LagStats), not sampled
	}

	/** Live report for node tools/mc.mjs game lag. */
	public static JsonObject report() {
		JsonObject o = lastWindow.deepCopy();
		o.addProperty("guard", enabled ? "on" : "off");
		o.addProperty("swaps", HotReloader.fastSwaps + " instant swaps, " + HotReloader.fullSwaps + " full");
		try { o.addProperty("lastAction", Files.readString(Projects.root().toPath().resolve("run/moddeus/lag.json"))); } catch (Exception ignored) {}
		return o;
	}

	@dev.moddeus.live.OnUnload
	public static void unload() {
		enabled = false;
		restoreTicking();
	}

	/** Hand the tick functions back to vanilla (guard off). */
	static void restoreTicking() {
		var sp = Minecraft.getInstance().getSingleplayerServer();
		if (sp == null || tickFns == null) return;
		sp.execute(() -> { try { var f = ServerFunctionManager.class.getDeclaredField("ticking"); f.setAccessible(true); if (f.get(sp.getFunctions()) == marker()) f.set(sp.getFunctions(), List.copyOf(tickFns)); } catch (Throwable ignored) {} });
	}

	@OnReload
	public static void hook() {
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.lagguard.reload", Boolean.TRUE) == null) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((s, rm) -> quiet(15_000));
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(LagGuard::reloaded);
		}
		if (System.getProperties().putIfAbsent("moddeus.lagguard", Boolean.TRUE) != null) return;
		ServerTickEvents.START_SERVER_TICK.register(LagGuard::start);
		ServerTickEvents.END_SERVER_TICK.register(LagGuard::end);
		Thread t = new Thread(LagGuard::watch, "Moddeus-LagGuard");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}
}
