package dev.moddeus.client;

import dev.moddeus.live.Standalone;
import net.fabricmc.api.ClientModInitializer;

/** Client entry point of the Moddeus runtime jar (see live/Standalone): rendering, VFX, keys, GUIs, then the mods' hooks. */
public final class StandaloneClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		dev.moddeus.client.content.ClientContent.hook();
		dev.moddeus.client.content.VfxClient.hook();
		Cutscene.hook();
		Keys.hook();
		ModGui.hook();
		CombatClient.hook();
		dev.moddeus.client.content.WeaponsClient.hook();
		SystemsClient.hook();
		WorldFeelClient.hook();
		FoodTooltip.hook();
		GearClient.hook();
		dev.moddeus.client.content.PhysicsMining.hook();
		Standalone.runHooks(true);
	}
}
