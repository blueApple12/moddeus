package dev.moddeus.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;

/**
 * Cutscene toolbox: a scripted camera (spline keyframes, orbit, dolly, follow, look-at), letterbox bars, titles,
 * subtitles, fades, flashes, shake, sounds and timed actions. The camera is an invisible client-only Marker set as the
 * camera entity, so the player stays where they are and is visible in the shot. Player input is blocked by an overlay
 * screen; Esc / Space skips. Runs on the real clock (keeps going during /tick freeze). All times are in seconds.
 *
 *   Cutscene.create()
 *       .cam(0, new Vec3(10, 80, 10), target)          // keyframe: position + look-at point (or yaw/pitch)
 *       .cam(4, new Vec3(-10, 75, 10), target)
 *       .orbit(4, 10, target, 8, 3, 0, 180)            // circle around target
 *       .title(0.5, 3, "THE BEGINNING", "a Moddeus story")
 *       .fadeIn(0, 1).fadeOut(9, 1)
 *       .shake(6, 1, 1.5f).sound(6, "minecraft:entity.generic.explode")
 *       .server(6, s -> s.getCommands()...)            // anything on the server thread
 *       .play();
 *   Cutscene.define("Mod.intro", () -> Cutscene.create()...);  then Cutscene.play("Mod.intro") or Quick.cutscene("Mod.intro")
 */
public final class Cutscene {
	record Key(double t, Vec3 pos, float yaw, float pitch, float fov) {}
	record Cue(double t, double dur, String a, String b, int color, int kind) {} // kind 0 title, 1 subtitle
	record Fade(double t, double dur, int from, int to) {}
	record Act(double t, Runnable r) {}
	record Shake(double t, double dur, float amp) {}
	record Track(double t0, double t1, Supplier<Vec3> pos, Supplier<Vec3> look) {} // follow / lookAt overrides

	final List<Key> keys = new ArrayList<>();
	final List<Cue> cues = new ArrayList<>();
	final List<Fade> fades = new ArrayList<>();
	final List<Act> acts = new ArrayList<>();
	final List<Shake> shakes = new ArrayList<>();
	final List<Track> tracks = new ArrayList<>();
	boolean bars = true, skippable = true, usesFov;
	Runnable onEnd;
	double length = -1;

	// runtime
	static Cutscene current;
	static Map<String, Supplier<Cutscene>> defs;
	static boolean enabled = true;
	long startNs;
	int nextAct;
	Marker cam;
	Entity prevCam;
	boolean prevHideGui;
	int prevFov;
	double t; // seconds since start (updated every frame by the overlay)

	public static Cutscene create() { return new Cutscene(); }

	// ---------------------------------------------------------------- camera

	/** Keyframe: camera at pos looking along yaw/pitch (degrees, Minecraft convention). */
	public Cutscene cam(double t, Vec3 pos, float yaw, float pitch) { keys.add(new Key(t, pos, yaw, pitch, Float.NaN)); return this; }
	/** Keyframe: camera at pos looking at a point. */
	public Cutscene cam(double t, Vec3 pos, Vec3 look) { float[] r = angles(pos, look); return cam(t, pos, r[0], r[1]); }
	/** Field of view (degrees) from this time on, interpolated between fov keys. */
	public Cutscene fov(double t, float fov) {
		usesFov = true;
		Key k = poseKeyAt(t);
		keys.add(new Key(t, k == null ? null : k.pos, k == null ? Float.NaN : k.yaw, k == null ? Float.NaN : k.pitch, fov));
		return this;
	}
	/** Circle around center from angle a0 to a1 (degrees, 0 = south), radius r, height h above center, looking at it. */
	public Cutscene orbit(double t0, double t1, Vec3 center, double r, double h, double a0, double a1) {
		int n = Math.max(2, (int) Math.ceil((t1 - t0) * 4));
		for (int i = 0; i <= n; i++) {
			double u = (double) i / n, a = Math.toRadians(a0 + (a1 - a0) * u);
			cam(t0 + (t1 - t0) * u, center.add(-Math.sin(a) * r, h, Math.cos(a) * r), center);
		}
		return this;
	}
	/** Straight move from a to b while looking at look. */
	public Cutscene dolly(double t0, double t1, Vec3 a, Vec3 b, Vec3 look) { return cam(t0, a, look).cam(t1, b, look); }
	/** Between t0 and t1 the camera sits at entity + offset (offset rotated with its body yaw) and looks at it. */
	public Cutscene follow(double t0, double t1, Entity e, Vec3 offset) {
		tracks.add(new Track(t0, t1, () -> e.position().add(offset.yRot((float) Math.toRadians(-e.getYRot()))), () -> e.getEyePosition()));
		return this;
	}
	/** Between t0 and t1 keep the keyframed position but aim at a moving target. */
	public Cutscene lookAt(double t0, double t1, Supplier<Vec3> target) { tracks.add(new Track(t0, t1, null, target)); return this; }
	public Cutscene lookAt(double t0, double t1, Entity e) { return lookAt(t0, t1, e::getEyePosition); }

