package dev.moddeus.live;

import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Map;

/**
 * Moddeus has no slash commands of its own: every engine is plain Java (Vfx.play, Bosses.attack, Combat.exec,
 * SoundLoops.follow, Systems.openShop/coins/addCoins/startSky, WorldFeel.toast ...), called from mod code or mc game run.
 * Only a MOD may add a command for its player (content "command", mc command), and only when the player asks for one.
 * This class removes the old engine commands from a running server (and from /reload until the next restart).
 */
public final class Api {
	static final List<String> OLD = List.of("vfx", "bossattack", "mobai", "mobvariant", "bossarena", "bosschest", "combat",
		"soundloop", "coins", "pay", "shop", "skills", "skyevent", "toast", "cc");

	/** Boss content JSON "attack" (phases, every) / "death_attack": one spec or a list, run as the boss via Bosses.attack. */
	public static void attacks(LivingEntity boss, JsonElement specs) {
		if (specs == null || specs.isJsonNull()) return;
		for (JsonElement s : specs.isJsonArray() ? specs.getAsJsonArray().asList() : List.of(specs))
			try { Bosses.attack(boss, s.getAsString()); } catch (Throwable t) { dev.moddeus.Moddeus.LOG.warn("boss attack {}: {}", s, t.toString()); }
	}

	@OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.api.strip", Boolean.TRUE) == null)
			net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(Api::strip); // registered last: runs after the old ones
		var s = LiveRegistry.server;
		if (s == null) return;
		strip(s.getCommands().getDispatcher(), null, null);
		for (var p : s.getPlayerList().getPlayers()) s.getCommands().sendCommands(p);
	}

	/** Drops the old engine literals (a mod's own custom command with the same name stays). */
	public static void strip(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx, Commands.CommandSelection sel) {
		var keep = WorldFeel.commandNames;
		try {
			for (String f : new String[] {"children", "literals", "arguments"}) {
				var fld = CommandNode.class.getDeclaredField(f);
				fld.setAccessible(true);
				Map<?, ?> m = (Map<?, ?>) fld.get(d.getRoot());
				for (String n : OLD) if (keep == null || !keep.contains(n)) m.remove(n);
			}
		} catch (ReflectiveOperationException e) { dev.moddeus.Moddeus.LOG.warn("command strip", e); }
	}
}
