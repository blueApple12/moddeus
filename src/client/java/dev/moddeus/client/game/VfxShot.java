package dev.moddeus.client.game;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * Offscreen shots of a mod's OWN world geometry (custom VFX: submitCustomGeometry) from a virtual perspective camera, a few
 * frames over time, never the player's camera or screen. No terrain or entities: a stone ground plane gives scale. The mod
 * passes a Scene that submits its geometry relative to {@code cam} and faces billboards with {@code camRot} (view -> world,
 * like Camera.rotation()). PNGs + result JSON land next to Studio's: run/moddeus/shots/studio/<name>.json
 * ({sheet, files, views, cols} or {error}). One frame per client tick at most.
 */
public final class VfxShot {
	public interface Scene { void submit(SubmitNodeCollector nodes, PoseStack ps, Vec3 cam, Quaternionf camRot); }

	static final class Job {
		String name; Vec3 cam, target; float fov; int size, frames, every, next, wait, pending; double groundY; Scene scene;
		int[][] px; GpuTexture tex, depth; GpuTextureView texView, depthView; boolean dead; long started;
	}

	static ConcurrentLinkedQueue<Job> queue;
	static Job job;
	static Projection proj;
	static ProjectionMatrixBuffer pmb;
	static final Vector4f BG = new Vector4f(0.46f, 0.53f, 0.62f, 1f);
	static final Identifier GROUND = Identifier.fromNamespaceAndPath("minecraft", "textures/block/stone.png");

	/** frames: 1..8, every: client ticks between frames, groundY: NaN = no ground plane. Returns the job name. */
	public static String queue(String name, Vec3 cam, Vec3 target, float fov, int size, int frames, int every, double groundY, Scene scene) {
		hook();
		Job j = new Job();
		j.name = name.replaceAll("[^A-Za-z0-9_]", "_") + "_" + Long.toString(System.nanoTime(), 36);
		j.cam = cam; j.target = target; j.fov = fov; j.size = Math.max(64, Math.min(1024, Math.round(size / 64f) * 64));
		j.frames = Math.max(1, Math.min(8, frames)); j.every = Math.max(1, every); j.groundY = groundY; j.scene = scene;
		j.px = new int[j.frames][];
		if (queue == null) queue = new ConcurrentLinkedQueue<>();
		queue.add(j);
		return j.name;
	}

