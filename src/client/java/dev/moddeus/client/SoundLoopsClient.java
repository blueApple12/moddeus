package dev.moddeus.client;

import com.google.gson.JsonObject;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import dev.moddeus.live.SoundLoops;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Client half of live/SoundLoops: one tickable, looping instance per server loop entry, following its entity, fading in,
 * and fading out + stopping when the entry goes away (entity died, stop, ticks ran out, world left). Also plays block
 * "ambient_sound" specs the way vanilla animateTick does: random positions around the player, a chance per sampled block.
 */
public final class SoundLoopsClient {
	public static volatile boolean enabled = true;
	private static Map<String, Looping> playing;
	private static Map<String, JsonObject> blocksFrom;
	private static Map<Block, JsonObject> blocks;
	private static final RandomSource R = RandomSource.create();

	@OnReload
	public static void hook() {
		enabled = true;
		if (playing == null) playing = new HashMap<>();
		if (System.getProperties().putIfAbsent("moddeus.soundloops.client", Boolean.TRUE) == null)
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(SoundLoopsClient::tick);
	}

	@OnUnload
	public static void unhook() { enabled = false; stopAll(); }

	public static int playingCount() { return playing == null ? 0 : playing.size(); }

	static void stopAll() { if (playing != null) { for (Looping l : playing.values()) l.release(); playing.clear(); } }

	static void tick(Minecraft mc) {
		if (playing == null) playing = new HashMap<>();
		if (!enabled || mc.level == null || SoundLoops.LOOPS == null) { stopAll(); return; }
		String dim = mc.level.dimension().identifier().toString();
		for (var it = playing.entrySet().iterator(); it.hasNext(); ) {
			var en = it.next();
			SoundLoops.Loop want = SoundLoops.LOOPS.get(en.getKey());
			if (want != en.getValue().spec || !want.dim().equals(dim)) { en.getValue().release(); it.remove(); } // gone or replaced: fade out
			else if (en.getValue().isStopped() || !mc.getSoundManager().isActive(en.getValue())) it.remove(); // culled, no free channel or engine reloaded: re-created below
		}
		for (var en : SoundLoops.LOOPS.entrySet()) {
			SoundLoops.Loop l = en.getValue();
			if (!l.dim().equals(dim) || playing.containsKey(en.getKey())) continue;
			if (mc.player != null && mc.player.distanceToSqr(l.x(), l.y(), l.z()) > 128 * 128 && l.entity() < 0) continue; // far fixed loops start when the player comes near
			if (l.entity() >= 0 && mc.level.getEntity(l.entity()) == null) continue; // entity not tracked by this client (too far): start when it is, never at a stale spot
			Looping s;
			try { s = new Looping(l, mc); }
			catch (RuntimeException ex) { SoundLoops.LOOPS.remove(en.getKey()); dev.moddeus.Moddeus.LOG.warn("SoundLoops: dropped bad loop {} ({})", en.getKey(), ex.toString()); continue; } // bad id must not crash the client tick
			playing.put(en.getKey(), s);
			mc.getSoundManager().play(s);
		}
		ambient(mc);
	}

	/** Vanilla ClientLevel.animateTick samples 667 random blocks within 16 and 32 every tick; same here for our blocks only. */
	static void ambient(Minecraft mc) {
		Map<String, JsonObject> src = SoundLoops.BLOCKS;
		if (src == null || src.isEmpty() || mc.player == null || mc.isPaused()) return;
		if (src != blocksFrom) {
			Map<Block, JsonObject> m = new HashMap<>();
			for (var e : src.entrySet()) BuiltInRegistries.BLOCK.getOptional(Identifier.parse(e.getKey())).ifPresent(b -> m.put(b, e.getValue()));
			blocks = m; blocksFrom = src;
		}
		if (blocks.isEmpty()) return;
		BlockPos c = mc.player.blockPosition();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 667; i++) for (int r : new int[]{16, 32}) {
			p.set(c.getX() + R.nextInt(r) - R.nextInt(r), c.getY() + R.nextInt(r) - R.nextInt(r), c.getZ() + R.nextInt(r) - R.nextInt(r));
			JsonObject o = blocks.get(mc.level.getBlockState(p).getBlock());
			if (o == null || !o.has("sound") || R.nextFloat() >= (o.has("chance") ? o.get("chance").getAsFloat() : 0.02f)) continue;
			Identifier id = Identifier.tryParse(o.get("sound").getAsString());
			if (id == null) continue;
			float vol = o.has("volume") ? o.get("volume").getAsFloat() : 0.6f, pitch = (o.has("pitch") ? o.get("pitch").getAsFloat() : 1f) * (0.9f + R.nextFloat() * 0.2f);
			mc.level.playLocalSound(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id)), SoundSource.BLOCKS, vol, pitch, false);
		}
	}

	static SoundSource source(String name) {
		for (SoundSource s : SoundSource.values()) if (s.getName().equals(name)) return s;
		return SoundSource.AMBIENT;
	}

	/** Follows its entity, fades in over fadeIn ticks, fades out over fadeOut ticks after release(), then stops. */
	static final class Looping extends AbstractTickableSoundInstance {
		final SoundLoops.Loop spec;
		final Minecraft mc;
		float fade;
		boolean releasing;

		Looping(SoundLoops.Loop l, Minecraft mc) {
			super(SoundEvent.createVariableRangeEvent(Identifier.parse(l.sound())), source(l.category()), SoundInstance.createUnseededRandom());
			this.spec = l; this.mc = mc;
			looping = true; delay = 0; pitch = l.pitch();
			fade = l.fadeIn() <= 0 ? 1f : 0.02f; // must start above 0: a silent start is culled by the engine
			volume = l.volume() * fade;
			x = l.x(); y = l.y(); z = l.z();
			follow();
		}

		void release() { releasing = true; if (spec.fadeOut() <= 0) stop(); }

		void follow() {
			if (spec.entity() < 0 || mc.level == null) return;
			Entity e = mc.level.getEntity(spec.entity());
			if (e == null || e.isRemoved()) { release(); return; }
			x = e.getX(); y = e.getY() + e.getBbHeight() * 0.5; z = e.getZ();
		}

		@Override public void tick() {
			follow();
			if (releasing) fade -= 1f / Math.max(1, spec.fadeOut());
			else if (fade < 1f) fade = Math.min(1f, fade + 1f / Math.max(1, spec.fadeIn()));
			if (fade <= 0f) { stop(); return; }
			volume = spec.volume() * fade;
		}
	}
}
