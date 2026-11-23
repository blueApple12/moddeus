package dev.moddeus.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Claude-Desktop-style front end for the bridged Claude Code session. Immediate-mode: hit boxes are rebuilt every frame. */
public class ClaudeScreen extends Screen {
	// Claude dark palette
	public static final int BG = 0xFF16121F, SIDEBAR = 0xFF100D18, PANEL = 0xFF201A33, PANEL_HI = 0xFF2B2345, BORDER = 0xFF3B3160,
		TEXT = 0xFFF3F0FF, SUB = 0xFFC6BFE0, MUTED = 0xFF8C84AE, ACCENT = 0xFF8B6CF0, USER = 0xFF2A2242,
		CODE_BG = 0xFF0E0B16, CODE = 0xFF7FE8DC, OK = 0xFF1FD6C4, ERR = 0xFFFF5C7A, WARN = 0xFFFFCC3D;
	static final int TOP = 22, LH = 10;
	static final char BT = (char) 96;
	static final String ESC = "\033";

	record Hit(int x, int y, int w, int h, Runnable run, boolean menu, boolean clipped) {}
	record Ln(FormattedCharSequence seq, int kind) {} // kind: 0 text, 1 code, 2 heading
	record Opt(String label, boolean selected, Runnable run) {}
	record Menu(int x, int y, List<Opt> opts) {}

	public MultiLineEditBox input;
	int composerY, boxH = 22;
	int slashSel;
	List<JsonObject> slashMatches = List.of();
	boolean terminal;
	int mx, my, side, colX, colW, clipTop, clipBot, contentH, termCols, termRows;
	double scrollUp, sideScroll;
	int sideMax;
	final List<Hit> hits = new ArrayList<>();
	Menu menu;
	final Set<String> expanded = new HashSet<>();
	final Map<String, Map<String, String>> answers = new HashMap<>();
	final Map<String, List<Ln>> wrapCache = new LinkedHashMap<>();

	public ClaudeScreen() { super(Component.literal("Moddeus")); }

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	protected void init() {
		String keep = input != null ? input.getValue() : drafts().getOrDefault(Bridge.active == null ? "" : Bridge.active, "");
		draftKey = Bridge.active;
		input = null;
		makeInput(keep, 300);
		setFocused(input);
		Bridge.send("skills");
		Bridge.send("recent");
		if (Bridge.active != null) Bridge.focus(Bridge.active);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int x, int y, float pt) {}

	@Override
	public void onClose() {
		saveDraft();
		Bridge.send("screen", "on", false);
		super.onClose();
	}

	// ---------------------------------------------------------------- render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt) {
		if (dev.moddeus.client.game.GameApi.capturing) return;
		mx = mouseX;
		my = mouseY;
		hits.clear();
		JsonObject st = Bridge.state;
		JsonArray items = st == null ? new JsonArray() : st.getAsJsonArray("items");
		ClaudeUi.seenItems = items.size();
		side = ClaudeUi.sidebarHidden ? 0 : Math.max(110, Math.min(150, width / 4));
		int areaW = width - side;
		colW = Math.min(areaW - 28, 400);
		colX = side + (areaW - colW) / 2;

