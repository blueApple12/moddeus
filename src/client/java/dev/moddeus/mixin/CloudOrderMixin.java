package dev.moddeus.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.moddeus.client.RenderOrder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Classic transparency: move the after-terrain phase (particles + VFX) from before clouds to after them (logic in RenderOrder, hot-swappable). */
@Mixin(LevelRenderer.class)
public abstract class CloudOrderMixin {
	@Redirect(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucentAfterTerrain(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void moddeus$early(FeatureRenderDispatcher.PreparedFrame f, RenderPass p) { RenderOrder.afterTerrain(f, p, false); }

	@Inject(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void moddeus$late(ChunkSectionsToRender s, FeatureRenderDispatcher.PreparedFrame f, RenderPass p, CallbackInfo ci) { RenderOrder.afterTerrain(f, p, true); }
}
