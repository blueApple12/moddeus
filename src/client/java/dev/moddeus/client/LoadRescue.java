package dev.moddeus.client;

import dev.moddeus.Moddeus;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Never Safe Mode: when a world fails to load because of a broken datapack file ("Errors in the currently selected data
 * pack(s)"), repair it and reopen the world by itself. Repair = the files named in the registry errors are moved to
 * run/moddeus/quarantine/, then tools/preflight.mjs fixes tags/loot tables/stale build copies. Max 3 tries per launch.
 */
public final class LoadRescue {
	private LoadRescue() {}

	static int tries;
	static volatile boolean busy;
	static Object handled;

	@OnReload
	public static void init() {
		if (System.getProperties().putIfAbsent("moddeus.loadrescue", Boolean.TRUE) == null) ClientTickEvents.END_CLIENT_TICK.register(LoadRescue::tick);
	}

	static void tick(Minecraft mc) {
		var s = mc.gui.screen();
		if (!(s instanceof DatapackLoadFailureScreen) || s == handled || busy) return;
		handled = s;
		if (tries >= 3) { Moddeus.LOG.error("Load rescue: world still fails after 3 repairs, leaving the screen"); return; }
		tries++;
		busy = true;
		Thread t = new Thread(() -> repairAndReopen(mc), "Moddeus-LoadRescue");
		t.setDaemon(true);
		t.start();
	}

	static void repairAndReopen(Minecraft mc) {
		Path proj = dev.moddeus.live.Workspace.project;
		if (proj == null) proj = Path.of(System.getProperty("user.dir")).getParent();
		try {
			List<String> fixed = quarantineFromLog(proj);
			Process p = new ProcessBuilder("node", "tools/preflight.mjs").directory(proj.toFile()).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes());
			p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
			Moddeus.LOG.warn("Load rescue #{}: quarantined {} | {}", tries, fixed, out.trim().replace('\n', ' '));
		} catch (Throwable e) {
			Moddeus.LOG.error("Load rescue repair failed", e);
		}
		mc.execute(() -> {
			busy = false;
			String world = newestWorld(mc);
			if (world == null) return;
			Moddeus.LOG.warn("Load rescue: reopening world {}", world);
			mc.createWorldOpenFlows().openWorld(world, () -> mc.setScreenAndShow(new TitleScreen()));
		});
	}

	static final Pattern REG = Pattern.compile("> Errors in registry ([a-z0-9_.-]+):([a-z0-9_/.-]+):"),
		EL = Pattern.compile(">> Errors in element ([a-z0-9_.-]+):([a-z0-9_/.-]+):");

	/** Files named in the last "Registry loading errors" block of latest.log (ours, not minecraft:) -> quarantine. */
	static List<String> quarantineFromLog(Path proj) throws Exception {
		List<String> out = new ArrayList<>();
		Path log = proj.resolve("run/logs/latest.log");
		if (!Files.exists(log)) return out;
		List<String> lines = Files.readAllLines(log);
		int start = -1;
		for (int i = lines.size() - 1; i >= 0; i--) if (lines.get(i).contains("Registry loading errors")) { start = i; break; }
		if (start < 0) return out;
		String reg = null;
		String stamp = java.time.LocalDateTime.now().toString().replace(':', '-');
		for (int i = start + 1; i < Math.min(lines.size(), start + 4000); i++) {
			String l = lines.get(i);
			Matcher r = REG.matcher(l);
			if (r.find()) { reg = r.group(2); continue; }
			Matcher e = EL.matcher(l);
			if (!e.find() || reg == null || reg.equals("root") || e.group(1).equals("minecraft")) continue;
			String rel = "data/" + e.group(1) + "/" + reg + "/" + e.group(2) + ".json";
			List<Path> roots = new ArrayList<>(List.of(proj.resolve("src/main/resources"), proj.resolve("build/resources/main")));
			try (var w = Files.list(proj.resolve("run/saves"))) { for (Path ws : (Iterable<Path>) w::iterator) try (var d = Files.list(ws.resolve("datapacks"))) { d.forEach(roots::add); } catch (Exception ignored) {} }
			for (Path root : roots) {
				Path f = root.resolve(rel);
				if (!Files.exists(f)) continue;
				Path q = proj.resolve("run/moddeus/quarantine/" + stamp).resolve(proj.relativize(f));
				Files.createDirectories(q.getParent());
				Files.move(f, q, StandardCopyOption.REPLACE_EXISTING);
				out.add(proj.relativize(f).toString().replace('\\', '/'));
			}
		}
		return out;
	}

	static String newestWorld(Minecraft mc) {
		try (var s = Files.list(mc.getLevelSource().getBaseDir())) {
			return s.filter(Files::isDirectory).max((a, b) -> {
				try { return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)); } catch (Exception e) { return 0; }
			}).map(p -> p.getFileName().toString()).orElse(null);
		} catch (Exception e) { return null; }
	}
}