	// ---------------------------------------------------------------- overlay

	public Cutscene title(double t, double dur, String title, String sub) { cues.add(new Cue(t, dur, title, sub, 0xFFFFFF, 0)); return this; }
	public Cutscene title(double t, double dur, String title, String sub, int rgb) { cues.add(new Cue(t, dur, title, sub, rgb, 0)); return this; }
	/** Bottom-of-screen line (dialogue / narration). */
	public Cutscene subtitle(double t, double dur, String text) { cues.add(new Cue(t, dur, text, null, 0xFFFFFF, 1)); return this; }
	public Cutscene subtitle(double t, double dur, String speaker, String text, int speakerRgb) { cues.add(new Cue(t, dur, text, speaker, speakerRgb, 1)); return this; }
	/** Screen colour overlay from ARGB 'from' to 'to' over dur; the end colour holds until the next fade. */
	public Cutscene fade(double t, double dur, int from, int to) { fades.add(new Fade(t, dur, from, to)); return this; }
	public Cutscene fadeIn(double t, double dur) { return fade(t, dur, 0xFF000000, 0x00000000); }
	public Cutscene fadeOut(double t, double dur) { return fade(t, dur, 0x00000000, 0xFF000000); }
	public Cutscene flash(double t, int rgb) { return fade(t, 0.35, 0xFF000000 | rgb, rgb & 0xFFFFFF); }
	public Cutscene shake(double t, double dur, float amp) { shakes.add(new Shake(t, dur, amp)); return this; }
	public Cutscene bars(boolean on) { bars = on; return this; }

	// ---------------------------------------------------------------- actions

