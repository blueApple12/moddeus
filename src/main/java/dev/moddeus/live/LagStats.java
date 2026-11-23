package dev.moddeus.live;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Time spent per datapack function (nanoseconds), filled by ServerFunctionManagerMixin / LagGuard; read by LagGuard. */
public final class LagStats {
	public static Map<String, Long> fnNanos;
	public static volatile boolean mixin; // true once the mixin is live (after a restart): it times every function call
	static long start;
	static int depth;

	public static Map<String, Long> fn() { if (fnNanos == null) fnNanos = new ConcurrentHashMap<>(); return fnNanos; }

	public static void add(String id, long ns) { fn().merge(id, ns, Long::sum); }

	public static void begin() { mixin = true; if (depth++ == 0) start = System.nanoTime(); }

	public static void end(String id) { if (--depth <= 0) { depth = 0; add(id, System.nanoTime() - start); } }
}
