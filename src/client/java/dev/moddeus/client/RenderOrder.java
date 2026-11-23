package dev.moddeus.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/** Called by CloudOrderMixin: the after-terrain phase (particles, VFX) runs after clouds instead of before, so clouds don't paint over them. */
public final class RenderOrder {
	public static boolean afterClouds = true;

	public static void afterTerrain(FeatureRenderDispatcher.PreparedFrame f, RenderPass p, boolean late) {
		if (afterClouds == late) f.executeTranslucentAfterTerrain(p);
	}
}
