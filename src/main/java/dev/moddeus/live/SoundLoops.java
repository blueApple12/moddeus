package dev.moddeus.live;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looping sounds that follow an entity (or stay at a spot), with fade in/out and a clean stop: hums, engines, charge-ups,
 * boss auras, held-item loops. Server side keeps WHAT should play ({@link #LOOPS}); the client (client/SoundLoopsClient,
 * same JVM in singleplayer) plays one tickable instance per entry and fades out entries that disappear. Loops of dead or
 * unloaded entities end by themselves, so nothing lingers.
 *
 * Java only (no command): SoundLoops.follow(entity, "ns:hum", "hum", 1f, 1f, 10); SoundLoops.at(level, pos, "ns:hum", "spot", 1f, 1f, 10);
 *   SoundLoops.stop(entity, "hum"); SoundLoops.stop("at:spot"); SoundLoops.stopEntity(entity); SoundLoops.stopAll().
 * Content JSON (mc sound-hold / mc block --ambient-sound): items "hold_sound" {sound, volume, pitch}: loops while held;
 * blocks "ambient_sound" {sound, chance, volume, pitch}: random ambient like vanilla animateTick (client samples).
 */
public final class SoundLoops {
	public static volatile boolean enabled = true; // false = all loops stop (OnUnload)
	/** One playing loop. entity = -1: fixed at x/y/z. ticks = 0: until stopped. */
	public record Loop(String sound, String category, int entity, String dim, double x, double y, double z,
		float volume, float pitch, int fadeIn, int fadeOut, long until) {}
	public static Map<String, Loop> LOOPS;
	/** item id -> hold_sound spec; block id -> ambient_sound spec (content JSON). */
	public static Map<String, JsonObject> HELD, BLOCKS;
	private static Map<String, JsonObject> heldNext, blocksNext;

	static void init() {
		if (LOOPS == null) LOOPS = new ConcurrentHashMap<>();
		if (HELD == null) HELD = new ConcurrentHashMap<>();
		if (BLOCKS == null) BLOCKS = new ConcurrentHashMap<>();
	}

	// ---- API -------------------------------------------------------------------------------------------------------
	public static String follow(Entity e, String sound, String key, float volume, float pitch, int fade) {
		return put(e.getId() + ":" + key, new Loop(ns(sound), e.getSoundSource().getName(), e.getId(), dim(e.level()), e.getX(), e.getY(), e.getZ(), volume, pitch, fade, fade, 0));
	}

	public static String at(ServerLevel l, Vec3 p, String sound, String key, float volume, float pitch, int fade) {
		return put("at:" + key, new Loop(ns(sound), "ambient", -1, dim(l), p.x, p.y, p.z, volume, pitch, fade, fade, 0));
	}

	public static void stop(Entity e, String key) { init(); LOOPS.remove(e.getId() + ":" + key); }
	public static void stop(String fullKey) { init(); LOOPS.remove(fullKey); }
	public static void stopEntity(Entity e) { init(); LOOPS.keySet().removeIf(k -> k.startsWith(e.getId() + ":")); }
	public static void stopAll() { init(); if (!LOOPS.isEmpty()) LOOPS.clear(); }

	static String put(String key, Loop l) { init(); LOOPS.put(key, l); return key; }
	static String dim(net.minecraft.world.level.Level l) { return l.dimension().identifier().toString(); }
	static String ns(String id) { return id.contains(":") ? id : "moddeus:" + id; }

	// ---- content (ContentLoader calls begin / item / end) ----------------------------------------------------------
	public static void begin() { SoundScan.wired = true; heldNext = new HashMap<>(); blocksNext = new HashMap<>(); }

	public static void item(String id, JsonObject o) {
		if (heldNext == null) begin();
		String full = (o.has("namespace") ? o.get("namespace").getAsString() : "moddeus") + ":" + id;
		if (o.has("hold_sound") && o.get("hold_sound").isJsonObject()) heldNext.put(full, o.getAsJsonObject("hold_sound"));
		if (o.has("ambient_sound") && o.get("ambient_sound").isJsonObject()) blocksNext.put(full, o.getAsJsonObject("ambient_sound"));
	}

	public static void end() {
		init();
		if (heldNext == null) return;
		HELD = new ConcurrentHashMap<>(heldNext);
		BLOCKS = new ConcurrentHashMap<>(blocksNext);
		heldNext = blocksNext = null;
	}

	// ---- hooks -----------------------------------------------------------------------------------------------------
	@OnReload
	public static void hook() {
		enabled = true;
		init();
		if (System.getProperties().putIfAbsent("moddeus.soundloops", Boolean.TRUE) == null) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(SoundLoops::tick);
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(SoundLoops::stopping);
		}
	}

	@OnUnload
	public static void unhook() { enabled = false; stopAll(); }

	static void stopping(MinecraftServer s) { stopAll(); }

	static void tick(MinecraftServer s) {
		if (!enabled) return;
		init();
		long now = s.overworld().getGameTime();
		SoundScan.maybe(now);
		for (var it = LOOPS.entrySet().iterator(); it.hasNext(); ) { // end loops of gone entities / timed loops: nothing lingers
			var en = it.next(); Loop l = en.getValue();
			if (l.until() > 0 && now >= l.until()) { it.remove(); continue; }
			int hi = en.getKey().indexOf(":held:");
			if (hi >= 0) { // hold_sound removed or changed in the content JSON: end the old loop (re-added below with the new spec)
				JsonObject h = HELD.get(en.getKey().substring(hi + 6));
				if (h == null || !h.has("sound") || !ns(h.get("sound").getAsString()).equals(l.sound())) { it.remove(); continue; }
			}
			if (l.entity() < 0) continue;
			ServerLevel lv = level(s, l.dim());
			Entity e = lv == null ? null : lv.getEntity(l.entity());
			if (e == null || e.isRemoved() || (e instanceof net.minecraft.world.entity.LivingEntity le && le.isDeadOrDying())) it.remove();
		}
		if (HELD.isEmpty()) { LOOPS.keySet().removeIf(k -> k.contains(":held:")); return; }
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			for (String item : HELD.keySet()) {
				String key = p.getId() + ":held:" + item;
				boolean holding = !p.isDeadOrDying() && (is(p.getMainHandItem(), item) || is(p.getOffhandItem(), item)); // dead (keepInventory) = off, else it re-adds every tick
				JsonObject h = HELD.get(item);
				if (holding && h != null && h.has("sound") && net.minecraft.resources.Identifier.tryParse(ns(h.get("sound").getAsString())) != null && !LOOPS.containsKey(key)) {
					LOOPS.put(key, new Loop(ns(h.get("sound").getAsString()), "player", p.getId(), dim(p.level()), p.getX(), p.getY(), p.getZ(),
						h.has("volume") ? h.get("volume").getAsFloat() : 0.6f, h.has("pitch") ? h.get("pitch").getAsFloat() : 1f, 6, 6, 0));
				} else if (!holding) LOOPS.remove(key);
			}
		}
	}

	static boolean is(ItemStack st, String id) { return !st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(id); }

	static ServerLevel level(MinecraftServer s, String dim) {
		for (ServerLevel l : s.getAllLevels()) if (dim(l).equals(dim)) return l;
		return null;
	}
}

