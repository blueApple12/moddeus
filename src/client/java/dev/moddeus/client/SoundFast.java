package dev.moddeus.client;

import dev.moddeus.Moddeus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.InactiveProfiler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hot-swap fast path for SOUNDS (like Pixels for textures): a changed sounds.json / .ogg no longer runs the SoundManager
 * reload listener, whose apply() restarts the whole OpenAL engine (SoundEngine.reload) and drags the atlas/model listeners
 * along (~280 ms frame hitch). Instead: sounds.json is parsed off-thread (SoundManager.prepare, the same code vanilla runs),
 * then the render thread swaps the event registry + resource cache in place (~1 ms), evicts the changed .ogg buffers and
 * pre-decodes them off-thread. Playing sounds keep playing. Any failure falls back to the normal light reload once.
 */
public final class SoundFast {
	private static volatile boolean off;
	private static final AtomicInteger SEQ = new AtomicInteger();
	private static final Set<Identifier> EVICT = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public static boolean isSound(String p) {
		return p.startsWith("assets/") && (p.endsWith("/sounds.json") || (p.contains("/sounds/") && p.endsWith(".ogg")));
	}

	/** LightReload hook: takes the sound paths out of {@code changed} and applies them live. True = nothing else left to reload. */
	public static boolean handle(Minecraft mc, List<String> changed) {
		if (off) { off = false; return false; } // last fast pass failed: this pass is the normal reload (SoundManager included)
		List<String> mine = new ArrayList<>();
		for (String p : changed) if (isSound(p)) mine.add(p);
		if (mine.isEmpty()) return false;
		changed.removeAll(mine);
		for (String p : mine) if (p.endsWith(".ogg")) { String[] s = p.split("/", 3); EVICT.add(Identifier.fromNamespaceAndPath(s[1], s[2])); }
		int seq = SEQ.incrementAndGet();
		long t0 = System.nanoTime();
		SoundManager sm = mc.getSoundManager();
		ResourceManager rm = mc.getResourceManager();
		CompletableFuture.supplyAsync(() -> prepare(sm, rm), Util.backgroundExecutor())
			.thenAcceptAsync(prep -> { if (seq == SEQ.get()) swap(sm, prep, mine.size(), t0); }, mc) // a newer pass re-read everything: skip the stale one
			.exceptionally(t -> { fallback(mc, mine, t); return null; });
		return changed.isEmpty();
	}

	private static Object prepare(SoundManager sm, ResourceManager rm) {
		try {
			Method m = SoundManager.class.getDeclaredMethod("prepare", ResourceManager.class, net.minecraft.util.profiling.ProfilerFiller.class);
			m.setAccessible(true);
			return m.invoke(sm, rm, InactiveProfiler.INSTANCE);
		} catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
	}

	@SuppressWarnings("unchecked")
	private static void swap(SoundManager sm, Object prep, int files, long t0) {
		long t1 = System.nanoTime();
		try {
			Map<Identifier, WeighedSoundEvents> reg = (Map<Identifier, WeighedSoundEvents>) get(sm, "registry"), fresh = (Map<Identifier, WeighedSoundEvents>) get(prep, "registry");
			Map<Object, Object> cache = (Map<Object, Object>) get(sm, "soundCache");
			Map<Object, Object> freshCache = (Map<Object, Object>) get(prep, "soundCache");
			SoundEngine eng = (SoundEngine) get(sm, "soundEngine");
			SoundBufferLibrary lib = (SoundBufferLibrary) get(eng, "soundBuffers");
			Map<Identifier, ?> bufs = (Map<Identifier, ?>) get(lib, "cache");
			reg.clear(); reg.putAll(fresh);
			cache.clear(); cache.putAll(freshCache); // SoundBufferLibrary reads files through this map: new .ogg files become visible
			Set<Identifier> evict = new HashSet<>(EVICT);
			EVICT.removeAll(evict);
			// ponytail: the old AL buffer is dropped, not discarded (a channel may still play it); a few KB leaked per edit until restart
			for (Identifier id : evict) bufs.remove(id);
			Set<String> nss = new HashSet<>();
			for (Identifier id : evict) nss.add(id.getNamespace());
			for (var e : reg.entrySet()) {
				if (!nss.contains(e.getKey().getNamespace())) continue;
				e.getValue().preloadIfRequired(eng);
				for (Object w : (List<?>) get(e.getValue(), "list"))
					if (w instanceof Sound s && !s.shouldStream() && evict.contains(s.getPath())) lib.getCompleteBuffer(s.getPath()); // decode off-thread now, not on first play
			}
			Moddeus.LOG.info("Sound fast-reload: {} file(s), prepare {} ms off-thread, swap {} ms", files, (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000);
		} catch (Throwable t) { fallback(Minecraft.getInstance(), List.of(), t); }
	}

	private static Object get(Object o, String name) throws ReflectiveOperationException {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
			try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) {}
		throw new NoSuchFieldException(o.getClass().getName() + "." + name);
	}

	private static void fallback(Minecraft mc, List<String> paths, Throwable t) {
		Moddeus.LOG.warn("Sound fast-reload failed, doing the normal reload", t);
		off = true;
		LightReload.pending.addAll(paths);
		LightReload.pending.add("assets/minecraft/sounds.json"); // forces the SoundManager listener in that pass
		mc.execute(() -> LightReload.run(mc));
	}
}
