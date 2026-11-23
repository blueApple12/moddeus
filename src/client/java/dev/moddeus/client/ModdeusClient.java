package dev.moddeus.client;

import dev.moddeus.Moddeus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ModdeusClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HotReloader.start();
		dev.moddeus.client.ui.ClaudeUi.install();
		dev.moddeus.client.game.GameApi.start();
		dev.moddeus.client.game.ClientTasks.hook();
		dev.moddeus.client.content.ClientContent.hook();
		dev.moddeus.client.content.VfxClient.hook();
		dev.moddeus.client.content.AvatarFeed.hook();
		dev.moddeus.client.content.ClaudeOrb.hook();
		dev.moddeus.client.Cutscene.hook();
		LagGuard.hook();
		Keys.hook();
		ModGui.hook();
		PackExtras.hook();
		dev.moddeus.client.content.PhysicsMining.hook();
		Projects.hook();
		Gens.startAll(); // mod projects load as generations (instant reloads)
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
			client.execute(() -> HotReloader.say("online. Hotswap: " + HotReloader.status())));
		Moddeus.LOG.info("Moddeus client ready, hotswap: {}", HotReloader.status());
	}
}
