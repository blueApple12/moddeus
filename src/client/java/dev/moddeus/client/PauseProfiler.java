package dev.moddeus.client;

import dev.moddeus.Moddeus;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Hot-swap work that pauses the game (hooks + content on the server thread while the client waits): samples that thread every
 * 2 ms and, when it took > 80 ms, logs what it spent its time in ("Content pause: ... | cause: ...", also run/moddeus/hitches.log),
 * so every slow content add names its cause.
 */
public final class PauseProfiler {
	private PauseProfiler() {}

	public static Runnable wrap(Runnable work, String what) {
		return new Sampled(work, what);
	}

	static final class Sampled implements Runnable {
		final Runnable work; final String what;
		Sampled(Runnable work, String what) { this.work = work; this.what = what; }

		public void run() {
			Thread me = Thread.currentThread();
			Map<String, Integer> counts = new HashMap<>();
			int[] samples = {0};
			boolean[] stop = {false};
			Thread s = new Thread(new Sampler(me, counts, samples, stop), "Moddeus-PauseProfiler");
			s.setDaemon(true);
			long t0 = System.nanoTime();
			s.start();
			try { work.run(); } finally {
				stop[0] = true;
				long ms = (System.nanoTime() - t0) / 1_000_000;
				if (ms > 80) {
					StringBuilder sb = new StringBuilder();
					synchronized (counts) {
						counts.entrySet().stream().filter(e -> !boring(e.getKey())).sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
							.forEach(e -> sb.append(e.getKey()).append(' ').append(Math.round(100.0 * e.getValue() / Math.max(1, samples[0]))).append("%, "));
					}
					String line = "Content pause: " + what + " " + ms + " ms | cause (" + samples[0] + " samples): " + sb;
					Moddeus.LOG.info(line);
					try {
						Files.writeString(dev.moddeus.live.Workspace.project.resolve("run/moddeus/hitches.log"), java.time.LocalTime.now() + " " + line + "\n",
							StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					} catch (Exception ignored) {}
				}
			}
		}
	}

	static boolean boring(String k) {
		return k.startsWith("Thread.") || k.startsWith("MinecraftServer.") || k.startsWith("BlockableEventLoop.") || k.startsWith("ReentrantBlockableEventLoop.")
			|| k.startsWith("TickTask.") || k.startsWith("PauseProfiler") || k.startsWith("HotReloader.lambda") || k.contains("Pipeline") || k.startsWith("Method.")
			|| k.startsWith("DirectMethodHandleAccessor.");
	}

	static final class Sampler implements Runnable {
		final Thread t; final Map<String, Integer> counts; final int[] samples; final boolean[] stop;
		Sampler(Thread t, Map<String, Integer> counts, int[] samples, boolean[] stop) { this.t = t; this.counts = counts; this.samples = samples; this.stop = stop; }

		public void run() {
			while (!stop[0]) {
				StackTraceElement[] st = t.getStackTrace();
				Set<String> seen = new HashSet<>();
				synchronized (counts) {
					samples[0]++;
					for (StackTraceElement f : st) {
						String c = f.getClassName();
						String k = c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName();
						if (seen.add(k)) counts.merge(k, 1, Integer::sum);
					}
				}
				try { Thread.sleep(2); } catch (InterruptedException e) { return; }
			}
		}
	}
}
