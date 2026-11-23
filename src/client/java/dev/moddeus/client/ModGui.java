package dev.moddeus.client;

import dev.moddeus.Moddeus;
import dev.moddeus.live.OnReload;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Immediate-mode GUI toolkit for mods: screens and HUD overlays as a plain lambda, redrawn every frame, so mod code never
 * extends a Minecraft class (stays on the instant generation reload) and every edit shows up live while the GUI is open.
 *
 *   ModGui.open("Stone Forge", ui -> {
 *       int x = ui.w / 2 - 120, y = ui.h / 2 - 80;
 *       ui.panel(x, y, 240, 160, 0xE0141020, 0xFFB050FF);            // your own shape + colors, not a default skin
 *       ui.big("STONE FORGE", ui.w / 2, y + 10, 2f, 0xFFE0C0FF, true);
 *       if (ui.button("forge", "Forge", x + 70, y + 120, 100, 20)) Quick.cmd("...");
 *   });
 *   ModGui.hud("Storm Staff", "stones", ui -> ...);   // drawn on the HUD every frame; same id = replaced
 *   ModGui.close(); ModGui.removeHud(mod, id);
 *
 * Ui gives: real-time clocks (t, dt, open 0..1, keep running in /tick freeze), mouse + hover, smooth per-id animations
 * (anim), primitives (rect, gradient, rounded panel, glow, ring, line, item icons, scaled/wrapped text, scissor),
 * widgets (button, toggle, slider, bar, tab strip, radial wheel, tooltip) that only provide BEHAVIOUR + a minimal look:
 * how it looks is yours (guide: .claude/skills/mc-content/GUI.md).
 */
public final class ModGui {
	public interface View { void draw(Ui ui); }

	static Map<String, View> huds;
	static Map<String, View> huds() { if (huds == null) huds = new ConcurrentHashMap<>(); return huds; }

	/** Open a screen (does not pause the game unless pause = true). Call from any thread. */
	public static void open(String title, View view) { open(title, view, false); }

