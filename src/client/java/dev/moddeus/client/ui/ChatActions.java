package dev.moddeus.client.ui;

import com.google.gson.JsonElement;

/** Sidebar chat actions. Own class so its lambdas load live (ClaudeScreen is core: new lambdas there wait for a restart). */
final class ChatActions {
	static String armedKey;
	static long armedAt;

	/** The row's "×" was clicked once in the last 3 s: it now reads "remove?". */
	static boolean armed(String key) { return key.equals(armedKey) && System.currentTimeMillis() - armedAt < 3000; }

	/** First click arms, second removes the chat (bridge "close": kills its Claude, forgets it). An open chat hands focus to Voice. */
	static Runnable close(String key) {
		return () -> {
			if (!armed(key)) { armedKey = key; armedAt = System.currentTimeMillis(); return; }
			armedKey = null;
			Bridge.send("close", "s", key);
			if (!key.equals(Bridge.active) || Bridge.meta == null || !Bridge.meta.has("sessions")) return;
			for (JsonElement e : Bridge.meta.getAsJsonArray("sessions")) {
				var o = e.getAsJsonObject();
				if (o.has("voice") && o.get("voice").getAsBoolean()) { Bridge.focus(o.get("key").getAsString()); return; }
			}
		};
	}
}
