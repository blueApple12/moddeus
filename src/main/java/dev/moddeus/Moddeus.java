package dev.moddeus;

import net.fabricmc.api.ModInitializer;
import dev.moddeus.live.ContentLoader;
import dev.moddeus.live.LiveRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Moddeus implements ModInitializer {
	public static final String MOD_ID = "moddeus";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		dev.moddeus.live.Workspace.init(); // portable mode: workspace folder + its resources (before content loads)
		ContentLoader.loadAll();
		dev.moddeus.content.ModTab.register();
		dev.moddeus.content.Mobs.hook();
		dev.moddeus.live.DataReload.hook();
		dev.moddeus.content.Vfx.hook();
		dev.moddeus.content.Physics.hook();
		dev.moddeus.live.Quick.hook();
				ServerLifecycleEvents.SERVER_STARTED.register(s -> LiveRegistry.server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> LiveRegistry.server = null);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
			dispatcher.register(Commands.literal("cc").then(Commands.literal("hello").executes(ctx -> {
				ctx.getSource().sendSuccess(() -> Component.literal(Hello.message()), false);
				return 1;
			}))));
		LOG.info("Moddeus loaded");
	}}
