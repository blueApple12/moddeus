package dev.moddeus.mixin;

import dev.moddeus.live.LegacyIds;
import net.minecraft.core.MappedRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Old "claudecraft:" ids in saved worlds/commands resolve to "moddeus:" (logic in LegacyIds). */
@Mixin(MappedRegistry.class)
public abstract class LegacyIdMixin {
	@ModifyVariable(method = {"get(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;", "getValue(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;",
		"containsKey(Lnet/minecraft/resources/Identifier;)Z"}, at = @At("HEAD"), argsOnly = true)
	private Identifier moddeus$legacyId(Identifier id) { return LegacyIds.fix(id); }

	@ModifyVariable(method = {"get(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;", "getValue(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;",
		"containsKey(Lnet/minecraft/resources/ResourceKey;)Z"}, at = @At("HEAD"), argsOnly = true)
	private ResourceKey<?> moddeus$legacyKey(ResourceKey<?> k) { return LegacyIds.fix(k); }
}
