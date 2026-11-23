package dev.moddeus.client.game;

import dev.moddeus.Moddeus;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Runs a Java snippet in the live game WITHOUT Gradle, files or hotswap: javac in-process (in memory), class defined
 * in a throwaway child class loader of the mod's loader, run at once. ~0.1-0.4s warm (first call ~2s, warmed at start).
 */
public final class FastRun {
	static JavaCompiler javac;
	static String classPath;

	/** The JDK's javac when the game runs on a JDK (Mojang's launcher Java has it), else the bundled Eclipse compiler (ECJ). */
	public static synchronized JavaCompiler compiler() {
		if (javac != null) return javac;
		javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) {
			try { javac = (JavaCompiler) Class.forName("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler").getDeclaredConstructor().newInstance(); }
			catch (Throwable t) { throw new IllegalStateException("no Java compiler: neither javac nor the bundled ECJ is available", t); }
		}
		return javac;
	}

	static boolean ecj() { return compiler().getClass().getName().contains("eclipse"); }

	/** Compiler options for both compilers (ECJ rejects some javac flags). */
	public static List<String> options(String cp, boolean debug) {
		List<String> o = new ArrayList<>(List.of("-classpath", cp, "-proc:none", debug ? "-g" : "-g:none", "-nowarn", "-encoding", "UTF-8"));
		if (ecj()) o.addAll(List.of("-source", "25", "-target", "25")); else o.add("-Xlint:none");
		return o;
	}

	static synchronized String classPath() {
		if (classPath != null) return classPath;
		Set<String> cp = new LinkedHashSet<>();
		try {
			for (Path p : net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getClassPath()) cp.add(p.toString());
		} catch (Throwable t) { Moddeus.LOG.warn("FastRun: launcher classpath unavailable: {}", t.toString()); }
		for (String s : System.getProperty("java.class.path", "").split(File.pathSeparator)) if (!s.isBlank()) cp.add(s);
		Path root = GameApi.root();
		for (String d : new String[] { "build/classes/java/main", "build/classes/java/client" }) if (Files.isDirectory(root.resolve(d))) cp.add(root.resolve(d).toString());
		return classPath = String.join(File.pathSeparator, cp);
	}

	/** Compiles {@code src} (top-level class {@code name} in package dev.moddeus.eval) and returns the loaded class. */
	public static Class<?> compile(String name, String src) throws Exception {
		JavaCompiler javac = compiler();
		Map<String, ByteArrayOutputStream> out = new HashMap<>();
		DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
		StandardJavaFileManager std = javac.getStandardFileManager(diags, null, null);
		var fm = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
			@Override public JavaFileObject getJavaFileForOutput(Location loc, String cls, JavaFileObject.Kind kind, FileObject sibling) {
				return new SimpleJavaFileObject(URI.create("mem:///" + cls.replace('.', '/') + kind.extension), kind) {
					@Override public OutputStream openOutputStream() { ByteArrayOutputStream b = new ByteArrayOutputStream(); out.put(cls.replace('/', '.'), b); return b; }
				};
			}
		};
		JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///dev/moddeus/eval/" + name + ".java"), JavaFileObject.Kind.SOURCE) {
			@Override public CharSequence getCharContent(boolean ignore) { return src; }
		};
		List<String> opts = options(classPath(), false);
		JavaFileObject src0 = unit;
		if (ecj()) { // ECJ only reads real files: write the snippet to a temp file
			Path tmp = Files.createTempDirectory("ccrun").resolve("dev/moddeus/eval/" + name + ".java");
			Files.createDirectories(tmp.getParent());
			Files.writeString(tmp, src);
			src0 = std.getJavaFileObjects(tmp.toFile()).iterator().next();
		}
		boolean ok = javac.getTask(null, fm, diags, opts, null, List.of(src0)).call();
		if (!ok) {
			List<String> errs = new ArrayList<>();
			for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics())
				if (d.getKind() == Diagnostic.Kind.ERROR) errs.add("line " + d.getLineNumber() + ": " + d.getMessage(null));
			throw new IllegalArgumentException("compile failed:\n" + String.join("\n", errs));
		}
		Map<String, byte[]> bytes = new HashMap<>();
		out.forEach((k, v) -> bytes.put(k, v.toByteArray()));
		// snippets that name a mod class (dev.moddeus.content.Foo) must reach its live generation (Foo__g3)
		var ren = dev.moddeus.client.Gens.renames();
		if (!ren.isEmpty()) bytes.replaceAll((k, b) -> {
			var cw = new org.objectweb.asm.ClassWriter(0);
			new org.objectweb.asm.ClassReader(b).accept(new org.objectweb.asm.commons.ClassRemapper(cw, new org.objectweb.asm.commons.SimpleRemapper(ren)), 0);
			return cw.toByteArray();
		});
		ClassLoader loader = new ClassLoader(FastRun.class.getClassLoader()) {
			@Override protected Class<?> findClass(String n) throws ClassNotFoundException {
				byte[] b = bytes.get(n);
				if (b == null) throw new ClassNotFoundException(n);
				return defineClass(n, b, 0, b.length);
			}
		};
		return loader.loadClass("dev.moddeus.eval." + name);
	}

	/** First javac use in a JVM takes ~2s; pay it in the background at startup, not on the player's first voice command. */
	public static void warm() {
		Thread t = new Thread(FastRun::warmNow, "Moddeus-FastRun-warm");
		t.setDaemon(true);
		t.start();
	}

	static void warmNow() {
		try {
			long t0 = System.nanoTime();
			compile("Warm", "package dev.moddeus.eval; public final class Warm { public static String run() { return net.minecraft.client.Minecraft.class.getName(); } }");
			Moddeus.LOG.info("FastRun warm in {} ms", (System.nanoTime() - t0) / 1_000_000);
		} catch (Throwable e) { Moddeus.LOG.warn("FastRun warm failed", e); }
	}
}
