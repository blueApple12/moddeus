package dev.moddeus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Client half of live/Bosses: the boss intro cutscene (camera orbits the boss, title card, roar). */
public final class BossesClient {
	public static void intro(int entityId, String title, String sub, double secs, String sound) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.level == null || mc.player == null) return;
			Entity e = mc.level.getEntity(entityId);
			if (e == null || e.distanceTo(mc.player) > 64 || Cutscene.playing()) return;
			double h = e.getBbHeight();
			Vec3 c = e.position().add(0, h * 0.6, 0), toP = mc.player.position().subtract(e.position());
			double a0 = Math.toDegrees(Math.atan2(toP.z, toP.x)), r = Math.max(4.5, h * 2.4);
			Cutscene.create()
				.orbit(0, secs, c, r, h * 0.35 + 1, a0 - 40, a0 + 50)
				.fadeIn(0, 0.6)
				.title(secs * 0.25, secs * 0.7, title, sub, 0xFF4040)
				.sound(secs * 0.3, sound, 2, 0.8f)
				.shake(secs * 0.3, 0.8, 1.2f)
				.length(secs)
				.play();
		});
	}
}
