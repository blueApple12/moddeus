package dev.moddeus.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.moddeus.live.LiveRegistry;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import dev.moddeus.live.Systems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

/** Client half of live/Systems: HUD panels + skill XP bar + sky tint (one ModGui HUD), waystone and shop screens, client jukebox songs. */
public final class SystemsClient {
	static List<Object[]> pendingSongs;
	static String shopMsg = "";
	static long shopMsgAt;
	static Map<String, String[]> hudCache;
	static long hudAt;

	@OnReload
	public static void hook() {
		if (pendingSongs == null) pendingSongs = new ArrayList<>();
		if (hudCache == null) hudCache = new java.util.HashMap<>();
		Systems.client = SystemsClient::fromServer;
		ModGui.hud("moddeus", "systems", SystemsClient::hud);
		if (System.getProperties().putIfAbsent("moddeus.systemsclient", Boolean.TRUE) == null)
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(SystemsClient::join);
	}

	@OnUnload
	public static void unhook() { Systems.client = null; ModGui.removeHud("moddeus", "systems"); }

	static void join(Object handler, Object sender, Minecraft mc) { mc.execute(SystemsClient::flushSongs); }

	static void fromServer(String what, Object arg) {
		Minecraft mc = Minecraft.getInstance();
		switch (what) {
			case "song" -> { synchronized (SystemsClient.class) { pendingSongs.add((Object[]) arg); } mc.execute(SystemsClient::flushSongs); }
			case "teleport" -> { @SuppressWarnings("unchecked") List<JsonObject> l = (List<JsonObject>) arg; mc.execute(() -> waystones(l)); }
			case "shop" -> mc.execute(() -> shop((String) arg));
			default -> {}
		}
	}

