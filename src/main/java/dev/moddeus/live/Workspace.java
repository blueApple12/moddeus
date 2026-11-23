package dev.moddeus.live;

import dev.moddeus.Moddeus;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Where Claude works. Dev mode: the Gradle project (-Dmoddeus.project, game runs in &lt;project&gt;/run).
 * Portable mode (the mod jar dropped into any launcher instance, no JBR/agent/Gradle): the workspace is ~/Moddeus,
 * unpacked from the jar's workspace.zip (tools, skills, CLAUDE.md), &lt;workspace&gt;/run links to the instance folder so the
 * tools' run/... paths work, mod sources compile in game (SourceBuild) and load as generations (Gens, no agent),
 * and &lt;workspace&gt;/src/main/resources is added as a root of this mod so its content/assets/data load like built-in ones.
 */
public final class Workspace {
	public static final String NAME = "Moddeus";
	public static Path project;
	/** True when there is no Gradle build: compile in game, resources straight from src/main/resources. */
	public static boolean portable;

	private Workspace() {}

	/** First thing in onInitialize (before content loads). */
	public static void init() {
		try {
			String prop = System.getProperty("moddeus.project");
			if (prop != null) project = Path.of(prop).toAbsolutePath().normalize();
			else {
				project = Path.of(System.getProperty("user.home"), NAME).toAbsolutePath();
				System.setProperty("moddeus.project", project.toString());
			}
			portable = !Files.exists(project.resolve("gradlew")) && !Files.exists(project.resolve("gradlew.bat"));
			if (!portable) return;
			Moddeus.LOG.info("{} portable mode, workspace {}", NAME, project);
			Files.createDirectories(project.resolve("src/main/resources"));
			Files.createDirectories(project.resolve("src/main/java"));
			Files.createDirectories(project.resolve("src/client/java"));
			unpack();
			linkRun(FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize());
			addResourceRoot(project.resolve("src/main/resources"));
		} catch (Throwable t) {
			Moddeus.LOG.error("Workspace setup failed", t);
		}
	}

	/** Managed files (tools, skills, docs) are re-extracted when the mod version changes; user files are never in the zip. */
	static void unpack() throws Exception {
		String ver = FabricLoader.getInstance().getModContainer(Moddeus.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		Path stamp = project.resolve(".workspace-version");
		if (Files.exists(stamp) && Files.readString(stamp).strip().equals(ver)) return;
		try (InputStream in = Workspace.class.getResourceAsStream("/workspace.zip")) {
			if (in == null) { Moddeus.LOG.warn("no workspace.zip in the jar"); return; }
			int n = 0;
			try (ZipInputStream z = new ZipInputStream(in)) {
				for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
					Path out = project.resolve(e.getName()).normalize();
					if (!out.startsWith(project)) continue; // zip-slip guard
					if (e.isDirectory()) { Files.createDirectories(out); continue; }
					Files.createDirectories(out.getParent());
					Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
					n++;
				}
			}
			Files.writeString(stamp, ver);
			Moddeus.LOG.info("Workspace: unpacked {} files ({})", n, ver);
		}
	}

	/** &lt;workspace&gt;/run -> the game folder (junction on Windows: no admin rights needed). */
	static void linkRun(Path game) throws Exception {
		Path run = project.resolve("run");
		if (Files.exists(run, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isDirectory(run) && run.toRealPath().equals(game.toRealPath())) return;
			if (!isLink(run)) { Moddeus.LOG.warn("{} is a real folder, not linking it to {}", run, game); return; }
			if (windows()) new ProcessBuilder("cmd", "/c", "rmdir", run.toString()).start().waitFor(); // removes only the junction
			else Files.delete(run);
		}
		if (windows()) new ProcessBuilder("cmd", "/c", "mklink", "/J", run.toString(), game.toString()).start().waitFor();
		else Files.createSymbolicLink(run, game);
	}

	static boolean isLink(Path p) {
		try {
			if (Files.isSymbolicLink(p)) return true;
			var a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
			return a.isOther() || !p.toRealPath().equals(p.toAbsolutePath().normalize()); // junctions
		} catch (Exception e) { return false; }
	}

	public static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

	/** Workspace resources win over the jar's (same path = override). Fabric's resource packs read getRootPaths(). */
	static void addResourceRoot(Path dir) throws Exception {
		var c = FabricLoader.getInstance().getModContainer(Moddeus.MOD_ID).orElseThrow();
		List<Path> roots = new ArrayList<>();
		roots.add(dir);
		for (Path r : c.getRootPaths()) if (!r.equals(dir)) roots.add(r);
		var f = c.getClass().getDeclaredField("roots");
		f.setAccessible(true);
		f.set(c, java.util.Collections.unmodifiableList(roots));
	}
}
