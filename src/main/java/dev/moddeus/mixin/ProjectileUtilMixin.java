package dev.moddeus.mixin;

import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Crosshair/arrow picking goes through Collisions.pick (hot-swappable) so physics blocks are hit by their rotated box. */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
	@Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;", at = @At("HEAD"), cancellable = true)
	private static void moddeus$pick(Entity shooter, Vec3 from, Vec3 to, AABB area, Predicate<Entity> filter, double maxSq, CallbackInfoReturnable<EntityHitResult> cir) {
		cir.setReturnValue(dev.moddeus.content.Collisions.pick(shooter, from, to, area, filter, maxSq));
	}
}
