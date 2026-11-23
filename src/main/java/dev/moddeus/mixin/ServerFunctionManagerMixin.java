package dev.moddeus.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times every datapack function call (tick tags, advancement rewards, /function) for the lag guard. */
@Mixin(ServerFunctionManager.class)
public class ServerFunctionManagerMixin {
	@Inject(method = "execute", at = @At("HEAD"))
	private void moddeus$begin(CommandFunction<CommandSourceStack> f, CommandSourceStack s, CallbackInfo ci) { dev.moddeus.live.LagStats.begin(); }

	@Inject(method = "execute", at = @At("RETURN"))
	private void moddeus$end(CommandFunction<CommandSourceStack> f, CommandSourceStack s, CallbackInfo ci) { dev.moddeus.live.LagStats.end(f.id().toString()); }
}
