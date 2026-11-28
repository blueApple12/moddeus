package dev.moddeus.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One-line delegation: lets mods pose humanoid limbs (bending stances, casting arms). Logic: client/BendPoses. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidPoseMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void moddeus$pose(HumanoidRenderState state, CallbackInfo ci) { dev.moddeus.client.BendPoses.apply((HumanoidModel<?>) (Object) this, state); }
}
