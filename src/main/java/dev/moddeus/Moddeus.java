package dev.moddeus;

import net.fabricmc.api.ModInitializer;
import dev.moddeus.live.ContentLoader;
import dev.moddeus.live.LiveRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
		dev.moddeus.live.Combat.hook();
		dev.moddeus.live.Dimensions.hook();
		dev.moddeus.live.Structures.hook(); // structures, terrain, live worldgen rules
		dev.moddeus.content.Gameplay.hook();
		dev.moddeus.live.Bosses.hook(); // boss attacks, mob behaviours, variants, spawn rules
		dev.moddeus.live.Life.hook(); // mob hitboxes/parts, food/breeding/taming, sounds, trades, professions
		dev.moddeus.live.Systems.hook();
		dev.moddeus.live.Effects.hook();
		dev.moddeus.live.Weapons.hook(); // bows, crossbows, tridents, spears, maces, shields, custom arrows, throwables, guns/wands/staffs, specials
		dev.moddeus.live.SoundLoops.hook(); // looping sounds (follow entity, fades), held-item loops, block ambient specs
		dev.moddeus.live.WorldFeel.hook(); // ambience, logic blocks, banners, gamerules, custom commands, toasts
		ServerLifecycleEvents.SERVER_STARTED.register(s -> LiveRegistry.server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> LiveRegistry.server = null);
		LOG.info("Moddeus loaded");
	}
}
