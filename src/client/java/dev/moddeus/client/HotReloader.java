package dev.moddeus.client;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.moddeus.Moddeus;
import dev.moddeus.live.LiveRegistry;
import dev.moddeus.live.OnReload;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Watches the dev build output. When Gradle recompiles (gradlew classes), changed classes are
 * redefined in the running JVM (JBR enhanced redefinition: method/field add/remove OK),
 * {@link OnReload} hooks re-run, and changed assets/data trigger a resource / datapack reload.
 */
public final class HotReloader {
	private static final String PINK = "\u00a7d", RED = "\u00a7c", RESET = "\u00a7r";
	private static Instrumentation inst;
	private static boolean enhanced;
	private static boolean baselineDone;
	static final List<Path> classRoots = new ArrayList<>(); // Gens reads it
	private static final List<Path> resRoots = new ArrayList<>();
	private static final Map<Path, Long> stamps = new HashMap<>();
	private static Map<Path, Integer> classHashes; // lazy: hotswap-added statics start null

	private static Map<Path, Integer> hashes() {
		if (classHashes == null) classHashes = new HashMap<>();
		return classHashes;
	}

	private HotReloader() {}

	public static String status() {
		if (inst == null) return "OFF (no agent)";
		return enhanced ? "ON (enhanced)" : "ON (method bodies only)";
	}

	static void start() {
		inst = (Instrumentation) System.getProperties().get("moddeus.instrumentation");
		String project = System.getProperty("moddeus.project");
		if (project == null) {
			Moddeus.LOG.warn("Hotswap disabled (no project folder)");
			return;
		}
		// no agent (portable mode / plain launcher Java): mod code still reloads instantly as generations; core needs a restart
		if (inst == null) Moddeus.LOG.info("Hotswap: no agent, mod classes reload as generations only");
		enhanced = ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+AllowEnhancedClassRedefinition");
		Path build = Path.of(project, "build");
		for (String s : List.of("main", "client")) {
			classRoots.add(build.resolve("classes/java/" + s));
			if (!dev.moddeus.live.Workspace.portable) resRoots.add(build.resolve("resources/" + s));
		}
		if (dev.moddeus.live.Workspace.portable) resRoots.add(Path.of(project, "src/main/resources")); // no processResources step
		scan(); // baseline
		Thread t = new Thread(HotReloader::loop, "Moddeus-HotReloader");
		t.setDaemon(true);
		t.start();
	}

	/** GameApi "build": queued here so EVERY class swap happens on this one thread (two threads redefining at once, or
	 *  redefining classes the calling thread is running, crashed the JVM once). */
	public static JsonObject build(boolean withJava) throws Exception {
		CompletableFuture<JsonObject> f = new CompletableFuture<>();
		requests().add(new Object[]{withJava, f});
		return f.get(120, java.util.concurrent.TimeUnit.SECONDS);
	}

	static java.util.concurrent.ConcurrentLinkedQueue<Object[]> requests;
	static synchronized java.util.concurrent.ConcurrentLinkedQueue<Object[]> requests() {
		if (requests == null) requests = new java.util.concurrent.ConcurrentLinkedQueue<>();
		return requests;
	}

	/** One in-game build (req = {java, future}, or null for an auto-build on save) + its swap. */
	@SuppressWarnings("unchecked")
	static void runBuild(Object[] req) {
		JsonObject r;
		try {
			Set<Path> swap = new java.util.LinkedHashSet<>();
			r = dev.moddeus.client.game.SourceBuild.build(req == null || (Boolean) req[0], swap);
			long t = System.nanoTime();
			if (!swap.isEmpty()) {
				SwapHitch.mark(swap.size() + " files: " + swap.stream().map(p -> p.getFileName().toString()).limit(3).toList());
				applyNow(swap);
				SwapHitch.swapped(System.nanoTime() - t);
			}
			r.addProperty("swapMs", (System.nanoTime() - t) / 1_000_000);
		} catch (Throwable e) {
			r = new JsonObject();
			r.addProperty("ok", false);
			JsonArray errs = new JsonArray();
			errs.add(String.valueOf(e));
			r.add("errors", errs);
			Moddeus.LOG.warn("in-game build failed", e);
		}
		if (req != null) { ((CompletableFuture<JsonObject>) req[1]).complete(r); return; }
		dev.moddeus.client.game.SourceBuild.lastAuto = r;
		if (!r.get("ok").getAsBoolean()) Moddeus.LOG.info("Auto-build: compile errors (waiting for the next save): {}", r.get("errors"));
		else if (r.get("changed").getAsInt() > 0) Moddeus.LOG.info("Auto-build: {} compiled, {} files, build {} ms (javac {}) + swap {} ms", r.get("compiled"), r.get("changed"), r.get("ms"), r.get("javacMs"), r.get("swapMs"));
	}