	public static void open(String title, View view, boolean pause) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.setScreen(new Host(title, view, pause)));
	}

	public static void close() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> { if (mc.gui.screen() instanceof Host) mc.gui.setScreen(null); });
	}

	/** Is one of our screens open (optionally with this title)? */
	public static boolean isOpen(String title) {
		return Minecraft.getInstance().gui.screen() instanceof Host h && (title == null || h.getTitle().getString().equals(title));
	}

	public static void hud(String mod, String id, View view) { huds().put(mod + "/" + id, view); }

	public static void removeHud(String mod, String id) { huds().remove(mod + "/" + id); }

	/** A mod switched off: its HUD parts disappear (screens close). */
	public static void clear(String mod) {
		huds().keySet().removeIf(k -> k.startsWith(mod + "/"));
		if (Minecraft.getInstance().gui.screen() instanceof Host h && h.view != null) close();
	}

	// ---------------------------------------------------------------- screen host

	static final class Host extends Screen {
		final View view;
		final boolean pause;
		final Ui ui = new Ui();

		Host(String title, View view, boolean pause) { super(Component.literal(title)); this.view = view; this.pause = pause; }

		@Override public boolean isPauseScreen() { return pause; }

		@Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {} // the view draws its own backdrop

		@Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			ui.begin(g, font, mx, my, width, height, false);
			try { view.draw(ui); }
			catch (Throwable t) { ui.error(t); }
			ui.end();
		}

		@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			ui.clickX = (int) e.x(); ui.clickY = (int) e.y(); ui.clickButton = e.button(); ui.clicked = true; ui.down = true;
			return true;
		}

		@Override public boolean mouseReleased(MouseButtonEvent e) { ui.down = false; ui.released = true; return true; }

		@Override public boolean mouseScrolled(double x, double y, double dx, double dy) { ui.scroll += dy; return true; }

		@Override public boolean keyPressed(KeyEvent e) {
			ui.keys.add(e.key());
			if (e.key() == 256) { onClose(); return true; } // Esc
			return true;
		}

		@Override public boolean charTyped(CharacterEvent e) { ui.typed.append(Character.toChars(e.codepoint())); return true; }

		@Override public void onClose() { super.onClose(); }
	}

	// ---------------------------------------------------------------- HUD host (one stable element for all mods)

	static void drawHuds(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker dt) {
		if (huds == null || huds.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		for (var e : huds.entrySet()) {
			Ui ui = hudUi().computeIfAbsent(e.getKey(), k -> new Ui());
			ui.begin(g, mc.font, -1, -1, g.guiWidth(), g.guiHeight(), true);
			try { e.getValue().draw(ui); }
			catch (Throwable t) { huds.remove(e.getKey()); Moddeus.LOG.error("HUD {} failed (removed)", e.getKey(), t); HotReloader.say("§cHUD " + e.getKey() + " failed: " + t); }
			ui.end();
		}
	}

	static Map<String, Ui> hudUis;
	static Map<String, Ui> hudUi() { if (hudUis == null) hudUis = new HashMap<>(); return hudUis; }

	@OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.modgui", Boolean.TRUE) != null) return;
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
			net.minecraft.resources.Identifier.fromNamespaceAndPath("moddeus", "mod_huds"), ModGui::drawHuds);
	}

	// ---------------------------------------------------------------- the toolkit

	public static final class Ui {
		public GuiGraphicsExtractor g;
		public Font font;
		/** Screen size (GUI pixels), mouse position. */
		public int w, h, mx, my;
		/** Seconds since this screen/HUD first drew, seconds since last frame, open animation 0..1 (eased). Real clock. */
		public float t, dt, open;
		public boolean hud;
		/** Mouse wheel since last frame (+ = up). Read and keep, or ignore. */
		public double scroll;
		boolean clicked, down, released;
		int clickX, clickY, clickButton;
		final java.util.Set<Integer> keys = new java.util.HashSet<>();
		final StringBuilder typed = new StringBuilder();
		final Map<String, Float> anims = new HashMap<>();
		long start, last;
		String hot; // id of the widget being dragged

		void begin(GuiGraphicsExtractor g, Font font, int mx, int my, int w, int h, boolean hud) {
			this.g = g; this.font = font; this.mx = mx; this.my = my; this.w = w; this.h = h; this.hud = hud;
			long now = System.nanoTime();
			if (start == 0) { start = now; last = now; }
			dt = Math.min(0.1f, (now - last) / 1e9f);
			last = now;
			t = (now - start) / 1e9f;
			open = easeOut(Math.min(1f, t / 0.25f));
		}

		void end() { clicked = false; released = false; scroll = 0; keys.clear(); typed.setLength(0); }

		void error(Throwable t) {
			Moddeus.LOG.error("ModGui view failed", t);
			rect(0, 0, w, 14, 0xC0400000);
			text("GUI error: " + t, 4, 3, 0xFFFFC0C0);
		}

		// ------------------------------------------------ input

		public boolean hover(int x, int y, int bw, int bh) { return mx >= x && my >= y && mx < x + bw && my < y + bh; }

		/** Clicked inside this box this frame (left button). Consumes the click. */
		public boolean click(int x, int y, int bw, int bh) {
			if (!clicked || clickButton != 0 || clickX < x || clickY < y || clickX >= x + bw || clickY >= y + bh) return false;
			clicked = false;
			return true;
		}

		public boolean rightClick(int x, int y, int bw, int bh) {
			if (!clicked || clickButton != 1 || clickX < x || clickY < y || clickX >= x + bw || clickY >= y + bh) return false;
			clicked = false;
			return true;
		}

		public boolean mouseDown() { return down; }

		/** A key pressed this frame (GLFW code: 65 = A, 32 = space, 257 = enter, 262/263 = right/left). */
		public boolean key(int glfw) { return keys.contains(glfw); }

		/** Characters typed this frame (for your own text fields). */
		public String typed() { return typed.toString(); }

		// ------------------------------------------------ animation

		/** Smoothly moves the value stored under id toward target (speed ~ 8-20). Use for hover glow, sliding panels, counters. */
		public float anim(String id, float target, float speed) {
			float v = anims.getOrDefault(id, target);
			v += (target - v) * (1 - (float) Math.exp(-speed * dt));
			anims.put(id, v);
			return v;
		}

		public static float easeOut(float x) { return 1 - (1 - x) * (1 - x) * (1 - x); }
		public static float easeInOut(float x) { return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2; }
		public static float pulse(float t, float hz) { return 0.5f + 0.5f * (float) Math.sin(t * hz * Math.PI * 2); }

		/** argb with its alpha multiplied (fade things in with ui.open). */
		public static int alpha(int argb, float a) { return (Math.round(((argb >>> 24) & 255) * Math.max(0, Math.min(1, a))) << 24) | (argb & 0xFFFFFF); }

		/** Blend two argb colors. */
		public static int mix(int a, int b, float f) {
			f = Math.max(0, Math.min(1, f));
			int r = 0;
			for (int s = 0; s < 32; s += 8) r |= Math.round(((a >>> s) & 255) * (1 - f) + ((b >>> s) & 255) * f) << s;
			return r;
		}

		/** Hue 0..1 -> argb (rainbows, per-stone colors). */
		public static int hsv(float hue, float sat, float val) { return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF); }

		// ------------------------------------------------ primitives

		public void rect(int x, int y, int bw, int bh, int argb) { g.fill(x, y, x + bw, y + bh, argb); }

		public void gradient(int x, int y, int bw, int bh, int top, int bottom) { g.fillGradient(x, y, x + bw, y + bh, top, bottom); }

		public void border(int x, int y, int bw, int bh, int argb) { g.outline(x, y, bw, bh, argb); }

		/** Rounded box (radius in px), optional 1px border (0 = none). */
		public void round(int x, int y, int bw, int bh, int r, int fill, int border) {
			r = Math.max(0, Math.min(r, Math.min(bw, bh) / 2));
			for (int i = 0; i < r; i++) {
				int inset = r - (int) Math.round(Math.sqrt(r * r - (r - i - 0.5) * (r - i - 0.5)));
				g.fill(x + inset, y + i, x + bw - inset, y + i + 1, fill);
				g.fill(x + inset, y + bh - i - 1, x + bw - inset, y + bh - i, fill);
				if ((border >>> 24) != 0) {
					g.fill(x + inset, y + i, x + inset + 1, y + i + 1, border); g.fill(x + bw - inset - 1, y + i, x + bw - inset, y + i + 1, border);
					g.fill(x + inset, y + bh - i - 1, x + inset + 1, y + bh - i, border); g.fill(x + bw - inset - 1, y + bh - i - 1, x + bw - inset, y + bh - i, border);
				}
			}
			g.fill(x, y + r, x + bw, y + bh - r, fill);
			if ((border >>> 24) != 0) {
				g.fill(x + r, y, x + bw - r, y + 1, border); g.fill(x + r, y + bh - 1, x + bw - r, y + bh, border);
				g.fill(x, y + r, x + 1, y + bh - r, border); g.fill(x + bw - 1, y + r, x + bw, y + bh - r, border);
			}
		}

		/** Soft glow / shadow around a box: stacked translucent rings (size px, color with alpha). */
		public void glow(int x, int y, int bw, int bh, int size, int argb) {
			for (int i = size; i > 0; i--) {
				float f = 1 - i / (float) (size + 1);
				round(x - i, y - i, bw + 2 * i, bh + 2 * i, i + 2, alpha(argb, f * f * 0.35f), 0);
			}
		}

		/** A panel: glow + rounded fill + border, scaled in by the open animation. Just one option; draw your own shapes too. */
		public void panel(int x, int y, int bw, int bh, int fill, int accent) {
			float s = hud ? 1 : 0.92f + 0.08f * open;
			push(); g.pose().translate(x + bw / 2f, y + bh / 2f); g.pose().scale(s, s); g.pose().translate(-(x + bw / 2f), -(y + bh / 2f));
			glow(x, y, bw, bh, 6, alpha(accent, open));
			round(x, y, bw, bh, 5, alpha(fill, open), alpha(accent, open));
			pop();
		}

		/** Line between two points (any angle), thickness px. */
		public void line(float x1, float y1, float x2, float y2, float thick, int argb) {
			float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
			if (len < 0.01f) return;
			push();
			g.pose().translate(x1, y1);
			g.pose().rotate((float) Math.atan2(dy, dx));
			g.fill(0, Math.round(-thick / 2), Math.round(len), Math.max(1, Math.round(thick / 2)), argb);
			pop();
		}

		/** Ring / arc (angles in turns 0..1, 0 = up, clockwise): cooldown dials, radial menus, charge meters. */
		public void arc(float cx, float cy, float radius, float thick, float from, float to, int argb) {
			int seg = Math.max(8, (int) (Math.abs(to - from) * radius * 1.2f));
			for (int i = 0; i < seg; i++) {
				double a0 = (from + (to - from) * i / seg) * Math.PI * 2 - Math.PI / 2, a1 = (from + (to - from) * (i + 1) / seg) * Math.PI * 2 - Math.PI / 2;
				line(cx + (float) Math.cos(a0) * radius, cy + (float) Math.sin(a0) * radius, cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius, thick, argb);
			}
		}

		public void ring(float cx, float cy, float radius, float thick, int argb) { arc(cx, cy, radius, thick, 0, 1, argb); }

		/** Filled circle. */
		public void disc(float cx, float cy, float radius, int argb) {
			int r = Math.round(radius);
			for (int dy = -r; dy < r; dy++) {
				int half = (int) Math.round(Math.sqrt(r * r - (dy + 0.5) * (dy + 0.5)));
				g.fill(Math.round(cx) - half, Math.round(cy) + dy, Math.round(cx) + half, Math.round(cy) + dy + 1, argb);
			}
		}

		public void item(ItemStack s, int x, int y) { g.item(s, x, y); }

		/** Item icon at any size (16 = normal). */
		public void item(ItemStack s, int x, int y, float size) {
			push(); g.pose().translate(x, y); g.pose().scale(size / 16f, size / 16f); g.item(s, 0, 0); pop();
		}

		/** A texture from assets (e.g. moddeus:textures/gui/forge.png) drawn at x,y,w,h. */
		public void image(net.minecraft.resources.Identifier tex, int x, int y, int bw, int bh) { g.blit(tex, x, y, x + bw, y + bh, 0, 1, 0, 1); }

		public void text(String s, int x, int y, int argb) { g.text(font, s, x, y, argb, false); }

		public void text(Component c, int x, int y, int argb) { g.text(font, c, x, y, argb, false); }

		public void shadowText(String s, int x, int y, int argb) { g.text(font, s, x, y, argb, true); }

		public void center(String s, int cx, int y, int argb) { g.text(font, s, cx - font.width(s) / 2, y, argb, false); }

		/** Scaled text (titles, big numbers); centered when center = true. */
		public void big(String s, int x, int y, float scale, int argb, boolean center) {
			push();
			g.pose().translate(center ? x - font.width(s) * scale / 2 : x, y);
			g.pose().scale(scale, scale);
			g.text(font, s, 0, 0, argb, true);
			pop();
		}

		/** Word-wrapped text; returns the height used. */
		public int wrap(String s, int x, int y, int width, int argb) {
			var lines = font.split(Component.literal(s), width);
			for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x, y + i * 10, argb, false);
			return lines.size() * 10;
		}

		public int width(String s) { return font.width(s); }

		public void tooltip(String text) { if (!hud) g.setTooltipForNextFrame(font, Component.literal(text), mx, my); }

		public void tooltip(ItemStack s) { if (!hud) g.setTooltipForNextFrame(font, s, mx, my); }

		/** Clip drawing to a box (scroll lists). Always pair with unclip(). */
		public void clip(int x, int y, int bw, int bh) { g.enableScissor(x, y, x + bw, y + bh); }

		public void unclip() { g.disableScissor(); }

		public void push() { g.pose().pushMatrix(); }

		public void pop() { g.pose().popMatrix(); }

		// ------------------------------------------------ widgets: behaviour + a minimal look (restyle freely)

		/** Button; true the frame it is clicked. Hover lifts + brightens smoothly; plays the click sound. */
		public boolean button(String id, String label, int x, int y, int bw, int bh) { return button(id, label, x, y, bw, bh, 0xFF2A2A30, 0xFFA58BF0); }

		public boolean button(String id, String label, int x, int y, int bw, int bh, int fill, int accent) {
			boolean over = hover(x, y, bw, bh);
			float hv = anim("btn:" + id, over ? 1 : 0, 14);
			int lift = Math.round(hv);
			if (hv > 0.02f) glow(x, y - lift, bw, bh, 4, alpha(accent, hv));
			round(x, y - lift, bw, bh, 3, mix(fill, accent, hv * 0.35f), mix(alpha(accent, 0.5f), accent, hv));
			center(label, x + bw / 2, y - lift + (bh - 8) / 2, mix(0xFFD0D0D0, 0xFFFFFFFF, hv));
			boolean hit = click(x, y, bw, bh);
			if (hit) clickSound();
			return hit;
		}

		/** Item icon button (inventories, shops, ability pickers). */
		public boolean iconButton(String id, ItemStack icon, int x, int y, int size, int accent) {
			boolean over = hover(x, y, size, size);
			float hv = anim("icon:" + id, over ? 1 : 0, 14);
			round(x, y, size, size, 3, alpha(0xFF101014, 0.7f + 0.3f * hv), mix(alpha(accent, 0.4f), accent, hv));
			item(icon, x + (size - 16 * (1 + hv * 0.15f)) / 2 > 0 ? Math.round(x + (size - 16 * (1 + hv * 0.15f)) / 2) : x, Math.round(y + (size - 16 * (1 + hv * 0.15f)) / 2), 16 * (1 + hv * 0.15f));
			if (over) tooltip(icon);
			boolean hit = click(x, y, size, size);
			if (hit) clickSound();
			return hit;
		}

		/** Toggle switch; returns the new value. */
		public boolean toggle(String id, String label, int x, int y, boolean on, int accent) {
			float k = anim("tog:" + id, on ? 1 : 0, 16);
			round(x, y, 22, 12, 6, mix(0xFF3A3A40, accent, k), 0);
			disc(x + 6 + 10 * k, y + 6, 4.5f, 0xFFF4F4F4);
			text(label, x + 28, y + 2, 0xFFE0E0E0);
			if (click(x, y, 28 + width(label), 12)) { clickSound(); return !on; }
			return on;
		}

		/** Horizontal slider 0..1; drag to change; returns the new value. */
		public float slider(String id, int x, int y, int bw, float value, int accent) {
			if (clicked && clickButton == 0 && hover(x - 3, y - 4, bw + 6, 12)) { hot = id; clicked = false; }
			if (released && id.equals(hot)) hot = null;
			if (id.equals(hot)) value = Math.max(0, Math.min(1, (mx - x) / (float) bw));
			round(x, y, bw, 4, 2, 0xFF2A2A30, 0);
			round(x, y, Math.max(4, Math.round(bw * value)), 4, 2, accent, 0);
			disc(x + bw * value, y + 2, id.equals(hot) || hover(x - 3, y - 4, bw + 6, 12) ? 5 : 4, 0xFFF4F4F4);
			return value;
		}

		/** Animated fill bar (health, mana, charge, cooldown). */
		public void bar(String id, int x, int y, int bw, int bh, float frac, int back, int fill) {
			float f = anim("bar:" + id, Math.max(0, Math.min(1, frac)), 10);
			round(x, y, bw, bh, Math.min(bh / 2, 3), back, 0);
			if (f > 0.001f) round(x, y, Math.max(bh, Math.round(bw * f)), bh, Math.min(bh / 2, 3), fill, 0);
		}

		/** Row of tabs; returns the selected index (keeps your value until a tab is clicked). */
		public int tabs(String id, String[] names, int x, int y, int tabW, int selected, int accent) {
			for (int i = 0; i < names.length; i++) {
				int tx = x + i * (tabW + 2);
				float k = anim("tab:" + id + i, i == selected ? 1 : hover(tx, y, tabW, 16) ? 0.4f : 0, 14);
				round(tx, y, tabW, 16, 3, mix(0xFF22222A, accent, k * 0.5f), 0);
				rect(tx + 3, y + 14, Math.round((tabW - 6) * k), 2, accent);
				center(names[i], tx + tabW / 2, y + 4, mix(0xFFA0A0A0, 0xFFFFFFFF, k));
				if (click(tx, y, tabW, 16)) { clickSound(); selected = i; }
			}
			return selected;
		}

		/**
		 * Radial wheel (ability / stone pickers): count slices around cx,cy. Returns the hovered slice (-1 = center), and
		 * draws only the highlight wedge: put your own icons/labels with radialPos(). Selection = click or release of a key.
		 */
		public int radial(String id, int cx, int cy, float inner, float outer, int count, int accent) {
			double dx = mx - cx, dy = my - cy, d = Math.sqrt(dx * dx + dy * dy);
			int sel = -1;
			if (d > inner) sel = (int) Math.floor(((Math.atan2(dy, dx) + Math.PI / 2 + Math.PI * 2 + Math.PI / count) % (Math.PI * 2)) / (Math.PI * 2) * count) % count;
			for (int i = 0; i < count; i++) {
				float k = anim("rad:" + id + i, i == sel ? 1 : 0, 16);
				float a0 = (i - 0.5f) / count + 0.004f, a1 = (i + 0.5f) / count - 0.004f;
				float r = outer + 4 * k;
				for (float rr = inner; rr < r; rr += 2) arc(cx, cy, rr, 2.2f, a0, a1, alpha(mix(0xFF18181E, accent, k * 0.7f), 0.55f + 0.4f * k));
			}
			return sel;
		}

		/** Center of slice i of a radial wheel at radius r. */
		public int[] radialPos(int cx, int cy, float r, int i, int count) {
			double a = (double) i / count * Math.PI * 2 - Math.PI / 2;
			return new int[] { (int) Math.round(cx + Math.cos(a) * r), (int) Math.round(cy + Math.sin(a) * r) };
		}

		public void clickSound() {
			try { Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f)); }
			catch (Throwable ignored) {}
		}

		/** Any sound for UI feedback (moddeus:<id> or minecraft:...). */
		public void sound(String id, float pitch) {
			try {
				var ev = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.parse(id));
				Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(ev, pitch));
			} catch (Throwable ignored) {}
		}
	}
}
