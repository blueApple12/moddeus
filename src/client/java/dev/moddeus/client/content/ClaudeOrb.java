package dev.moddeus.client.content;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Moddeus in the world: the floating 3D mascot (cube-head god) (client-only, no entity, no AI). It follows the player, rays pulse with
 * Claude's voice (TTS loudness envelope from the bridge) and with the player's mic while G is held, spins while Claude works.
 * Right-click it = type to the Voice chat. Controlled through GameApi "buddy" (follow/come/stay/teleport/fly/say/spin).
 */
public final class ClaudeOrb {
	public static boolean enabled = true;
	public static volatile boolean listening, busy;
	public static volatile String status = "", speech = "";
	static String mode = "follow", said = "";
	static Vec3 pos, stayAt, renderPos;
	static Object dim;
	static float level, spin, sizeMul = 1;
	static long lastNs, flashAt, spinUntil, saidUntil;
	static volatile float[] env;
	static volatile long envAt;
	static volatile int envHz = 30;
	static volatile float mic;
	static volatile Thread micThread;
	static volatile boolean micFailed;

	// Moddeus palette (pixel-art ramps): outline, violet body ramp, teal eyes/core, gold halo, white, blush
	static final int K = 0x140B26, D = 0x2A1650, V = 0x4A2A8A, L = 0x6E4CCF, H = 0xA58BF0, T = 0x1FD6C4, Tl = 0x0F8F8F, W = 0xF2FFF8, G = 0xFFCC3D, Gd = 0xC7861F, Y = 0xFFF0A0, R = 0xFF6FA8;
	static final float[] ANG = {0, 31, 58, 92, 118, 152, 181, 209, 238, 270, 300, 331};
	static final float[] LEN = {1.0f, 0.8f, 0.93f, 0.84f, 0.97f, 0.78f, 0.95f, 0.86f, 0.9f, 0.8f, 0.99f, 0.83f};

	static Identifier tex(String n) { return Identifier.fromNamespaceAndPath("moddeus", "textures/vfx/" + n + ".png"); }

	@OnReload
	public static void hook() {
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.orb", Boolean.TRUE) != null) return;
		LevelRenderEvents.COLLECT_SUBMITS.register(ClaudeOrb::render);
		ClientTickEvents.START_CLIENT_TICK.register(ClaudeOrb::clicks);
	}

	@OnUnload
	public static void off() { enabled = false; listening = false; }

	// ------------------------------------------------------------------ voice

	/** TTS loudness envelope (0..1, hz samples per second) for the sentence that starts playing now. */
	public static void speak(float[] e, int hz) { env = e; envHz = Math.max(1, hz); envAt = System.currentTimeMillis(); }
	public static void hush() { env = null; }
	public static void flash() { flashAt = System.currentTimeMillis(); }
	public static void heard(String text) { say("\"" + (text.length() > 80 ? text.substring(0, 80) + "..." : text) + "\"", 3000); }
	static void say(String text, long ms) { said = text; saidUntil = System.currentTimeMillis() + ms; }

	static float envAt(long at) {
		float[] e = env;
		if (e == null) return 0;
		float i = (at - envAt) * envHz / 1000f;
		if (i < 0) return 0;
		int a = (int) i;
		if (a >= e.length - 1) { if (a >= e.length + envHz) env = null; return a < e.length ? e[a] : 0; }
		return e[a] + (e[a + 1] - e[a]) * (i - a);
	}

	/** Player's mic level while G is held (a second, read-only capture; Claude Code does the actual dictation). */
	static void micCheck() {
		if (listening && micThread == null && !micFailed) {
			Thread t = new Thread(ClaudeOrb::micLoop, "claude-orb-mic");
			t.setDaemon(true);
			micThread = t;
			t.start();
		}
	}

