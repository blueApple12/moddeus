package dev.moddeus.client.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.moddeus.content.Vfx;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Renders custom VFX: each vfx is a list of layers, each with its own drawn texture (textures/vfx/<name>.png),
 * timing (delay/duration), size/color/alpha over life, spin and animation frames (vertical strip).
 * Layer types: billboard ring pillar beam slash sphere spiral orbit shards particles flash shake sound.
 * Any shape: "face": true turns it toward dir (yaw), "pitch": degrees tilts it (ring + face + pitch 90 = upright portal). Position x/y/z animates to x_end/y_end/z_end; "ease": out|in|inout. Glows (additive) unless "blend": "alpha".
 * Definitions are looked up every frame, so editing a vfx JSON changes effects that are already playing.
 */
public final class VfxClient {
	static final class Inst {
		Identifier id; Vec3 pos, dir; int follow; float start; final Set<Integer> fired = new HashSet<>();
	}
	static final List<Inst> ACTIVE = new CopyOnWriteArrayList<>();
	private static float shakeYaw, shakePitch;

	@OnReload
	public static void hook() {
		Vfx.client = VfxClient::play;
		if (System.getProperty("moddeus.vfx.client") != null) return;
		System.setProperty("moddeus.vfx.client", "1"); // events can't be unregistered: once per JVM, method refs only
		LevelRenderEvents.COLLECT_SUBMITS.register(VfxClient::render);
		ClientTickEvents.END_CLIENT_TICK.register(VfxClient::tick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("moddeus", "vfx_flash"), VfxClient::hud);
	}

