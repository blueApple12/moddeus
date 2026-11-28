package dev.moddeus.client;

import dev.moddeus.live.OnReload;
import dev.moddeus.live.Workspace;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import java.nio.file.Files;

/**
 * Claude's spoken voice (tools/bridge/tts.py) plays outside Minecraft, so it ignored the game's volume and spoke while the game
 * was muted. The game writes Master x Voice/Speech volume to run/moddeus/volume.json whenever it changes; tts.py scales by it
 * (0 = silent) before every sentence.
 */
public final class TtsVolume {
	private TtsVolume() {}

	static float last = -1;
	static int t;

	@OnReload
	public static void init() {
		if (System.getProperties().putIfAbsent("moddeus.ttsvolume", Boolean.TRUE) == null) ClientTickEvents.END_CLIENT_TICK.register(TtsVolume::tick);
		last = -1; // rewrite on the next tick
	}

	static void tick(Minecraft mc) {
		if (++t % 10 != 0 || Workspace.project == null) return;
		float v = mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.VOICE);
		if (Math.abs(v - last) < 0.005f && t % 100 != 0) return; // every 5 s anyway: heartbeat, tts.py plays through the game only while it's alive
		last = v;
		String json = "{\"volume\": " + v + "}";
		Thread.startVirtualThread(() -> {
			try {
				var f = Workspace.project.resolve("run/moddeus/volume.json");
				var tmp = f.resolveSibling("volume.json.tmp");
				Files.writeString(tmp, json);
				Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception ignored) {}
		});
	}
}
