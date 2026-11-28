package dev.moddeus.live;

import dev.moddeus.Moddeus;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Instant datapack reload. Vanilla /reload rebuilds everything (recipes, tags, advancements, loot...) and freezes the
 * server ~300+ ms. When only .mcfunction files changed since the last reload, this recompiles just those functions and
 * swaps them into the live function library (~1-5 ms), then re-runs #minecraft:load like vanilla. Anything else changed
 * (tags, advancements, recipes, pack.mcmeta...) falls back to the vanilla full reload.
 * Used by node tools/mc.mjs game cmd reload (GameApi) and by HotReloader for the mod's own data/.
 */
public final class DataReload {
	static Map<Path, Long> last; // file -> lastModified ^ size, of every directory datapack, at the last reload
	static boolean enabled = true;
	static volatile Map<Path, Long> prepared; // snapshot taken off the server thread (GameApi) just before reload()
	static volatile long preparedAt;

	/** Call from any thread right before reload(): the file scan (~4 ms) then stays off the server thread. */
	public static void prepare(MinecraftServer s) { prepared = snapshot(s); preparedAt = System.nanoTime(); }

	/** Run on the server thread. Returns a one-line report. */
	public static String reload(MinecraftServer s) {
		long t0 = System.nanoTime();
		PackNamespaces.refresh(); // a new mod namespace (data/<ns>) is visible from now on
		Map<Path, Long> now = prepared != null && System.nanoTime() - preparedAt < 2_000_000_000L ? prepared : snapshot(s);
		prepared = null;
		Map<Path, Long> before = last;
		if (before == null || !enabled) return full(s, now, "first reload");
		List<Path> changed = new ArrayList<>();
		for (var e : now.entrySet()) if (!e.getValue().equals(before.get(e.getKey()))) changed.add(e.getKey());
		for (Path p : before.keySet()) if (!now.containsKey(p)) changed.add(p);
		if (changed.isEmpty()) return "reload: nothing changed (0 ms)";
		Map<Identifier, Path> fns = new LinkedHashMap<>();
		boolean extras = changed.removeIf(p -> p.getFileName().toString().equals("moddeus.json"));
		if (extras) packExtras();
		if (changed.isEmpty()) { last = now; return "reload: tabs + key binds updated (moddeus.json)"; }
		for (Path p : changed) {
			Identifier id = functionId(p);
			if (id == null) return full(s, now, "changed " + p.getFileName());
			fns.put(id, p);
		}
		try {
			String r = swapFunctions(s, fns);
			last = now;
			if (extras) r += " + tabs/keys";
			return r + " in " + (System.nanoTime() - t0) / 100_000 / 10.0 + " ms (instant reload, functions only)";
		} catch (Throwable t) {
			Moddeus.LOG.warn("instant reload failed, doing a full one", t);
			return full(s, now, "instant reload failed: " + t);
		}
	}

