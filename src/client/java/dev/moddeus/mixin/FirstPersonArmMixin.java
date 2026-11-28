package dev.moddeus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One-line delegation: mods move the first-person empty arm (bending motions). Logic: client/BendPoses. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonArmMixin {
	@Inject(method = "renderPlayerArm", at = @At("HEAD"))
	private void moddeus$arm(PoseStack ps, SubmitNodeCollector c, int light, float equip, float swing, HumanoidArm arm, PlayerRenderState s, CallbackInfo ci) { dev.moddeus.client.BendPoses.firstPerson(ps, arm); }
}
