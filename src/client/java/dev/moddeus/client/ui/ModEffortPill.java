package dev.moddeus.client.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** "Mods: Quality" pill next to Model/Effort: how much research and content a chat puts into a mod (tools/mod-effort.md). */
public final class ModEffortPill {
	static final String[][] LEVELS = {
		{"fast", "core features, one pass"},
		{"balanced", "quick research, 4-5 moves each"},
		{"quality", "full research, 6+ moves, real mechanics"},
		{"max", "everything + bosses, progression, wow pass"}};

	/** Draws the pill left of rx and returns the new rx. */
	static int draw(ClaudeScreen s, GuiGraphicsExtractor g, int rx, JsonObject st) {
		if (ClaudeScreen.bool(st, "voice")) return rx;
		String cur = ClaudeScreen.str(st, "modEffort");
		if (cur.isEmpty()) cur = "quality";
		String label = "Mods: " + ClaudeScreen.cap(cur) + " ▾";
		rx -= s.pillW(label) + 3;
		int x = rx;
		String sel = cur;
		s.pill(g, rx, 4, label, false, () -> {
			List<ClaudeScreen.Opt> o = new ArrayList<>();
			for (String[] l : LEVELS) o.add(new ClaudeScreen.Opt(ClaudeScreen.cap(l[0]) + "  " + l[1], l[0].equals(sel), () -> Bridge.send("mod-effort", "value", l[0])));
			s.openMenu(x, o);
		});
		return rx;
	}
}
