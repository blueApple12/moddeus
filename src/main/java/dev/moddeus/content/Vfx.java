package dev.moddeus.content;

import com.google.gson.JsonObject;
import dev.moddeus.live.LiveRegistry;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom VFX: content type "vfx" = layered, textured, glowing effects rendered by the client
 * (VfxClient). This side only decides WHEN to play them: Java Vfx.play(Vfx.id("ns:id"), pos, dir, followOrNull), item "vfx": {"use": id, "hit": id,
 * "cooldown": ticks}, mob "aura": id (+ "aura_every" ticks).
 */
public final class Vfx {
	/** vfx id -> definition JSON ({"layers": [...]}); re-read on every content load, so edits are live. */
	public static Map<Identifier, JsonObject> DEFS = new ConcurrentHashMap<>(); // not final: hook() re-creates it after a swap
	/** item id -> {"use": vfx, "hit": vfx, "cooldown": ticks}. */
	public static Map<Identifier, JsonObject> ITEM = new ConcurrentHashMap<>();

	/** Implemented by the client renderer. ponytail: singleplayer only (same JVM); LAN guests would need a packet. */
	public interface Sink { void play(Identifier id, double x, double y, double z, double dx, double dy, double dz, int followId); }
	public static volatile Sink client;
	static boolean hooked;

	@OnReload
	public static void hook() {
		if (DEFS == null) DEFS = new ConcurrentHashMap<>(); // statics added by a hot swap start null
		if (ITEM == null) ITEM = new ConcurrentHashMap<>();
		if (!hooked) {
			hooked = true; // method refs only: they survive hot swaps of this class (lambdas may not)
			ServerTickEvents.END_SERVER_TICK.register(Vfx::tick);
			UseItemCallback.EVENT.register(Vfx::onUse);
			AttackEntityCallback.EVENT.register(Vfx::onHit);
		}
	}

	public static Identifier id(String s) { return s.contains(":") ? Identifier.parse(s) : LiveRegistry.id(s); }

	/** Plays a vfx at pos (following the entity when given). */
	public static void play(Identifier id, Vec3 pos, Vec3 dir, Entity follow) {
		if (!DEFS.containsKey(id)) throw new IllegalArgumentException("no vfx " + id + " (have: " + DEFS.keySet() + ")");
		if (dev.moddeus.live.Stage.isStage(pos) || dev.moddeus.live.Stage.isStage(follow)) { dev.moddeus.live.Stage.note(pos, "vfx " + id); return; } // stage test: no shake/visuals on the player's screen
		Sink s = client;
		if (s != null) s.play(id, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, follow == null ? -1 : follow.getId());
	}

	public static void tick(MinecraftServer server) {
		for (var e : Mobs.SPECS.entrySet()) {
			JsonObject o = e.getValue();
			if (!o.has("aura")) continue;
			int every = o.has("aura_every") ? o.get("aura_every").getAsInt() : 40;
			if (server.getTickCount() % Math.max(1, every) != 0) continue;
			var type = Mobs.TYPES.get(e.getKey());
			if (type == null) continue;
			Identifier vfx = id(o.get("aura").getAsString());
			if (!DEFS.containsKey(vfx)) continue;
			for (ServerLevel level : server.getAllLevels())
				for (Entity m : level.getEntities(type, Entity::isAlive)) play(vfx, m.position(), m.getLookAngle(), m);
		}
	}

	private static JsonObject trig(net.minecraft.world.item.ItemStack stack) { return ITEM.get(BuiltInRegistries.ITEM.getKey(stack.getItem())); }

	public static InteractionResult onUse(Player p, Level level, InteractionHand hand) {
		var stack = p.getItemInHand(hand);
		JsonObject t = trig(stack);
		if (t == null || !t.has("use") || !(level instanceof ServerLevel) || p.getCooldowns().isOnCooldown(stack)) return InteractionResult.PASS;
		try {
			play(id(t.get("use").getAsString()), p.position(), p.getLookAngle(), p);
			if (t.has("cooldown")) p.getCooldowns().addCooldown(stack, t.get("cooldown").getAsInt());
		} catch (Exception e) {
			p.sendSystemMessage(Component.literal("vfx: " + e.getMessage()));
		}
		return InteractionResult.PASS;
	}

	public static InteractionResult onHit(Player p, Level level, InteractionHand hand, Entity target, EntityHitResult hit) {
		JsonObject t = trig(p.getItemInHand(hand));
		if (t != null && t.has("hit") && level instanceof ServerLevel) {
			try { play(id(t.get("hit").getAsString()), target.position(), p.getLookAngle(), target); }
			catch (Exception e) { p.sendSystemMessage(Component.literal("vfx: " + e.getMessage())); }
		}
		return InteractionResult.PASS;
	}
}
