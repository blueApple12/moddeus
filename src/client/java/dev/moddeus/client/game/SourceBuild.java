package dev.moddeus.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.moddeus.live.Workspace;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
 * The fast build (dev AND portable; replaces "gradlew classes clientClasses processResources" for live work):
 * compiles only the CHANGED sources (+ the sources that mention them) in game with the warm compiler, against the running
 * classpath + the current class output, writes only class files whose bytes changed, syncs changed resources
 * (dev: src/main/resources -> build/resources/main), then HotReloader (its thread does every swap) swaps exactly those files (no
 * debounce). Typical: ~100-150 ms from save to live (due() waits on OS file events, not polling). Gradle stays for launching and releases.
 */
public final class SourceBuild {
	private SourceBuild() {}

	static Map<Path, Long> srcStamps, resStamps; // last synced mtimes (null = not baselined yet)
	static Map<Path, String> texts;              // source text cache for the dependents search
	static StandardJavaFileManager fileManager;  // reused: keeps the Minecraft jars indexed between builds (much faster javac)
	static JavaCompiler fileManagerOf;
	public static volatile boolean auto = true;  // compile + swap on save (HotReloader thread), no command needed
	public static volatile JsonObject lastAuto;  // result of the last automatic build (errors stay here until fixed)
	static long seenSig, seenAt, builtSig = Long.MIN_VALUE, lastResSig;
	static Map<Path, Long> textAt;
	static long compileNs; // javac time of the current build

	static Path proj() { return Workspace.project; }
	static Path out(String side) { return proj().resolve("build/classes/java/" + side); }
	static Path resSrc() { return proj().resolve("src/main/resources"); }
	static Path resOut() { return proj().resolve("build/resources/main"); }

	static Map<Path, Long> list(Path root, String ext) {
		Map<Path, Long> m = new HashMap<>();
		if (!Files.isDirectory(root)) return m;
		try {
			Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
				@Override public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) {
					if (ext == null || p.toString().endsWith(ext)) m.put(p.toAbsolutePath().normalize(), a.lastModifiedTime().toMillis());
					return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
		} catch (Exception ignored) {}
		return m;
	}

	static Map<Path, Long> sources() {
		Map<Path, Long> m = list(proj().resolve("src/main/java"), ".java");
		m.putAll(list(proj().resolve("src/client/java"), ".java"));
		m.keySet().removeIf(p -> p.toString().replace('\\', '/').contains("/dev/moddeus/eval/")); // game eval throwaways
		return m;
	}