	static void micLoop() {
		AudioFormat f = new AudioFormat(16000, 16, 1, true, false);
		try (TargetDataLine line = AudioSystem.getTargetDataLine(f)) {
			line.open(f, 3200);
			line.start();
			byte[] b = new byte[640];
			while (listening && enabled) {
				int n = line.read(b, 0, b.length);
				double sum = 0;
				for (int i = 0; i + 1 < n; i += 2) { double s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8)) / 32768.0; sum += s * s; }
				mic = (float) Math.min(1, Math.sqrt(sum / Math.max(1, n / 2)) * 7);
			}
		} catch (Throwable t) {
			micFailed = true;
			dev.moddeus.Moddeus.LOG.warn("orb mic unavailable: {}", t.toString());
		} finally {
			mic = 0;
			micThread = null;
		}
	}

	// ------------------------------------------------------------------ movement

	static Vec3 vel, roam;
	static long roamUntil;
	static java.util.Random rng;
	static java.util.Random rng() { if (rng == null) rng = new java.util.Random(); return rng; } // lazy: statics added by a swap start null

	/** Where the orb wants to be: a wander point around the player (re-picked every few seconds), a stay spot, or a fly target. */
	static Vec3 goal(Minecraft mc, float pt, boolean active, long now) {
		if ("stay".equals(mode) && stayAt != null) return stayAt;
		var p = mc.player;
		Vec3 feet = p.getPosition(pt);
		boolean far = roam == null || roam.distanceTo(feet) > (active ? 5 : 7.5) || now > roamUntil;
		if (far) {
			for (int i = 0; i < 12; i++) { // a free air spot near the player that it can see
				double yaw = Math.toRadians(p.getViewYRot(pt)) + (active ? (rng().nextDouble() - 0.5) * 1.4 : rng().nextDouble() * Math.PI * 2);
				double r = active ? 2.4 + rng().nextDouble() * 1.2 : 2.5 + rng().nextDouble() * 3.5;
				Vec3 c = feet.add(-Math.sin(yaw) * r, 1.2 + rng().nextDouble() * (active ? 1.0 : 2.2), Math.cos(yaw) * r);
				if (solid(mc, c) || solid(mc, c.add(0, 0.5, 0))) continue;
				if (mc.level.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(pt), c, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
				roam = c;
				break;
			}
			if (roam == null || roam.distanceTo(feet) > 9) roam = feet.add(0, 2.2, 0);
			roamUntil = now + (active ? 2500 : 3500 + rng().nextInt(5000));
		}
		return roam;
	}

	static boolean solid(Minecraft mc, Vec3 v) {
		net.minecraft.core.BlockPos b = net.minecraft.core.BlockPos.containing(v);
		return !mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty();
	}

	/** Flies like a little creature: steering toward the goal with a top speed (faster when far), easing in, sliding around blocks. */
	static void move(Minecraft mc, Vec3 g, float dt) {
		if (vel == null) vel = Vec3.ZERO;
		Vec3 to = g.subtract(pos);
		double dist = to.length();
		double player = mc.player.position().distanceTo(pos);
		double top = "stay".equals(mode) ? (stayAt != null && dist > 12 ? 14 : 5) : player > 10 ? 12 : 3.2;
		Vec3 want = dist < 1e-3 ? Vec3.ZERO : to.scale(Math.min(top, dist * 1.6) / dist);
		vel = vel.add(want.subtract(vel).scale(1 - Math.exp(-dt * 3.2)));
		Vec3 next = pos.add(vel.scale(dt));
		if (solid(mc, next)) { // slide along axes, else lift over
			Vec3 n = pos;
			if (!solid(mc, new Vec3(next.x, n.y, n.z))) n = new Vec3(next.x, n.y, n.z); else vel = new Vec3(0, vel.y, vel.z);
			if (!solid(mc, new Vec3(n.x, next.y, n.z))) n = new Vec3(n.x, next.y, n.z); else vel = new Vec3(vel.x, 0, vel.z);
			if (!solid(mc, new Vec3(n.x, n.y, next.z))) n = new Vec3(n.x, n.y, next.z); else vel = new Vec3(vel.x, vel.y, 0);
			if (n == pos) { n = pos.add(0, 2.5 * dt, 0); vel = new Vec3(0, 1, 0); }
			next = n;
		}
		pos = next;
	}

	// ------------------------------------------------------------------ render

	static int renderErrors;

	public static void render(LevelRenderContext ctx) {
		try { draw(ctx); } catch (RuntimeException e) { if (renderErrors++ < 3) dev.moddeus.Moddeus.LOG.warn("orb render", e); }
	}

	static void draw(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled || mc.player == null || mc.level == null) { renderPos = null; return; }
		long ns = System.nanoTime(), now = System.currentTimeMillis();
		float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (ns - lastNs) / 1e9f), time = (float) ((ns % 3_600_000_000_000L) / 1e9);
		lastNs = ns;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		micCheck();
		float tts = envAt(now), voice = Math.max(tts, listening ? (micFailed ? 0.25f + 0.15f * (float) Math.sin(time * 5) : mic) : 0);
		level += (voice - level) * (1 - (float) Math.exp(-dt * (voice > level ? 30 : 9)));
		boolean active = env != null || listening || now < saidUntil || !speech.isEmpty();
		Vec3 g = goal(mc, pt, active, now);
		if (pos == null || dim != mc.level.dimension() || (!"stay".equals(mode) && mc.player.position().distanceTo(pos) > 48)) { pos = g; vel = Vec3.ZERO; dim = mc.level.dimension(); }
		move(mc, g, dt);
		renderPos = pos;

		var cam = mc.gameRenderer.mainCamera();
		Vec3 cp = cam.position();
		if (cp.distanceTo(pos) < 0.45) return;
		spin += dt * (0.35f + (busy ? 2.4f : 0) + (now < spinUntil ? 9 : 0) + level * 1.2f);
		float flash = Math.max(0, 1 - (now - flashAt) / 400f);
		float s = 0.55f * sizeMul * (1 + 0.05f * level + 0.2f * flash);

		PoseStack ps = ctx.poseStack();
		ps.pushPose();
		ps.translate(pos.x - cp.x, pos.y - cp.y, pos.z - cp.z);
		Vec3 d = cp.subtract(pos);
		double spd = vel == null ? 0 : vel.length();
		if (spd > 1.5) d = d.normalize().scale(1 - Math.min(0.85, (spd - 1.5) / 4)).add(vel.normalize().scale(Math.min(0.85, (spd - 1.5) / 4)));
		Quaternionf face = new Quaternionf().rotateY((float) Math.atan2(d.x, d.z)).rotateX((float) Math.max(-0.45, Math.min(0.45, -Math.atan2(d.y, Math.hypot(d.x, d.z))))) // limited tilt: halo stays on top
			.rotateY(0.5f * (float) Math.sin(time * 0.6)).rotateX(0.22f * (float) Math.sin(time * 0.43));
		ps.rotate(face);
		if (now < spinUntil) ps.rotate(new Quaternionf().rotateY((spinUntil - now) * 0.012f)); // "spin"/"dance": whole body twirls
		ps.scale(s, s, s);
		float[] amp = new float[ANG.length];
		for (int i = 0; i < amp.length; i++) {
			float a = env != null ? envAt(now - i * 28L) : listening ? level * (0.7f + 0.3f * (float) Math.sin(time * 11 + i * 2.1f)) : 0;
			if (busy) a += 0.12f * (0.5f + 0.5f * (float) Math.sin(time * 6 + i * 1.3f));
			amp[i] = a;
		}
		final float sp = spin;
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entityCutout(tex("orb_white")), (pose, vc) -> mascot(pose, vc, amp, sp, time, now));
		ps.popPose(); // no glow disc / ring / speech bubble: status lives in the top HUD pill
	}

	/** Speech bubble: what Claude says / is doing / just heard. */
	static void bubble(LevelRenderContext ctx, Minecraft mc, Vec3 cp, float s, long now) {
		String text = !speech.isEmpty() ? speech : now < saidUntil ? said : status;
		if (text == null || text.isEmpty() || cp.distanceTo(pos) > 24) return;
		int color = !speech.isEmpty() ? 0xFFFFFF : now < saidUntil ? 0xF2FFF8 : 0xA58BF0;
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String w : text.split(" ")) {
			if (cur.length() + w.length() > 38 && cur.length() > 0) { lines.add(cur.toString()); cur.setLength(0); }
			if (cur.length() > 0) cur.append(' ');
			cur.append(w);
		}
		if (cur.length() > 0) lines.add(cur.toString());
		if (lines.size() > 4) { lines = new java.util.ArrayList<>(lines.subList(0, 4)); lines.set(3, lines.get(3) + "..."); }
		PoseStack ps = ctx.poseStack();
		ps.pushPose();
		ps.translate(pos.x - cp.x, pos.y - cp.y, pos.z - cp.z);
		var camState = ctx.levelState().cameraRenderState;
		for (int i = 0; i < lines.size(); i++)
			ctx.submitNodeCollector().submitNameTag(ps, new Vec3(0, s * 1.3 + 0.25, 0), -(lines.size() - 1 - i) * 10, Component.literal(lines.get(i)).withColor(color), true, 0xF000F0, camState);
		ps.popPose();
	}

	/** The Moddeus mascot: violet cube-head god with teal eyes, gold halo, floating body + hands, teal core. Front = +Z. */
	static void mascot(PoseStack.Pose pose, VertexConsumer vc, float[] amp, float spin, float time, long now) {
		float talk = 0;
		for (float a : amp) talk = Math.max(talk, a);
		float bob = 0.035f * (float) Math.sin(time * 2.2f);
		boolean blink = (now % 4300) < 110 && !listening;
		// head
		box(pose, vc, -0.4f, -0.15f + bob, -0.4f, 0.4f, 0.65f + bob, 0.4f, V, L, H);
		// eyes: wide while listening, glance around while working, blink now and then
		float eh = blink ? 0.03f : listening ? 0.26f : 0.21f, ey = 0.3f + bob, look = busy ? 0.05f * (float) Math.sin(time * 4) : 0;
		for (float sx : new float[] {-1, 1}) {
			float x0 = sx < 0 ? -0.27f : 0.08f, x1 = sx < 0 ? -0.08f : 0.27f;
			box(pose, vc, x0 + look, ey - eh / 2, 0.4f, x1 + look, ey + eh / 2, 0.44f, Tl, T, T);
			if (!blink) box(pose, vc, x0 + look + 0.03f, ey + eh / 2 - 0.08f, 0.44f, x0 + look + 0.09f, ey + eh / 2 - 0.02f, 0.46f, W, W, W);
			box(pose, vc, sx * 0.28f - 0.04f, 0.06f + bob, 0.4f, sx * 0.28f + 0.04f, 0.11f + bob, 0.42f, R, R, R); // cheeks
		}
		// mouth opens with the voice
		float open = 0.03f + 0.15f * Math.min(1, talk);
		box(pose, vc, -0.09f, 0.07f + bob - open, 0.4f, 0.09f, 0.07f + bob, 0.43f, K, K, K);
		if (talk > 0.15f) box(pose, vc, -0.05f, 0.075f + bob - open, 0.4f, 0.05f, 0.07f + bob - open + 0.05f, 0.435f, R, R, R);
		// halo: gold ring that spins faster while working
		int n = 14;
		for (int i = 0; i < n; i++) {
			double a = spin * 1.4 + i * Math.PI * 2 / n;
			float hx = 0.36f * (float) Math.cos(a), hz = 0.36f * (float) Math.sin(a), hy = 0.86f + bob + 0.06f * (float) Math.sin(a + time);
			int c = i % 2 == 0 ? G : Y;
			box(pose, vc, hx - 0.045f, hy - 0.03f, hz - 0.045f, hx + 0.045f, hy + 0.03f, hz + 0.045f, Gd, c, c);
		}
		// body + core gem (pulses with the voice, bright while working)
		float bb = 0.05f * (float) Math.sin(time * 2.2f + 0.7f);
		box(pose, vc, -0.22f, -0.52f + bb, -0.2f, 0.22f, -0.22f + bb, 0.2f, D, V, L);
		float glow = Math.min(1, 0.35f + 0.65f * talk + (busy ? 0.3f + 0.3f * (float) Math.sin(time * 6) : 0));
		box(pose, vc, -0.07f, -0.44f + bb, 0.2f, 0.07f, -0.3f + bb, 0.25f, mix(Tl, W, glow * 0.3f), mix(Tl, T, glow), T);
		// floating hands: bob idle, type fast while working
		for (float sx : new float[] {-1, 1}) {
			float hy = -0.3f + (busy ? 0.05f * (float) Math.sin(time * 14 + sx * 1.7f) : 0.05f * (float) Math.sin(time * 3 + sx));
			box(pose, vc, sx * 0.4f - 0.065f, hy - 0.065f, -0.02f, sx * 0.4f + 0.065f, hy + 0.065f, 0.11f, V, L, H);
		}
	}

	/** Axis box with a vertical gradient (lo at the bottom, hi at the top) on the sides and a flat top color. */
	static void box(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int lo, int hi, int top) {
		Vector3f c = new Vector3f((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2);
		Vector3f a = new Vector3f(x0, y0, z0), b = new Vector3f(x1, y0, z0), cc = new Vector3f(x1, y1, z0), d = new Vector3f(x0, y1, z0);
		Vector3f e = new Vector3f(x0, y0, z1), f = new Vector3f(x1, y0, z1), g = new Vector3f(x1, y1, z1), h = new Vector3f(x0, y1, z1);
		int sl = scale(lo, 0.8f) & 0xFFFFFF, sh = scale(hi, 0.8f) & 0xFFFFFF;
		face(pose, vc, c, e, f, g, h, lo, hi);
		face(pose, vc, c, a, b, cc, d, sl, sh);
		face(pose, vc, c, a, e, h, d, sl, sh);
		face(pose, vc, c, b, f, g, cc, sl, sh);
		face(pose, vc, c, d, h, g, cc, top, top);
		int bot = scale(lo, 0.6f) & 0xFFFFFF;
		face(pose, vc, c, a, b, f, e, bot, bot);
	}

	/** Quad a-b-c-d (or a triangle when c == d) wound to face away from center; colors: base edge (a,b) / far edge (c,d). */
	static void face(PoseStack.Pose pose, VertexConsumer vc, Vector3f center, Vector3f a, Vector3f b, Vector3f c, Vector3f d, int cn, int cf) {
		Vector3f n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
		Vector3f m = new Vector3f(a).add(b).add(c).add(d).mul(0.25f).sub(center);
		if (n.lengthSquared() < 1e-12f) n.set(m);
		if (n.dot(m) < 0) { Vector3f t = a; a = d; d = t; t = b; b = c; c = t; int ct = cn; cn = cf; cf = ct; n.negate(); }
		n.normalize();
		v(pose, vc, a, cn, n); v(pose, vc, b, cn, n); v(pose, vc, c, cf, n); v(pose, vc, d, cf, n);
	}

	static void v(PoseStack.Pose pose, VertexConsumer vc, Vector3f p, int col, Vector3f n) {
		vc.addVertex(pose, p.x, p.y, p.z).setColor(0xFF000000 | col).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, n.x, n.y, n.z);
	}

	static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	static int scale(int c, float k) {
		k = Math.max(0, k);
		return (Math.min(255, (int) (((c >> 16) & 255) * k)) << 16) | (Math.min(255, (int) (((c >> 8) & 255) * k)) << 8) | Math.min(255, (int) ((c & 255) * k)) | 0xFF000000;
	}

	// ------------------------------------------------------------------ input

	/** Right-click the orb = talk to Claude (type). Runs before vanilla handles the click, and only eats it when the orb is hit. */
	public static void clicks(Minecraft mc) {
		if (!enabled || renderPos == null || mc.player == null || mc.gui.screen() != null) return;
		Vec3 eye = mc.player.getEyePosition(), look = mc.player.getViewVector(1), o = renderPos.subtract(eye);
		double along = o.dot(look), r = 0.55 * sizeMul;
		if (along < 0 || along > 6 || o.lengthSqr() - along * along > r * r) return;
		HitResult h = mc.hitResult;
		if (h != null && h.getType() != HitResult.Type.MISS && eye.distanceTo(h.getLocation()) < along - r) return;
		boolean clicked = false;
		while (mc.options.keyUse.consumeClick()) clicked = true;
		if (clicked) mc.gui.setScreen(new TalkScreen());
	}

	/** The orb is within 32 blocks and in the view cone (the HUD pill hides; the bubble shows the same thing). */
	public static boolean onScreen() {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled || renderPos == null || mc.player == null) return false;
		Vec3 to = renderPos.subtract(mc.player.getEyePosition());
		return to.length() < 32 && to.normalize().dot(mc.player.getViewVector(1)) > Math.cos(Math.toRadians(36));
	}

	// ------------------------------------------------------------------ API (GameApi "buddy", client thread)

	public static String api(JsonObject in) {
		Minecraft mc = Minecraft.getInstance();
		String a = in.has("action") ? in.get("action").getAsString() : "status";
		if (mc.player == null) return "no world loaded";
		switch (a) {
			case "follow", "come" -> { mode = "follow"; roam = null; enabled = true; return "following you"; }
			case "teleport", "tp" -> { mode = "follow"; roam = null; pos = null; enabled = true; return "next to you"; }
			case "stop", "stay", "wait" -> { mode = "stay"; stayAt = pos; vel = Vec3.ZERO; return "staying here"; }
			case "fly", "walk", "goto" -> {
				Vec3 t;
				if (in.has("x")) t = new Vec3(in.get("x").getAsDouble(), in.get("y").getAsDouble(), in.get("z").getAsDouble());
				else if ("player".equals(str(in, "at"))) t = mc.player.position().add(0, 2.2, 0);
				else if (in.has("blocks")) { Vec3 l = mc.player.getLookAngle(); t = mc.player.getEyePosition().add(l.x * in.get("blocks").getAsDouble(), 0.5, l.z * in.get("blocks").getAsDouble()); }
				else { HitResult h = mc.player.pick(96, 1, false); t = h.getLocation().add(0, 1.2, 0); }
				mode = "stay"; stayAt = t;
				return "flying to " + (int) t.x + " " + (int) t.y + " " + (int) t.z;
			}
			case "say" -> { say(str(in, "text"), 2500 + 60L * str(in, "text").length()); return "said"; }
			case "spin", "dance" -> { spinUntil = System.currentTimeMillis() + 1000L * (in.has("seconds") ? in.get("seconds").getAsInt() : 3); flash(); return "spinning"; }
			case "size" -> { sizeMul = Math.max(0.3f, Math.min(4, in.has("value") ? in.get("value").getAsFloat() : 1)); return "size " + sizeMul; }
			case "hide" -> { enabled = false; return "hidden"; }
			case "show" -> { enabled = true; return "shown"; }
			case "status" -> {
				Vec3 p = pos == null ? mc.player.position() : pos;
				return "Moddeus mascot: " + (enabled ? "visible" : "hidden") + ", " + mode + " at " + (int) p.x + " " + (int) p.y + " " + (int) p.z
					+ "; player at " + mc.player.blockPosition().toShortString() + (busy ? "; working" : "") + (env != null ? "; speaking" : "");
			}
			default -> { return "unknown action " + a + " (follow, come, stay, teleport, fly, say, spin, size, hide, show, status)"; }
		}
	}

	static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}
