package dev.moddeus.client.game;

import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Runs Quick.everyClient(...) tasks each client tick (player movement abilities live on the client). */
public final class ClientTasks {
	@OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.clienttasks", Boolean.TRUE) != null) return;
		ClientTickEvents.END_CLIENT_TICK.register(ClientTasks::tick);
	}

	static void tick(Minecraft mc) {
		if (mc.player == null) return;
		dev.moddeus.content.Collisions.resolve(mc.player); // tilted physics blocks: true rotated collision
		dev.moddeus.live.Quick.runClientTasks();
		try { dev.moddeus.client.content.AvatarFeed.tick(); } catch (RuntimeException e) { dev.moddeus.Moddeus.LOG.warn("avatar feed", e); }
	}
}