	/** Startup (background): the class output was just built from these sources, so they are the baseline. */
	public static synchronized void baseline() {
		if (proj() == null) return;
		// in sync = its class file exists and is newer than the source; everything else counts as changed on the next build
		srcStamps = new HashMap<>();
		for (var e : sources().entrySet()) {
			Path cls = out(side(e.getKey())).resolve(rel(e.getKey()) + ".class");
			try { if (Files.exists(cls) && Files.getLastModifiedTime(cls).toMillis() >= e.getValue()) srcStamps.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
		}
		resStamps = new HashMap<>();
		for (var e : list(resSrc(), null).entrySet()) {
			Path d = resOut().resolve(resSrc().relativize(e.getKey()).toString());
			try { if (Workspace.portable || (Files.exists(d) && Files.getLastModifiedTime(d).toMillis() >= e.getValue())) resStamps.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
		}
		// auto-build starts from what is on disk now (edits made while the game was closed were built by Gradle at launch)
		lastResSig = signature(true);
		seenSig = builtSig = signature(false) * 31 + lastResSig;
		try { FastRun.compiler(); } catch (Throwable ignored) {}
	}

	static String side(Path src) { return src.startsWith(proj().resolve("src/client/java")) ? "client" : "main"; }
	static String simpleName(Path src) { String n = src.getFileName().toString(); return n.substring(0, n.length() - 5); }
	static String rel(Path src) { // dev/moddeus/x/Foo (no .java)
		Path root = proj().resolve("src/" + side(src) + "/java");
		String r = root.relativize(src).toString().replace('\\', '/');
		return r.substring(0, r.length() - 5);
	}

	static String text(Path p, long m) {
		if (texts == null) { texts = new HashMap<>(); textAt = new HashMap<>(); }
		Long at = textAt.get(p);
		if (at == null || at != m) { try { texts.put(p, Files.readString(p)); textAt.put(p, m); } catch (Exception e) { texts.put(p, ""); } }
		return texts.get(p);
	}

	/** Compiles + syncs what changed and adds the output files to swap; the caller (HotReloader thread) swaps them. */
	public static synchronized JsonObject build(boolean java, Set<Path> swap) throws Exception {
		long t0 = System.nanoTime();
		compileNs = 0;
		if (srcStamps == null) baseline();
		JsonObject r = new JsonObject();
		int compiled = 0;
		Set<Path> held = new HashSet<>(); // files with compile errors (and files linked to them): keep old classes, retried next save (BuildSplit)
		JsonArray allErrs = new JsonArray();
		if (java) {
			Map<Path, Long> now = sources();
			Set<Path> changed = new LinkedHashSet<>(), gone = new HashSet<>();
			now.forEach((p, m) -> { Long o = srcStamps.get(p); if (o == null || o.longValue() != m) changed.add(p); });
			for (Path p : srcStamps.keySet()) if (!now.containsKey(p)) gone.add(p);
			if (!changed.isEmpty() || !gone.isEmpty()) {
				// pass 1: only the changed files. Their dependents are recompiled only if a class's non-private API changed (or one
				// was removed), so signature changes surface as compile errors, not crashes later.
				Set<Path> todo = new LinkedHashSet<>(changed);
				boolean apiSame = gone.isEmpty() && !srcStamps.isEmpty() && !changed.isEmpty();
				Map<String, byte[]> first = null;
				Map<String, Path> origin = new HashMap<>(); // class -> its source (package-private top-level classes live in other files)
				if (apiSame) {
					JsonArray errs1 = new JsonArray();
					first = BuildSplit.compile(new ArrayList<>(changed), errs1, origin, held); // never fails the whole batch
					allErrs.addAll(errs1);
					changed.removeAll(held);
					todo.removeAll(held);
					for (var e : first.entrySet()) {
						String cls = e.getKey().replace('.', '/'), top = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
						Path old = null;
						for (Path p : changed) if (rel(p).equals(top)) old = out(side(p)).resolve(cls + ".class");
						try { if (old == null || !Files.exists(old) || !api(Files.readAllBytes(old)).equals(api(e.getValue()))) { apiSame = false; break; } }
						catch (Throwable t) { apiSame = false; break; }
					}
				}
				if (!apiSame && !srcStamps.isEmpty()) {
					List<String> names = new ArrayList<>();
					for (Path p : changed) names.add(simpleName(p));
					for (Path p : gone) names.add(simpleName(p));
					Pattern ref = Pattern.compile("\\b(" + String.join("|", names.stream().map(Pattern::quote).toList()) + ")\\b");
					now.forEach((p, m) -> { if (!todo.contains(p) && ref.matcher(text(p, m)).find()) todo.add(p); });
				} else if (srcStamps.isEmpty()) todo.addAll(now.keySet()); // nothing in sync yet: compile everything
				for (Path g : gone) for (String s : List.of("main", "client")) deleteOutputs(out(s), rel(g), swap, Set.of());
				if (!todo.isEmpty()) {
					JsonArray errs = new JsonArray();
					Map<String, byte[]> produced = apiSame ? first : BuildSplit.compile(new ArrayList<>(todo), errs, origin, held);
					allErrs.addAll(errs);
					todo.removeAll(held);
					compiled = todo.size();
					Map<String, String> sideOf = new HashMap<>();
					for (Path p : todo) sideOf.put(rel(p), side(p));
					Set<Path> written = new HashSet<>();
					for (var e : produced.entrySet()) {
						String cls = e.getKey().replace('.', '/'), top = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
						Path src = origin.get(e.getKey());
						Path f = out(sideOf.getOrDefault(top, src != null ? side(src) : "main")).resolve(cls + ".class");
						written.add(f);
						if (Files.exists(f) && Arrays.equals(Files.readAllBytes(f), e.getValue())) continue;
						Files.createDirectories(f.getParent());
						Path tmp = f.resolveSibling(f.getFileName() + ".tmp"); // atomic: a class loading right now never sees half a file
						Files.write(tmp, e.getValue());
						Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
						swap.add(f);
					}
					for (Path p : todo) deleteOutputs(out(side(p)), rel(p), swap, written); // inner classes that no longer exist
				}
			}
			BuildSplit.keepStale(now, srcStamps, held);
			srcStamps = now;
		}
		// resources: dev copies changed files into build/resources/main (what processResources did); portable reads src directly
		Map<Path, Long> res = list(resSrc(), null);
		for (var e : res.entrySet()) {
			Long o = resStamps.get(e.getKey());
			if (o != null && o.longValue() == e.getValue()) continue;
			String rr = resSrc().relativize(e.getKey()).toString().replace('\\', '/');
			if (rr.equals("fabric.mod.json")) continue; // has ${version}: Gradle only
			if (Workspace.portable) { swap.add(e.getKey()); continue; }
			Path d = resOut().resolve(rr);
			Files.createDirectories(d.getParent());
			Files.copy(e.getKey(), d, StandardCopyOption.REPLACE_EXISTING);
			swap.add(d);
		}
		for (Path p : resStamps.keySet()) if (!res.containsKey(p)) {
			String rr = resSrc().relativize(p).toString().replace('\\', '/');
			Path d = Workspace.portable ? p : resOut().resolve(rr);
			if (!Workspace.portable) Files.deleteIfExists(d);
			swap.add(d);
		}
		resStamps = res;
		r.addProperty("ok", held.isEmpty());
		if (!held.isEmpty()) { r.add("errors", allErrs); JsonArray h = new JsonArray(); for (Path p : held) h.add(proj().relativize(p).toString().replace('\\', '/')); r.add("held", h); }
		r.addProperty("compiled", compiled);
		r.addProperty("changed", swap.size());
		r.addProperty("compiler", compiled == 0 ? "-" : FastRun.compiler().getClass().getSimpleName());
		r.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
		r.addProperty("javacMs", compileNs / 1_000_000);
		return r;
	}

	/** Auto-build check, called by the HotReloader thread every 50 ms: true once the sources (resources: when res) changed
	 *  and then stayed unchanged for ~100 ms. Each new state is built once, even if it fails (half-done edits are normal). */
	public static boolean due(boolean res) {
		if (!auto || srcStamps == null || proj() == null) return false;
		java.nio.file.WatchService ws = watcher();
		if (ws == null) return check(res, 100);
		// OS file events (no polling): wait up to ~45 ms for one, then until 15 ms pass without another, then build at once
		long end = System.currentTimeMillis() + 45;
		try {
			while (true) {
				long now = System.currentTimeMillis(), wait = dirtyAt > 0 ? dirtyAt + 15 - now : end - now;
				if (wait <= 0) break;
				java.nio.file.WatchKey k = ws.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
				if (k == null) continue;
				if (k == resKey) resDirty = true;
				k.pollEvents();
				k.reset();
				dirtyAt = System.currentTimeMillis();
			}
		} catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
		if (dirtyAt == 0 && !res) return false; // res = the 500 ms safety scan (in case an event was missed)
		boolean r = res || resDirty;
		dirtyAt = 0;
		resDirty = false;
		return check(r, 0);
	}

	/** Builds when the source (+ resource) signature differs from the last built one and has been stable for quiet ms. */
	static boolean check(boolean res, long quiet) {
		long sig = signature(false);
		if (res) lastResSig = signature(true);
		long all = sig * 31 + lastResSig, now = System.currentTimeMillis();
		if (all != seenSig) { seenSig = all; seenAt = now; if (quiet > 0) return false; }
		if (all == builtSig || now - seenAt < quiet) return false;
		builtSig = all;
		return true;
	}

	static java.nio.file.WatchService ws;
	static java.nio.file.WatchKey resKey;
	static boolean watchTried, resDirty;
	static long dirtyAt;

	/** One recursive OS watch per source root (Windows: FILE_TREE); null where unsupported (then: 100 ms polling). */
	static java.nio.file.WatchService watcher() {
		if (ws != null || watchTried) return ws;
		watchTried = true;
		try {
			java.nio.file.WatchService w = java.nio.file.FileSystems.getDefault().newWatchService();
			var kinds = new java.nio.file.WatchEvent.Kind<?>[]{java.nio.file.StandardWatchEventKinds.ENTRY_CREATE, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY, java.nio.file.StandardWatchEventKinds.ENTRY_DELETE};
			var tree = com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE;
			for (String d : List.of("src/main/java", "src/client/java")) if (Files.isDirectory(proj().resolve(d))) proj().resolve(d).register(w, kinds, tree);
			if (Files.isDirectory(resSrc())) resKey = resSrc().register(w, kinds, tree);
			ws = w;
		} catch (Throwable t) { ws = null; }
		return ws;
	}

	static long signature(boolean res) {
		long h = 0;
		for (var e : (res ? list(resSrc(), null) : sources()).entrySet()) h += e.getKey().hashCode() * 1_000_003L ^ e.getValue();
		return h;
	}

	/** A class's outside-visible shape: superclass, interfaces, non-private fields and methods (name + descriptor + access). */
	static String api(byte[] cls) {
		ApiVisitor v = new ApiVisitor();
		new org.objectweb.asm.ClassReader(cls).accept(v, org.objectweb.asm.ClassReader.SKIP_CODE);
		java.util.Collections.sort(v.members);
		return v.head + "\n" + String.join("\n", v.members);
	}

	/** Deletes Foo.class / Foo$*.class of one source that the latest compile did not produce. */
	static void deleteOutputs(Path outRoot, String rel, Set<Path> swap, Set<Path> keep) throws Exception {
		Path dir = outRoot.resolve(rel).getParent();
		String base = outRoot.resolve(rel).getFileName().toString();
		if (dir == null || !Files.isDirectory(dir)) return;
		try (Stream<Path> s = Files.list(dir)) {
			for (Path f : s.toList()) {
				String n = f.getFileName().toString();
				if ((n.equals(base + ".class") || (n.startsWith(base + "$") && n.endsWith(".class"))) && !keep.contains(f)) { Files.delete(f); swap.add(f); }
			}
		}
	}

	/** In-memory compile; null on errors (filled into errs). */
	static Map<String, byte[]> compile(List<Path> sources, JsonArray errs, Map<String, Path> origin) throws Exception {
		JavaCompiler javac = FastRun.compiler();
		DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
		if (fileManager == null || fileManagerOf != javac) { fileManager = javac.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8); fileManagerOf = javac; }
		StandardJavaFileManager std = fileManager;
		Map<String, ByteArrayOutputStream> out = new HashMap<>();
		var fm = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
			@Override public JavaFileObject getJavaFileForOutput(Location loc, String cls, JavaFileObject.Kind kind, FileObject sibling) {
				try { if (sibling != null) origin.put(cls.replace('/', '.'), Path.of(sibling.toUri()).toAbsolutePath().normalize()); } catch (Exception ignored) {}
				return new SimpleJavaFileObject(URI.create("mem:///" + cls.replace('.', '/') + kind.extension), kind) {
					@Override public OutputStream openOutputStream() { ByteArrayOutputStream b = new ByteArrayOutputStream(); out.put(cls.replace('/', '.'), b); return b; }
				};
			}
		};
		String cp = FastRun.classPath() + File.pathSeparator + out("main") + File.pathSeparator + out("client");
		// a reused file manager ignores -classpath after its first task: set the location on it directly
		std.setLocation(javax.tools.StandardLocation.CLASS_PATH, Arrays.stream(cp.split(File.pathSeparator)).filter(s -> !s.isBlank()).map(File::new).toList());
		List<String> opts = new ArrayList<>(FastRun.options(cp, true));
		int ci = opts.indexOf("-classpath");
		if (ci >= 0) { opts.remove(ci + 1); opts.remove(ci); }
		long tc = System.nanoTime();
		boolean ok = javac.getTask(null, fm, diags, opts, null, std.getJavaFileObjectsFromPaths(sources)).call();
		compileNs += System.nanoTime() - tc;
		if (!ok) {
			for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
				if (d.getKind() != Diagnostic.Kind.ERROR) continue;
				String f = d.getSource() == null ? "?" : proj().relativize(Path.of(d.getSource().toUri())).toString().replace('\\', '/');
				errs.add(f + ":" + d.getLineNumber() + ": error: " + d.getMessage(null));
				if (errs.size() >= 30) break;
			}
			return null;
		}
		Map<String, byte[]> m = new HashMap<>();
		out.forEach((k, v) -> m.put(k, v.toByteArray()));
		return m;
	}

	/** Named (not anonymous): a new anonymous class renumbers Foo$1.. and the swap fails ("attempted to change superclass"). */
	static final class ApiVisitor extends org.objectweb.asm.ClassVisitor {
		String head = "";
		final java.util.List<String> members = new java.util.ArrayList<>();
		ApiVisitor() { super(org.objectweb.asm.Opcodes.ASM9); }
		@Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) { head = acc + " " + sup + java.util.Arrays.toString(itf); }
		@Override public org.objectweb.asm.FieldVisitor visitField(int acc, String name, String desc, String sig, Object val) {
			if ((acc & org.objectweb.asm.Opcodes.ACC_PRIVATE) == 0) members.add("F " + acc + " " + name + desc + "=" + val);
			return null;
		}
		@Override public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
			if ((acc & org.objectweb.asm.Opcodes.ACC_PRIVATE) == 0 && !name.startsWith("lambda$")) members.add("M " + acc + " " + name + desc);
			return null;
		}
	}
}