	private static void loop() {
		for (int tick = 0; ; tick++) {
			try {
				Thread.sleep(5); // SourceBuild.due() blocks up to ~45 ms on file events itself
				Object[] req;
				while ((req = requests().poll()) != null) runBuild(req);
				if (dev.moddeus.client.game.SourceBuild.due(tick % 10 == 0)) runBuild(null);
				if (tick % 10 != 0) continue; // Gradle / external output: every 500 ms as before
				Set<Path> changed = scan();
				if (changed.isEmpty()) continue;
				Set<Path> more; // debounce: Gradle writes files over a few hundred ms
				do {
					Thread.sleep(400);
					more = scan();
					changed.addAll(more);
				} while (!more.isEmpty());
				apply(changed);
			} catch (Throwable e) {
				Moddeus.LOG.error("HotReloader loop error", e);
			}
		}
	}

	/** Files whose mtime changed, or that are new, since the last scan. */
	private static Set<Path> scan() {
		Set<Path> changed = new HashSet<>();
		List<Path> roots = new ArrayList<>(classRoots);
		roots.addAll(resRoots);
		roots.addAll(rpRoots());
		for (Path root : roots) {
			if (!Files.isDirectory(root)) continue;
			// attributes come with the directory listing (per-file stat calls were ~10x slower on Windows)
			try {
				Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
					@Override public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) {
						try {
							long m = a.lastModifiedTime().toMillis();
							Long old = stamps.put(p, m);
							if (!baselineDone) hashes().put(p, Arrays.hashCode(Files.readAllBytes(p))); // classes + resources: content, not mtime
							if (baselineDone && (old == null || old != m)) changed.add(p);
						} catch (Exception ignored) {}
						return java.nio.file.FileVisitResult.CONTINUE;
					}
				});
			} catch (Exception e) {
				Moddeus.LOG.warn("scan failed for {}", root, e);
			}
		}
		// deleted files count as changes too (e.g. mc remove)
		if (baselineDone) {
			for (Path p : new ArrayList<>(stamps.keySet())) {
				// keep the hash: Gradle deletes + rewrites recompiled outputs, and dropping it made every unchanged sibling class
				// (inner classes, records) look new, so each swap redefined ~9 classes instead of 1 (more lag)
				if (!Files.exists(p)) { stamps.remove(p); changed.add(p); }
			}
		}
		baselineDone = true;
		return changed;
	}

	/** SourceBuild wrote exactly these files: swap them now (no polling, no debounce, no quiet wait). */
	public static void applyNow(Set<Path> files) {
		Set<Path> changed = new HashSet<>(files);
		for (Path p : changed) {
			try { if (Files.exists(p)) stamps.put(p, Files.getLastModifiedTime(p).toMillis()); else stamps.remove(p); } catch (Exception ignored) {}
		}
		apply(changed, false);
	}

	private static void apply(Set<Path> changed) { apply(changed, true); }

	private static void apply(Set<Path> changed, boolean wait) {
		synchronized (HotReloader.class) { applyLocked(changed, wait); }
	}

	private static void applyLocked(Set<Path> changed, boolean wait) {
		// Gradle path only: one build = one swap. Gradle writes main classes, then client classes ~1-2s later, and each swap
		// is a stop-the-world redefinition, so wait until its output has been quiet for 1.5s. SourceBuild skips this.
		long quietSince = System.currentTimeMillis();
		while (wait && System.currentTimeMillis() - quietSince < 1500) {
			try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
			Set<Path> more = scan();
			if (!more.isEmpty()) { changed.addAll(more); quietSince = System.currentTimeMillis(); }
		}
		long t0 = System.nanoTime();
		Map<String, Path> classFiles = new HashMap<>();
		boolean assets = false, data = false, content = false;
		List<String> gone = new ArrayList<>();
		for (Path p : changed) {
			Optional<Path> root = classRoots.stream().filter(p::startsWith).findFirst();
			if (root.isPresent() && p.toString().endsWith(".class")) {
				String rel = root.get().relativize(p).toString().replace('\\', '/');
				if (rel.startsWith("dev/moddeus/mixin/") || rel.startsWith("dev/moddeus/client/mixin/")) continue; // mixins apply at class load only: restart
				if (!Files.exists(p)) { gone.add(rel.substring(0, rel.length() - 6).replace('/', '.')); continue; } // deleted: stays loaded, run its @OnUnload

				classFiles.put(rel.substring(0, rel.length() - 6).replace('/', '.'), p);
				continue;
			}
			// processResources re-copies EVERY resource on any change (new mtimes): only files whose bytes changed count,
			// so one new texture no longer reloads all assets + data.
			List<Path> res = new ArrayList<>(resRoots);
			res.addAll(rpRoots());
			if (Files.exists(p) && res.stream().anyMatch(p::startsWith)) {
				try {
					int h = Arrays.hashCode(Files.readAllBytes(p));
					Integer old = hashes().put(p, h);
					if (old != null && old == h) continue;
				} catch (Exception ignored) {}
			}
			for (Path r : res) {
				if (!p.startsWith(r)) continue;
				String top = r.relativize(p).getName(0).toString();
				if (top.equals("assets")) { assets = true; LightReload.pending.add(r.relativize(p).toString().replace('\\', '/')); }
				if (top.equals("data")) data = true;
				if (r.relativize(p).toString().replace('\\', '/').startsWith("moddeus/content/")) content = true;
			}
		}
		if (!gone.isEmpty()) gone.removeAll(Gens.reload(gone, Set.of())); // a mod's deleted class: new generation (or mod off)
		if (!gone.isEmpty()) runUnload(gone);

		List<Class<?>> redefined = new ArrayList<>();
		List<String> freshNames = new ArrayList<>();
		int fresh = 0;
		if (!classFiles.isEmpty()) {
			Map<String, Class<?>> loaded = new HashMap<>();
			if (inst != null) for (Class<?> c : inst.getAllLoadedClasses()) {
				if (c.getClassLoader() != null && classFiles.containsKey(c.getName())) loaded.put(c.getName(), c);
			}
			// mod projects: load a new generation instead of redefining (no stop-the-world pause at all)
			Set<String> genDone = Gens.reload(new ArrayList<>(classFiles.keySet()), loaded.keySet());
			if (!genDone.isEmpty()) { classFiles.keySet().removeAll(genDone); Snapshots.after(List.of(), new ArrayList<>(genDone), false, false); }
			List<ClassDefinition> defs = new ArrayList<>();
			try {
				for (var e : classFiles.entrySet()) {
					Class<?> c = loaded.get(e.getKey());
					if (c == null) { // not loaded yet: new bytes load on first use
						if (!hashes().containsKey(e.getValue())) { fresh++; freshNames.add(e.getKey()); }
						hashes().put(e.getValue(), Arrays.hashCode(Files.readAllBytes(e.getValue())));
						continue;
					}
					byte[] bytes = Files.readAllBytes(e.getValue());
					Integer old = hashes().put(e.getValue(), Arrays.hashCode(bytes));
					if (old != null && old == Arrays.hashCode(bytes)) continue; // recompiled, identical bytecode
					defs.add(new ClassDefinition(c, bytes));
					redefined.add(c);
				}
				if (inst != null && !defs.isEmpty()) {
					List<ClassDefinition> later = splitPending(defs); // schema changes + bodies that need them: next restart
					defs.removeAll(later);
					for (ClassDefinition d : later) redefined.remove(d.getDefinitionClass());
					if (!later.isEmpty()) deferred(later, "fields/methods changed");
					redefineFast(inst, defs.toArray(ClassDefinition[]::new));
				}
			} catch (UnsupportedOperationException e) { // a schema change the check missed: whole batch waits for the restart
				deferred(defs, String.valueOf(e.getMessage()));
				redefined.clear();
			} catch (Throwable e) {
				Moddeus.LOG.error("Hotswap FAILED", e);
				String msg = e.toString();
				Minecraft.getInstance().execute(() -> say(RED + "Hotswap failed: " + msg));
				return;
			}
		}

		// (see redefineFast) Everything after the swap runs in afterSwap: a plain static call with a fixed signature, so the
		// OLD apply() (still on the stack when this class swaps itself) lands in NEW code. Never put lambdas here.
		afterSwap(new Object[] {redefined, freshNames, fresh, assets, data, content, (System.nanoTime() - t0) / 1_000_000});
	}

	/** Keep this signature stable forever (see comment in apply). */
	@SuppressWarnings("unchecked")
	static void afterSwap(Object[] s) {
		List<Class<?>> redefined = (List<Class<?>>) s[0];
		List<String> freshNames = (List<String>) s[1];
		final int n = redefined.size(), f = (Integer) s[2];
		final boolean a = (Boolean) s[3], d = (Boolean) s[4], ct = (Boolean) s[5];
		if (s.length > 6) Moddeus.LOG.info("Hotswap timing: redefine {} ms ({} classes)", s[6], redefined.size());
		LagGuard.ignoreUntil = System.currentTimeMillis() + 3000; // our own swap pause is not a mod lagging
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				finish(mc, redefined, freshNames, n, f, a, d, ct);
			} catch (Throwable t) {
				Moddeus.LOG.error("Post-swap step failed", t);
				say(RED + "Post-swap step failed: " + t);
			}
		});
	}

	private static void finish(Minecraft mc, List<Class<?>> redefined, List<String> freshNames, int n, int f, boolean a, boolean d, boolean ct) {
		// New classes only load on first use; load ours now so their @OnReload hooks can run.
		List<Class<?>> hookTargets = new ArrayList<>(redefined);
		for (String name : freshNames) {
			if (name.contains("$")) continue;
			try {
				hookTargets.add(Class.forName(name, false, Moddeus.class.getClassLoader()));
			} catch (Throwable t) {
				Moddeus.LOG.warn("Could not load new class {}", name, t);
			}
		}
		MinecraftServer sp = mc.getSingleplayerServer();
		LiveRegistry.server = sp;
		int[] hooksBox = {0};
		// Hooks + content run on the server thread while the client thread waits: both threads are
		// quiet, so registry edits can't race with ticking or rendering.
		Runnable work = () -> {
			hooksBox[0] = runHooks(hookTargets);
			if (ct) dev.moddeus.live.ContentLoader.loadAll();
		};
		long h0 = System.nanoTime();
		// Registry edits (LiveRegistry / content) need both threads quiet: block the client. Plain hooks (listeners,
		// commands, flags) just run on the server thread while the game keeps rendering (no ~1-tick freeze).
		boolean registry = ct || touchesRegistry(hookTargets);
		if (sp == null) work.run();
		else if (registry) sp.executeBlocking(PauseProfiler.wrap(work, ct ? "content" : "hooks")); // slow pause logs its cause
		else { sp.execute(work); hooksBox[0] = hookTargets.size(); }
		Moddeus.LOG.info("Hotswap timing: hooks+content {} ms ({})", (System.nanoTime() - h0) / 1_000_000, registry ? "game paused meanwhile" : "async");
		int hooks = hooksBox[0];
		if (n + f > 0) say("Hotswapped " + n + " class(es)" + (f > 0 ? ", " + f + " new" : "") + (hooks > 0 ? ", ran " + hooks + " @OnReload" : ""));
		Moddeus.LOG.info("Hotswap applied: {} redefined, {} new, {} hooks, assets={}, data={}, content={}", n, f, hooks, a, d, ct);
		Snapshots.after(redefined, freshNames, a, d);
		if (d) {
			MinecraftServer server = mc.getSingleplayerServer();
			if (server != null) server.execute(() -> Moddeus.LOG.info(dev.moddeus.live.DataReload.reload(server)));
		}
		if (a) LightReload.run(mc);
	}

	/** Does any of these classes call LiveRegistry (adds blocks/items/entities live)? Checked in the class file bytes. */
	static boolean touchesRegistry(List<Class<?>> classes) {
		for (Class<?> c : classes) {
			try (var in = Moddeus.class.getClassLoader().getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
				if (in == null) return true;
				if (new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("LiveRegistry")) return true;
			} catch (Exception e) { return true; }
		}
		return false;
	}

	/** Deleted classes stay loaded until restart: give each a chance to switch itself off (@OnUnload, server thread). */
	static void runUnload(List<String> names) {
		MinecraftServer sp = Minecraft.getInstance().getSingleplayerServer();
		for (String name : names) {
			if (name.contains("$") || name.startsWith("dev.moddeus.eval.")) continue; // throwaway mc game eval classes
			Class<?> c;
			try { c = Class.forName(name, false, Moddeus.class.getClassLoader()); } catch (Throwable t) { continue; } // never loaded: nothing to undo
			for (Method m : c.getDeclaredMethods()) {
				if (!m.isAnnotationPresent(dev.moddeus.live.OnUnload.class) || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
				Runnable r = new Unload(m);
				if (sp != null) sp.executeBlocking(r); else r.run();
			}
			Moddeus.LOG.info("Removed {} (gone from code; class unloads on restart)", c.getSimpleName()); // not say(): wrong thread for chat
		}
	}

	private record Unload(Method m) implements Runnable {
		public void run() {
			try { m.setAccessible(true); m.invoke(null); }
			catch (Throwable e) { Moddeus.LOG.error("@OnUnload {} failed", m, e); }
		}
	}

	static int runHooks(List<Class<?>> classes) {
		int count = 0;
		for (Class<?> c : classes) {
			if (Projects.disabled(c.getSimpleName())) continue; // a switched-off mod stays off across swaps
			for (Method m : c.getDeclaredMethods()) {
				if (!m.isAnnotationPresent(OnReload.class) || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
				try {
					m.setAccessible(true);
					m.invoke(null);
					count++;
				} catch (Throwable e) {
					Moddeus.LOG.error("@OnReload {}.{} failed", c.getName(), m.getName(), e);
					say(RED + "@OnReload " + c.getSimpleName() + "." + m.getName() + " failed: " + e.getCause());
				}
			}
		}
		return count;
	}

	static void say(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal(PINK + "[Moddeus]" + RESET + " " + msg));
	}

	// ---------------------------------------------------------------- fast swap (no lambdas here: may run as old code)
	public static int fastSwaps, fullSwaps;

	/**
	 * Standard JVMTI redefinition only (method bodies, ~1 ms + a global ~60-80 ms safepoint). A change that adds/removes
	 * fields or methods throws UnsupportedOperationException and the caller defers it to the next restart. Never DCEVM
	 * (-XX:+AllowEnhancedClassRedefinition): its replaced classes crashed the JVM 3 times in G1 remark class unloading
	 * (jvm.dll+0x81ec5a, VM Thread, G1PauseRemark), killing the world session.
	 */
	static void redefineFast(java.lang.instrument.Instrumentation inst, ClassDefinition[] defs) throws Exception {
		if (defs.length == 0) return;
		inst.redefineClasses(defs);
		fastSwaps++;
	}

	/** Core classes whose new version changes fields/methods: they load at the next restart. While any are pending, all core swaps wait (a body calling a pending new method would throw NoSuchMethodError). */
	public static Set<String> pendingRestart;

	/** Which of these must wait for a restart: their own fields/methods changed, or they use a member only a waiting class has. */
	static List<ClassDefinition> splitPending(List<ClassDefinition> defs) {
		if (pendingRestart == null) pendingRestart = new java.util.LinkedHashSet<>();
		Set<String> pend = new HashSet<>(pendingRestart);
		List<ClassDefinition> wait = new ArrayList<>();
		for (ClassDefinition d : defs) {
			Class<?> c = d.getDefinitionClass();
			if (SwapCheck.schemaChanged(c, d.getDefinitionClassFile())) { wait.add(d); pend.add(c.getName()); }
			else if (pend.remove(c.getName())) { pendingRestart.remove(c.getName()); Moddeus.LOG.info("Hotswap: {} is back to its live shape, swaps again", c.getSimpleName()); } // the loop below re-checks what it uses
		}
		for (boolean grew = true; grew; ) { // bodies calling into waiting classes' new members wait too
			grew = false;
			for (ClassDefinition d : defs) {
				if (wait.contains(d)) continue;
				List<String> miss = SwapCheck.missing(d.getDefinitionClassFile(), pend, d.getDefinitionClass().getClassLoader());
				if (!miss.isEmpty()) { wait.add(d); pend.add(d.getDefinitionClass().getName()); grew = true; Moddeus.LOG.info("Hotswap: {} waits (uses {})", d.getDefinitionClass().getSimpleName(), miss); }
			}
		}
		return wait;
	}

	static void deferred(List<ClassDefinition> defs, String why) {
		if (pendingRestart == null) pendingRestart = new java.util.LinkedHashSet<>();
		for (ClassDefinition d : defs) pendingRestart.add(d.getDefinitionClass().getName());
		fullSwaps++;
		String msg = "Core change waits for the next restart (" + why + "): " + pendingRestart;
		Moddeus.LOG.warn("Hotswap deferred: {}", msg);
		Minecraft.getInstance().execute(new Deferred(msg));
	}

	static final class Deferred implements Runnable {
		final String msg;
		Deferred(String msg) { this.msg = msg; }
		public void run() { say("\u00a76" + msg); }
	}

	/** Datapack-mod resource packs (run/resourcepacks/<Id>-RP): their edits light-reload too (no F3+T loading screen). */
	static List<Path> rpRoots() {
		List<Path> l = new ArrayList<>();
		String proj = System.getProperty("moddeus.project");
		if (proj == null) return l;
		try (Stream<Path> s = Files.list(Path.of(proj, "run/resourcepacks"))) { s.filter(d -> Files.isDirectory(d) && d.getFileName().toString().endsWith("-RP")).forEach(l::add); }
		catch (Exception ignored) {}
		return l;
	}
}