	@dev.moddeus.live.OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.vfxshot", Boolean.TRUE) == null) ClientTickEvents.END_CLIENT_TICK.register(VfxShot::tick);
	}

	static void tick(Minecraft mc) {
		if (job == null) {
			Job o = queue == null ? null : queue.poll();
			if (o == null) return;
			job = o;
			job.started = System.currentTimeMillis();
		}
		Job j = job;
		try {
			if (mc.level == null) throw new IllegalStateException("no world loaded");
			if (j.next < j.frames) {
				if (j.wait-- > 0) return;
				render(mc, j, j.next++);
				j.wait = j.every - 1;
			} else if (j.pending == 0) { close(j); write(j); job = null; }
			else if (System.currentTimeMillis() - j.started > 30000) fail(j, new IllegalStateException("GPU readback timed out"));
		} catch (Throwable t) { fail(j, t); }
	}

	static void render(Minecraft mc, Job j, int idx) {
		int w = j.size, h = j.size;
		GpuDevice gpu = RenderSystem.getDevice();
		if (j.tex == null) {
			j.tex = gpu.createTexture(() -> "moddeus vfxshot", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			j.texView = gpu.createTextureView(j.tex);
			j.depth = gpu.createTexture(() -> "moddeus vfxshot depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, w, h, 1, 1);
			j.depthView = gpu.createTextureView(j.depth);
		}
		if (proj == null) { proj = new Projection(); pmb = new ProjectionMatrixBuffer("moddeus vfxshot"); }
		Vec3 d = j.target.subtract(j.cam).normalize();
		Matrix4f view = new Matrix4f().lookAlong((float) d.x, (float) d.y, (float) d.z, 0, 1, 0);
		Quaternionf camRot = view.getNormalizedRotation(new Quaternionf()).conjugate();
		SubmitNodeStorage nodes = new SubmitNodeStorage();
		PoseStack ps = new PoseStack();
		if (!Double.isNaN(j.groundY)) ground(nodes, ps, j);
		j.scene.submit(nodes, ps, j.cam, camRot); // before any GPU command: a broken scene fails cleanly
		CommandEncoder enc = gpu.createCommandEncoder();
		enc.clearColorAndDepthTextures(j.tex, BG, j.depth, 0.0);
		proj.setupPerspective(0.05f, 3000f, j.fov, w, h);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(pmb.getBuffer(proj), ProjectionType.PERSPECTIVE);
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix().identity().mul(view);
		try {
			mc.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
			FeatureRenderDispatcher.PreparedFrame frame = mc.gameRenderer.featureRenderDispatcher().prepareFrame(nodes);
			try {
				RenderPass pass = enc.createRenderPass(() -> "moddeus vfxshot", j.texView, Optional.empty(), j.depthView, OptionalDouble.empty());
				try {
					RenderSystem.bindDefaultUniforms(pass);
					FeatureRenderDispatcher.renderAllFeatures(pass, frame);
				} finally { pass.close(); }
			} finally { frame.close(); }
		} finally {
			mv.popMatrix();
			RenderSystem.restoreProjectionMatrix();
		}
		GpuBuffer buf = gpu.createBuffer(() -> "moddeus vfxshot readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
		j.pending++;
		try {
			enc.copyTextureToBuffer(j.tex, buf, 0, () -> {
				try (var v = buf.map(true, false)) {
					int[] a = new int[w * h];
					v.data().asIntBuffer().get(a);
					j.px[idx] = a;
				} catch (Throwable t) {
					dev.moddeus.Moddeus.LOG.warn("vfxshot readback", t);
				} finally {
					buf.close();
					if (--j.pending == 0 && j.dead) close(j);
				}
			}, 0);
		} catch (RuntimeException e) { buf.close(); j.pending--; throw e; }
	}

	/** 320x320 block stone floor (8-block tiles) under the target, for scale */
	static void ground(SubmitNodeStorage nodes, PoseStack ps, Job j) {
		float y = (float) (j.groundY - j.cam.y);
		double cx = Math.floor(j.target.x / 8) * 8 - j.cam.x, cz = Math.floor(j.target.z / 8) * 8 - j.cam.z;
		nodes.submitCustomGeometry(ps, RenderTypes.entityCutout(GROUND), (pose, vc) -> {
			for (int a = -20; a < 20; a++) for (int b = -20; b < 20; b++) {
				float x0 = (float) (cx + a * 8), z0 = (float) (cz + b * 8), x1 = x0 + 8, z1 = z0 + 8;
				vc.addVertex(pose, x0, y, z0).setColor(0xFF8A8A8A).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
				vc.addVertex(pose, x0, y, z1).setColor(0xFF8A8A8A).setUv(0, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
				vc.addVertex(pose, x1, y, z1).setColor(0xFF8A8A8A).setUv(1, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
				vc.addVertex(pose, x1, y, z0).setColor(0xFF8A8A8A).setUv(1, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
			}
		});
	}

	static void close(Job j) {
		if (j.texView != null) j.texView.close();
		if (j.tex != null) j.tex.close();
		if (j.depthView != null) j.depthView.close();
		if (j.depth != null) j.depth.close();
		j.tex = j.depth = null;
		j.texView = j.depthView = null;
	}

	static void fail(Job j, Throwable t) {
		dev.moddeus.Moddeus.LOG.warn("vfxshot {} failed", j.name, t);
		if (j.pending == 0) close(j); else j.dead = true;
		JsonObject r = new JsonObject();
		r.addProperty("error", String.valueOf(t));
		done(j.name, r);
		job = null;
	}

	static void write(Job j) {
		Thread.startVirtualThread(() -> {
			JsonObject r = new JsonObject();
			try {
				Path dir = Path.of("moddeus", "shots", "studio");
				Files.createDirectories(dir);
				int n = j.frames, size = j.size, cols = Math.min(n, 4), rows = (n + cols - 1) / cols;
				JsonArray files = new JsonArray(), views = new JsonArray();
				try (NativeImage sheet = new NativeImage(cols * size, rows * size, false)) {
					for (int i = 0; i < n; i++) {
						int[] a = j.px[i];
						if (a == null) continue;
						int ox = (i % cols) * size, oy = (i / cols) * size;
						try (NativeImage img = new NativeImage(size, size, false)) {
							for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
								int c = a[(size - 1 - y) * size + x] | 0xFF000000; // GPU rows are bottom-up
								img.setPixelABGR(x, y, c);
								sheet.setPixelABGR(ox + x, oy + y, c);
							}
							Path f = dir.resolve(j.name + "_f" + i + ".png");
							img.writeToFile(f);
							files.add(f.toAbsolutePath().toString());
							views.add("t+" + (i * j.every) + "ticks");
						}
					}
					Path sp = dir.resolve(j.name + "_sheet.png");
					sheet.writeToFile(sp);
					r.addProperty("sheet", sp.toAbsolutePath().toString());
				}
				r.add("files", files);
				r.add("views", views);
				r.addProperty("cols", cols);
			} catch (Throwable t) { r.addProperty("error", String.valueOf(t)); }
			done(j.name, r);
		});
	}

	static void done(String name, JsonObject r) {
		try {
			Path dir = Path.of("moddeus", "shots", "studio");
			Files.createDirectories(dir);
			Path tmp = dir.resolve(name + ".json.tmp");
			Files.writeString(tmp, r.toString());
			Files.move(tmp, dir.resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) { dev.moddeus.Moddeus.LOG.warn("vfxshot result: {}", e.toString()); }
	}
}
