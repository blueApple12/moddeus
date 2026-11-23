package dev.moddeus.client;

import dev.moddeus.Moddeus;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asset hot reload without the F3+T cost: re-runs only the reload listeners content changes can affect
 * (textures, atlases, models, equipment, entity/particle renderers, chunk rebuild), skipping shaders,
 * fonts, clouds, splashes... Sounds only when a sound changed. No loading screen. Falls back to a full reload on error.
 */
public final class LightReload {
	/** Asset paths (relative to the resource root) changed since the last reload. */
	public static final Set<String> pending = ConcurrentHashMap.newKeySet();
	private static final Set<String> SKIP = Set.of("ShaderManager", "FontManager", "GrassColorReloadListener", "FoliageColorReloadListener",
		"DryFoliageColorReloadListener", "GameRenderer", "CloudRenderer", "GpuWarnlistManager", "PeriodicNotificationManager", "SplashManager", "WaypointStyleManager");
	private static volatile boolean busy, rebuildChunks;

	/** Call on the render thread. */
	public static void run(Minecraft mc) {
		if (busy) return; // the running reload reads files fresh; a new change triggers another pass afterwards
		List<String> changed = new ArrayList<>(pending);
		pending.clear();
		boolean sounds = changed.stream().anyMatch(p -> p.contains("/sounds") || p.endsWith("sounds.json"));
		// Lag: only block/item atlas or model changes move chunk UVs (full chunk rebuild); VFX/entity textures and lang
		// need just their own listener.
		rebuildChunks = changed.isEmpty() || changed.stream().anyMatch(p -> p.contains("textures/block") || p.contains("textures/item") || p.contains("/models/") || p.contains("/blockstates/") || p.contains("/atlases/"));
		boolean texOnly = !changed.isEmpty() && changed.stream().noneMatch(p -> p.contains("/shaders/") || p.contains("/post_effect/")) && changed.stream().allMatch(p -> p.contains("textures/vfx/") || p.contains("textures/entity/") || p.contains("textures/gui/"));
		Moddeus.LOG.info("Light reload for {}", changed.size() > 6 ? changed.subList(0, 6) + "..." : changed);
		boolean shaders = changed.stream().anyMatch(p -> p.contains("/shaders/") || p.contains("/post_effect/"));
		boolean langOnly = !changed.isEmpty() && changed.stream().allMatch(p -> p.contains("/lang/"));
		long t0 = System.nanoTime();
		try {
			var rm = mc.getResourceManager();
			var f = rm.getClass().getDeclaredField("listeners");
			f.setAccessible(true);
			List<PreparableReloadListener> keep = new ArrayList<>();
			for (Object l : (List<?>) f.get(rm)) {
				String n = l.getClass().getSimpleName();
				if (n.equals("ShaderManager") && shaders) { keep.add((PreparableReloadListener) l); continue; } // shader / post effect edits hot-reload
				if (SKIP.contains(n) || (!sounds && n.equals("SoundManager"))) continue;
				if (texOnly && !n.equals("TextureManager")) continue;
				if (langOnly && !n.equals("LanguageManager")) continue;
				keep.add((PreparableReloadListener) l);
			}
			busy = true;
			SimpleReloadInstance.create(rm, keep, Util.backgroundExecutor(), mc, CompletableFuture.completedFuture(Unit.INSTANCE), false)
				.done().whenComplete(LightReload::done).thenRun(() -> Moddeus.LOG.info("Light reload: {} listeners in {} ms", keep.size(), (System.nanoTime() - t0) / 1_000_000));
		} catch (Throwable t) {
			busy = false;
			Moddeus.LOG.warn("Light reload unavailable, doing a full reload", t);
			mc.reloadResourcePacks();
		}
	}


	private static void done(Object ignored, Throwable err) {
		busy = false;
		if (err != null) {
			Moddeus.LOG.error("Light reload failed, doing a full reload", err);
			Minecraft.getInstance().execute(Minecraft.getInstance()::reloadResourcePacks);
		} else {
			// Atlases were re-stitched, so sprites may have moved: chunk meshes built before still carry the old UVs and show
			// other blocks' textures ("texture swap"). Vanilla's F3+T rebuilds them; the light reload must too.
			Minecraft mc = Minecraft.getInstance();
			if (rebuildChunks) mc.execute(mc.levelExtractor::allChanged);
			if (!pending.isEmpty()) mc.execute(() -> run(mc));
		}
	}
}
