package dev.moddeus.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import dev.moddeus.Moddeus;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Opens the Claude screen with K and shows a status pill on the HUD. Listeners delegate to static methods so they hot-swap. */
public final class ClaudeUi {
	/** Rebindable in Options → Controls → Miscellaneous ("Open Claude"). Default K. */
	public static KeyMapping openKey;
	static int seenItems;
	/** Ticks K has been held; past HOLD it records voice (Claude Code dictation in the Voice chat). A shorter tap opens the screen. */
	static int kHeld;
	public static boolean recording;
	static final int HOLD = 5;
	public static boolean sidebarHidden;

	/** At startup Fabric registers it (options.txt binding loads). Mid-game it is appended to Options.keyMappings live. */
	static void ensureKey() {
		if (openKey != null) return;
		KeyMapping k = new KeyMapping("key.moddeus.open", InputConstants.KEY_G, KeyMapping.Category.MISC);
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) {
			openKey = KeyMappingHelper.registerKeyMapping(k);
			return;
		}
		try {
			KeyMapping[] old = mc.options.keyMappings;
			KeyMapping[] arr = Arrays.copyOf(old, old.length + 1);
			arr[old.length] = k;
			Field f = mc.options.getClass().getField("keyMappings");
			f.setAccessible(true);
			f.set(mc.options, arr);
			KeyMapping.resetMapping();
		} catch (Throwable t) {
			Moddeus.LOG.warn("Live keybind registration failed: {}", t.toString());
		}
		openKey = k;
	}

	@OnReload
	public static void install() {
		if (System.getProperties().putIfAbsent("moddeus.ui.installed", Boolean.TRUE) != null) return;
		ensureKey();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
		try {
			HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("moddeus", "status"), (g, dt) -> hud(g));
		} catch (Throwable t) {
			Moddeus.LOG.warn("HUD element not registered live (restart adds it): {}", t.toString());
		}
	}

	/** First live build used scancode 75 (= Page Up in 26.3). Move anyone still on that default to K. */
	@OnReload
	static void fixDefault() {
		Minecraft.getInstance().execute(() -> {
			if (openKey == null) return;
			KeyMapping fresh = new KeyMapping("key.moddeus.open", InputConstants.KEY_K, KeyMapping.Category.MISC);
			boolean wasPgUp = openKey.getName() != null && openKey.saveString().equals(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_PAGEUP).getName());
			Minecraft mc = Minecraft.getInstance();
			try {
				KeyMapping[] arr = mc.options.keyMappings;
				for (int i = 0; i < arr.length; i++) if (arr[i] == openKey) arr[i] = fresh;
				if (!wasPgUp) fresh.setKey(InputConstants.getKey(openKey.saveString()));
				openKey = fresh;
				KeyMapping.resetMapping();
				mc.options.save();
			} catch (Throwable t) {
				Moddeus.LOG.warn("keybind fix failed: {}", t.toString());
			}
		});
	}

	static boolean keyChecked;

	static void tick(Minecraft mc) {
		Bridge.ensure();
		ensureKey();
		if (!keyChecked) { keyChecked = true; moveToCNow(); } // options.txt is loaded by now
		while (openKey.consumeClick()) {} // handled via isDown below (tap vs hold)
		if (openKey.isDown() && mc.gui.screen() == null && mc.player != null) {
			if (++kHeld == HOLD) { recording = true; Bridge.send("voice", "on", true); }
		} else if (kHeld > 0) {
			if (recording) { recording = false; Bridge.send("voice", "on", false); }
			else if (mc.gui.screen() == null && mc.player != null) mc.gui.setScreen(new ClaudeScreen());
			kHeld = 0;
		}
	}

	/** Voice pill: live transcript while K is held, then the helper's status / reply for a few seconds. */
	static boolean voiceHud(GuiGraphicsExtractor g, Minecraft mc) {
		JsonObject v = null;
		for (JsonObject o : Bridge.states().values()) if (o.has("voice") && o.get("voice").getAsBoolean()) v = o;
		String msg = null;
		int dot = ClaudeScreen.ACCENT;
		if (recording) { // the key is the truth: held = listening, released = not (the bridge's flag lags)
			String d = v == null ? "" : ClaudeScreen.str(v, "dictation");
			msg = "Listening…  " + (d == null ? "" : d);
			dot = (System.currentTimeMillis() / 400) % 2 == 0 ? 0xFFE5484D : 0xFF7A2A2C;
		} else if (v != null) {
			String d = ClaudeScreen.str(v, "dictation");
			JsonArray items = v.getAsJsonArray("items");
			JsonObject last = items == null || items.isEmpty() ? null : items.get(items.size() - 1).getAsJsonObject();
			long age = last == null || !last.has("at") ? Long.MAX_VALUE : System.currentTimeMillis() - last.get("at").getAsLong();
			if (d != null && !d.isEmpty()) msg = "Heard: " + d;
			else if ("busy".equals(ClaudeScreen.str(v, "status"))) msg = ClaudeScreen.spinner() + " Voice: " + (last != null && "tool".equals(ClaudeScreen.str(last, "kind")) ? ClaudeScreen.str(last, "summary") : "thinking…");
			else if (last != null && "assistant".equals(ClaudeScreen.str(last, "kind")) && age < 7000) { msg = ClaudeScreen.str(last, "text"); dot = ClaudeScreen.OK; }
		}
		if (msg == null) return false;
		msg = msg.replace('\n', ' ');
		if (mc.font.width(msg) > 320) msg = mc.font.plainSubstrByWidth(msg, 310) + "…";
		int w = mc.font.width(msg) + 16, x = g.guiWidth() - w - 6, y = 6;
		ClaudeScreen.rrect(g, x, y, w, 15, 0xE016121F);
		g.fill(x + 5, y + 6, x + 8, y + 9, dot);
		g.text(mc.font, msg, x + 11, y + 4, ClaudeScreen.TEXT, false);
		return true;
	}

	/** Default moved K -> C -> G (player's request): move a K or C binding to G once; a later rebind is respected. */
	@OnReload
	static void moveToC() {
		Minecraft.getInstance().execute(ClaudeUi::moveToCNow);
	}

	static void moveToCNow() {
		try {
			java.nio.file.Path done = dev.moddeus.client.game.GameApi.projectDir().resolve("run/moddeus/key-g.done");
			if (openKey == null || java.nio.file.Files.exists(done)) return;
			String now = openKey.saveString();
			if (now.equals(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_K).getName()) || now.equals(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_C).getName())) {
				openKey.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_G));
				KeyMapping.resetMapping();
				Minecraft.getInstance().options.save();
			}
			java.nio.file.Files.writeString(done, "1");
		} catch (Throwable t) {
			Moddeus.LOG.warn("key move failed: {}", t.toString());
		}
	}

	public static String keyName() { return openKey == null ? "K" : openKey.getTranslatedKeyMessage().getString(); }

	static void hud(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof ClaudeScreen) return;
		if (dev.moddeus.client.content.AvatarFeed.visible) return; // Claude's body is on screen: its bubble shows this
		if (voiceHud(g, mc)) return;
		JsonObject s = Bridge.state;
		if (s == null) return;
		String status = ClaudeScreen.str(s, "status");
		for (JsonObject o : Bridge.states().values()) {
			String st = ClaudeScreen.str(o, "status");
			if ("waiting".equals(st)) status = "waiting";
			else if ("busy".equals(st) && !"waiting".equals(status)) status = "busy";
		}
		JsonArray items = s.getAsJsonArray("items");
		String msg;
		int dot;
		if ("waiting".equals(status)) { msg = "Moddeus needs your approval  ·  " + keyName(); dot = ClaudeScreen.WARN; }
		else if ("busy".equals(status)) { msg = ClaudeScreen.spinner() + " Moddeus is working…"; dot = ClaudeScreen.ACCENT; }
		else if (items != null && items.size() > seenItems && seenItems > 0) { msg = "Moddeus replied  ·  " + keyName(); dot = ClaudeScreen.OK; }
		else return;
		int w = mc.font.width(msg) + 16, x = g.guiWidth() - w - 6, y = 6;
		ClaudeScreen.rrect(g, x, y, w, 15, 0xE016121F);
		g.fill(x + 5, y + 6, x + 8, y + 9, dot);
		g.text(mc.font, msg, x + 11, y + 4, ClaudeScreen.TEXT, false);
	}
}
