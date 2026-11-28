package dev.moddeus.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fills SoundLoops.HELD / BLOCKS from the content dir while ContentLoader does not call SoundLoops.begin/item/end yet
 * (that ContentLoader edit is a deferred core change until the next restart). Off-thread, every 2 s, only when a file changed.
 */
public final class SoundScan {
	public static volatile boolean wired;
	private static volatile boolean scanning;
	private static long stamp;

	static void maybe(long now) {
		if (wired || scanning || now % 40 != 0) return;
		scanning = true;
		CompletableFuture.runAsync(SoundScan::rescan);
	}

	static void rescan() {
		try {
			List<Path> files = new ArrayList<>();
			for (Path r : net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(dev.moddeus.Moddeus.MOD_ID).map(m -> m.getRootPaths()).orElse(List.of())) {
				Path d = r.resolve("moddeus/content");
				if (Files.isDirectory(d)) try (var s = Files.list(d)) { s.filter(f -> f.toString().endsWith(".json")).forEach(files::add); }
			}
			long st = files.size();
			for (Path f : files) st = st * 31 + Files.getLastModifiedTime(f).toMillis();
			if (st == stamp || wired) return;
			stamp = st; // set before parsing: a broken JSON warns once per change, not every 2 s
			Map<String, JsonObject> h = new HashMap<>(), b = new HashMap<>();
			for (Path f : files) {
				String t = Files.readString(f);
				if (!t.contains("hold_sound") && !t.contains("ambient_sound")) continue;
				JsonObject o = JsonParser.parseString(t).getAsJsonObject();
				String full = (o.has("namespace") ? o.get("namespace").getAsString() : "moddeus") + ":" + f.getFileName().toString().replace(".json", "");
				if (o.has("hold_sound") && o.get("hold_sound").isJsonObject()) h.put(full, o.getAsJsonObject("hold_sound"));
				if (o.has("ambient_sound") && o.get("ambient_sound").isJsonObject()) b.put(full, o.getAsJsonObject("ambient_sound"));
			}
			if (wired) return;
			SoundLoops.HELD = new ConcurrentHashMap<>(h);
			SoundLoops.BLOCKS = new ConcurrentHashMap<>(b);
		} catch (Exception e) { dev.moddeus.Moddeus.LOG.warn("SoundScan", e); }
		finally { scanning = false; }
	}
}
