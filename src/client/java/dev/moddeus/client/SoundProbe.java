package dev.moddeus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.List;

/**
 * Sound self-test for tools (mc sound-test): what the client knows about a sound event (variants, files, stream,
 * attenuation, subtitle, missing files) and a real playback check. Thread-safe to call from game run (server thread):
 * playback is scheduled on the render thread, its result read by a second call.
 */
public final class SoundProbe {
	private static volatile String last = "none";
	private static volatile SoundInstance lastInst;

	public static String info(String id) {
		Minecraft mc = Minecraft.getInstance();
		Identifier k = Identifier.parse(id);
		WeighedSoundEvents w = mc.getSoundManager().getSoundEvent(k);
		if (w == null) return "UNKNOWN " + id + " (not in any sounds.json the client loaded)";
		StringBuilder sb = new StringBuilder(id).append(": subtitle=").append(w.getSubtitle() == null ? "NONE" : w.getSubtitle().getString());
		try {
			var f = WeighedSoundEvents.class.getDeclaredField("list"); f.setAccessible(true);
			List<?> l = (List<?>) f.get(w);
			sb.append(", entries=").append(l.size());
			for (Object o : l) {
				if (o instanceof Sound s) {
					boolean exists = mc.getResourceManager().getResource(s.getPath()).isPresent();
					sb.append("\n  ").append(s.getType() == Sound.Type.SOUND_EVENT ? "event " : "file ").append(s.getLocation())
						.append(" weight=").append(s.getWeight()).append(" stream=").append(s.shouldStream()).append(" preload=").append(s.shouldPreload())
						.append(" attenuation=").append(s.getAttenuationDistance()).append(s.getType() == Sound.Type.FILE && !exists ? " MISSING FILE" : "");
				} else sb.append("\n  ").append(o);
			}
		} catch (ReflectiveOperationException e) { sb.append(" (variants unreadable: ").append(e).append(')'); }
		return sb.toString();
	}

	/** Plays at x/y/z (far away = inaudible but really started), result via {@link #result()}. */
	public static void play(String id, double x, double y, double z, float volume, float pitch, String category) {
		Minecraft mc = Minecraft.getInstance();
		last = "pending";
		mc.execute(() -> {
			SoundSource src = SoundSource.MASTER;
			for (SoundSource s : SoundSource.values()) if (s.getName().equals(category)) src = s;
			SoundInstance in = new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(Identifier.parse(id)), src, volume, pitch, SoundInstance.createUnseededRandom(), x, y, z);
			lastInst = in;
			last = String.valueOf(mc.getSoundManager().play(in));
		});
	}

	public static String result() {
		SoundInstance in = lastInst;
		boolean active = in != null && Minecraft.getInstance().getSoundManager().isActive(in);
		return "play=" + last + " active=" + active + (in != null && in.getSound() != null ? " picked=" + in.getSound().getLocation() : "")
			+ " loopsPlaying=" + SoundLoopsClient.playingCount();
	}
}