	static synchronized void flushSongs() {
		var c = Minecraft.getInstance().getConnection();
		if (c == null || pendingSongs.isEmpty()) return;
		var reg = c.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);
		for (Object[] s : pendingSongs) { JukeboxSong song = (JukeboxSong) s[1]; LiveRegistry.register(reg, (Identifier) s[0], k -> song); }
		pendingSongs.clear();
	}

	static ServerPlayer me() {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer s = mc.getSingleplayerServer();
		return s == null || mc.player == null ? null : s.getPlayerList().getPlayer(mc.player.getUUID());
	}

	static void server(Runnable r) { MinecraftServer s = Minecraft.getInstance().getSingleplayerServer(); if (s != null) s.execute(r); }

	// ---- HUD: sky tint, info panels, skill bar ----------------------------------------------------------------
	static void hud(ModGui.Ui ui) {
		if (Minecraft.getInstance().player == null) return;
		String sky = Systems.sky;
		JsonObject so = sky == null || Systems.SPECS == null ? null : Systems.SPECS.get(sky);
		if (so != null && so.has("tint")) {
			float in = Math.min(1f, (System.nanoTime() - Systems.skyStart) / 3e9f);
			int c = 0xFF000000 | Integer.parseInt(so.get("tint").getAsString().replace("#", ""), 16);
			float a = (so.has("alpha") ? so.get("alpha").getAsFloat() : 0.22f) * in * (0.85f + 0.15f * ModGui.Ui.pulse(ui.t, 0.25f));
			ui.gradient(0, 0, ui.w, ui.h, ModGui.Ui.alpha(c, a * 1.4f), ModGui.Ui.alpha(c, a * 0.6f));
			int v = ui.h / 5; // vignette
			ui.gradient(0, ui.h - v, ui.w, v, 0, ModGui.Ui.alpha(c, a * 1.6f));
		}
		ServerPlayer p = me();
		if (p == null || Systems.SPECS == null) return;
		long now = System.currentTimeMillis();
		if (now - hudAt > 250) { hudAt = now; hudCache.clear(); }
		int yl = 4, yr = 4;
		for (var en : Systems.of("hud")) {
			JsonObject o = en.getValue();
			String[] lines = hudCache.computeIfAbsent(en.getKey(), k -> resolve(p, o));
			String pos = o.has("pos") ? o.get("pos").getAsString() : "top_right";
			int accent = o.has("color") ? 0xFF000000 | Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16) : 0xFFE0B040;
			String title = o.has("title") ? o.get("title").getAsString() : "";
			int w = ui.width(title) + 12;
			for (String l : lines) w = Math.max(w, ui.width(l) + 12);
			int h = (title.isEmpty() ? 6 : 17) + lines.length * 10;
			boolean right = pos.contains("right");
			int x = right ? ui.w - w - 4 : 4, y = right ? yr : yl;
			if (pos.startsWith("middle")) y = ui.h / 2 - h / 2;
			ui.round(x, y, w, h, 3, 0x9A0E0F14, ModGui.Ui.alpha(accent, 0.55f));
			ui.rect(x + 3, y + 1, w - 6, 1, ModGui.Ui.alpha(accent, 0.9f));
			int ty = y + 4;
			if (!title.isEmpty()) { ui.shadowText(title, x + 6, ty, accent); ty += 13; }
			for (String l : lines) { ui.shadowText(l, x + 6, ty, 0xFFEDEDED); ty += 10; }
			if (right) yr += h + 3; else yl += h + 3;
		}
		Object[] ping = Systems.skillPing == null ? null : Systems.skillPing.get(p.getUUID());
		if (ping != null) {
			float age = (System.nanoTime() - (long) ping[1]) / 1e9f;
			if (age < 3.5f) {
				JsonObject o = Systems.SPECS.get((String) ping[0]);
				if (o != null) {
					int[] lv = Systems.levelOf(o, Systems.skillLevelXp(p, (String) ping[0]));
					float a = age < 3f ? 1 : 1 - (age - 3f) / 0.5f;
					int accent = o.has("bar_color") ? 0xFF000000 | Integer.parseInt(o.get("bar_color").getAsString().replace("#", ""), 16) : 0xFF5CE08A;
					int w = 150, x = ui.w / 2 - w / 2, y = ui.h - 62;
					ui.round(x - 4, y - 12, w + 8, 22, 3, ModGui.Ui.alpha(0xB0101014, a), ModGui.Ui.alpha(accent, a * 0.6f));
					String name = (o.has("name") ? o.get("name").getAsString() : (String) ping[0]) + "  Lv " + lv[0];
					ui.shadowText(name, x, y - 9, ModGui.Ui.alpha(0xFFFFFFFF, a));
					String g = "+" + ping[2] + " xp";
					ui.shadowText(g, x + w - ui.width(g), y - 9, ModGui.Ui.alpha(accent, a));
					float f = lv[2] <= 0 ? 1 : Math.min(1f, lv[1] / (float) lv[2]);
					float shown = ui.anim("skillbar", f, 10);
					ui.rect(x, y + 2, w, 4, ModGui.Ui.alpha(0xFF2A2A30, a));
					ui.rect(x, y + 2, (int) (w * shown), 4, ModGui.Ui.alpha(accent, a));
					ui.rect(x, y + 2, (int) (w * shown), 1, ModGui.Ui.alpha(0xFFFFFFFF, a * 0.5f));
				}
			}
		}
	}

	/** {coins} {score:obj} {skill:id} (level) {skillxp:id} {day} {time} {x} {y} {z} {biome} {dim} {health} {food} {player} {sky} */
	static String[] resolve(ServerPlayer p, JsonObject o) {
		List<String> out = new ArrayList<>();
		for (JsonElement el : o.has("lines") ? o.getAsJsonArray("lines") : new com.google.gson.JsonArray()) {
			String s = el.getAsString();
			StringBuilder b = new StringBuilder();
			int i = 0;
			while (i < s.length()) {
				int a = s.indexOf('{', i), z = a < 0 ? -1 : s.indexOf('}', a);
				if (a < 0 || z < 0) { b.append(s.substring(i)); break; }
				b.append(s, i, a).append(value(p, s.substring(a + 1, z)));
				i = z + 1;
			}
			out.add(b.toString());
		}
		return out.toArray(new String[0]);
	}

	static String value(ServerPlayer p, String k) {
		var l = p.level();
		long clock = l.getDefaultClockTime();
		String arg = k.contains(":") ? k.substring(k.indexOf(':') + 1) : "";
		return switch (k.contains(":") ? k.substring(0, k.indexOf(':')) : k) {
			case "coins" -> Systems.coins(p) + "";
			case "symbol" -> Systems.symbol();
			case "score" -> Systems.score(p, arg) + "";
			case "skill" -> Systems.skillLevel(p, arg) + "";
			case "skillxp" -> Systems.skillLevelXp(p, arg) + "";
			case "day" -> (clock / 24000 + 1) + "";
			case "time" -> { long t = Math.floorMod(clock + 6000, 24000L); yield String.format("%02d:%02d", t / 1000, t % 1000 * 60 / 1000); }
			case "x" -> p.getBlockX() + "";
			case "y" -> p.getBlockY() + "";
			case "z" -> p.getBlockZ() + "";
			case "biome" -> l.getBiome(p.blockPosition()).unwrapKey().map(b -> Systems.nice(b.identifier().getPath())).orElse("?");
			case "dim" -> Systems.nice(l.dimension().identifier().getPath());
			case "health" -> Math.round(p.getHealth()) + "";
			case "food" -> p.getFoodData().getFoodLevel() + "";
			case "player" -> p.getName().getString();
			case "sky" -> Systems.sky == null ? "-" : Systems.nice(Systems.sky);
			default -> "{" + k + "}";
		};
	}

	// ---- waystone list ------------------------------------------------------------------------------------------
	static void waystones(List<JsonObject> list) {
		ModGui.open("Waystones", ui -> {
			int w = 220, h = 36 + list.size() * 22, x = ui.w / 2 - w / 2, y = ui.h / 2 - h / 2;
			float o = ui.open;
			ui.gradient(0, 0, ui.w, ui.h, ModGui.Ui.alpha(0xC0081420, o), ModGui.Ui.alpha(0xC0140828, o));
			ui.glow(x, y, w, h, 8, ModGui.Ui.alpha(0x6040E0FF, o));
			ui.round(x, y, w, h, 6, 0xF0101820, 0xFF40D8F0);
			ui.ring(x + 16, y + 15, 7 + 1.5f * ModGui.Ui.pulse(ui.t, 0.8f), 1.5f, 0xFFB080FF);
			ui.shadowText("Travel to...", x + 30, y + 11, 0xFF9FF4FF);
			int ry = y + 30;
			for (JsonObject wpt : list) {
				boolean here = wpt.get("here").getAsBoolean();
				int d = wpt.get("dist").getAsInt();
				String label = wpt.get("name").getAsString() + (here ? "  (here)" : d >= 0 ? "  " + d + "m" : "  other world");
				if (ui.button("wp" + ry, label, x + 8, ry, w - 16, 18, here ? 0xFF1A2228 : 0xFF14303A, here ? 0xFF505A60 : 0xFF40D8F0) && !here) {
					ModGui.close();
					server(() -> { ServerPlayer p = me(); if (p != null) Systems.teleport(p, wpt); });
				}
				ry += 22;
			}
		});
	}

	// ---- shop ---------------------------------------------------------------------------------------------------
	static void shop(String id) {
		shopMsg = ""; shopMsgAt = 0;
		ModGui.open("Shop " + id, ui -> {
			JsonObject o = Systems.SPECS == null ? null : Systems.SPECS.get(id);
			if (o == null) { ModGui.close(); return; }
			var items = o.has("items") ? o.getAsJsonArray("items") : new com.google.gson.JsonArray();
			int rows = items.size(), w = 260, h = 34 + rows * 24 + 16, x = ui.w / 2 - w / 2, y = ui.h / 2 - h / 2;
			int gold = 0xFFF2C14E, green = 0xFF58D68D;
			ui.gradient(0, 0, ui.w, ui.h, ModGui.Ui.alpha(0xB0180E04, ui.open), ModGui.Ui.alpha(0xB0040A06, ui.open));
			ui.glow(x, y, w, h, 10, ModGui.Ui.alpha(0x50F2C14E, ui.open));
			ui.round(x, y, w, h, 5, 0xF2221A10, gold);
			ui.rect(x + 1, y + 24, w - 2, 1, 0x80F2C14E);
			String name = o.has("name") ? o.get("name").getAsString() : Systems.nice(id);
			ui.big(name, x + 10, y + 7, 1.25f, gold, false);
			ServerPlayer p = me();
			String bal = (p == null ? 0 : Systems.coins(p)) + " " + Systems.symbol();
			ui.disc(x + w - 18 - ui.width(bal), y + 12, 4, gold);
			ui.shadowText(bal, x + w - 10 - ui.width(bal), y + 8, 0xFFFFE9A8);
			int ry = y + 32;
			for (int i = 0; i < rows; i++) {
				JsonObject it = items.get(i).getAsJsonObject();
				int cnt = it.has("count") ? it.get("count").getAsInt() : 1;
				ItemStack st = BuiltInRegistries.ITEM.getOptional(Identifier.parse(it.get("item").getAsString())).map(t -> new ItemStack(t, cnt)).orElse(ItemStack.EMPTY);
				boolean hov = ui.hover(x + 6, ry - 2, w - 12, 22);
				ui.rect(x + 6, ry - 2, w - 12, 22, hov ? 0x40F2C14E : 0x20FFFFFF);
				ui.item(st, x + 10, ry + 1);
				if (ui.hover(x + 10, ry + 1, 16, 16)) ui.tooltip(st);
				ui.text((cnt > 1 ? cnt + "x " : "") + st.getHoverName().getString(), x + 30, ry + 6, 0xFFEFE6D2);
				final int row = i;
				int bx = x + w - 12;
				if (it.has("sell")) { bx -= 56; if (ui.button("s" + i, "Sell " + it.get("sell").getAsInt(), bx, ry, 54, 18, 0xFF1C3324, green)) act(id, row, true); }
				if (it.has("buy")) { bx -= 58; if (ui.button("b" + i, "Buy " + it.get("buy").getAsInt(), bx, ry, 54, 18, 0xFF3A2C12, gold)) act(id, row, false); }
				ry += 24;
			}
			if (!shopMsg.isEmpty() && System.currentTimeMillis() - shopMsgAt < 3000) ui.center(shopMsg, x + w / 2, y + h - 12, 0xFFFFF1C4);
		});
	}

	static void act(String id, int row, boolean sell) {
		server(() -> { ServerPlayer p = me(); if (p != null) { shopMsg = Systems.trade(p, id, row, sell); shopMsgAt = System.currentTimeMillis(); } });
	}
}
