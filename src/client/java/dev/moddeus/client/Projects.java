package dev.moddeus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moddeus.Moddeus;
import dev.moddeus.client.ui.Bridge;
import dev.moddeus.live.OnReload;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * Mod projects (G screen sidebar): a mod = Java classes named <Id>*.java in content/ or client/content/, plus packs named
 * <Id>-DP (world datapacks/) and <Id>-RP (run/resourcepacks/). On/off runs the classes' @OnUnload / @OnReload live, runs
 * the datapack's <ns>:uninstall (or <ns>:off) then /datapack disable (enable re-runs its load), and toggles the resource pack; off survives swaps and restarts (run/moddeus/projects.json, owned by the bridge).
 * Undo / delete run node tools/mc.mjs undo|remove <Id> in the background. Delete removes everything the mod owns (Java, content, assets/data
 * of its namespace, packs, shared lang/sounds keys: tools/share.mjs owned) and its project entry.
 */
public final class Projects {
	static Map<String, Boolean> enabled; // id -> on
	static long readAt;

	static File root() { return new File(System.getProperty("moddeus.project", new File(System.getProperty("user.dir")).getParent())); }

	/** Display name of a mod project (projects.json), or the id. */
	public static String name(String id) {
		try {
			for (var e : com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root().toPath().resolve("run/moddeus/projects.json"))).getAsJsonArray()) {
				var o = e.getAsJsonObject();
				if (o.get("id").getAsString().equals(id) && o.has("name")) return o.get("name").getAsString();
			}
		} catch (Exception ignored) {}
		return id;
	}

	static Map<String, Boolean> state() {
		File f = new File(root(), "run/moddeus/projects.json");
		if (enabled == null || f.lastModified() != readAt) {
			Map<String, Boolean> m = new HashMap<>();
			try {
				for (JsonElement e : JsonParser.parseString(Files.readString(f.toPath())).getAsJsonArray()) {
					JsonObject o = e.getAsJsonObject();
					m.put(o.get("id").getAsString(), !o.has("enabled") || o.get("enabled").getAsBoolean());
				}
			} catch (Exception ignored) {}
			enabled = m;
			readAt = f.lastModified();
		}
		return enabled;
	}

	/** Is this class (simple name) part of a switched-off mod? */
	public static boolean disabled(String simpleName) {
		try {
			for (var e : state().entrySet()) if (!e.getValue() && simpleName.startsWith(e.getKey())) return true;
		} catch (Exception ignored) {}
		return false;
	}

	public static JsonArray list() {
		JsonObject m = Bridge.meta;
		return m != null && m.has("projects") ? m.getAsJsonArray("projects") : new JsonArray();
	}

	/** Top-level classes of a mod, from the build output. */
	static List<String> classes(String id) {
		List<String> out = new ArrayList<>();
		for (String d : new String[] {"build/classes/java/main", "build/classes/java/client"}) {
			for (String pkg : new String[] {"dev/moddeus/content", "dev/moddeus/client/content"}) {
				Path dir = root().toPath().resolve(d).resolve(pkg);
				if (!Files.isDirectory(dir)) continue;
				try (Stream<Path> s = Files.list(dir)) {
					s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(id) && n.endsWith(".class") && !n.contains("$"))
						.forEach(n -> out.add(pkg.replace('/', '.') + "." + n.substring(0, n.length() - 6)));
				}
				catch (Exception ignored) {}
			}
		}
		return out;
	}

	static boolean ownPack(String folder, String id) { return folder.equals(id) || folder.startsWith(id + "-"); }

	/** The mod's datapack folders in the open world (<Id>-DP). */
	static List<Path> datapacks(String id) {
		List<Path> out = new ArrayList<>();
		var sp = Minecraft.getInstance().getSingleplayerServer();
		if (sp == null) return out;
		try (Stream<Path> s = Files.list(sp.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR))) {
			s.filter(p -> Files.isDirectory(p) && ownPack(p.getFileName().toString(), id)).forEach(out::add);
		} catch (Exception ignored) {}
		return out;
	}

	/** The mod's resource pack folders (run/resourcepacks/<Id>-RP). */
	static List<Path> resourcepacks(String id) {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(root().toPath().resolve("run/resourcepacks"))) {
			s.filter(p -> Files.isDirectory(p) && ownPack(p.getFileName().toString(), id)).forEach(out::add);
		} catch (Exception ignored) {}
		return out;
	}

	static Map<String, String> kindCache;
	static long kindAt;
	/** "java dp rp" style label for the sidebar (cached 2 s). */
	public static String kinds(String id) {
		if (kindCache == null || System.currentTimeMillis() - kindAt > 2000) { kindCache = new HashMap<>(); kindAt = System.currentTimeMillis(); }
		return kindCache.computeIfAbsent(id, k -> ((classes(k).isEmpty() ? "" : "java ") + (datapacks(k).isEmpty() ? "" : "dp ") + (resourcepacks(k).isEmpty() ? "" : "rp")).trim());
	}

	/** Datapacks: on = enable (its load runs), off = run <ns>:uninstall / <ns>:off first (clean state), then disable. Server thread. */
	static void toggleDatapacks(net.minecraft.server.MinecraftServer sp, String id, boolean on) {
		var src = sp.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
		for (Path dp : datapacks(id)) {
			String pack = "file/" + dp.getFileName();
			if (!on) {
				try (Stream<Path> ns = Files.list(dp.resolve("data"))) {
					for (Path n : ns.toList()) for (String f : new String[] {"uninstall", "off"})
						if (Files.exists(n.resolve("function/" + f + ".mcfunction"))) { sp.getCommands().performPrefixedCommand(src, "function " + n.getFileName() + ":" + f); break; }
				} catch (Exception ignored) {}
			}
			sp.getCommands().performPrefixedCommand(src, "datapack " + (on ? "enable" : "disable") + " \"" + pack + "\"");
		}
	}

	/** Resource packs: select / unselect in the pack list and reload (client thread). */
	static void toggleResourcepacks(String id, boolean on) {
		List<Path> rps = resourcepacks(id);
		if (rps.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		var repo = mc.getResourcePackRepository();
		repo.reload();
		List<String> sel = new ArrayList<>(repo.getSelectedIds());
		for (Path rp : rps) { String n = "file/" + rp.getFileName(); sel.remove(n); if (on) sel.add(n); }
		repo.setSelected(sel);
		mc.options.updateResourcePacks(repo);
	}

	/** Packs off before deleting them (mc remove): runs from game run on the server thread. */
	public static String packsOff(String id) {
		toggleDatapacks(Minecraft.getInstance().getSingleplayerServer(), id, false);
		Minecraft.getInstance().execute(() -> toggleResourcepacks(id, false));
		return "off";
	}

	/** Switch a mod on/off right now (its @OnReload / @OnUnload hooks, server thread). */
	public static void setEnabled(String id, boolean on) { setEnabled(id, on, true); }

	/** rp=false: leave its resource pack selected (switching packs = a multi-second full resource reload). */
	public static void setEnabled(String id, boolean on, boolean rp) {
		LagGuard.quiet(15_000); // the reloads this causes are not the mod's lag
		state().put(id, on);
		Bridge.send("project-set", "id", id, "enabled", on);
		uninstallLook(id, on);
		List<String> names = classes(id);
		int dps = datapacks(id).size(), rps = resourcepacks(id).size();
		Runnable work = () -> {
			try { toggleDatapacks(Minecraft.getInstance().getSingleplayerServer(), id, on); } catch (Throwable t) { Moddeus.LOG.warn("datapack toggle " + id, t); }
			if (Gens.manages(id)) { if (on) Gens.on(id); else Gens.off(id); return; }
			if (!on) { HotReloader.runUnload(names); return; }
			List<Class<?>> cs = new ArrayList<>();
			for (String n : names) try {
				Class<?> c = Class.forName(n, true, Moddeus.class.getClassLoader());
				cs.add(c);
				// convention: @OnUnload sets a static "enabled" guard false; switching the mod back on sets it true again
				try { var f = c.getDeclaredField("enabled"); f.setAccessible(true); if (f.getType() == boolean.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) f.setBoolean(null, true); } catch (NoSuchFieldException ignored) {}
			} catch (Throwable ignored) {}
			HotReloader.runHooks(cs);
		};
		var sp = Minecraft.getInstance().getSingleplayerServer();
		if (sp != null) sp.execute(work); else work.run();
		if (rp) try { toggleResourcepacks(id, on); } catch (Throwable t) { Moddeus.LOG.warn("resource pack toggle " + id, t); }
		List<String> parts = new ArrayList<>();
		if (!names.isEmpty()) parts.add(names.size() + " classes");
		if (dps > 0) parts.add("datapack");
		if (rps > 0) parts.add("resource pack");
		HotReloader.say((on ? "§aOn§r: " : "§7Off§r: ") + id + (parts.isEmpty() ? " (nothing found: name classes " + id + "*.java, packs " + id + "-DP / " + id + "-RP)" : " (" + String.join(", ", parts) + ")"));
	}

	// ---------------------------------------------------------------- sharing (tools/share.mjs): exports/<Name>.moddeus.zip
	static java.nio.file.Path exportsDir() { return root().toPath().resolve("exports"); }
	static List<java.nio.file.Path> importable;
	static long importableAt;

	/** .moddeus.zip files in exports/ and ~/Downloads, newest first (re-listed at most every 2 s). */
	public static List<java.nio.file.Path> importable() {
		if (importable != null && System.currentTimeMillis() - importableAt < 2000) return importable;
		List<java.nio.file.Path> out = new ArrayList<>();
		for (java.nio.file.Path d : List.of(exportsDir(), java.nio.file.Path.of(System.getProperty("user.home"), "Downloads")))
			try (Stream<java.nio.file.Path> s = Files.list(d)) { s.filter(p -> p.getFileName().toString().endsWith(".moddeus.zip")).forEach(out::add); } catch (Exception ignored) {}
		out.sort(java.util.Comparator.comparingLong((java.nio.file.Path p) -> p.toFile().lastModified()).reversed());
		importableAt = System.currentTimeMillis();
		return importable = out.size() > 12 ? out.subList(0, 12) : out;
	}

	public static void export(String id) { mc("Exported " + name(id), Projects::openExports, "export", id); }

	public static void importMod(java.nio.file.Path f) { importableAt = 0; mc("Imported " + f.getFileName(), null, "import", f.toString()); }

	public static void openExports() {
		try {
			Files.createDirectories(exportsDir());
			String d = exportsDir().toString(), os = System.getProperty("os.name").toLowerCase();
			new ProcessBuilder(os.contains("win") ? List.of("explorer", d) : os.contains("mac") ? List.of("open", d) : List.of("xdg-open", d)).start();
		} catch (Exception ignored) {}
	}

	public static void undo(String id) { mc("Undo " + id, null, "undo", id); }

	/** Delete = gone from the game: switched off live first (hooks, tab, keys, packs), then mc remove deletes every file it owns. */
	public static void delete(String id) {
		try { setEnabled(id, false); } catch (Throwable t) { Moddeus.LOG.warn("delete: off " + id, t); }
		mc("Deleted " + name(id), () -> Bridge.send("project-delete", "id", id), "remove", id);
	}

	/** node tools/mc.mjs <args> in the background; its last lines go to chat. */
	static void mc(String title, Runnable onSuccess, String... args) {
		Thread t = new Thread(() -> {
			try {
				List<String> cmd = new ArrayList<>(List.of("node", "tools/mc.mjs"));
				cmd.addAll(List.of(args));
				Process p = new ProcessBuilder(cmd).directory(root()).redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes()).trim();
				int code = p.waitFor();
				if (code == 0 && onSuccess != null) onSuccess.run();
				String[] ls = out.split("\n");
				String tail = String.join(" | ", java.util.Arrays.asList(ls).subList(Math.max(0, ls.length - 3), ls.length));
				Minecraft.getInstance().execute(() -> HotReloader.say((code == 0 ? title : title + " FAILED") + ": " + tail));
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> HotReloader.say(title + " failed: " + e));
			}
		}, "moddeus-project");
		t.setDaemon(true);
		t.start();
	}

	/** Mods switched off stay off after a restart: unload them when a world is joined. */
	public static void onJoin(net.minecraft.client.multiplayer.ClientPacketListener h, net.fabricmc.fabric.api.networking.v1.PacketSender s, Minecraft mc) {
		mc.execute(() -> {
			for (var e : state().entrySet()) if (!e.getValue()) {
				List<String> names = classes(e.getKey());
				var sp = mc.getSingleplayerServer();
				if (sp != null) sp.execute(() -> HotReloader.runUnload(names));
			}
		});
	}

	@OnReload
	/** Off looks like uninstalled: items out of the creative inventory, key binds gone (On: back, its hooks re-add the keys). */
	static void uninstallLook(String id, boolean on) {
		try { dev.moddeus.content.ModTab.setHidden(name(id), id, !on); } catch (Throwable t) { Moddeus.LOG.warn("tab hide " + id, t); }
		if (!on) try { Keys.remove(name(id)); Keys.remove(id); } catch (Throwable t) { Moddeus.LOG.warn("keys " + id, t); }
	}

	public static void hook() {
		for (var e : state().entrySet()) if (!e.getValue()) uninstallLook(e.getKey(), false); // switched-off mods stay hidden after a restart
		if (System.getProperties().putIfAbsent("moddeus.projects", Boolean.TRUE) != null) return;
		ClientPlayConnectionEvents.JOIN.register(Projects::onJoin);
	}
}