	public static void play(Identifier id, double x, double y, double z, double dx, double dy, double dz, int follow) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.level == null) return;
			Inst i = new Inst();
			i.id = id; i.pos = new Vec3(x, y, z); i.dir = new Vec3(dx, dy, dz); i.follow = follow; i.start = now(mc);
			ACTIVE.add(i);
		});
	}

	private static float now(Minecraft mc) { return (float) ((System.nanoTime() - T0) / 5e7); } // real-time ticks: effects keep playing during /tick freeze (Time Stop)
	private static final long T0 = System.nanoTime() - (1L << 40); // offset keeps values stable whether or not this static was initialised by a swap

	// ---- json helpers ------------------------------------------------------------------------------------------
	private static float f(JsonObject o, String k, float d) { return o.has(k) ? o.get(k).getAsFloat() : d; }
	private static String s(JsonObject o, String k, String d) { return o.has(k) ? o.get(k).getAsString() : d; }
	private static int rgb(JsonObject o, String k, int d) { return o.has(k) ? Integer.parseInt(o.get(k).getAsString().replace("#", ""), 16) : d; }
	/** "ease": "out" (fast then slow, rising smoke), "in", "inout"; default linear. */
	private static float ease(JsonObject l, float t) {
		return switch (s(l, "ease", "linear")) {
			case "out" -> 1 - (1 - t) * (1 - t) * (1 - t);
			case "in" -> t * t;
			case "inout" -> t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
			default -> t;
		};
	}

	private static float lerp(JsonObject o, String a, String b, float da, float t) { float x = f(o, a, da); return x + (f(o, b, x) - x) * t; }
	private static int color(JsonObject l, float t, float alpha) {
		int c0 = rgb(l, "color", 0xFFFFFF), c1 = rgb(l, "color_end", c0);
		int r = (int) Mth.lerp(t, c0 >> 16 & 255, c1 >> 16 & 255), g = (int) Mth.lerp(t, c0 >> 8 & 255, c1 >> 8 & 255), b = (int) Mth.lerp(t, c0 & 255, c1 & 255);
		return (int) (Mth.clamp(alpha, 0, 1) * 255) << 24 | r << 16 | g << 8 | b;
	}
	private static JsonArray layers(Inst i) { JsonObject d = Vfx.DEFS.get(i.id); return d == null || !d.has("layers") ? new JsonArray() : d.getAsJsonArray("layers"); }
	private static float length(Inst i) {
		float max = 0;
		for (var e : layers(i)) { JsonObject l = e.getAsJsonObject(); max = Math.max(max, f(l, "delay", 0) + f(l, "duration", 20)); }
		return max;
	}
	/** Follows the entity, if any. */
	private static void track(Minecraft mc, Inst i) {
		if (i.follow < 0) return;
		Entity e = mc.level.getEntity(i.follow);
		if (e == null || !e.isAlive()) { i.follow = -1; JsonObject d = Vfx.DEFS.get(i.id); if (d != null && d.has("follow_only")) i.start = -1e6f; return; }
		i.pos = e.position(); i.dir = e.getLookAngle();
	}

	// ---- tick: one-shot layers (sound, particles) ----------------------------------------------------------------
	public static void tick(Minecraft mc) {
		if (mc.level == null) { ACTIVE.clear(); return; }
		float now = now(mc);
		for (Inst i : ACTIVE) {
			float age = now - i.start;
			if (age > length(i) + 1) { ACTIVE.remove(i); continue; }
			track(mc, i);
			JsonArray ls = layers(i);
			for (int n = 0; n < ls.size(); n++) {
				JsonObject l = ls.get(n).getAsJsonObject();
				float delay = f(l, "delay", 0), dur = f(l, "duration", 20);
				if (age < delay || age > delay + dur) continue;
				String type = s(l, "type", "billboard");
				Vec3 p = i.pos.add(f(l, "x", 0), f(l, "y", 0), f(l, "z", 0));
				if (type.equals("sound") && i.fired.add(n)) {
					Identifier sid = Identifier.parse(s(l, "sound", "minecraft:entity.generic.explode"));
					SoundEvent se = BuiltInRegistries.SOUND_EVENT.getOptional(sid).orElseGet(() -> SoundEvent.createVariableRangeEvent(sid));
					mc.level.playLocalSound(p.x, p.y, p.z, se, SoundSource.PLAYERS, f(l, "volume", 1), f(l, "pitch", 1), false);
				}
				if (type.equals("particles")) emit(mc, l, p, n, i);
			}
		}
	}

	private static void emit(Minecraft mc, JsonObject l, Vec3 p, int n, Inst i) {
		boolean stream = l.has("rate");
		if (!stream && !i.fired.add(n)) return;
		var type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(s(l, "particle", "minecraft:end_rod")));
		if (!(type instanceof ParticleOptions opt)) return;
		int count = stream ? (int) f(l, "rate", 2) : (int) f(l, "count", 30);
		float speed = f(l, "speed", 0.2f), r = f(l, "radius", 0);
		String pattern = s(l, "pattern", "burst");
		var rnd = mc.level.getRandom();
		for (int k = 0; k < count; k++) {
			double a = rnd.nextDouble() * Math.PI * 2, x = 0, y = 0, z = 0, vx, vy, vz;
			switch (pattern) {
				case "ring" -> { a = Math.PI * 2 * k / count; x = Math.cos(a) * r; z = Math.sin(a) * r; vx = Math.cos(a); vy = 0.05; vz = Math.sin(a); }
				case "up" -> { x = (rnd.nextDouble() - .5) * r * 2; z = (rnd.nextDouble() - .5) * r * 2; vx = rnd.nextGaussian() * .1; vy = 1; vz = rnd.nextGaussian() * .1; }
				case "inward" -> { double rr = Math.max(r, 1); x = Math.cos(a) * rr; y = rnd.nextDouble() * 2; z = Math.sin(a) * rr; vx = -x / rr; vy = 0; vz = -z / rr; }
				default -> { Vec3 v = new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize(); vx = v.x; vy = v.y; vz = v.z; }
			}
			mc.level.addParticle(opt, true, true, p.x + x, p.y + y, p.z + z, vx * speed, vy * speed, vz * speed);
		}
	}

	// ---- render ----------------------------------------------------------------------------------------------------
	/** VFX go to the "after terrain" phase like particles: drawn after water/glass and (CloudOrderMixin) after clouds, so nothing farther paints over them. */
	public static void late(LevelRenderContext ctx, PoseStack ps, RenderType rt, SubmitNodeCollector.CustomGeometryRenderer g) {
		if (ctx.submitNodeCollector() instanceof SubmitNodeStorage s) s.order(0).afterTerrain.submit(new CustomFeatureRenderer.Submit(ps.last().copy(), rt, g));
		else ctx.submitNodeCollector().submitCustomGeometry(ps, rt, g);
	}

	public static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		var cam = mc.gameRenderer.mainCamera();
		Vec3 cp = cam.position();
		Quaternionf rot = cam.rotation();
		float now = now(mc), shake = 0;
		PoseStack ps = ctx.poseStack();
		for (Inst i : ACTIVE) {
			float age = now - i.start;
			JsonArray ls = layers(i);
			for (int n = 0; n < ls.size(); n++) {
				JsonObject l = ls.get(n).getAsJsonObject();
				float delay = f(l, "delay", 0), dur = f(l, "duration", 20);
				if (age < delay || age > delay + dur) continue;
				float t = (age - delay) / dur;
				String type = s(l, "type", "billboard");
				if (type.equals("shake")) { shake = Math.max(shake, f(l, "size", 1) * (1 - t)); continue; }
				if (!l.has("texture")) continue;
				float te = ease(l, t); // motion/size easing; alpha stays linear
				float size = lerp(l, "size", "size_end", 1, te), alpha = lerp(l, "alpha", "alpha_end", 1, t);
				int col = color(l, t, alpha), frames = (int) f(l, "frames", 1);
				int frame = Math.min(frames - 1, (int) (t * frames * f(l, "loops", 1)) % frames);
				float v0 = frame / (float) frames, v1 = (frame + 1) / (float) frames, spin = (float) Math.toRadians(f(l, "spin", 0) * age);
				String tex = s(l, "texture", "");
				Identifier tid = tex.contains(":") ? Identifier.parse(tex) : Identifier.fromNamespaceAndPath("moddeus", "textures/vfx/" + tex + ".png");
				RenderType rt = "alpha".equals(s(l, "blend", "add")) ? RenderTypes.entityTranslucentEmissive(tid) : RenderTypes.eyes(tid);
				Vec3 p = i.pos.add(lerp(l, "x", "x_end", 0, te), lerp(l, "y", "y_end", 0, te), lerp(l, "z", "z_end", 0, te));
				Vec3 dir = i.dir.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : i.dir.normalize();
				ps.pushPose();
				ps.translate(p.x - cp.x, p.y - cp.y, p.z - cp.z);
				if (l.has("face")) ps.rotate(new Quaternionf().rotateY((float) Math.atan2(dir.x, dir.z)));
				if (l.has("pitch")) ps.rotate(new Quaternionf().rotateX((float) Math.toRadians(f(l, "pitch", 0))));
				final float fs = size;
				late(ctx, ps, rt, (pose, vc) -> {
					switch (type) {
						case "ring" -> ring(pose, vc, fs, spin, col, v0, v1);
						case "pillar" -> pillar(pose, vc, fs, lerp(l, "height", "height_end", 4, t), (int) f(l, "planes", 3), spin, col, v0, v1);
						case "beam" -> beam(pose, vc, dir, fs, (l.has("fit") ? (float) i.dir.length() : f(l, "length", 12)) * Math.min(1, t / Math.max(0.01f, f(l, "grow", 0.15f))), col, v0, v1);
						case "slash" -> slash(pose, vc, dir, fs, f(l, "width", 0.6f), f(l, "arc", 160), f(l, "tilt", 0), t, col, v0, v1);
						case "sphere" -> sphere(pose, vc, fs, f(l, "squash", 1), l.has("half"), spin, col, v0, v1);
						case "spiral" -> spiral(pose, vc, fs, lerp(l, "height", "height_end", 4, te), f(l, "turns", 2), (int) f(l, "arms", 2), f(l, "width", 0.4f), spin, Math.min(1, t / Math.max(0.01f, f(l, "grow", 0.4f))), f(l, "flare", 0), col, v0, v1);
						case "orbit" -> orbit(pose, vc, rot, fs, (int) f(l, "count", 6), f(l, "piece", 0.4f), spin + (float) Math.toRadians(f(l, "phase", 0)), f(l, "bob", 0.3f), age, col, v0, v1);
						case "shards" -> shards(pose, vc, rot, fs, (int) f(l, "count", 12), lerp(l, "piece", "piece_end", 0.4f, t), f(l, "low", -0.2f), col, v0, v1);
						default -> billboard(pose, vc, rot, fs, spin, col, v0, v1);
					}
				});
				ps.popPose();
			}
		}
		applyShake(mc, shake);
	}

	private static void applyShake(Minecraft mc, float deg) {
		var p = mc.player;
		if (p == null) return;
		float ny = deg > 0 ? (float) ((Math.random() - .5) * 2 * deg) : 0, np = deg > 0 ? (float) ((Math.random() - .5) * 2 * deg) : 0;
		if (ny == shakeYaw && np == shakePitch) return;
		p.setYRot(p.getYRot() - shakeYaw + ny);
		p.setXRot(p.getXRot() - shakePitch + np);
		shakeYaw = ny; shakePitch = np;
	}

	public static void hud(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		float now = now(mc);
		for (Inst i : ACTIVE) {
			float age = now - i.start;
			for (var e : layers(i)) {
				JsonObject l = e.getAsJsonObject();
				if (!"flash".equals(s(l, "type", ""))) continue;
				float delay = f(l, "delay", 0), dur = f(l, "duration", 6);
				if (age < delay || age > delay + dur) continue;
				float t = (age - delay) / dur;
				g.fill(0, 0, g.guiWidth(), g.guiHeight(), color(l, t, lerp(l, "alpha", "alpha_end", 0.6f, t) * (1 - t)));
			}
		}
	}

	// ---- geometry: every quad is emitted twice (both faces), full bright --------------------------------------------
	private static void v(PoseStack.Pose pose, VertexConsumer vc, Vector3f p, float u, float v, int col) {
		vc.addVertex(pose, p.x, p.y, p.z).setColor(col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
	}
	private static void quad(PoseStack.Pose pose, VertexConsumer vc, Vector3f a, Vector3f b, Vector3f c, Vector3f d, float u0, float u1, float v0, float v1, int col) {
		v(pose, vc, a, u0, v1, col); v(pose, vc, b, u1, v1, col); v(pose, vc, c, u1, v0, col); v(pose, vc, d, u0, v0, col);
		v(pose, vc, d, u0, v0, col); v(pose, vc, c, u1, v0, col); v(pose, vc, b, u1, v1, col); v(pose, vc, a, u0, v1, col);
	}
	public static void billboard(PoseStack.Pose pose, VertexConsumer vc, Quaternionf rot, float s, float spin, int col, float v0, float v1) {
		Quaternionf q = new Quaternionf(rot).rotateZ(spin);
		Vector3f a = q.transform(new Vector3f(-s, -s, 0)), b = q.transform(new Vector3f(s, -s, 0)), c = q.transform(new Vector3f(s, s, 0)), d = q.transform(new Vector3f(-s, s, 0));
		quad(pose, vc, a, b, c, d, 0, 1, v0, v1, col);
	}
	private static void ring(PoseStack.Pose pose, VertexConsumer vc, float s, float spin, int col, float v0, float v1) {
		float c = (float) Math.cos(spin) * s, sn = (float) Math.sin(spin) * s;
		quad(pose, vc, new Vector3f(-c + sn, 0.02f, -sn - c), new Vector3f(c + sn, 0.02f, sn - c), new Vector3f(c - sn, 0.02f, sn + c), new Vector3f(-c - sn, 0.02f, -sn + c), 0, 1, v0, v1, col);
	}
	private static void pillar(PoseStack.Pose pose, VertexConsumer vc, float r, float h, int planes, float spin, int col, float v0, float v1) {
		for (int k = 0; k < planes; k++) {
			double a = spin + Math.PI * k / planes;
			float x = (float) Math.cos(a) * r, z = (float) Math.sin(a) * r;
			quad(pose, vc, new Vector3f(-x, 0, -z), new Vector3f(x, 0, z), new Vector3f(x, h, z), new Vector3f(-x, h, -z), 0, 1, v0, v1, col);
		}
	}
	private static void beam(PoseStack.Pose pose, VertexConsumer vc, Vec3 dir, float w, float len, int col, float v0, float v1) {
		Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z), up = Math.abs(d.y) > 0.95f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
		Vector3f a = new Vector3f(d).cross(up).normalize(w / 2), b = new Vector3f(d).cross(a).normalize(w / 2);
		Vector3f o = new Vector3f(0, 1.4f, 0), e = new Vector3f(d).mul(len).add(o);
		for (Vector3f side : new Vector3f[] {a, b})
			quad(pose, vc, new Vector3f(o).sub(side), new Vector3f(o).add(side), new Vector3f(e).add(side), new Vector3f(e).sub(side), 0, 1, v0, v1, col);
	}
	/** Sweeping arc in front of dir: revealed over the first 40% of its life, tail fading behind. */
	private static void slash(PoseStack.Pose pose, VertexConsumer vc, Vec3 dir, float r, float w, float arcDeg, float tiltDeg, float t, int col, float v0, float v1) {
		float yaw = (float) Math.atan2(dir.z, dir.x), arc = (float) Math.toRadians(arcDeg), head = Math.min(1, t / 0.4f), tail = Math.max(0, head - 0.7f);
		Quaternionf tilt = new Quaternionf().rotateAxis((float) Math.toRadians(tiltDeg), (float) dir.x, 0, (float) dir.z);
		int segs = 16;
		for (int k = 0; k < segs; k++) {
			float f0 = tail + (head - tail) * k / segs, f1 = tail + (head - tail) * (k + 1) / segs;
			float a0 = yaw - arc / 2 + arc * f0, a1 = yaw - arc / 2 + arc * f1;
			Vector3f i0 = pt(a0, r - w / 2, tilt), o0 = pt(a0, r + w / 2, tilt), i1 = pt(a1, r - w / 2, tilt), o1 = pt(a1, r + w / 2, tilt);
			quad(pose, vc, i0, i1, o1, o0, (float) k / segs, (float) (k + 1) / segs, v0, v1, col);
		}
	}
	/** Textured sphere (or dome with "half"), centred on the layer position; "squash" scales it vertically. */
	private static void sphere(PoseStack.Pose pose, VertexConsumer vc, float r, float squash, boolean half, float spin, int col, float v0, float v1) {
		int lat = half ? 6 : 12, lon = 18;
		double top = half ? Math.PI / 2 : Math.PI;
		for (int i = 0; i < lat; i++) for (int j = 0; j < lon; j++) {
			double t0 = top * i / lat, t1 = top * (i + 1) / lat, p0 = spin + 2 * Math.PI * j / lon, p1 = spin + 2 * Math.PI * (j + 1) / lon;
			float fv0 = v0 + (v1 - v0) * i / lat, fv1 = v0 + (v1 - v0) * (i + 1) / lat;
			quad(pose, vc, sp(r, squash, t1, p0), sp(r, squash, t1, p1), sp(r, squash, t0, p1), sp(r, squash, t0, p0), (float) j / lon, (float) (j + 1) / lon, fv0, fv1, col);
		}
	}
	private static Vector3f sp(float r, float sq, double th, double ph) { return new Vector3f((float) (Math.sin(th) * Math.cos(ph) * r), (float) (Math.cos(th) * r * sq), (float) (Math.sin(th) * Math.sin(ph) * r)); }
	/** Helix ribbons ("arms") of radius r climbing to height h (height ~0 = flat spiral disc, like an accretion disk); grows in over "grow". */
	private static void spiral(PoseStack.Pose pose, VertexConsumer vc, float r, float h, float turns, int arms, float w, float spin, float reveal, float flare, int col, float v0, float v1) {
		int segs = 40;
		for (int a = 0; a < arms; a++) for (int k = 0; k < segs * reveal; k++) {
			float f0 = (float) k / segs, f1 = (float) (k + 1) / segs;
			double a0 = spin + 2 * Math.PI * (turns * f0 + (double) a / arms), a1 = spin + 2 * Math.PI * (turns * f1 + (double) a / arms);
			float r0 = h < 0.5f ? r * (0.25f + 0.75f * f0) : r * (1 + flare * f0), r1 = h < 0.5f ? r * (0.25f + 0.75f * f1) : r * (1 + flare * f1); // flare: funnel widening with height
			Vector3f p0 = new Vector3f((float) Math.cos(a0) * r0, h * f0, (float) Math.sin(a0) * r0), p1 = new Vector3f((float) Math.cos(a1) * r1, h * f1, (float) Math.sin(a1) * r1);
			Vector3f q0 = h < 0.5f ? new Vector3f(p0).mul(1 + w / Math.max(r0, 0.1f), 1, 1 + w / Math.max(r0, 0.1f)) : new Vector3f(p0).add(0, w, 0);
			Vector3f q1 = h < 0.5f ? new Vector3f(p1).mul(1 + w / Math.max(r1, 0.1f), 1, 1 + w / Math.max(r1, 0.1f)) : new Vector3f(p1).add(0, w, 0);
			float fv0 = v0 + (v1 - v0) * f0, fv1 = v0 + (v1 - v0) * f1;
			v(pose, vc, p0, 0, fv0, col); v(pose, vc, q0, 1, fv0, col); v(pose, vc, q1, 1, fv1, col); v(pose, vc, p1, 0, fv1, col);
			v(pose, vc, p1, 0, fv1, col); v(pose, vc, q1, 1, fv1, col); v(pose, vc, q0, 1, fv0, col); v(pose, vc, p0, 0, fv0, col);
		}
	}
	private static void bb(PoseStack.Pose pose, VertexConsumer vc, Quaternionf rot, Vector3f c, float s, int col, float v0, float v1) {
		quad(pose, vc, rot.transform(new Vector3f(-s, -s, 0)).add(c), rot.transform(new Vector3f(s, -s, 0)).add(c), rot.transform(new Vector3f(s, s, 0)).add(c), rot.transform(new Vector3f(-s, s, 0)).add(c), 0, 1, v0, v1, col);
	}
	/** "count" glowing pieces circling at radius r, bobbing. */
	private static void orbit(PoseStack.Pose pose, VertexConsumer vc, Quaternionf rot, float r, int count, float piece, float spin, float bob, float age, int col, float v0, float v1) {
		for (int k = 0; k < count; k++) {
			double a = spin + 2 * Math.PI * k / count;
			bb(pose, vc, rot, new Vector3f((float) Math.cos(a) * r, (float) Math.sin(age * 0.15 + k * 1.7) * bob, (float) Math.sin(a) * r), piece, col, v0, v1);
		}
	}
	/** "count" pieces spread over a sphere at distance d (size): grow d to burst outward, shrink it to converge. "low" = lowest direction y (-1 full sphere). */
	private static void shards(PoseStack.Pose pose, VertexConsumer vc, Quaternionf rot, float d, int count, float piece, float low, int col, float v0, float v1) {
		for (int k = 0; k < count; k++) {
			double y = low + (1 - low) * ((k * 0.618034 + 0.31) % 1), rr = Math.sqrt(Math.max(0, 1 - y * y)), az = k * 2.399963;
			bb(pose, vc, rot, new Vector3f((float) (Math.cos(az) * rr * d), (float) (y * d), (float) (Math.sin(az) * rr * d)), piece, col, v0, v1);
		}
	}
	private static Vector3f pt(float a, float rad, Quaternionf tilt) { return tilt.transform(new Vector3f((float) Math.cos(a) * rad, 0, (float) Math.sin(a) * rad)).add(0, 1.1f, 0); }
}
