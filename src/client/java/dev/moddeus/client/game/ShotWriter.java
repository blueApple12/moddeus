package dev.moddeus.client.game;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Encodes screenshots off the render thread (PNG encode on it froze the game ~200 ms per shot). */
public final class ShotWriter {
	private ShotWriter() {}

	public static void save(NativeImage img, Path file, CompletableFuture<Void> done) {
		Thread.startVirtualThread(() -> {
			try { img.writeToFile(file); done.complete(null); } catch (Throwable t) { done.completeExceptionally(t); } finally { img.close(); }
		});
	}
}
