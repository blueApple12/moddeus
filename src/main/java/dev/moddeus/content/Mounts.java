package dev.moddeus.content;

import net.minecraft.world.entity.Entity;

/** Mount veto, called from EntityRidingMixin before any startRiding (hot-swappable). Nothing is vetoed right now. */
public class Mounts {
	public static boolean blocked(Entity passenger, Entity vehicle) {
		return false;
	}
}
