package dev.moddeus.mixin;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Routes entity collision shapes through Collisions (hot-swappable) so physics blocks can collide diagonally. Needs a restart to change. */
@Mixin(Level.class)
public abstract class LevelCollisionMixin {
	public List<VoxelShape> getEntityCollisions(@Nullable Entity source, AABB testArea) {
		return dev.moddeus.content.Collisions.entities((Level) (Object) this, source, testArea);
	}
}
