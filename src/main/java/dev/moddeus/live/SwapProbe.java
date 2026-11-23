package dev.moddeus.live;

/** Temporary: measures hot-swap cost. Safe to delete. */
public final class SwapProbe {
	public static int v() { return w() + 1; }
	static int w() { return 2; }
	static int counter;
}
