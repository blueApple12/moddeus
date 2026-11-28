package dev.moddeus.client;

import dev.moddeus.Moddeus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ModdeusClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LoadRescue.init();
		TtsVolume.init();
		TtsPlayer.init(); // Claude's voice plays inside the game (game sliders + Windows mixer mute apply) // TTS follows the game's Master x Voice volume (muted game = silent Claude) // first: a broken datapack must never leave the world in Safe Mode
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
		CombatClient.hook();
		dev.moddeus.client.content.WeaponsClient.hook();
		SystemsClient.hook();
		WorldFeelClient.hook();
		SoundLoopsClient.hook();
		FoodTooltip.hook();
		GearClient.hook();
		PackExtras.hook();
		dev.moddeus.client.content.PhysicsMining.hook();
		Projects.hook();
		Gens.startAll(); // mod projects load as generations (instant reloads)
		// a mod's state belongs to one world: off when it closes, fresh generation in the next (Gens.worldClosing)
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(Gens::worldClosing);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(Gens::worldOpened);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
			client.execute(() -> HotReloader.say("online. Hotswap: " + HotReloader.status())));
		Moddeus.LOG.info("Moddeus client ready, hotswap: {}", HotReloader.status());
	}
}
