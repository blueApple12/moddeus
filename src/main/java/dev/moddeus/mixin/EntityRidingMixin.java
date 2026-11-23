package dev.moddeus.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every mount (right-click, /ride, startRiding even when forced) asks Mounts.blocked first (hot-swappable). */
@Mixin(Entity.class)
public abstract class EntityRidingMixin {
	@Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
	private void moddeus$noMount(Entity vehicle, boolean force, boolean sendEvent, CallbackInfoReturnable<Boolean> cir) {
		if (dev.moddeus.content.Mounts.blocked((Entity) (Object) this, vehicle)) cir.setReturnValue(false);
	}
}
