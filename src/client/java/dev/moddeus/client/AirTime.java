package dev.moddeus.client;

/** Ticks the local player has been airborne. A jump press on the tick it leaves the ground is the ground jump, not a double jump
 *  (the client tick runs after vanilla's jump, so the player is already off the ground then). */
public final class AirTime {
	static int n;
	public static int tick(boolean free) { return n = free ? n + 1 : 0; }
}