		g.fill(0, 0, width, height, BG);
		if (side > 0) sessions(g);
		topBar(g, st);
		if (terminal) terminalView(g);
		else chat(g, st, items);
		super.extractRenderState(g, mouseX, mouseY, pt);
		if (!terminal) slashPopup(g, st);
		if (menu != null) drawMenu(g);
	}

	/** Left sidebar: Voice (the voice helper + its Opus brain), then Mods, each with its own chats. */
	void sessions(GuiGraphicsExtractor g) {
		g.fill(0, TOP, side, height, SIDEBAR);
		g.fill(side - 1, TOP, side, height, 0xFF241D38);
		int y = TOP + 6;
		y += 6 + wideButton(g, 6, y, side - 12, "+  New mod", () -> Bridge.send("project-new"));
		int listTop = y, listBot = height - 4;
		g.enableScissor(0, listTop, side, listBot);
		int sy = listTop - (int) sideScroll, start = sy;
		JsonArray all = Bridge.meta != null && Bridge.meta.has("sessions") ? Bridge.meta.getAsJsonArray("sessions") : new JsonArray();
		g.text(font, "Quick", 8, sy, MUTED, false); // voice + general chats that aren't about one mod
		boolean addHov = mx < side && in(side - 18, sy - 2, 14, 12);
		g.text(font, "+", side - 13, sy, addHov ? ACCENT : MUTED, false);
		if (sy >= listTop) hits.add(new Hit(side - 18, sy - 2, 14, 12, () -> Bridge.send("quick-new"), false, false));
		sy += 12;
		JsonObject voice = null;
		for (JsonElement e : all) if (bool(e.getAsJsonObject(), "voice")) voice = e.getAsJsonObject();
		sy = chatRow(g, voice, "Voice", 4, sy, listTop, listBot, null);
		for (JsonElement e : all) { // Voice brain + quick chats
			JsonObject s = e.getAsJsonObject();
			if (bool(s, "brain")) sy = chatRow(g, s, "Voice brain", 4, sy, listTop, listBot, null);
			else if (bool(s, "quick")) sy = chatRow(g, s, str(s, "title"), 4, sy, listTop, listBot, null);
		}
		sy += 8;
		sy = mods(g, sy, listTop, listBot);
		g.disableScissor();
		sideMax = Math.max(0, sy - start - (listBot - listTop));
	}

	/** One chat row (s may be null: then the row creates it on click, if open != null). */
	int chatRow(GuiGraphicsExtractor g, JsonObject s, String label, int x, int sy, int listTop, int listBot, Runnable open) {
		if (s == null && open == null) return sy;
		String key = s == null ? "" : str(s, "key"), status = s == null ? "" : str(s, "status");
		boolean act = !key.isEmpty() && key.equals(Bridge.active), hov = mx < side && in(x, sy, side - x - 4, 14) && my >= listTop;
		if (act || hov) rrect(g, x, sy, side - x - 4, 14, act ? PANEL_HI : PANEL);
		String mark = switch (status) { case "busy" -> spinner(); case "waiting" -> "!"; case "" -> "+"; case "paused", "stopped" -> "○"; default -> "●"; };
		int mc = switch (status) { case "busy" -> ACCENT; case "waiting" -> WARN; case "", "paused", "stopped" -> 0xFF5F577F; default -> OK; };
		g.text(font, mark, x + 5, sy + 3, mc, false);
		g.text(font, font.plainSubstrByWidth(label, side - x - 30), x + 15, sy + 3, act ? TEXT : SUB, false);
		if (sy >= listTop && sy + 14 <= listBot) hits.add(new Hit(x, sy, side - x - 4, 14, s == null ? open : () -> { Bridge.focus(key); scrollUp = 0; }, false, false));
		return sy + 15;
	}

	static String openMod, confirmDelete;

	/** Sidebar "Mods": each mod is a project with its own chats, On/Off, Undo, Delete. */
	int mods(GuiGraphicsExtractor g, int sy, int listTop, int listBot) {
		g.text(font, "Mods", 8, sy, MUTED, false);
		sy += 12;
		JsonArray sessions = Bridge.meta != null && Bridge.meta.has("sessions") ? Bridge.meta.getAsJsonArray("sessions") : new JsonArray();
		for (JsonElement e : dev.moddeus.client.Projects.list()) {
			JsonObject p = e.getAsJsonObject();
			String id = str(p, "id");
			boolean on = !p.has("enabled") || p.get("enabled").getAsBoolean(), open = id.equals(openMod);
			boolean hov = mx < side && in(4, sy, side - 8, 14) && my >= listTop;
			boolean busy = false;
			for (JsonElement se : sessions) { JsonObject s = se.getAsJsonObject(); if (id.equals(str(s, "project")) && str(s, "status").equals("busy")) busy = true; }
			if (open || hov) rrect(g, 4, sy, side - 8, 14, open ? PANEL_HI : PANEL);
			g.text(font, busy ? spinner() : open ? "▾" : "▸", 9, sy + 3, busy ? ACCENT : on ? OK : 0xFF5F577F, false);
			String kind = on ? dev.moddeus.client.Projects.kinds(id) : "off"; // what the mod is made of: java / dp / rp
			g.text(font, font.plainSubstrByWidth(str(p, "name"), side - 32 - (hov ? 0 : font.width(kind) + 4)), 19, sy + 3, on ? (open ? TEXT : SUB) : MUTED, false);
			if (!kind.isEmpty() && !hov) g.text(font, kind, side - 8 - font.width(kind), sy + 3, on ? 0xFF5F577F : MUTED, false);
			final int ry = sy;
			String last = null;
			for (JsonElement se : sessions) { JsonObject s = se.getAsJsonObject(); if (id.equals(str(s, "project"))) last = str(s, "key"); }
			final String lastKey = last;
			if (sy >= listTop && sy + 14 <= listBot) hits.add(new Hit(4, ry, side - 8, 14, () -> {
				confirmDelete = null;
				if (id.equals(openMod)) { openMod = null; return; }
				openMod = id; // open the mod = open its chat (every mod has one)
				if (lastKey != null) { Bridge.focus(lastKey); scrollUp = 0; } else Bridge.send("project-chat", "id", id);
			}, false, false));
			sy += 15;
			if (!open) continue;
			for (JsonElement se : sessions) { // the mod's chats
				JsonObject s = se.getAsJsonObject();
				if (!id.equals(str(s, "project"))) continue;
				String key = str(s, "key");
				boolean act = key.equals(Bridge.active), h2 = mx < side && in(12, sy, side - 16, 13) && my >= listTop;
				if (act || h2) rrect(g, 12, sy, side - 16, 13, act ? PANEL_HI : PANEL);
				g.text(font, str(s, "status").equals("busy") ? spinner() : "·", 15, sy + 3, ACCENT, false);
				g.text(font, font.plainSubstrByWidth(str(s, "title"), side - 44), 24, sy + 3, act ? TEXT : SUB, false);
				final int cy = sy;
				if (sy >= listTop && sy + 13 <= listBot) {
					hits.add(new Hit(12, cy, side - 16, 13, () -> { Bridge.focus(key); scrollUp = 0; }, false, false));
					if (h2) { g.text(font, "×", side - 14, sy + 3, MUTED, false); hits.add(new Hit(side - 18, cy, 12, 13, () -> Bridge.send("close", "s", key), false, false)); }
				}
				sy += 14;
			}
			// actions: + chat, On/Off, Undo, Delete (click twice)
			int bx = 12;
			String[] labels = {"+ chat", on ? "Off" : "On", "Undo", id.equals(confirmDelete) ? "Sure?" : "Delete"};
			Runnable[] acts = {
				() -> Bridge.send("project-chat", "id", id),
				() -> dev.moddeus.client.Projects.setEnabled(id, !on),
				() -> dev.moddeus.client.Projects.undo(id),
				() -> { if (id.equals(confirmDelete)) { confirmDelete = null; openMod = null; dev.moddeus.client.Projects.delete(id); } else confirmDelete = id; }};
			for (int i = 0; i < labels.length; i++) {
				int w = font.width(labels[i]) + 6;
				if (bx + w > side - 4) { bx = 12; sy += 13; }
				boolean bh = in(bx, sy, w, 12) && my >= listTop;
				int fg = i == 3 ? (id.equals(confirmDelete) ? 0xFFFF5C7A : bh ? 0xFFFF5C7A : MUTED) : bh ? TEXT : MUTED;
				if (bh) rrect(g, bx, sy, w, 12, PANEL);
				g.text(font, labels[i], bx + 3, sy + 2, fg, false);
				if (sy >= listTop && sy + 12 <= listBot) hits.add(new Hit(bx, sy, w, 12, acts[i], false, false));
				bx += w + 3;
			}
			sy += 16;
		}
		return sy + 6;
	}

	/** Typing "/" opens a skill & command picker above the composer (like Claude Code / Desktop). */
	void slashPopup(GuiGraphicsExtractor g, JsonObject st) {
		String v = input.getValue();
		JsonArray skills = Bridge.meta == null ? null : Bridge.meta.getAsJsonArray("skills");
		if (!v.startsWith("/") || v.contains(" ") || skills == null) { slashMatches = List.of(); return; }
		String q = v.substring(1).toLowerCase();
		List<JsonObject> starts = new ArrayList<>(), contains = new ArrayList<>();
		for (JsonElement e : skills) {
			JsonObject s = e.getAsJsonObject();
			String n = str(s, "name").toLowerCase();
			if (n.startsWith(q) || n.contains(":" + q)) starts.add(s);
			else if (n.contains(q) || (q.length() > 2 && str(s, "description").toLowerCase().contains(q))) contains.add(s);
		}
		starts.addAll(contains);
		slashMatches = starts;
		if (starts.isEmpty()) return;
		slashSel = Math.max(0, Math.min(slashSel, starts.size() - 1));
		int rows = Math.min(8, starts.size());
		int first = Math.max(0, Math.min(slashSel - rows + 1, starts.size() - rows));
		int rh = 14, h = rows * rh + 6, y = composerY - 4 - h, x = colX, w = colW;
		g.nextStratum();
		rrect(g, x - 1, y - 1, w + 2, h + 2, BORDER);
		rrect(g, x, y, w, h, 0xFF1C1729);
		for (int i = 0; i < rows; i++) {
			int idx = first + i;
			JsonObject s = starts.get(idx);
			int ry = y + 3 + i * rh;
			boolean sel = idx == slashSel, hov = in(x + 2, ry, w - 4, rh);
			if (sel || hov) rrect(g, x + 2, ry, w - 4, rh, sel ? PANEL_HI : PANEL);
			String name = "/" + str(s, "name");
			String nm = font.plainSubstrByWidth(name + (str(s, "args").isEmpty() ? "" : " " + str(s, "args")), w / 2);
			g.text(font, nm, x + 7, ry + 3, sel ? TEXT : SUB, false);
			int dx = x + 14 + font.width(nm);
			String src = str(s, "source");
			String d = str(s, "description");
			g.text(font, font.plainSubstrByWidth(d, Math.max(0, x + w - dx - 50)), dx, ry + 3, MUTED, false);
			g.text(font, src, x + w - 6 - font.width(src), ry + 3, 0xFF5F577F, false);
			hits.add(new Hit(x + 2, ry, w - 4, rh, () -> runSlash(name), false, false));
		}
		g.text(font, "↑↓ select · Enter run · Tab add arguments", x + 4, y - 10, 0xFF5F577F, false);
	}

	/** Commands that open a picker or print output in the TUI (/model, /mcp, /context…) are shown in the live terminal. */
	static boolean interactive(String t) {
		String name = t.substring(1).split(" ", 2)[0];
		boolean hasArgs = t.trim().contains(" ");
		if (Bridge.meta == null || !Bridge.meta.has("skills")) return false;
		for (JsonElement e : Bridge.meta.getAsJsonArray("skills")) {
			JsonObject s = e.getAsJsonObject();
			if (str(s, "name").equals(name)) return bool(s, "interactive") && (!hasArgs || name.equals("context") || name.equals("mcp"));
		}
		return false;
	}

	void runSlash(String name) {
		input.setValue(name);
		slashMatches = List.of();
		submit();
	}


	void topBar(GuiGraphicsExtractor g, JsonObject st) {
		g.fill(0, TOP - 1, width, TOP, 0xFF241D38);
		String status = st == null ? "offline" : str(st, "status");
		int dot;
		String label;
		switch (status) {
			case "idle" -> { dot = OK; label = "Ready"; }
			case "busy" -> { dot = ACCENT; label = spinner() + " Working"; }
			case "waiting" -> { dot = WARN; label = "Needs approval"; }
			case "starting" -> { dot = WARN; label = "Starting"; }
			case "live" -> { dot = 0xFF6BA4E8; label = "Live view"; }
			case "paused" -> { dot = 0xFF5F577F; label = "Not started"; }
			default -> { dot = ERR; label = Bridge.connected() ? "Stopped" : "Offline"; }
		}
		g.fill(0, 0, width, TOP - 1, SIDEBAR);
		pill(g, 4, 4, "☰", !ClaudeUi.sidebarHidden, () -> ClaudeUi.sidebarHidden = !ClaudeUi.sidebarHidden);
		int lx = 4 + pillW("☰") + 6;
		g.blit(net.minecraft.resources.Identifier.fromNamespaceAndPath("moddeus", "textures/gui/logo.png"), lx, 2, lx + 84, 2 + 17, 0, 1, 0, 1); // pixel logo (167x34 at half size)
		int x = lx + 84 + 10;
		g.fill(x, 9, x + 4, 13, dot);
		g.text(font, label, x + 8, 7, SUB, false);
		if (st == null) return;

		// right-aligned controls, laid out right to left
		int rx = width - 6;
		rx -= pillW("⋯");
		pill(g, rx, 4, "⋯", false, () -> openMenu(width - 120, List.of(
			new Opt("New chat", false, () -> Bridge.send("new")),
			new Opt("Clear this chat", false, () -> Bridge.send("clear")),
			new Opt("Close this chat", false, () -> Bridge.send("close")),
			new Opt("Compact conversation", false, () -> Bridge.send("slash", "text", "/compact")),
			new Opt("Show context usage", false, () -> Bridge.send("slash", "text", "/context")),
			new Opt("Open raw terminal", false, () -> setTerminal(true)),
			new Opt("Restart Claude Code", false, () -> Bridge.send("restart")),
			new Opt("Restart with a fresh session", false, () -> Bridge.send("restart", "fresh", true)))));
		rx -= pillW("+ New") + 3;
		pill(g, rx, 4, "+ New", false, () -> Bridge.send("new"));
		if (terminal) {
			rx -= pillW("← Back to chat") + 3;
			pill(g, rx, 4, "← Back to chat", true, () -> setTerminal(false));
		}
		boolean ultra = bool(st, "ultracode");
		rx -= pillW("Ultracode") + 3;
		pill(g, rx, 4, "Ultracode", ultra, () -> Bridge.send("ultracode", "on", !ultra));
		String mode = str(st, "mode");
		String ml = modeLabel(mode) + " ▾";
		rx -= pillW(ml) + 3;
		int modeX = rx;
		pill(g, rx, 4, ml, false, () -> {
			List<Opt> o = new ArrayList<>();
			for (String m : List.of("default", "acceptEdits", "plan", "auto", "bypassPermissions"))
				o.add(new Opt(modeLabel(m) + "  " + modeHint(m), m.equals(mode), () -> Bridge.send("mode", "value", m)));
			openMenu(modeX, o);
		});
		String effort = str(st, "effort");
		String el = (effort.isEmpty() ? "Effort" : cap(effort)) + " ▾";
		rx -= pillW(el) + 3;
		int effX = rx;
		pill(g, rx, 4, el, false, () -> {
			List<Opt> o = new ArrayList<>();
			for (String e : List.of("low", "medium", "high", "xhigh", "max")) o.add(new Opt(cap(e), e.equals(effort), () -> Bridge.send("effort", "value", e)));
			openMenu(effX, o);
		});
		String model = str(st, "modelLabel").isEmpty() ? str(st, "model") : str(st, "modelLabel");
		String mdl = (model.isEmpty() ? "Model" : model) + " ▾";
		rx -= pillW(mdl) + 3;
		int modelX = rx;
		pill(g, rx, 4, mdl, false, () -> {
			List<Opt> o = new ArrayList<>();
			JsonArray models = Bridge.meta == null ? null : Bridge.meta.getAsJsonArray("models");
			if (models != null) for (JsonElement e : models) {
				JsonObject m = e.getAsJsonObject();
				String id = str(m, "id"), lab = str(m, "label");
				o.add(new Opt(lab, lab.equalsIgnoreCase(model) || id.equalsIgnoreCase(str(st, "model")), () -> Bridge.send("model", "value", id)));
			}
			openMenu(modelX, o);
		});
		if ("busy".equals(status) || "waiting".equals(status)) {
			rx -= pillW("■ Stop") + 3;
			pill(g, rx, 4, "■ Stop", false, () -> Bridge.send("interrupt"));
		}
	}

	/** Unsent text per chat: kept when the screen closes or another chat opens. */
	static java.util.Map<String, String> drafts;
	static String draftKey;
	static java.util.Map<String, String> drafts() { if (drafts == null) drafts = new java.util.HashMap<>(); return drafts; }
	void saveDraft() { if (input != null) drafts().put(draftKey == null ? "" : draftKey, input.getValue()); }

	void chat(GuiGraphicsExtractor g, JsonObject st, JsonArray items) {
		if (!java.util.Objects.equals(draftKey, Bridge.active)) { // switched chat: park this text, bring back that chat's
			saveDraft();
			draftKey = Bridge.active;
			input.setValue(drafts().getOrDefault(draftKey == null ? "" : draftKey, ""));
		}
		if (inputW != colW - 30) makeInput(input.getValue(), colW - 30);
		int lines = Math.max(1, Math.min(6, font.split(Component.literal(input.getValue().isEmpty() ? " " : input.getValue()), Math.max(20, colW - 38)).size()));
		boxH = 17 + lines * 9;
		composerY = height - 14 - boxH;
		clipTop = TOP + 2;
		clipBot = composerY - 14;
		int viewH = clipBot - clipTop;
		int maxScroll = Math.max(0, contentH - viewH);
		scrollUp = Math.max(0, Math.min(scrollUp, maxScroll));
		int y = clipTop + 8 - (maxScroll - (int) scrollUp);

		if (items.isEmpty()) emptyState(g);
		g.enableScissor(side, clipTop, width, clipBot);
		int total = 8;
		List<JsonObject> run = new ArrayList<>();
		for (int i = 0; i <= items.size(); i++) {
			JsonObject it = i < items.size() ? items.get(i).getAsJsonObject() : null;
			if (it != null && "tool".equals(str(it, "kind"))) { run.add(it); continue; }
			if (!run.isEmpty()) {
				int h = run.size() == 1 ? item(g, run.get(0), y, false) : toolGroup(g, run, y, false);
				if (y + h > clipTop && y < clipBot) { if (run.size() == 1) item(g, run.get(0), y, true); else toolGroup(g, run, y, true); }
				y += h;
				total += h;
				run = new ArrayList<>();
			}
			if (it == null) break;
			int h = item(g, it, y, false);
			if (y + h > clipTop && y < clipBot) item(g, it, y, true);
			y += h;
			total += h;
		}
		g.disableScissor();
		contentH = total + 4;

		// status line
		String status = st == null ? "" : str(st, "status");
		String notice = st == null ? "" : str(st, "notice");
		String line = !Bridge.error.isEmpty() && !Bridge.connected() ? Bridge.error
			: "busy".equals(status) ? spinner() + " Moddeus is working…"
			: "waiting".equals(status) ? "Waiting for your approval ↑"
			: notice;
		if (!line.isEmpty()) g.text(font, font.plainSubstrByWidth(line, colW), colX + 2, composerY - 11, "busy".equals(status) ? ACCENT : MUTED, false);

		// composer
		rrect(g, colX - 1, composerY - 1, colW + 2, boxH + 2, input.isFocused() ? 0xFF5C4F8F : BORDER);
		rrect(g, colX, composerY, colW, boxH, PANEL);
		input.visible = true;
		input.setX(colX + 4);
		input.setY(composerY + 3);
		input.setWidth(colW - 66);
		input.setHeight(boxH - 6);
		input.refreshScrollAmount(); // clamp after resizing so short text is never scrolled out of view
		boolean can = !input.getValue().isBlank() || !refs().isEmpty();
		int bx = colX + colW - 20;
		int by = composerY + boxH - 18;
		rrect(g, bx, by, 15, 14, can ? ACCENT : 0xFF3A3352);
		g.centeredText(font, "↑", bx + 8, by + 3, can ? 0xFF100D18 : MUTED);
		hits.add(new Hit(bx, by, 15, 14, this::submit, false, false));
		// reference images: "+ img" button and one chip per attached image (click a chip to remove it)
		rrect(g, bx - 34, by, 31, 14, PANEL_HI);
		g.centeredText(font, "+ img", bx - 18, by + 3, SUB);
		hits.add(new Hit(bx - 34, by, 31, 14, this::pickImages, false, false));
		int chipX = colX + 2, chipY = composerY - 24;
		for (java.nio.file.Path p : new java.util.ArrayList<>(refs())) {
			String name = p.getFileName().toString().replaceFirst("^\\d{10,}-", "");
			String label = "▣ " + font.plainSubstrByWidth(name, 90) + "  ×";
			int cw = font.width(label) + 10;
			if (chipX + cw > colX + colW) break;
			rrect(g, chipX, chipY, cw, 13, PANEL_HI);
			g.text(font, label, chipX + 5, chipY + 3, ACCENT, false);
			hits.add(new Hit(chipX, chipY, cw, 13, () -> refs().remove(p), false, false));
			chipX += cw + 4;
		}
		g.text(font, "Enter send · Shift+Enter new line · / commands · Esc close · " + ClaudeUi.keyName() + " opens", colX + 2, height - 10, 0xFF5F577F, false);
	}

	void emptyState(GuiGraphicsExtractor g) {
		int cy = (clipTop + clipBot) / 2 - 30;
		g.pose().pushMatrix();
		g.pose().translate(colX + colW / 2f, cy);
		g.pose().scale(1.6f, 1.6f);
		g.centeredText(font, "✻ What should we build?", 0, 0, TEXT);
		g.pose().popMatrix();
	}

	int inputW;

	/** MultiLineEditBox wraps at the width it was built with, so it is rebuilt when the chat column changes. */
	void makeInput(String value, int w) {
		boolean focused = input == null || input.isFocused();
		if (input != null) removeWidget(input);
		input = MultiLineEditBox.builder().setShowBackground(false).setShowDecorations(false).setTextColor(TEXT).setCursorColor(TEXT)
			.setPlaceholder(Component.literal("Ask Moddeus…  (/ for commands, Shift+Enter for a new line)").withColor(MUTED & 0xFFFFFF))
			.build(font, w, 20, Component.literal("Message"));
		input.setCharacterLimit(32000);
		input.setValue(value);
		inputW = w;
		addRenderableWidget(input);
		if (focused) setFocused(input);
	}

	/** Measures (draw=false) or draws one conversation item; returns its height. */
	int item(GuiGraphicsExtractor g, JsonObject it, int y, boolean draw) {
		String kind = str(it, "kind"), id = str(it, "id");
		switch (kind) {
			case "user": {
				List<Ln> ls = wrap(id, str(it, "text"), colW * 3 / 4 - 14, false);
				int w = 0;
				for (Ln l : ls) w = Math.max(w, font.width(l.seq()));
				int h = ls.size() * LH + 8;
				if (draw) {
					int bx = colX + colW - w - 14;
					rrect(g, bx, y, w + 14, h, USER);
					for (int i = 0; i < ls.size(); i++) g.text(font, ls.get(i).seq(), bx + 7, y + 5 + i * LH, TEXT, false);
				}
				return h + 10;
			}
			case "assistant": {
				List<Ln> ls = wrap(id, str(it, "text").strip(), colW, true);
				if (draw) lines(g, ls, colX, y);
				return ls.size() * LH + 8;
			}
			case "tool":
				return tool(g, it, id, y, draw);
			case "permission":
				return permission(g, it, id, y, draw);
			default:
				if (draw) g.centeredText(font, font.plainSubstrByWidth(str(it, "text"), colW), colX + colW / 2, y + 2, MUTED);
				return 16;
		}
	}

	/** Consecutive tool calls collapse into one row, like Claude Desktop: "Used 12 tools". Click to list them. */
	int toolGroup(GuiGraphicsExtractor g, List<JsonObject> tools, int y, boolean draw) {
		String gid = "g" + str(tools.get(0), "id");
		boolean open = expanded.contains(gid);
		JsonObject last = tools.get(tools.size() - 1);
		boolean running = false, failed = false;
		for (JsonObject t : tools) { running |= "running".equals(str(t, "state")); failed |= "error".equals(str(t, "state")); }
		int h = 13;
		if (draw) {
			if (in(colX, y, colW, 12)) rrect(g, colX - 3, y - 1, colW + 6, 13, 0x18FFFFFF);
			g.text(font, running ? spinner() : failed ? "!" : "✓", colX, y + 2, running ? ACCENT : failed ? WARN : OK, false);
			String head = running ? "Working" : tools.size() + (tools.size() == 1 ? " step" : " steps");
			g.text(font, Component.literal(head).withStyle(Style.EMPTY.withBold(true)), colX + 10, y + 2, SUB, false);
			int nx = colX + 16 + font.width(head) + 2;
			String tail = running ? friendly(last) : friendly(last) + (failed ? " (something failed, click to see)" : "");
			g.text(font, font.plainSubstrByWidth(tail.replace('\n', ' '), Math.max(0, colX + colW - nx - 12)), nx, y + 2, MUTED, false);
			g.text(font, open ? "▴" : "▾", colX + colW - 8, y + 2, MUTED, false);
			hits.add(new Hit(colX, y, colW, 12, () -> { if (!expanded.remove(gid)) expanded.add(gid); }, false, true));
		}
		if (open) {
			int yy = y + 14;
			for (JsonObject t : tools) {
				int th = tool(g, t, str(t, "id"), yy, false);
				if (draw && yy + th > clipTop && yy < clipBot) {
					g.fill(colX + 3, yy, colX + 4, yy + th - 2, 0xFF2B2345);
					tool(g, t, str(t, "id"), yy, true);
				}
				yy += th;
			}
			h = yy - y;
		}
		return h + 4;
	}

	/** What a tool step does, in words a player understands (Claude's own description when it gave one). */
	public static String friendly(JsonObject it) {
		String name = str(it, "name"), sum = str(it, "summary").strip(), input = str(it, "input");
		var dm = java.util.regex.Pattern.compile("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(input);
		if (dm.find() && !dm.group(1).isBlank()) return cap1(dm.group(1).replace("\\\"", "\""));
		String file = sum.replaceAll(".*[/\\\\]", "");
		String low = sum.toLowerCase();
		String s = switch (name) {
			case "Read" -> "Reading " + file;
			case "Write" -> "Creating " + file;
			case "Edit", "MultiEdit", "NotebookEdit" -> "Changing " + file;
			case "Glob", "Grep" -> "Searching the project";
			case "WebSearch" -> "Searching the web: " + sum;
			case "WebFetch" -> "Reading a web page";
			case "Task", "Agent" -> "Asking a helper: " + sum;
			case "TodoWrite", "TaskCreate", "TaskUpdate" -> "Updating its to-do list";
			case "Skill" -> "Using the " + sum + " guide";
			case "ToolSearch" -> "Looking for the right tool";
			case "AskUserQuestion" -> "Asking you something";
			case "Bash", "PowerShell" -> null;
			default -> name.startsWith("mcp__buddy") ? "Moving Moddeus" : name.startsWith("mcp__") ? "Using " + name.replaceAll("^mcp__([^_]+)__", "$1: ").replace('_', ' ') : null;
		};
		if (s != null) return s;
		if (low.contains("gradlew")) return "Building the mod";
		if (low.contains("mc.mjs game shot")) return "Looking at the game (screenshot)";
		if (low.contains("mc.mjs game run") || low.contains("mc.mjs game eval")) return "Trying something live in the game";
		if (low.contains("mc.mjs game cmd")) return "Running a game command";
		if (low.contains("mc.mjs game context")) return "Checking where you are";
		if (low.contains("mc.mjs game log")) return "Reading the game log";
		if (low.contains("mc.mjs test")) return "Testing";
		if (low.contains("mc.mjs remove")) return "Removing something";
		if (low.contains("mc.mjs undo")) return "Undoing a change";
		if (low.contains("mc.mjs project")) return "Naming the mod";
		if (low.matches("(?s).*mc\\.mjs (block|item|food|sound|texture|mob|vfx).*")) return "Adding new content";
		if (low.contains("curl") || low.contains("invoke-webrequest") || low.contains("wget")) return "Downloading something";
		if (low.startsWith("git ")) return "Saving history";
		if (low.matches("(?s)^(ls|dir|find|cat|head|tail|type)\\b.*")) return "Looking through files";
		if (low.contains("rg ") || low.contains("grep")) return "Searching the project";
		if (low.contains("javap") || low.contains("unzip")) return "Looking up how Minecraft works";
		return "Running a command";
	}

	static String cap1(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

	int tool(GuiGraphicsExtractor g, JsonObject it, String id, int y, boolean draw) {
		String state = str(it, "state");
		boolean open = expanded.contains(id);
		int h = 13;
		List<Ln> in = List.of(), out = List.of();
		if (open) {
			in = cap(wrap(id + "i", str(it, "input"), colW - 14, false, 1), 24);
			out = cap(wrap(id + "o", str(it, "output"), colW - 14, false, 1), 24);
			h += 6 + (in.size() + (out.isEmpty() ? 0 : out.size() + 1)) * LH;
		}
		if (draw) {
			boolean hov = in(colX, y, colW, 12);
			if (hov) rrect(g, colX - 3, y - 1, colW + 6, 13, 0x18FFFFFF);
			String icon = switch (state) { case "ok" -> "✓"; case "error" -> "✗"; default -> spinner(); };
			int ic = switch (state) { case "ok" -> OK; case "error" -> ERR; default -> ACCENT; };
			g.text(font, icon, colX, y + 2, ic, false);
			// plain language first; the raw tool + command show when the row is opened
			String name = friendly(it);
			g.text(font, font.plainSubstrByWidth(name, Math.max(0, colW - 24)), colX + 10, y + 2, SUB, false);
			g.text(font, open ? "▴" : "▾", colX + colW - 8, y + 2, MUTED, false);
			hits.add(new Hit(colX, y, colW, 12, () -> { if (!expanded.remove(id)) expanded.add(id); }, false, true));
			if (open) {
				int by = y + 14;
				g.fill(colX, by, colX + colW, y + h - 2, CODE_BG);
				int ly = by + 3;
				g.text(font, str(it, "name"), colX + colW - 6 - font.width(str(it, "name")), ly, MUTED, false);
				for (Ln l : in) { g.text(font, l.seq(), colX + 6, ly, CODE, false); ly += LH; }
				if (!out.isEmpty()) {
					g.text(font, "→ result", colX + 6, ly, MUTED, false);
					ly += LH;
					for (Ln l : out) { g.text(font, l.seq(), colX + 6, ly, SUB, false); ly += LH; }
				}
			}
		}
		return h + 3;
	}

	int permission(GuiGraphicsExtractor g, JsonObject it, String id, int y, boolean draw) {
		String name = str(it, "name"), state = str(it, "state");
		boolean pending = "pending".equals(state);
		JsonObject raw = it.has("raw") && it.get("raw").isJsonObject() ? it.getAsJsonObject("raw") : null;
		int pad = 8, w = colW, iw = w - pad * 2;
		int cy = y + pad;
		String title = switch (name) {
			case "AskUserQuestion" -> "Moddeus has a question";
			case "ExitPlanMode" -> "Moddeus' plan is ready";
			default -> "Moddeus wants to: " + friendly(it);
		};
		List<Runnable> deferred = new ArrayList<>();
		final int titleY = cy;
		deferred.add(() -> g.text(font, Component.literal(title).withStyle(Style.EMPTY.withBold(true)), colX + pad, titleY, TEXT, false));
		cy += 13;

		if ("AskUserQuestion".equals(name) && raw != null && raw.has("questions")) {
			Map<String, String> ans = answers.computeIfAbsent(id, k -> new LinkedHashMap<>());
			JsonArray qs = raw.getAsJsonArray("questions");
			for (JsonElement qe : qs) {
				JsonObject q = qe.getAsJsonObject();
				String qt = str(q, "question");
				boolean multi = bool(q, "multiSelect");
				for (Ln l : wrap(id + qt, qt, iw, false)) { final int ly = cy; deferred.add(() -> g.text(font, l.seq(), colX + pad, ly, SUB, false)); cy += LH; }
				cy += 2;
				JsonArray opts = q.getAsJsonArray("options");
				if (opts != null) for (JsonElement oe : opts) {
					JsonObject o = oe.getAsJsonObject();
					String lab = str(o, "label"), d = str(o, "description");
					String cur = ans.getOrDefault(qt, "");
					boolean sel = multi ? List.of(cur.split(", ")).contains(lab) : cur.equals(lab);
					final int oy = cy;
					deferred.add(() -> {
						boolean hov = pending && in(colX + pad, oy, iw, 14);
						rrect(g, colX + pad, oy, iw, 14, sel ? 0xFF3A2C66 : hov ? PANEL_HI : 0xFF1A1526);
						String head = (sel ? "● " : "○ ") + lab;
						g.text(font, head, colX + pad + 5, oy + 3, sel ? ACCENT : TEXT, false);
						int lw = font.width(head) + 12;
						g.text(font, font.plainSubstrByWidth(d, Math.max(0, iw - lw - 8)), colX + pad + lw, oy + 3, MUTED, false);
						if (pending) hits.add(new Hit(colX + pad, oy, iw, 14, () -> {
							if (multi) {
								List<String> l = new ArrayList<>(cur.isEmpty() ? List.of() : List.of(cur.split(", ")));
								if (!l.remove(lab)) l.add(lab);
								ans.put(qt, String.join(", ", l));
							} else ans.put(qt, lab);
							if (qs.size() == 1 && !multi) answer(id, ans);
						}, false, true));
					});
					cy += 16;
				}
				cy += 4;
			}
			if (pending) {
				final int by = cy;
				boolean all = ans.size() >= qs.size();
				deferred.add(() -> {
					int bx = colX + pad;
					if (all) bx += 4 + button(g, bx, by, "Submit answers", ACCENT, 0xFF100D18, () -> answer(id, ans));
					button(g, bx, by, "Skip", PANEL_HI, SUB, () -> Bridge.send("permission", "id", id, "behavior", "deny", "message", "The player skipped the question."));
				});
				cy += 18;
			}
		} else if ("ExitPlanMode".equals(name) && raw != null) {
			List<Ln> plan = cap(wrap(id + "plan", str(raw, "plan"), iw, true), 80);
			final int py = cy;
			deferred.add(() -> lines(g, plan, colX + pad, py));
			cy += plan.size() * LH + 4;
			if (pending) {
				final int by = cy;
				deferred.add(() -> {
					int bx = colX + pad;
					bx += 4 + button(g, bx, by, "Approve plan", ACCENT, 0xFF100D18, () -> Bridge.send("permission", "id", id, "behavior", "allow"));
					button(g, bx, by, "Keep planning", PANEL_HI, SUB, () -> Bridge.send("permission", "id", id, "behavior", "deny", "message", "Keep planning; the player wants changes."));
				});
				cy += 18;
			}
		} else {
			List<Ln> sum = cap(wrap(id + "s", str(it, "summary"), iw - 8, false, 1), 12);
			final int sy = cy;
			int sh = sum.size() * LH + 6;
			deferred.add(() -> {
				g.fill(colX + pad, sy, colX + pad + iw, sy + sh, CODE_BG);
				int ly = sy + 3;
				for (Ln l : sum) { g.text(font, l.seq(), colX + pad + 4, ly, CODE, false); ly += LH; }
			});
			cy += sh + 4;
			if (pending) {
				final int by = cy;
				boolean always = bool(it, "canAlways");
				deferred.add(() -> {
					int bx = colX + pad;
					bx += 4 + button(g, bx, by, "Allow", ACCENT, 0xFF100D18, () -> Bridge.send("permission", "id", id, "behavior", "allow"));
					if (always) bx += 4 + button(g, bx, by, "Always allow", PANEL_HI, TEXT, () -> Bridge.send("permission", "id", id, "behavior", "allow", "always", true));
					button(g, bx, by, "Deny", PANEL_HI, SUB, () -> Bridge.send("permission", "id", id, "behavior", "deny"));
				});
				cy += 18;
			}
		}
		if (!pending) {
			final int ry = cy;
			boolean ok = "allowed".equals(state);
			deferred.add(() -> g.text(font, ok ? "✓ Allowed" : "✗ Declined", colX + pad, ry, ok ? OK : MUTED, false));
			cy += 12;
		}
		int h = cy - y + pad - 2;
		if (draw) {
			rrect(g, colX - 1, y - 1, w + 2, h + 2, pending ? ACCENT : BORDER);
			rrect(g, colX, y, w, h, PANEL);
			for (Runnable r : deferred) r.run();
		}
		return h + 10;
	}

	void answer(String id, Map<String, String> ans) {
		JsonObject a = new JsonObject();
		ans.forEach(a::addProperty);
		Bridge.send("permission", "id", id, "behavior", "allow", "answers", a);
	}

	void lines(GuiGraphicsExtractor g, List<Ln> ls, int x, int y) {
		for (int i = 0; i < ls.size(); i++) {
			Ln l = ls.get(i);
			int ly = y + i * LH;
			if (l.kind() == 1) g.fill(x - 3, ly - 1, colX + colW + 3, ly + LH - 1, CODE_BG);
			g.text(font, l.seq(), x, ly, l.kind() == 1 ? CODE : TEXT, false);
		}
	}

	void terminalView(GuiGraphicsExtractor g) {
		input.visible = false;
		int x0 = side + 6, y0 = TOP + 6, w = width - side - 12, h = height - TOP - 12;
		rrect(g, x0, y0, w, h, CODE_BG);
		int cols = (w - 8) / 5, rows = (h - 8) / 9;
		if (cols != termCols || rows != termRows) {
			termCols = cols;
			termRows = rows;
			Bridge.send("resize", "cols", cols, "rows", rows);
		}
		List<String> ls = Bridge.screen;
		g.pose().pushMatrix();
		g.pose().translate(x0 + 4, y0 + 4);
		g.pose().scale(5f / 6f, 9f / 10f);
		for (int r = 0; r < Math.min(rows, ls.size()); r++) {
			String s = ls.get(r);
			for (int c = 0; c < Math.min(cols, s.length()); c++) {
				char ch = s.charAt(c);
				if (ch == ' ') continue;
				String cs = String.valueOf(ch);
				g.text(font, cs, c * 6 + (6 - font.width(cs)) / 2, r * 10, SUB, false);
			}
		}
		g.pose().popMatrix();
	}

	void setTerminal(boolean on) {
		terminal = on;
		termCols = termRows = 0;
		Bridge.send("screen", "on", on);
		setFocused(input); // SDL3 only delivers typed characters while a text field is focused; in terminal mode they are forwarded
	}

	// ---------------------------------------------------------------- widgets

	int pillW(String label) { return font.width(label) + 10; }

	void pill(GuiGraphicsExtractor g, int x, int y, String label, boolean active, Runnable r) {
		int w = pillW(label);
		boolean hov = in(x, y, w, 14) && menu == null;
		rrect(g, x, y, w, 14, active ? 0xFF3A2C66 : hov ? PANEL_HI : PANEL);
		g.text(font, label, x + 5, y + 3, active ? ACCENT : SUB, false);
		hits.add(new Hit(x, y, w, 14, r, false, false));
	}

	int button(GuiGraphicsExtractor g, int x, int y, String label, int bg, int fg, Runnable r) {
		int w = font.width(label) + 14;
		boolean hov = in(x, y, w, 15);
		rrect(g, x, y, w, 15, hov ? lighten(bg) : bg);
		g.text(font, label, x + 7, y + 4, fg, false);
		hits.add(new Hit(x, y, w, 15, r, false, true));
		return w;
	}

	int wideButton(GuiGraphicsExtractor g, int x, int y, int w, String label, Runnable r) {
		boolean hov = in(x, y, w, 15);
		rrect(g, x, y, w, 15, hov ? PANEL_HI : PANEL);
		g.text(font, label, x + 6, y + 4, TEXT, false);
		hits.add(new Hit(x, y, w, 15, r, false, false));
		return 15;
	}

	void openMenu(int x, List<Opt> opts) { menu = new Menu(x, TOP, opts); }

	void drawMenu(GuiGraphicsExtractor g) {
		g.nextStratum();
		int w = 0;
		for (Opt o : menu.opts()) w = Math.max(w, font.width(o.label()) + 22);
		int x = Math.min(menu.x(), width - w - 4), y = menu.y(), h = menu.opts().size() * 14 + 6;
		rrect(g, x - 1, y - 1, w + 2, h + 2, BORDER);
		rrect(g, x, y, w, h, 0xFF1C1729);
		int oy = y + 3;
		for (Opt o : menu.opts()) {
			boolean hov = in(x + 2, oy, w - 4, 14);
			if (hov) rrect(g, x + 2, oy, w - 4, 14, PANEL_HI);
			if (o.selected()) g.text(font, "✓", x + 5, oy + 3, ACCENT, false);
			g.text(font, o.label(), x + 15, oy + 3, o.selected() ? TEXT : SUB, false);
			Runnable run = o.run();
			hits.add(new Hit(x + 2, oy, w - 4, 14, () -> { menu = null; run.run(); }, true, false));
			oy += 14;
		}
	}

	// ---------------------------------------------------------------- reference images
	// Drop image files on the window, Ctrl+V a screenshot (clipboard), or click "+ img" (file picker).
	static java.util.List<java.nio.file.Path> refs;
	static java.util.List<java.nio.file.Path> refs() { if (refs == null) refs = new java.util.ArrayList<>(); return refs; }
	static final java.util.regex.Pattern IMG = java.util.regex.Pattern.compile("(?i).*\\.(png|jpe?g|gif|webp|bmp)$");

	static java.nio.file.Path refsDir() throws java.io.IOException {
		return java.nio.file.Files.createDirectories(dev.moddeus.client.game.GameApi.projectDir().resolve("run/moddeus/refs"));
	}

	static void addRef(java.nio.file.Path src) {
		if (!IMG.matcher(src.toString()).matches()) return;
		try {
			java.nio.file.Path dir = refsDir(), dest = src.startsWith(dir) ? src : dir.resolve(System.currentTimeMillis() + "-" + src.getFileName());
			if (!dest.equals(src)) java.nio.file.Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			if (!refs().contains(dest)) refs().add(dest);
		} catch (java.io.IOException e) { dev.moddeus.Moddeus.LOG.warn("reference image {} failed", src, e); }
	}

	@Override
	public void onFilesDrop(java.util.List<java.nio.file.Path> paths) { for (java.nio.file.Path p : paths) addRef(p); }

	/** Runs a PowerShell snippet (STA, for WinForms) off the render thread; hands its stdout to done on the client thread. */
	void ps(String script, java.util.function.Consumer<String> done) {
		Thread t = new Thread(() -> {
			try {
				Process pr = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-Command", script).redirectErrorStream(true).start();
				String out = new String(pr.getInputStream().readAllBytes()).trim();
				pr.waitFor();
				net.minecraft.client.Minecraft.getInstance().execute(() -> done.accept(out));
			} catch (Exception e) { dev.moddeus.Moddeus.LOG.warn("powershell failed", e); }
		}, "Moddeus-refs");
		t.setDaemon(true);
		t.start();
	}

	void pickImages() {
		ps("Add-Type -AssemblyName System.Windows.Forms; $f = New-Object Windows.Forms.Form -Property @{TopMost=$true}; "
			+ "$d = New-Object Windows.Forms.OpenFileDialog; $d.Title = 'Reference images for Moddeus'; $d.Multiselect = $true; "
			+ "$d.Filter = 'Images|*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp'; if ($d.ShowDialog($f) -eq 'OK') { $d.FileNames -join '|' }",
			out -> { for (String s : out.split("\\|")) if (!s.isBlank()) addRef(java.nio.file.Path.of(s.trim())); });
	}

	void pasteImage() {
		try {
			java.nio.file.Path dest = refsDir().resolve("clipboard-" + System.currentTimeMillis() + ".png");
			ps("Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; $i = [Windows.Forms.Clipboard]::GetImage(); "
				+ "if ($i) { $i.Save('" + dest.toAbsolutePath().toString().replace("'", "''") + "', [Drawing.Imaging.ImageFormat]::Png); 'ok' }",
				out -> { if (out.endsWith("ok")) addRef(dest); });
		} catch (java.io.IOException e) { dev.moddeus.Moddeus.LOG.warn("paste image failed", e); }
	}

	// ---------------------------------------------------------------- input

	void submit() {
		String t = input.getValue().strip();
		if (t.isEmpty() && refs().isEmpty()) return;
		if (!refs().isEmpty()) { // reference images: Claude reads them from disk
			StringBuilder sb = new StringBuilder("Reference image(s) for this request (look at each with the Read tool):\n");
			for (java.nio.file.Path p : refs()) sb.append("- ").append(p.toAbsolutePath()).append('\n');
			t = sb + "\n" + (t.isEmpty() ? "Use these as the reference." : t);
			refs().clear();
		}
		input.setValue("");
		scrollUp = 0;
		if (t.startsWith("/")) {
			Bridge.send("slash", "text", t);
			if (interactive(t)) setTerminal(true);
		}
		else Bridge.send("send", "text", t, "context", context());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		double x = e.x(), y = e.y();
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (menu != null && !h.menu()) continue;
			if (x >= h.x() && x < h.x() + h.w() && y >= h.y() && y < h.y() + h.h()) {
				if (h.clipped() && (y < clipTop || y >= clipBot)) continue;
				h.run().run();
				return true;
			}
		}
		if (menu != null) { menu = null; return true; }
		return super.mouseClicked(e, dbl);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (x < side) sideScroll = Math.max(0, Math.min(sideMax, sideScroll - dy * 24));
		else if (!slashMatches.isEmpty()) slashSel = Math.max(0, Math.min(slashMatches.size() - 1, slashSel - (int) Math.signum(dy)));
		else if (terminal) Bridge.send("key", "data", dy > 0 ? ESC + "[5~" : ESC + "[6~");
		else scrollUp += dy * 24;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key(), mods = e.modifiers();
		if (!terminal && (mods & 2) != 0 && k == InputConstants.KEY_V && minecraft.keyboardHandler.getClipboard().isBlank()) { pasteImage(); return true; } // Ctrl+V with an image on the clipboard
		boolean shift = (mods & InputConstants.MOD_SHIFT) != 0, ctrl = (mods & InputConstants.MOD_CONTROL) != 0;
		if (terminal) {
			String seq = switch (k) {
				case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> "\r";
				case InputConstants.KEY_ESCAPE -> { Bridge.send("key", "data", ESC); setTerminal(false); yield null; }
				case InputConstants.KEY_BACKSPACE -> "\177";
				case InputConstants.KEY_TAB -> shift ? ESC + "[Z" : "\t";
				case InputConstants.KEY_UP -> ESC + "[A";
				case InputConstants.KEY_DOWN -> ESC + "[B";
				case InputConstants.KEY_RIGHT -> ESC + "[C";
				case InputConstants.KEY_LEFT -> ESC + "[D";
				case InputConstants.KEY_DELETE -> ESC + "[3~";
				case InputConstants.KEY_HOME -> ESC + "[H";
				case InputConstants.KEY_END -> ESC + "[F";
				case InputConstants.KEY_PAGEUP -> ESC + "[5~";
				case InputConstants.KEY_PAGEDOWN -> ESC + "[6~";
				default -> null;
			};
			if (ctrl && k == InputConstants.KEY_V) seq = ESC + "[200~" + minecraft.keyboardHandler.getClipboard() + ESC + "[201~";
			else if (ctrl && k >= InputConstants.KEY_A && k <= InputConstants.KEY_Z) seq = String.valueOf((char) (k - InputConstants.KEY_A + 1));
			if (seq != null) Bridge.send("key", "data", seq);
			return true;
		}
		final int ENTER = InputConstants.KEY_RETURN, KP = InputConstants.KEY_NUMPADENTER, ESCK = InputConstants.KEY_ESCAPE;
		if (k == ESCK && menu != null) { menu = null; return true; }
		if (!slashMatches.isEmpty() && getFocused() == input) {
			if (k == InputConstants.KEY_UP) { slashSel = Math.max(0, slashSel - 1); return true; }
			if (k == InputConstants.KEY_DOWN) { slashSel = Math.min(slashMatches.size() - 1, slashSel + 1); return true; }
			if (k == InputConstants.KEY_TAB) { input.setValue("/" + str(slashMatches.get(slashSel), "name") + " "); return true; }
			if (k == ENTER || k == KP) { runSlash("/" + str(slashMatches.get(slashSel), "name")); return true; }
			if (k == ESCK) { input.setValue(""); return true; }
		}
		if ((k == ENTER || k == KP) && getFocused() == input && !shift) { submit(); return true; }
		return super.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (terminal) {
			Bridge.send("key", "data", e.codepointAsString());
			return true;
		}
		if (getFocused() == null) setFocused(input);
		return super.charTyped(e);
	}

	// ---------------------------------------------------------------- text

	List<Ln> wrap(String id, String text, int w, boolean md) { return wrap(id, text, w, md, 0); }

	/** Wrapped lines, cached by item id + width + text. style 1 = code. */
	List<Ln> wrap(String id, String text, int w, boolean md, int style) {
		String key = id + "|" + w + "|" + md + "|" + text.length() + "|" + text.hashCode();
		List<Ln> c = wrapCache.get(key);
		if (c != null) return c;
		if (wrapCache.size() > 3000) wrapCache.clear();
		List<Ln> out = new ArrayList<>();
		if (md) markdown(text, w, out);
		else for (String raw : text.split("\n", -1)) {
			List<FormattedCharSequence> parts = font.split(Component.literal(raw.replace('\t', ' ')), Math.max(20, w));
			if (parts.isEmpty()) out.add(new Ln(FormattedCharSequence.EMPTY, style));
			for (FormattedCharSequence p : parts) out.add(new Ln(p, style));
		}
		wrapCache.put(key, out);
		return out;
	}

	void markdown(String text, int w, List<Ln> out) {
		boolean code = false;
		String fence = "" + BT + BT + BT;
		for (String raw : text.split("\n", -1)) {
			if (raw.strip().startsWith(fence)) { code = !code; continue; }
			if (code) {
				for (FormattedCharSequence p : font.split(Component.literal(raw.isEmpty() ? " " : raw.replace('\t', ' ')), w - 6)) out.add(new Ln(p, 1));
				continue;
			}
			String line = raw;
			boolean heading = false;
			if (line.matches("^#{1,6} .*")) { line = line.replaceFirst("^#+ ", ""); heading = true; }
			else if (line.matches("^\\s*[-*] .*")) line = line.replaceFirst("^(\\s*)[-*] ", "$1• ");
			else if (line.matches("^\\s*---+\\s*$")) line = "";
			List<FormattedCharSequence> parts = font.split(inline(line, heading), w);
			if (parts.isEmpty()) out.add(new Ln(FormattedCharSequence.EMPTY, 0));
			for (FormattedCharSequence p : parts) out.add(new Ln(p, heading ? 2 : 0));
		}
	}

	static MutableComponent inline(String s, boolean bold) {
		MutableComponent root = Component.empty();
		StringBuilder cur = new StringBuilder();
		boolean b = false, k = false;
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (!k && ch == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') { seg(root, cur, b || bold, k); b = !b; i++; continue; }
			if (ch == BT) { seg(root, cur, b || bold, k); k = !k; continue; }
			cur.append(ch);
		}
		seg(root, cur, b || bold, k);
		return root;
	}

	static void seg(MutableComponent root, StringBuilder cur, boolean bold, boolean code) {
		if (cur.isEmpty()) return;
		root.append(Component.literal(cur.toString()).withStyle(Style.EMPTY.withBold(bold).withColor((code ? CODE : TEXT) & 0xFFFFFF)));
		cur.setLength(0);
	}

	static <T> List<T> cap(List<T> l, int n) { return l.size() <= n ? l : l.subList(0, n); }

	// ---------------------------------------------------------------- helpers

	static String context() {
		Minecraft mc = Minecraft.getInstance();
		var p = mc.player;
		if (p == null) return "";
		StringBuilder sb = new StringBuilder("Sent from the in-game Moddeus UI (the player is in the live world; changes hot-swap in).");
		try {
			sb.append("\nplayer=").append(p.getName().getString()).append(" pos=").append(p.blockPosition().toShortString())
				.append(" dimension=").append(p.level().dimension()).append(" facing=").append(p.getDirection());
			sb.append("\nholding=").append(BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()));
			if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK)
				sb.append(" looking_at=").append(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bh.getBlockPos()).getBlock()))
					.append("@").append(bh.getBlockPos().toShortString());
		} catch (Throwable ignored) {
		}
		return sb.toString();
	}

	boolean in(int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

	public static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
		if ((c >>> 24) == 0 || w <= 0 || h <= 0) return;
		g.fill(x + 2, y, x + w - 2, y + h, c);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, c);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, c);
		g.fill(x, y + 2, x + 1, y + h - 2, c);
		g.fill(x + w - 1, y + 2, x + w, y + h - 2, c);
	}

	static int lighten(int c) {
		int a = c & 0xFF000000, r = Math.min(255, ((c >> 16) & 255) + 22), gg = Math.min(255, ((c >> 8) & 255) + 22), b = Math.min(255, (c & 255) + 22);
		return a | r << 16 | gg << 8 | b;
	}

	public static String spinner() {
		String f = "·✢✳✶✻✽✻✶✳✢";
		return String.valueOf(f.charAt((int) (System.currentTimeMillis() / 110 % f.length())));
	}

	static String modeLabel(String m) {
		return switch (m) {
			case "acceptEdits" -> "Accept edits";
			case "plan" -> "Plan";
			case "auto" -> "Auto";
			case "bypassPermissions" -> "Bypass";
			case "dontAsk" -> "Don't ask";
			default -> "Manual";
		};
	}

	static String modeHint(String m) {
		return switch (m) {
			case "acceptEdits" -> "· edits without asking";
			case "plan" -> "· plan first, no changes";
			case "auto" -> "· safe actions run on their own";
			case "bypassPermissions" -> "· never asks";
			default -> "· asks before acting";
		};
	}

	static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

	public static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		if (e == null || e.isJsonNull()) return "";
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	static boolean bool(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsBoolean();
	}
}
