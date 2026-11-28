package dev.moddeus.client;

import dev.moddeus.Moddeus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measures what a hot-swap costs the player: every frame (HUD render) and server tick is timed; mark() at a swap, and 3 s
 * later one log line says the worst frame/tick after it vs. the normal frame. While the window is open a sampler thread
 * records the render thread's stack every ~2 ms, so a slow frame is logged WITH what it was doing ("cause: ...").
 * No samples inside the slow frame = the whole JVM was stopped (class redefinition safepoint or GC).
 * Log: "Hitch:" in latest.log; slow ones (worst frame > 50 ms) also go to run/moddeus/hitches.log.
 */
public final class SwapHitch {
	private SwapHitch() {}

	static long lastFrame, markAt, swapNs, baseSum, baseN, worstFrame, worstEnd, worstTick, slowFrames, frames;
	static String label;
	static volatile Thread render;
	static volatile long sampleUntil;
	static List<long[]> sampleTimes;
	static List<StackTraceElement[]> samples;

	/** Called by HotReloader right before a swap. */
	public static void mark(String what) {
		label = what;
		markAt = System.nanoTime();
		worstFrame = worstEnd = worstTick = slowFrames = frames = 0;
		startSampler();
	}

	public static void swapped(long ns) { swapNs = ns; }

	/** Every rendered frame. */
	public static void frame() {
		if (render == null) render = Thread.currentThread();
		long now = System.nanoTime(), dt = lastFrame == 0 ? 0 : now - lastFrame;
		lastFrame = now;
		if (dt <= 0 || dt > 1_500_000_000L) return; // paused / unfocused, not a hitch
		if (label == null) { // normal frames: running average (ignore pauses)
			if (dt < 200_000_000L) { baseSum += dt; baseN++; if (baseN > 600) { baseSum /= 2; baseN /= 2; } }
			return;
		}
		frames++;
		if (dt > worstFrame) { worstFrame = dt; worstEnd = now; }
		if (dt > 50_000_000L) slowFrames++;
		if (now - markAt > 3_000_000_000L) report();
	}

	/** Every server tick (LagGuard). */
	public static void tick(long dt) { if (label != null) worstTick = Math.max(worstTick, dt); }

	static synchronized void startSampler() {
		boolean running = System.nanoTime() < sampleUntil;
		sampleUntil = System.nanoTime() + 3_300_000_000L;
		if (running) return;
		sampleTimes = new ArrayList<>();
		samples = new ArrayList<>();
		Thread t = new Thread(SwapHitch::sample, "Moddeus-HitchSampler");
		t.setDaemon(true);
		t.start();
	}

	static void sample() {
		while (System.nanoTime() < sampleUntil) {
			Thread r = render;
			if (r != null) {
				StackTraceElement[] st = r.getStackTrace();
				synchronized (SwapHitch.class) { sampleTimes.add(new long[]{System.nanoTime()}); samples.add(st); }
			}
			try { Thread.sleep(2); } catch (InterruptedException e) { return; }
		}
	}

	static final Set<String> BORING = Set.of("Minecraft.run", "Minecraft.runTick", "Minecraft.renderFrame", "Main.main", "Knot.launch",
		"KnotClient.main", "MinecraftGameProvider.launch", "BlockableEventLoop.runAllTasks", "BlockableEventLoop.pollTask",
		"BlockableEventLoop.doRunTask", "ReentrantBlockableEventLoop.doRunTask", "Thread.run", "Method.invoke", "DirectMethodHandleAccessor.invoke",
		"CompletableFuture$Completion.run", "CompletableFuture$UniAccept.tryFire", "CompletableFuture$UniRun.tryFire", "CompletableFuture.postComplete");

	/** What the render thread did during the worst frame: the most common non-generic methods in its samples. */
	static synchronized String cause() {
		if (samples == null || worstFrame < 50_000_000L) return "";
		long a = worstEnd - worstFrame, b = worstEnd;
		Map<String, Integer> hits = new HashMap<>();
		int n = 0;
		for (int i = 0; i < samples.size(); i++) {
			long t = sampleTimes.get(i)[0];
			if (t < a || t > b) continue;
			n++;
			Set<String> seen = new HashSet<>();
			StackTraceElement[] st = samples.get(i);
			for (int k = 0; k < Math.min(st.length, 40); k++) {
				String c = st[k].getClassName();
				String f = c.substring(c.lastIndexOf('.') + 1) + "." + st[k].getMethodName();
				if (BORING.contains(f) || c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")) continue;
				if (seen.add(f)) hits.merge(f, 1, Integer::sum);
			}
		}
		if (n == 0) return " | cause: no samples = whole JVM stopped (class redefinition safepoint or GC)";
		List<Map.Entry<String, Integer>> l = new ArrayList<>(hits.entrySet());
		l.sort((x, y) -> y.getValue() - x.getValue());
		StringBuilder sb = new StringBuilder(" | cause (" + n + " samples): ");
		for (int i = 0; i < Math.min(8, l.size()); i++) sb.append(i == 0 ? "" : ", ").append(l.get(i).getKey()).append(' ').append(100 * l.get(i).getValue() / n).append('%');
		return sb.toString();
	}

	static void report() {
		double base = baseN == 0 ? 0 : baseSum / (double) baseN / 1e6;
		String line = String.format("Hitch: %s | swap %.0f ms | worst frame %.0f ms (normal %.1f ms) | %d of %d frames >50 ms | worst tick %.0f ms",
			label, swapNs / 1e6, worstFrame / 1e6, base, slowFrames, frames, worstTick / 1e6) + cause();
		Moddeus.LOG.info(line);
		if (worstFrame > 50_000_000L) try {
			Path p = Path.of(System.getProperty("moddeus.project", ".."), "run/moddeus/hitches.log");
			Files.writeString(p, java.time.LocalTime.now().withNano(0) + " " + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {}
		label = null;
	}
}
