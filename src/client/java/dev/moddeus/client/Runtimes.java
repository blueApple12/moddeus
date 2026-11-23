package dev.moddeus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.moddeus.Moddeus;
import dev.moddeus.live.Workspace;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * External programs the tools need, found on PATH or downloaded once into &lt;workspace&gt;/.runtime so players only install
 * Minecraft + Claude Code. Node.js runs the bridge and tools/mc.mjs.
 */
public final class Runtimes {
	private Runtimes() {}

	public static String status = "";

	/** Absolute path of a program on PATH, or null. */
	public static String which(String name) {
		try {
			Process p = new ProcessBuilder(Workspace.windows() ? new String[] { "where", name } : new String[] { "which", name }).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes()).strip();
			if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) return null;
			for (String l : out.split("\\R")) if (!l.isBlank()) return l.strip();
		} catch (Exception ignored) {}
		return null;
	}

	/** Folder holding the node executable (PATH's, or a portable copy downloaded on first use). */
	public static synchronized Path nodeDir() throws Exception {
		String sys = which("node");
		if (sys != null) return Path.of(sys).getParent();
		return portableNode();
	}

	/** The downloaded copy in <workspace>/.runtime/node (downloads it if missing). */
	public static synchronized Path portableNode() throws Exception {
		Path home = Workspace.project.resolve(".runtime/node");
		Path exe = Workspace.windows() ? home.resolve("node.exe") : home.resolve("bin/node");
		if (Files.exists(exe)) return exe.getParent();
		status = "Downloading Node.js (one time, ~30 MB)...";
		HotReloader.say("§e" + status);
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
		JsonArray idx = JsonParser.parseString(http.send(HttpRequest.newBuilder(URI.create("https://nodejs.org/dist/index.json")).build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonArray();
		String ver = null;
		for (var e : idx) if (!e.getAsJsonObject().get("lts").toString().equals("false")) { ver = e.getAsJsonObject().get("version").getAsString(); break; }
		if (ver == null) throw new IllegalStateException("no Node LTS found");
		String os = System.getProperty("os.name").toLowerCase(), arch = System.getProperty("os.arch").contains("aarch64") ? "arm64" : "x64";
		String name = "node-" + ver + "-" + (os.contains("win") ? "win-" + arch : os.contains("mac") ? "darwin-" + arch : "linux-" + arch);
		String file = name + (os.contains("win") ? ".zip" : ".tar.gz");
		Path rt = home.getParent();
		Files.createDirectories(rt);
		Path archive = rt.resolve(file);
		try (InputStream in = http.send(HttpRequest.newBuilder(URI.create("https://nodejs.org/dist/" + ver + "/" + file)).build(), HttpResponse.BodyHandlers.ofInputStream()).body()) {
			Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
		}
		// bsdtar ships with Windows 10+ and reads zip too
		Process p = new ProcessBuilder("tar", "-xf", archive.getFileName().toString()).directory(rt.toFile()).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) throw new IllegalStateException("unpacking Node failed: " + out);
		Files.move(rt.resolve(name), home, StandardCopyOption.REPLACE_EXISTING);
		Files.deleteIfExists(archive);
		status = "";
		Moddeus.LOG.info("Runtimes: Node {} installed in {}", ver, home);
		return exe.getParent();
	}

	/** PATH with the portable runtimes first (child processes: bridge -> claude -> Bash -> node tools/mc.mjs). */
	public static String path() throws Exception {
		String sep = java.io.File.pathSeparator;
		return nodeDir() + sep + System.getenv().getOrDefault(Workspace.windows() ? "Path" : "PATH", "");
	}
}
