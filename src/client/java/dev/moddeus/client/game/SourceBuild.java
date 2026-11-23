package dev.moddeus.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.moddeus.live.Workspace;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * Portable mode's "gradlew classes clientClasses": compiles the workspace's sources (src/main/java, src/client/java)
 * in game against the running classpath (javac, or the bundled ECJ on a plain JRE) into build/classes/java/{main,client}.
 * Only changed class files are written, stale ones deleted; HotReloader/Gens pick them up (~10 ms swap).
 */
public final class SourceBuild {
	private SourceBuild() {}

	public static synchronized JsonObject build() throws Exception {
		long t0 = System.nanoTime();
		if (!Workspace.portable) { JsonObject e = new JsonObject(); e.addProperty("ok", false); e.addProperty("error", "dev mode: build with gradlew classes clientClasses"); return e; }
		Path proj = Workspace.project;
		Map<String, Path> outDir = new HashMap<>(); // source root -> output dir
		outDir.put("main", proj.resolve("build/classes/java/main"));
		outDir.put("client", proj.resolve("build/classes/java/client"));
		List<Path> sources = new ArrayList<>();
		Map<Path, String> side = new HashMap<>();
		for (String s : List.of("main", "client")) {
			Path root = proj.resolve("src/" + s + "/java");
			if (!Files.isDirectory(root)) continue;
			try (Stream<Path> w = Files.walk(root)) {
				w.filter(p -> p.toString().endsWith(".java")).forEach(p -> { sources.add(p); side.put(p.toAbsolutePath().normalize(), s); });
			}
		}
		JsonObject r = new JsonObject();
		Map<Path, byte[]> produced = new HashMap<>();
		if (!sources.isEmpty()) {
			JavaCompiler javac = FastRun.compiler();
			DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
			StandardJavaFileManager std = javac.getStandardFileManager(diags, null, java.nio.charset.StandardCharsets.UTF_8);
			Map<String, ByteArrayOutputStream> out = new HashMap<>();
			Map<String, String> outSide = new HashMap<>();
			var fm = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
				@Override public JavaFileObject getJavaFileForOutput(Location loc, String cls, JavaFileObject.Kind kind, FileObject sibling) {
					String s = "main";
					try { if (sibling != null) s = side.getOrDefault(Path.of(sibling.toUri()).toAbsolutePath().normalize(), "main"); } catch (Exception ignored) {}
					outSide.put(cls.replace('/', '.'), s);
					return new SimpleJavaFileObject(URI.create("mem:///" + cls.replace('.', '/') + kind.extension), kind) {
						@Override public OutputStream openOutputStream() { ByteArrayOutputStream b = new ByteArrayOutputStream(); out.put(cls.replace('/', '.'), b); return b; }
					};
				}
			};
			var units = std.getJavaFileObjectsFromPaths(sources);
			boolean ok = javac.getTask(null, fm, diags, FastRun.options(FastRun.classPath(), true), null, units).call();
			if (!ok) {
				JsonArray errs = new JsonArray();
				for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
					if (d.getKind() != Diagnostic.Kind.ERROR) continue;
					String f = d.getSource() == null ? "?" : proj.relativize(Path.of(d.getSource().toUri())).toString().replace('\\', '/');
					errs.add(f + ":" + d.getLineNumber() + ": error: " + d.getMessage(null));
					if (errs.size() >= 30) break;
				}
				r.addProperty("ok", false);
				r.add("errors", errs);
				return r;
			}
			out.forEach((cls, b) -> produced.put(outDir.get(outSide.getOrDefault(cls, "main")).resolve(cls.replace('.', '/') + ".class"), b.toByteArray()));
		}
		int changed = 0, deleted = 0;
		for (var e : produced.entrySet()) {
			Path f = e.getKey();
			if (Files.exists(f) && Arrays.equals(Files.readAllBytes(f), e.getValue())) continue;
			Files.createDirectories(f.getParent());
			Files.write(f, e.getValue());
			changed++;
		}
		Set<Path> keep = new HashSet<>(produced.keySet());
		for (Path d : outDir.values()) {
			if (!Files.isDirectory(d)) continue;
			try (Stream<Path> w = Files.walk(d)) {
				for (Path f : w.filter(p -> p.toString().endsWith(".class") && !keep.contains(p)).toList()) { Files.delete(f); deleted++; }
			}
		}
		r.addProperty("ok", true);
		r.addProperty("classes", produced.size());
		r.addProperty("changed", changed);
		r.addProperty("deleted", deleted);
		r.addProperty("compiler", sources.isEmpty() ? "-" : FastRun.compiler().getClass().getSimpleName());
		r.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
		return r;
	}
}
