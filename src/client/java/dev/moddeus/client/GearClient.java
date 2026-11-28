package dev.moddeus.client;

import dev.moddeus.live.OnReload;

/** Client entry for gear (called from ModdeusClient / StandaloneClient): worn 3D armor models live in GearArmor. */
public final class GearClient {
	@OnReload
	public static void hook() { GearArmor.hook(); }
}
