package dev.moddeus.client.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.moddeus.client.ui.Bridge;
import dev.moddeus.client.ui.ClaudeUi;
import dev.moddeus.live.OnReload;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Feeds bridge state (status / speech / dictation) into the Claude orb (ClaudeOrb) and routes talking to it:
 * right-click the orb = type to it (ClaudeOrb.clicks), chat "claude, ..." = say it, hold G = voice. All of it goes to the Voice chat.
 */
public class AvatarFeed {
	static final long SPEECH_MS = 9000;
	static final Pattern CHAT = Pattern.compile("^\\s*@?(claude|קלוד)[,:!]?\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	/** True while Claude's body is in view: the HUD pill hides, the speech bubble shows the same thing. */
	public static volatile boolean visible;

	@OnReload
	public static void hook() {
		if (System.getProperty("moddeus.avatarfeed") != null) return;
		System.setProperty("moddeus.avatarfeed", "1");
		UseEntityCallback.EVENT.register(AvatarFeed::onUse);
		ClientSendMessageEvents.ALLOW_CHAT.register(AvatarFeed::onChat);
	}

	/** Kept registered (callbacks can't be removed); the orb is not an entity, clicks are handled in ClaudeOrb.clicks. */
	static InteractionResult onUse(Player player, Level level, InteractionHand hand, Entity e, EntityHitResult hit) {
		return InteractionResult.PASS;
	}

	static boolean onChat(String msg) {
		Matcher m = CHAT.matcher(msg);
		if (!m.matches()) return true;
		talk(m.group(2));
		return false; // not sent as a chat message
	}

	/** Sends text to the Voice chat (fast Haiku, the companion's brain). */
	public static void talk(String text) {
		String key = null;
		for (Map.Entry<String, JsonObject> en : Bridge.states().entrySet())
			if (en.getValue().has("voice") && en.getValue().get("voice").getAsBoolean()) key = en.getKey();
		if (key == null) Bridge.send("send", "text", text);
		else Bridge.send("send", "s", key, "text", text);
		ClaudeOrb.heard(text);
	}

	public static void tick() {
		boolean busy = false;
		String status = "", speech = "", dictation = "";
		long newest = 0;
		for (JsonObject s : Bridge.states().values()) {
			JsonArray items = s.getAsJsonArray("items");
			String st = str(s, "status");
			boolean voice = s.has("voice") && s.get("voice").getAsBoolean();
			if (voice) dictation = str(s, "dictation");
			if (items == null || items.isEmpty()) continue;
			if ("waiting".equals(st)) { busy = true; status = "I need your approval - press G"; }
			else if ("busy".equals(st) && !voice) { busy = true; if (status.isEmpty()) status = describe(items); }
			for (int i = items.size() - 1; i >= 0; i--) {
				JsonObject it = items.get(i).getAsJsonObject();
				if (!"assistant".equals(str(it, "kind"))) continue;
				long at = it.has("at") ? it.get("at").getAsLong() : 0;
				if (at > newest && System.currentTimeMillis() - at < SPEECH_MS) { newest = at; speech = shorten(str(it, "text"), voice ? 120 : 160); }
				break;
			}
		}
		ClaudeOrb.listening = ClaudeUi.recording;
		ClaudeOrb.busy = busy;
		ClaudeOrb.status = ClaudeUi.recording ? "Listening... " + dictation : status;
		ClaudeOrb.speech = speech;
		visible = false; // HUD pill always shows (orb has no bubble)
		flashOnCommand();
	}

	/** The orb flashes whenever any chat runs code or a command in the world. */
	static long lastToolAt;
	static void flashOnCommand() {
		for (JsonObject s : Bridge.states().values()) {
			JsonArray items = s.getAsJsonArray("items");
			if (items == null || items.isEmpty()) continue;
			JsonObject it = items.get(items.size() - 1).getAsJsonObject();
			long at = it.has("at") ? it.get("at").getAsLong() : 0;
			if (at <= lastToolAt || !"tool".equals(str(it, "kind"))) continue;
			String sum = str(it, "summary");
			if (sum.contains("game run") || sum.contains("game cmd") || sum.contains("game quick") || sum.contains("mcp__buddy")) { lastToolAt = at; ClaudeOrb.flash(); }
		}
	}

	/** What Claude is doing right now, from its latest tool call. */
	static String describe(JsonArray items) {
		for (int i = items.size() - 1; i >= 0; i--) {
			JsonObject it = items.get(i).getAsJsonObject();
			if (!"tool".equals(str(it, "kind"))) continue;
			String s = str(it, "summary"), n = str(it, "name");
			Matcher m = Pattern.compile("([A-Za-z0-9_]+\\.(java|json|png|mjs|md))").matcher(s);
			if (s.contains("gradlew") || s.contains("classes")) return "Compiling... hot-swapping into your world";
			if (s.contains("game run") || s.contains("game eval")) return "Running code in your world...";
			if (s.contains("game shot")) return "Looking at your screen...";
			if (s.matches("(?s).*mc\\.mjs (item|block|physics|mob|armor).*")) return "Making new content...";
			if (s.matches("(?s).*mc\\.mjs (pixels|vfx|vfx-tex|particle).*")) return "Drawing textures and effects...";
			if ("WebSearch".equals(n) || "WebFetch".equals(n) || s.contains("http")) return "Searching the web...";
			if (("Edit".equals(n) || "Write".equals(n) || s.contains("edit(") || s.contains("write(")) && m.find()) return "Editing " + m.group(1) + "...";
			if (m.find()) return "Reading " + m.group(1) + "...";
			return "Working on it...";
		}
		return "Thinking...";
	}

	static String shorten(String t, int max) {
		t = t.replaceAll("(?s)```.*?```", " ").replaceAll("[*_#`>]", "").replaceAll("\\s+", " ").trim();
		if (t.length() <= max) return t;
		int cut = Math.max(t.lastIndexOf(". ", max), t.lastIndexOf("! ", max));
		return cut > max / 3 ? t.substring(0, cut + 1) : t.substring(0, max) + "...";
	}

	static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}