	/** Runs on the client thread at time t. */
	public Cutscene at(double t, Runnable r) { acts.add(new Act(t, r)); return this; }
	/** Runs on the server thread at time t (spawn mobs, explosions, commands, Quick helpers...). */
	public Cutscene server(double t, Consumer<MinecraftServer> r) {
		return at(t, () -> { var s = Minecraft.getInstance().getSingleplayerServer(); if (s != null) s.execute(() -> r.accept(s)); });
	}
	/** A server command at time t (no leading slash needed). */
	public Cutscene cmd(double t, String command) {
		return server(t, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput()
			.withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), command));
	}
	public Cutscene sound(double t, String id) { return sound(t, id, 1, 1); }
	public Cutscene sound(double t, String id, float volume, float pitch) {
		return at(t, () -> Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(Identifier.parse(id)), pitch, volume)));
	}
	public Cutscene skippable(boolean on) { skippable = on; return this; }
	public Cutscene onEnd(Runnable r) { onEnd = r; return this; }
	/** Total length; default = last keyframe / cue / fade / action end. */
	public Cutscene length(double seconds) { length = seconds; return this; }

	// ---------------------------------------------------------------- named cutscenes

	public static void define(String name, Supplier<Cutscene> make) { if (defs == null) defs = new HashMap<>(); defs.put(name, make); }
	public static boolean play(String name) {
		if (dev.moddeus.live.Stage.quiet()) { dev.moddeus.live.Stage.note(null, "cutscene " + name + " (skipped: stage)"); return false; } // stage tests never take the camera
		Supplier<Cutscene> s = defs == null ? null : defs.get(name);
		if (s == null) return false;
		Minecraft.getInstance().execute(() -> s.get().play());
		return true;
	}
	public static boolean playing() { return current != null; }

	// ---------------------------------------------------------------- run

	/** Starts it (any thread). A running cutscene is stopped first. */
	public Cutscene play() {
		Minecraft.getInstance().execute(this::begin);
		return this;
	}

	void begin() {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled || mc.level == null || mc.player == null) return;
		if (current != null) current.end(false);
		keys.removeIf(k -> k.pos == null && Float.isNaN(k.fov));
		keys.sort((a, b) -> Double.compare(a.t, b.t));
		acts.sort((a, b) -> Double.compare(a.t, b.t));
		if (length < 0) {
			double e = 0;
			for (Key k : keys) e = Math.max(e, k.t);
			for (Cue c : cues) e = Math.max(e, c.t + c.dur);
			for (Fade f : fades) e = Math.max(e, f.t + f.dur);
			for (Act a : acts) e = Math.max(e, a.t);
			for (Track k : tracks) e = Math.max(e, k.t1);
			length = e + 0.05;
		}
		if (keys.stream().noneMatch(k -> k.pos != null)) cam(0, mc.player.getEyePosition(), mc.player.getYRot(), mc.player.getXRot());
		current = this;
		prevCam = mc.getCameraEntity();
		prevHideGui = mc.gui.hud.isHidden();
		prevFov = mc.options.fov().get();
		cam = new Marker(EntityTypes.MARKER, mc.level);
		Pose p = pose(0);
		cam.snapTo(p.pos.x, p.pos.y, p.pos.z, p.yaw, p.pitch);
		cam.setOldPosAndRot();
		mc.setCameraEntity(cam);
		hud(true);
		startNs = System.nanoTime();
		mc.gui.setScreen(new Overlay(this));
		InputConstants.grabMouse(mc.getWindow(), mc.getWindow().getWidth() / 2.0, mc.getWindow().getHeight() / 2.0); // hide the cursor
		tick(mc);
	}

	/** F1-style HUD hide (Hud.isHidden is private in 26.3). */
	static void hud(boolean hidden) {
		try { var f = net.minecraft.client.gui.Hud.class.getDeclaredField("isHidden"); f.setAccessible(true); f.setBoolean(Minecraft.getInstance().gui.hud, hidden); }
		catch (Exception ignored) {}
	}

	/** Stops the running cutscene (skip). */
	public static void stop() { if (current != null) current.end(true); }

	void end(boolean runEnd) {
		Minecraft mc = Minecraft.getInstance();
		if (current == this) current = null;
		mc.setCameraEntity(prevCam != null && prevCam.isAlive() ? prevCam : mc.player);
		hud(prevHideGui);
		if (usesFov) mc.options.fov().set(prevFov);
		if (mc.gui.screen() instanceof Overlay o && o.cut == this) mc.gui.setScreen(null);
		if (runEnd && onEnd != null) try { onEnd.run(); } catch (Throwable e) { dev.moddeus.Moddeus.LOG.warn("cutscene onEnd", e); }
	}

	/** Client tick: camera pose now -> one tick ahead (the renderer interpolates between them every frame). */
	static void onTick(Minecraft mc) {
		Cutscene c = current;
		if (c == null) return;
		try { c.tick(mc); } catch (Throwable e) { dev.moddeus.Moddeus.LOG.warn("cutscene", e); c.end(true); }
	}

	void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) { end(false); return; }
		double now = (System.nanoTime() - startNs) / 1e9;
		while (nextAct < acts.size() && acts.get(nextAct).t <= now) {
			try { acts.get(nextAct).r.run(); } catch (Throwable e) { dev.moddeus.Moddeus.LOG.warn("cutscene action", e); }
			nextAct++;
		}
		if (now >= length) { end(true); return; }
		if (mc.getCameraEntity() != cam) mc.setCameraEntity(cam);
		Pose a = pose(now), b = pose(now + 0.05);
		cam.setOldPosAndRot(a.pos, a.yaw, a.pitch);
		cam.setPos(b.pos);
		cam.setYRot(b.yaw);
		cam.setXRot(b.pitch);
		if (usesFov && !Float.isNaN(a.fov)) mc.options.fov().set(Math.round(Math.max(30, Math.min(110, a.fov))));
	}

	// ---------------------------------------------------------------- math

	record Pose(Vec3 pos, float yaw, float pitch, float fov) {}

	static float[] angles(Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		return new float[] {(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))};
	}

	Key poseKeyAt(double t) { Key best = null; for (Key k : keys) if (k.pos != null && k.t <= t) best = k; return best; }

	Pose pose(double t) {
		List<Key> ks = keys.stream().filter(k -> k.pos != null).toList();
		Vec3 pos;
		float yaw, pitch;
		if (ks.isEmpty()) { Minecraft mc = Minecraft.getInstance(); pos = mc.player.getEyePosition(); yaw = mc.player.getYRot(); pitch = mc.player.getXRot(); }
		else {
			// unwrap yaw so the camera turns the short way
			float[] yw = new float[ks.size()];
			for (int i = 0; i < yw.length; i++) { yw[i] = ks.get(i).yaw; if (i > 0) yw[i] = yw[i - 1] + wrap(yw[i] - yw[i - 1]); }
			int i = 0;
			while (i < ks.size() - 1 && ks.get(i + 1).t <= t) i++;
			if (i >= ks.size() - 1 || t <= ks.get(0).t) {
				int j = t <= ks.get(0).t ? 0 : ks.size() - 1;
				pos = ks.get(j).pos; yaw = yw[j]; pitch = ks.get(j).pitch;
			} else {
				Key k1 = ks.get(i), k2 = ks.get(i + 1), k0 = ks.get(Math.max(0, i - 1)), k3 = ks.get(Math.min(ks.size() - 1, i + 2));
				double u = (t - k1.t) / Math.max(1e-6, k2.t - k1.t);
				pos = new Vec3(cr(k0.pos.x, k1.pos.x, k2.pos.x, k3.pos.x, u), cr(k0.pos.y, k1.pos.y, k2.pos.y, k3.pos.y, u), cr(k0.pos.z, k1.pos.z, k2.pos.z, k3.pos.z, u));
				yaw = (float) cr(yw[Math.max(0, i - 1)], yw[i], yw[i + 1], yw[Math.min(ks.size() - 1, i + 2)], u);
				pitch = (float) cr(k0.pitch, k1.pitch, k2.pitch, k3.pitch, u);
			}
		}
		for (Track tr : tracks) {
			if (t < tr.t0 || t > tr.t1) continue;
			if (tr.pos != null) pos = tr.pos.get();
			if (tr.look != null) { float[] r = angles(pos, tr.look.get()); yaw = yaw + wrap(r[0] - yaw); pitch = r[1]; }
		}
		for (Shake s : shakes) {
			if (t < s.t || t > s.t + s.dur) continue;
			float k = s.amp * (float) (1 - (t - s.t) / s.dur);
			yaw += k * (float) (Math.sin(t * 47) + Math.sin(t * 29)) * 0.5f;
			pitch += k * (float) (Math.sin(t * 53 + 1) + Math.sin(t * 31)) * 0.5f;
			pos = pos.add(0, k * 0.03 * Math.sin(t * 61), 0);
		}
		float fov = Float.NaN;
		Key f0 = null, f1 = null;
		for (Key k : keys) if (!Float.isNaN(k.fov)) { if (k.t <= t) f0 = k; else if (f1 == null) f1 = k; }
		if (f0 != null) fov = f1 == null ? f0.fov : (float) (f0.fov + (f1.fov - f0.fov) * smooth((t - f0.t) / (f1.t - f0.t)));
		else if (f1 != null) fov = f1.fov;
		return new Pose(pos, yaw, Math.max(-90, Math.min(90, pitch)), fov);
	}

	static float wrap(float a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }
	static double smooth(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); }
	/** Catmull-Rom through p1..p2. */
	static double cr(double p0, double p1, double p2, double p3, double u) {
		double u2 = u * u, u3 = u2 * u;
		return 0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
	}

	// ---------------------------------------------------------------- overlay screen (bars, text, fades; blocks input)

	static final class Overlay extends Screen {
		final Cutscene cut;
		Overlay(Cutscene c) { super(Component.literal("Cutscene")); cut = c; }
		@Override public boolean isPauseScreen() { return false; }
		@Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float pt) {}
		@Override public boolean shouldCloseOnEsc() { return cut.skippable; }
		/** Skip after this key event is done: closing the screen inside it let the same Esc open the pause menu. */
		void skip() { Minecraft.getInstance().execute(() -> { if (current == cut) cut.end(true); }); }
		@Override public void onClose() { if (current == cut) skip(); else super.onClose(); }
		@Override public boolean keyPressed(KeyEvent e) {
			if (cut.skippable && (e.key() == InputConstants.KEY_ESCAPE || e.key() == InputConstants.KEY_SPACE)) { skip(); return true; }
			return true; // swallow everything else
		}

		@Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			double t = (System.nanoTime() - cut.startNs) / 1e9;
			cut.t = t;
			int w = width, h = height;
			// letterbox
			if (cut.bars) {
				double in = smooth(t / 0.8) * smooth((cut.length - t) / 0.8);
				int bh = (int) (h * 0.11 * in);
				g.fill(0, 0, w, bh, 0xFF000000);
				g.fill(0, h - bh, w, h, 0xFF000000);
			}
			for (Cue c : cut.cues) {
				if (t < c.t || t > c.t + c.dur) continue;
				double a = Math.min(smooth((t - c.t) / 0.5), smooth((c.t + c.dur - t) / 0.5));
				int alpha = (int) (255 * a) << 24;
				if (alpha >>> 24 < 5) continue;
				if (c.kind == 0) {
					g.pose().pushMatrix();
					g.pose().translate(w / 2f, h * 0.36f);
					g.pose().scale(3f, 3f);
					g.centeredText(font, c.a, 0, 0, alpha | c.color);
					g.pose().popMatrix();
					if (c.b != null) {
						g.pose().pushMatrix();
						g.pose().translate(w / 2f, h * 0.36f + 34);
						g.pose().scale(1.4f, 1.4f);
						g.centeredText(font, c.b, 0, 0, alpha | 0xCFCFCF);
						g.pose().popMatrix();
					}
				} else {
					String line = c.b != null ? c.b + ": " + c.a : c.a;
					int y = h - (int) (h * 0.11) - 18, tw = font.width(line);
					g.fill(w / 2 - tw / 2 - 6, y - 4, w / 2 + tw / 2 + 6, y + 12, ((int) (150 * a) << 24));
					if (c.b != null) {
						g.text(font, c.b + ": ", w / 2 - tw / 2, y, alpha | c.color, true);
						g.text(font, c.a, w / 2 - tw / 2 + font.width(c.b + ": "), y, alpha | 0xFFFFFF, true);
					} else g.centeredText(font, c.a, w / 2, y, alpha | 0xFFFFFF);
				}
			}
			// fades: the latest started one wins (its end colour holds)
			Fade cur = null;
			for (Fade f : cut.fades) if (t >= f.t && (cur == null || f.t >= cur.t)) cur = f;
			if (cur != null) {
				double u = smooth((t - cur.t) / Math.max(1e-6, cur.dur));
				int col = lerpArgb(cur.from, cur.to, u);
				if ((col >>> 24) > 0) g.fill(0, 0, w, h, col);
			}
			if (cut.skippable && t < 3) g.text(font, "Esc / Space: skip", w - font.width("Esc / Space: skip") - 6, h - 12, ((int) (120 * (1 - t / 3)) << 24) | 0xFFFFFF, false);
		}
	}

	static int lerpArgb(int a, int b, double u) {
		int r = 0;
		for (int s = 0; s < 32; s += 8) r |= ((int) Math.round(((a >>> s) & 255) + ((((b >>> s) & 255) - ((a >>> s) & 255)) * u)) & 255) << s;
		return r;
	}

	// ---------------------------------------------------------------- lifecycle

	@OnReload
	public static void hook() {
		enabled = true;
		hookPlayer();
		if (System.getProperties().putIfAbsent("moddeus.cutscene", Boolean.TRUE) != null) return;
		ClientTickEvents.END_CLIENT_TICK.register(Cutscene::onTick);
	}

	/** Vanilla never draws the local player while the camera entity is something else (our Marker): add it ourselves. */
	static void hookPlayer() {
		if (System.getProperties().putIfAbsent("moddeus.cutscene.player", Boolean.TRUE) != null) return;
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.END_EXTRACTION.register(Cutscene::showPlayer);
	}

	static void showPlayer(net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (current == null || !enabled || mc.player == null || mc.getCameraEntity() == mc.player || mc.player.isInvisible()) return;
		try {
			float pt = ctx.deltaTracker().getGameTimeDeltaPartialTick(false);
			ctx.levelState().entityRenderStates.add(mc.getEntityRenderDispatcher().extractEntity(mc.player, pt));
		} catch (Throwable t) { if (showErrors++ < 3) dev.moddeus.Moddeus.LOG.warn("cutscene player", t); }
	}
	static int showErrors;

	@OnUnload
	public static void unload() { enabled = false; stop(); }
}