	static String full(MinecraftServer s, Map<Path, Long> now, String why) {
		last = now;
		// vanilla /reload (also discovers new pack folders); the END_DATA_PACK_RELOAD listener re-takes the baseline
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), "reload");
		return "reload: full datapack reload (" + why + ")";
	}

	/** data/<ns>/function/<path>.mcfunction -> ns:path; null for any other file. */
	static Identifier functionId(Path p) {
		String s = p.toString().replace('\\', '/');
		int d = s.lastIndexOf("/data/");
		if (d < 0 || !s.endsWith(".mcfunction")) return null;
		String[] parts = s.substring(d + 6).split("/", 3); // ns, "function", rest
		if (parts.length < 3 || !parts[1].equals("function")) return null;
		return Identifier.tryParse(parts[0] + ":" + parts[2].substring(0, parts[2].length() - ".mcfunction".length()));
	}

	@SuppressWarnings("unchecked")
	static String swapFunctions(MinecraftServer s, Map<Identifier, Path> fns) throws Exception {
		ServerFunctionManager fm = s.getFunctions();
		ServerFunctionLibrary lib = (ServerFunctionLibrary) field(ServerFunctionManager.class, "library").get(fm);
		var perms = (net.minecraft.server.permissions.PermissionSet) field(ServerFunctionLibrary.class, "functionCompilationPermissions").get(lib);
		CommandSourceStack src = s.createCommandSourceStack().withSource(CommandSource.NULL).withPermission(perms).withSuppressedOutput();
		Map<Identifier, CommandFunction<CommandSourceStack>> map = new HashMap<>(lib.getFunctions());
		List<String> errors = new ArrayList<>();
		int ok = 0, removed = 0;
		for (var e : fns.entrySet()) {
			if (!Files.exists(e.getValue())) { if (map.remove(e.getKey()) != null) removed++; continue; }
			try {
				map.put(e.getKey(), CommandFunction.fromLines(e.getKey(), fm.getDispatcher(), src, Files.readAllLines(e.getValue())));
				ok++;
			} catch (Exception ex) {
				errors.add("Failed to load function " + e.getKey() + ": " + ex.getMessage()); // keeps the old version running
			}
		}
		Field tagsF = field(ServerFunctionLibrary.class, "tags");
		Map<Identifier, List<CommandFunction<CommandSourceStack>>> tags = new HashMap<>();
		for (var e : ((Map<Identifier, List<CommandFunction<CommandSourceStack>>>) tagsF.get(lib)).entrySet()) {
			List<CommandFunction<CommandSourceStack>> l = new ArrayList<>();
			for (var f : e.getValue()) { var nf = map.get(f.id()); if (nf != null) l.add(nf); }
			tags.put(e.getKey(), List.copyOf(l));
		}
		field(ServerFunctionLibrary.class, "functions").set(lib, Map.copyOf(map));
		tagsF.set(lib, Map.copyOf(tags));
		// tick list: LagGuard (or vanilla) re-reads this field
		field(ServerFunctionManager.class, "ticking").set(fm, List.copyOf(lib.getTag(Identifier.withDefaultNamespace("tick"))));
		for (var f : lib.getTag(Identifier.withDefaultNamespace("load"))) fm.execute(f, fm.getGameLoopSender()); // like vanilla /reload
		for (String err : errors) Moddeus.LOG.error(err);
		return "reload: " + ok + " function(s) updated" + (removed > 0 ? ", " + removed + " removed" : "") + (errors.isEmpty() ? "" : "\n" + String.join("\n", errors));
	}

	static void packExtras() {
		try { Class.forName("dev.moddeus.client.PackExtras").getMethod("load").invoke(null); }
		catch (Throwable t) { Moddeus.LOG.warn("pack extras: {}", t.toString()); }
	}

	static Field field(Class<?> c, String n) throws Exception { Field f = c.getDeclaredField(n); f.setAccessible(true); return f; }

	/** Every file of every folder datapack of the world + the mod's own resources (build/resources/main/data). */
	static Map<Path, Long> snapshot(MinecraftServer s) {
		Map<Path, Long> m = new HashMap<>();
		List<Path> roots = new ArrayList<>();
		try (Stream<Path> packs = Files.list(s.getWorldPath(LevelResource.DATAPACK_DIR))) { packs.filter(Files::isDirectory).forEach(roots::add); } catch (Exception ignored) {}
		String proj = System.getProperty("moddeus.project");
		if (proj != null) roots.add(Path.of(proj, "build/resources/main"));
		for (Path r : roots) {
			Path data = r.resolve("data");
			// one directory listing per folder, attributes included (per-file stat calls took ~50 ms on Windows)
			if (Files.isDirectory(data)) try {
				Files.walkFileTree(data, new java.nio.file.SimpleFileVisitor<>() {
					@Override public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) {
						m.put(p, a.lastModifiedTime().toMillis() * 31 + a.size());
						return java.nio.file.FileVisitResult.CONTINUE;
					}
				});
			} catch (Exception ignored) {}
			m.put(r.resolve("pack.mcmeta"), r.resolve("pack.mcmeta").toFile().lastModified());
			Path extras = r.resolve("moddeus.json"); // creative tab + key binds of a datapack mod (client PackExtras)
			if (Files.exists(extras)) m.put(extras, extras.toFile().lastModified() * 31 + extras.toFile().length());
		}
		m.put(Path.of("selected-packs"), (long) s.getPackRepository().getSelectedIds().hashCode()); // enable/disable = full reload
		return m;
	}

	/** Vanilla reloads (typed /reload, datapack enable...) reset the baseline. */
	static void afterVanilla(MinecraftServer s, CloseableResourceManager rm, boolean ok) { last = snapshot(s); }

	static void started(MinecraftServer s) { last = snapshot(s); }

	@OnReload
	public static void hook() {
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.datareload", Boolean.TRUE) != null) return;
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(DataReload::afterVanilla);
		ServerLifecycleEvents.SERVER_STARTED.register(DataReload::started);
		if (LiveRegistry.server != null) LiveRegistry.server.execute(() -> started(LiveRegistry.server));
	}

	@OnUnload
	public static void unload() { enabled = false; }
}
