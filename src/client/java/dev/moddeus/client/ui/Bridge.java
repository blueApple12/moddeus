package dev.moddeus.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Link to tools/bridge/bridge.mjs, which owns the real interactive Claude Code session (PTY).
 * Starts the bridge if it is not running. The bridge outlives the game, so the session survives restarts.
 */
public final class Bridge {
	/** State of the chat shown in the UI (the active session). */
	public static volatile JsonObject state;
	/** skills, models, recent, sessions (summaries). */
	public static volatile JsonObject meta;
	private static java.util.Map<String, JsonObject> states;
	/** Lazy: a static added by a hotswap starts null (static initializers don't re-run). */
	public static synchronized java.util.Map<String, JsonObject> states() {
		if (states == null) states = new java.util.concurrent.ConcurrentHashMap<>();
		return states;
	}
	public static volatile String active;
	public static volatile List<String> screen = List.of();
	public static volatile long version;
	public static volatile String error = "";
	private static volatile WebSocket ws;
	private static volatile boolean connecting;
	private static volatile long lastAttempt;
	private static CompletableFuture<?> sending;

	public static boolean connected() { return ws != null; }

	static Path root() { return Path.of(System.getProperty("moddeus.project", "..")).toAbsolutePath(); }

	public static void ensure() {
		if (ws != null || connecting || System.currentTimeMillis() - lastAttempt < 4000) return;
		lastAttempt = System.currentTimeMillis();
		connecting = true;
		Thread t = new Thread(Bridge::connect, "Moddeus-bridge");
		t.setDaemon(true);
		t.start();
	}

	private static void connect() {
		try {
			WebSocket s = open();
			if (s == null) {
				error = "Starting Claude Code…";
				spawn();
				for (int i = 0; i < 60 && s == null; i++) { Thread.sleep(500); s = open(); }
			}
			if (s == null) { error = "Can't reach the bridge. Run: node tools/bridge/bridge.mjs"; return; }
			ws = s;
			error = "";
		} catch (Throwable t) {
			error = String.valueOf(t);
		} finally {
			connecting = false;
		}
	}

	private static WebSocket open() {
		try {
			Path info = root().resolve("run/moddeus/bridge.json");
			if (!Files.exists(info)) return null;
			JsonObject j = JsonParser.parseString(Files.readString(info)).getAsJsonObject();
			URI uri = URI.create("ws://127.0.0.1:" + j.get("port").getAsInt() + "/ws?t=" + j.get("token").getAsString());
			return HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
				.buildAsync(uri, new Listener()).get(3, TimeUnit.SECONDS);
		} catch (Throwable t) {
			return null;
		}
	}

	private static void spawn() throws Exception {
		Path dir = root().resolve("tools/bridge");
		Path logs = root().resolve("run/moddeus");
		Files.createDirectories(logs);
		if (dev.moddeus.client.Runtimes.which("claude") == null) {
			error = "Claude Code is not installed. Get it at claude.com/claude-code, log in once (run: claude), then press K again.";
			return;
		}
		// Node: from PATH, or downloaded once into <workspace>/.runtime (players only install Minecraft + Claude Code)
		java.nio.file.Path node = dev.moddeus.client.Runtimes.nodeDir();
		String pathVar = dev.moddeus.client.Runtimes.path();
		boolean win = dev.moddeus.live.Workspace.windows();
		if (!Files.isDirectory(dir.resolve("node_modules"))) {
			ProcessBuilder npm = win ? new ProcessBuilder("cmd", "/c", node.resolve("npm.cmd").toString(), "install") : new ProcessBuilder(node.resolve("npm").toString(), "install");
			npm.environment().put(win ? "Path" : "PATH", pathVar);
			npm.directory(dir.toFile()).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("bridge.out").toFile())).start().waitFor(5, TimeUnit.MINUTES);
		}
		ProcessBuilder pb = new ProcessBuilder(node.resolve(win ? "node.exe" : "node").toString(), dir.resolve("bridge.mjs").toString());
		pb.environment().put(win ? "Path" : "PATH", pathVar);
		pb.directory(dir.toFile()).redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("bridge.out").toFile())).start();
	}

	private static final class Listener implements WebSocket.Listener {
		private final StringBuilder buf = new StringBuilder();

		@Override
		public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				handle(buf.toString());
				buf.setLength(0);
			}
			w.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
			ws = null;
			return null;
		}

		@Override
		public void onError(WebSocket w, Throwable e) {
			ws = null;
			error = String.valueOf(e);
		}
	}

	static void handle(String s) {
		try {
			JsonObject m = JsonParser.parseString(s).getAsJsonObject();
			switch (m.get("type").getAsString()) {
				case "meta" -> {
					meta = m.getAsJsonObject("meta");
					JsonArray ss = meta.getAsJsonArray("sessions");
					java.util.Set<String> keys = new java.util.HashSet<>();
					for (JsonElement e : ss) keys.add(e.getAsJsonObject().get("key").getAsString());
					states().keySet().retainAll(keys);
					if ((active == null || !keys.contains(active)) && ss.size() > 0) focus(ss.get(0).getAsJsonObject().get("key").getAsString());
					version++;
				}
				case "state" -> {
					String k = m.get("key").getAsString();
					states().put(k, m.getAsJsonObject("state"));
					if (k.equals(active)) state = states().get(k);
					version++;
				}
				case "focus" -> focus(m.get("key").getAsString());
			case "speak" -> {
				if (!m.has("env")) { dev.moddeus.client.content.ClaudeOrb.hush(); break; }
				com.google.gson.JsonArray a = m.getAsJsonArray("env");
				float[] env = new float[a.size()];
				for (int i = 0; i < env.length; i++) env[i] = a.get(i).getAsFloat();
				dev.moddeus.client.content.ClaudeOrb.speak(env, m.has("hz") ? m.get("hz").getAsInt() : 30);
			}
				case "screen" -> {
					if (!m.get("key").getAsString().equals(active)) return;
					List<String> l = new ArrayList<>();
					for (JsonElement e : m.getAsJsonArray("lines")) l.add(e.getAsString());
					screen = l;
				}
				default -> {}
			}
		} catch (Throwable ignored) {
		}
	}

	/** Switch the UI to another chat; the bridge starts its Claude Code if it is not running. */
	public static void focus(String key) {
		active = key;
		state = states().get(key);
		screen = List.of();
		send("focus");
	}

	/** send("model", "value", "opus") → {"type":"model","value":"opus","s":activeKey} */
	public static synchronized void send(String type, Object... kv) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		if (active != null) o.addProperty("s", active);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			String k = (String) kv[i];
			Object v = kv[i + 1];
			if (v instanceof JsonElement je) o.add(k, je);
			else if (v instanceof Boolean b) o.addProperty(k, b);
			else if (v instanceof Number n) o.addProperty(k, n);
			else o.addProperty(k, String.valueOf(v));
		}
		WebSocket w = ws;
		if (w == null) { ensure(); return; }
		String text = o.toString();
		CompletableFuture<?> prev = sending == null ? CompletableFuture.completedFuture(null) : sending;
		sending = prev.handle((a, b) -> null).thenCompose(x -> w.sendText(text, true));
	}
}
