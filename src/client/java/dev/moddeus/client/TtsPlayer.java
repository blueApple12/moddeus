package dev.moddeus.client;

import dev.moddeus.Moddeus;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.Workspace;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plays Claude's spoken voice INSIDE the game process, so it follows everything that mutes Minecraft: the in-game Master and
 * Voice sliders and the Windows volume mixer. tools/bridge/tts.py writes each sentence as run/moddeus/tts/NNN.wav (atomic
 * rename) while the game is alive (heartbeat = run/moddeus/volume.json, TtsVolume) and plays it itself only when the game is
 * closed. A "stop" file cuts the voice off (the player talked over Claude).
 */
public final class TtsPlayer {
	private TtsPlayer() {}

	static volatile Clip clip;
	static volatile boolean running;

	@OnReload
	public static void init() {
		if (running) return;
		running = true;
		Thread t = new Thread(TtsPlayer::loop, "Moddeus-TTS");
		t.setDaemon(true);
		t.start();
	}

	static float gain() {
		var o = Minecraft.getInstance().options;
		return o.getSoundSourceVolume(SoundSource.MASTER) * o.getSoundSourceVolume(SoundSource.VOICE);
	}

	static void loop() {
		while (running) {
			try {
				Thread.sleep(25);
				if (Workspace.project == null) continue;
				Path dir = Workspace.project.resolve("run/moddeus/tts");
				if (!Files.isDirectory(dir)) continue;
				Path stop = dir.resolve("stop");
				if (Files.exists(stop)) { Files.deleteIfExists(stop); Clip c = clip; if (c != null) c.stop(); }
				Path next = null;
				try (var s = Files.list(dir)) { next = s.filter(p -> p.toString().endsWith(".wav")).sorted().findFirst().orElse(null); }
				Clip c = clip;
				if (c != null) {
					if (c.isRunning() || c.getFramePosition() < c.getFrameLength() && c.isActive()) { volume(c); continue; }
					c.close(); clip = null;
				}
				if (next == null) continue;
				try {
					float g = gain();
					if (g > 0.001f) {
						var in = AudioSystem.getAudioInputStream(next.toFile());
						Clip n = AudioSystem.getClip();
						n.open(in);
						in.close();
						clip = n;
						volume(n);
						n.start();
					}
				} catch (Exception e) {
					Moddeus.LOG.warn("TTS clip failed: {}", e.toString());
				} finally {
					Files.deleteIfExists(next);
				}
			} catch (Throwable t) {
				Moddeus.LOG.warn("TTS player: {}", t.toString());
			}
		}
	}

	/** Follows the game's Master x Voice slider live (also mid-sentence). */
	static void volume(Clip c) {
		try {
			FloatControl f = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
			float g = Math.max(0.0001f, gain());
			f.setValue(Math.max(f.getMinimum(), Math.min(f.getMaximum(), (float) (20 * Math.log10(g)))));
		} catch (Exception ignored) {}
	}
}
