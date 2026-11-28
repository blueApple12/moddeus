package dev.moddeus.client;

import dev.moddeus.Moddeus;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * Generation hot reload for mod projects (classes content/<Id>*, client/content/<Id>*): ~1-10 ms, nothing stops.
 *
 * JVM class redefinition always freezes the game (62 ms for a body change: HotSpot walks all 45k loaded classes; 200-800 ms
 * when fields/methods change: heap walk). So a mod's code is never redefined. Each change loads a NEW GENERATION of all its
 * classes under new names (Foo -> Foo__g3, same package, defined straight into the game's class loader, so package-private
 * access still works). Calls to Fabric Event.register in that code are rewritten to {@link #register}: one stable proxy
 * listener per registration (slot) that points at the newest generation, so reloading never doubles listeners and old code
 * stops running. Static fields carry over by name (when their type has no mod class in it), guard System properties the
 * mod set are cleared, then the new generation's @OnReload hooks run. Fields, methods, classes: anything may change.
 *
 * Not used (falls back to normal redefinition) for a mod that has a class extending a Minecraft/library class (Item, Entity,
 * Screen...: those objects live in the world and keep their class) or whose original classes the core code loaded.
 */
public final class Gens {
	static Map<String, Mod> mods;
	static Map<String, Slot> slots;
	public static Set<String> extraIds; // ids treated as mods besides the G screen projects (tests)
	static final String GENS = "dev/moddeus/client/Gens", EVENT = "net/fabricmc/fabric/api/event/Event";

	static final class Mod {
		final String id;
		int gen;
		Map<String, String> names = new HashMap<>();      // original internal name -> current generation internal name
		List<Class<?>> classes = new ArrayList<>();
		Map<String, Integer> counts = new HashMap<>();    // registrations per event in this generation (slot index)
		Set<String> guardKeys = new HashSet<>();
		boolean on = true;
		Mod(String id) { this.id = id; }
	}

	static Map<String, Mod> mods() { if (mods == null) mods = new ConcurrentHashMap<>(); return mods; }
	static Map<String, Slot> slots() { if (slots == null) slots = new ConcurrentHashMap<>(); return slots; }
	public static Set<String> extra() { if (extraIds == null) extraIds = ConcurrentHashMap.newKeySet(); return extraIds; }

	// ---------------------------------------------------------------- which classes belong to which mod

	static Set<String> ids() {
		Set<String> s = new HashSet<>(extra());
		try { s.addAll(Projects.state().keySet()); } catch (Throwable ignored) {}
		return s;
	}

	static boolean modPackage(String binaryName) {
		return binaryName.startsWith("dev.moddeus.content.") || binaryName.startsWith("dev.moddeus.client.content.");
	}

	/** Mod id owning this class (binary name, original), or null. Longest id wins (Foo vs FooBar). */
	public static String owner(String binaryName) {
		if (!modPackage(binaryName)) return null;
		String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
		String best = null;
		for (String id : ids()) if (simple.startsWith(id) && (best == null || id.length() > best.length())) best = id;
		return best;
	}

	public static boolean manages(String id) { return mods().containsKey(id); }

	/** Current generation class for an original binary name (or null). */
	public static Class<?> current(String binaryName) {
		String id = owner(binaryName);
		Mod m = id == null ? null : mods().get(id);
		if (m == null) return null;
		String n = m.names.get(binaryName.replace('.', '/'));
		for (Class<?> c : m.classes) if (c.getName().replace('.', '/').equals(n)) return c;
		return null;
	}

	/** original internal name -> current internal name, for every generation-loaded mod (FastRun remaps snippets with it). */
	public static Map<String, String> renames() {
		Map<String, String> r = new HashMap<>();
		for (Mod m : mods().values()) r.putAll(m.names);
		return r;
	}

	/** All class files of a mod (binary name -> path) in the build output. */
	static Map<String, Path> files(String id) {
		Map<String, Path> out = new LinkedHashMap<>();
		for (Path root : HotReloader.classRoots) for (String pkg : List.of("dev/moddeus/content", "dev/moddeus/client/content")) {
			Path dir = root.resolve(pkg);
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> s = Files.list(dir)) {
				s.forEach(p -> {
					String f = p.getFileName().toString();
					if (!f.endsWith(".class")) return;
					String bn = pkg.replace('/', '.') + "." + f.substring(0, f.length() - 6);
					if (id.equals(owner(bn))) out.put(bn, p);
				});
			} catch (Exception ignored) {}
		}
		return out;
	}

	// ---------------------------------------------------------------- reload

	/**
	 * HotReloader: changed/new class names -> the ones handled here (whole mods reloaded as a new generation).
	 * The rest keep going through normal redefinition. loadedByCore = class names the game's loader already has.
	 */
	public static Set<String> reload(Collection<String> changed, Set<String> loadedByCore) {
		Set<String> handled = new HashSet<>();
		Set<String> ids = new HashSet<>();
		for (String n : changed) { String id = owner(n); if (id != null) ids.add(id); }
		for (String id : ids) {
			Map<String, Path> files = files(id);
			if (files.isEmpty()) { off(id); handled.addAll(changed.stream().filter(n -> id.equals(owner(n))).toList()); continue; }
			// core code uses the originals: redefine instead. But a mod ALREADY running as generations keeps them: a stray load of the
			// original name (a snippet, reflection) made every later change of that mod fall back to redefinition and wait for a restart.
			if (!manages(id) && files.keySet().stream().anyMatch(loadedByCore::contains)) continue;
			try {
				if (load(id, files)) for (String n : changed) if (id.equals(owner(n))) handled.add(n);
			} catch (Throwable t) {
				Moddeus.LOG.error("Generation reload of {} failed, using redefinition", id, t);
			}
		}
		return handled;
	}

	/** Startup: load every mod project that has classes as generation 1 (instead of onInitialize calls). */
	public static void startAll() {
		for (String id : ids()) {
			Map<String, Path> files = files(id);
			if (files.isEmpty()) continue;
			try { load(id, files); } catch (Throwable t) { Moddeus.LOG.error("Could not load mod {}", id, t); }
		}
	}

	/** Define a new generation of mod id and switch to it. False = not eligible (caller redefines instead). */
	static boolean load(String id, Map<String, Path> files) throws Exception {
		long t0 = System.nanoTime();
		Map<String, byte[]> orig = new LinkedHashMap<>();
		for (var e : files.entrySet()) orig.put(e.getKey().replace('.', '/'), Files.readAllBytes(e.getValue()));
		// eligibility: no class extends something outside the mod (its objects would live in the world with the old class)
		Map<String, String[]> supers = new HashMap<>();
		for (var e : orig.entrySet()) {
			ClassReader cr = new ClassReader(e.getValue());
			String sup = cr.getSuperName();
			if (!(sup == null || sup.equals("java/lang/Object") || sup.equals("java/lang/Record") || sup.equals("java/lang/Enum") || orig.containsKey(sup))) {
				Moddeus.LOG.info("Mod {}: {} extends {}, using redefinition", id, e.getKey(), sup);
				return false;
			}
			List<String> deps = new ArrayList<>();
			if (sup != null && orig.containsKey(sup)) deps.add(sup);
			for (String i : cr.getInterfaces()) if (orig.containsKey(i)) deps.add(i);
			supers.put(e.getKey(), deps.toArray(String[]::new));
		}
		Mod old = mods().get(id);
		Mod m = new Mod(id);
		m.gen = old == null ? 1 : old.gen + 1;
		if (old != null) { m.guardKeys = old.guardKeys; m.on = old.on; }
		for (String n : orig.keySet()) m.names.put(n, genName(n, m.gen));
		SimpleRemapper remap = new SimpleRemapper(m.names);
		// define supertypes first (defineClass resolves them right away)
		List<String> order = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String n : orig.keySet()) topo(n, supers, seen, order);
		for (String n : order) {
			byte[] b = transform(orig.get(n), remap);
			String pkg = n.substring(0, n.lastIndexOf('/')).replace('/', '.');
			m.classes.add(lookup(pkg).defineClass(b));
		}
		long defined = System.nanoTime();
		Runnable switchOver = () -> activate(m, old, t0, defined);
		var sp = Minecraft.getInstance().getSingleplayerServer();
		boolean registry = orig.values().stream().anyMatch(b -> new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).contains("LiveRegistry"));
		if (sp == null) switchOver.run();
		else if (registry) sp.executeBlocking(switchOver); // registry edits need both threads quiet
		else sp.execute(switchOver);
		return true;
	}

	static void topo(String n, Map<String, String[]> deps, Set<String> seen, List<String> out) {
		if (!seen.add(n)) return;
		for (String d : deps.getOrDefault(n, new String[0])) topo(d, deps, seen, out);
		out.add(n);
	}

	/** dev/x/Foo$Bar -> dev/x/Foo__g3$Bar */
	static String genName(String internal, int gen) {
		int slash = internal.lastIndexOf('/');
		int dollar = internal.indexOf('$', slash);
		String top = dollar < 0 ? internal : internal.substring(0, dollar);
		return top + "__g" + gen + (dollar < 0 ? "" : internal.substring(dollar));
	}

	/** Rename mod classes to the generation names; Event.register -> Gens.register (stable slots). */
	static byte[] transform(byte[] in, SimpleRemapper remap) {
		ClassReader cr = new ClassReader(in);
		ClassWriter cw = new ClassWriter(0);
		ClassVisitor redirect = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
				return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, name, desc, sig, ex)) {
					@Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
						if (owner.equals(EVENT) && n.equals("register") && op == Opcodes.INVOKEVIRTUAL) {
							if (d.equals("(Ljava/lang/Object;)V")) { super.visitMethodInsn(Opcodes.INVOKESTATIC, GENS, "register", "(L" + EVENT + ";Ljava/lang/Object;)V", false); return; }
							if (d.equals("(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V")) { super.visitMethodInsn(Opcodes.INVOKESTATIC, GENS, "register", "(L" + EVENT + ";Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V", false); return; }
						}
						super.visitMethodInsn(op, owner, n, d, itf);
					}
				};
			}
		};
		cr.accept(new ClassRemapper(redirect, remap), 0);
		return cw.toByteArray();
	}

	static Map<String, MethodHandles.Lookup> lookups;
	/** A full-access lookup in that package, anchored on a core (non-mod) class of the game's loader. */
	static MethodHandles.Lookup lookup(String pkg) throws Exception {
		if (lookups == null) lookups = new ConcurrentHashMap<>();
		var l = lookups.get(pkg);
		if (l != null) return l;
		String anchor = switch (pkg) {
			case "dev.moddeus.content" -> "dev.moddeus.content.Vfx";
			case "dev.moddeus.client.content" -> "dev.moddeus.client.content.VfxClient";
			default -> throw new IllegalStateException("no anchor class for " + pkg);
		};
		l = MethodHandles.privateLookupIn(Class.forName(anchor, false, Gens.class.getClassLoader()), MethodHandles.lookup());
		lookups.put(pkg, l);
		return l;
	}

	/** Server thread: carry state over, clear guards, run the new generation's hooks, retire the old one's listeners. */
	static void activate(Mod m, Mod old, long t0, long defined) {
		try {
			if (old != null && !fresh().remove(m.id)) copyStatics(old, m);
			mods().put(m.id, m);
			synchronized (Gens.class) { if (pending != null) { var pc = pending.remove(m.id + "#" + m.gen); if (pc != null) m.counts.putAll(pc); } }
			int hooks = 0;
			if (m.on && !Projects.disabled(m.id)) {
				for (String k : m.guardKeys) System.getProperties().remove(k);
				Set<Object> before = new HashSet<>(System.getProperties().keySet());
				hooks = runHooks(m.classes, OnReload.class);
				for (Object k : System.getProperties().keySet()) if (!before.contains(k)) m.guardKeys.add(String.valueOf(k));
			}
			int retired = 0;
			for (Slot s : slots().values()) if (s.id.equals(m.id) && s.gen != m.gen && s.target != null) { s.target = null; retired++; }
			long end = System.nanoTime();
			String msg = m.id + " gen " + m.gen + ": " + m.classes.size() + " classes in " + (defined - t0) / 100_000 / 10.0 + " ms, switched in " + (end - defined) / 100_000 / 10.0 + " ms (" + hooks + " hooks" + (retired > 0 ? ", " + retired + " old listeners off" : "") + ")";
			Moddeus.LOG.info("Hotswap (generation): {}", msg);
			if (old != null) Minecraft.getInstance().execute(() -> HotReloader.say("§aLive§r " + msg));
		} catch (Throwable t) {
			Moddeus.LOG.error("Generation switch {} failed", m.id, t);
			Minecraft.getInstance().execute(() -> HotReloader.say("§cReload of " + m.id + " failed: " + t));
		}
	}

	/** Same-named static fields keep their values (not finals; not when the declared type mentions a mod class). Old-generation
	 *  objects in them (also inside JDK containers) are rebuilt as the new generation's classes by {@link GenMigrator}. */
	static void copyStatics(Mod old, Mod m) {
		Map<String, Class<?>> oldByOrig = new HashMap<>();
		for (Class<?> c : old.classes) oldByOrig.put(orig(c.getName()), c);
		GenMigrator mig = new GenMigrator(old.classes, m.classes);
		for (Class<?> nc : m.classes) {
			Class<?> oc = oldByOrig.get(orig(nc.getName()));
			if (oc == null || nc.isInterface()) continue;
			for (Field nf : nc.getDeclaredFields()) {
				int mod = nf.getModifiers();
				if (!Modifier.isStatic(mod) || Modifier.isFinal(mod) || nf.isSynthetic()) continue;
				if (nf.getGenericType().getTypeName().contains("__g")) continue; // holds mod objects: old classes
				try {
					Field of = oc.getDeclaredField(nf.getName());
					if (!of.getGenericType().getTypeName().equals(nf.getGenericType().getTypeName())) continue;
					of.setAccessible(true); nf.setAccessible(true);
					Object v = of.get(null);
					// Old-generation objects (also inside List<Object>/Map<UUID,Object>/Object[]) must become new-generation ones:
					// the new code casts them to its own classes -> ClassCastException in a server tick crashed the world once
					// (Brickjaw JOBS held BrickFight__g32, cast to VillainKit__g33$Fight). Can't rebuild -> the field starts fresh.
					long f0 = System.nanoTime(); try { v = mig.fix(v); }
					catch (Throwable t) { Moddeus.LOG.info("Gens: {}.{} starts fresh (old-generation objects: {})", orig(nc.getSimpleName()), nf.getName(), t.toString()); continue; }
					long fms = (System.nanoTime() - f0) / 1_000_000;
					if (fms > 30) Moddeus.LOG.warn("Gens: slow carry-over {}.{} took {} ms ({} objects walked)", orig(nc.getSimpleName()), nf.getName(), fms, 200_000 - mig.budget);
					nf.set(null, v);
				} catch (NoSuchFieldException ignored) {
				} catch (Throwable t) { Moddeus.LOG.debug("state copy {}.{}: {}", nc.getSimpleName(), nf.getName(), t.toString()); }
			}
		}
	}

	static String orig(String genBinaryName) { return genBinaryName.replaceFirst("__g\\d+", ""); }

	static int runHooks(List<Class<?>> classes, Class<? extends java.lang.annotation.Annotation> a) {
		int n = 0;
		for (Class<?> c : classes) for (Method mt : c.getDeclaredMethods()) {
			if (!mt.isAnnotationPresent(a) || !Modifier.isStatic(mt.getModifiers()) || mt.getParameterCount() != 0) continue;
			long h0 = System.nanoTime();
			try { mt.setAccessible(true); mt.invoke(null); n++; }
			catch (Throwable e) {
				Throwable c2 = e instanceof InvocationTargetException ite ? ite.getCause() : e;
				Moddeus.LOG.error("@{} {}.{} failed", a.getSimpleName(), orig(c.getName()), mt.getName(), c2);
				Minecraft.getInstance().execute(() -> HotReloader.say("§c@" + a.getSimpleName() + " " + orig(c.getSimpleName()) + "." + mt.getName() + " failed: " + c2));
			}
			long hms = (System.nanoTime() - h0) / 1_000_000;
			if (hms > 30) Moddeus.LOG.warn("Gens: slow @{} {}.{} took {} ms (blocks the server thread on every swap)", a.getSimpleName(), orig(c.getName()), mt.getName(), hms);
		}
		return n;
	}

	// ---------------------------------------------------------------- world switch
	// Mod statics are per JVM, but their state (owned powers, saved files, test dummies, levels held in lists) belongs to ONE world.
	// Carrying it into the next world froze the server once (a power ticked a test dummy whose level's server was gone: getChunk
	// waited forever on the dead server thread). So: world closing = every running mod switched off (@OnUnload saves + cleans
	// while the levels still exist); next world = a FRESH generation (static initializers run again, nothing copied over).
	static Set<String> fresh;
	static Set<String> fresh() { if (fresh == null) fresh = ConcurrentHashMap.newKeySet(); return fresh; }
	static Set<String> closed;
	public static void worldClosing(net.minecraft.server.MinecraftServer s) {
		if (closed == null) closed = ConcurrentHashMap.newKeySet();
		for (Mod m : new ArrayList<>(mods().values())) if (m.on && !Projects.disabled(m.id)) {
			try { off(m.id); closed.add(m.id); } catch (Throwable t) { Moddeus.LOG.error("world close: mod {} off", m.id, t); }
		}
		if (!closed.isEmpty()) Moddeus.LOG.info("World closed: switched off {}", closed);
	}
	public static void worldOpened(net.minecraft.server.MinecraftServer s) {
		if (closed == null || closed.isEmpty()) return;
		List<String> ids = new ArrayList<>(closed); closed.clear();
		for (String id : ids) { fresh().add(id); on(id); }
		Moddeus.LOG.info("World opened: fresh generation of {}", ids);
	}

	/** Switch a mod off: its @OnUnload hooks, listeners pass, guards cleared (G screen Off, mc remove). */
	public static void off(String id) {
		Mod m = mods().get(id);
		if (m == null) return;
		m.on = false;
		runHooks(m.classes, OnUnload.class);
		for (Slot s : slots().values()) if (s.id.equals(id)) s.target = null;
		for (String k : m.guardKeys) System.getProperties().remove(k);
		Keys.clear(Projects.name(id));
		Keys.clear(id);
		ModGui.clear(Projects.name(id));
		ModGui.clear(id);
	}

	/** Switch a mod back on: a fresh generation (re-registers everything). */
	public static void on(String id) {
		Mod m = mods().get(id);
		if (m != null) m.on = true;
		try { load(id, files(id)); } catch (Throwable t) { Moddeus.LOG.error("mod {} on", id, t); }
	}

	// ---------------------------------------------------------------- event slots

	/** Rewritten target of Event.register(listener) inside generation code. */
	public static void register(Event<Object> event, Object listener) { slot(event, null, listener); }

	public static void register(Event<Object> event, Identifier phase, Object listener) { slot(event, phase, listener); }

	static void slot(Event<Object> event, Identifier phase, Object listener) {
		Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s -> s.skip(2).findFirst().map(StackWalker.StackFrame::getDeclaringClass).orElse(Gens.class));
		String id = owner(orig(caller.getName()));
		Mod m = id == null ? null : mods().get(id);
		if (m == null) { if (phase == null) event.register(listener); else event.register(phase, listener); return; }
		int gen = m.gen;
		// a hook of the new generation may run before activate() put it in mods(): read the gen from the caller name
		var gm = java.util.regex.Pattern.compile("__g(\\d+)").matcher(caller.getName());
		if (gm.find()) gen = Integer.parseInt(gm.group(1));
		Mod counter = gen == m.gen ? m : null;
		String base = id + "|" + System.identityHashCode(event) + "|" + phase;
		int idx;
		synchronized (Gens.class) {
			Map<String, Integer> counts = counter != null ? counter.counts : pendingCounts(id, gen);
			idx = counts.merge(base, 1, Integer::sum);
		}
		String key = base + "|" + idx;
		Slot s = slots().get(key);
		if (s == null) {
			s = new Slot(id);
			Class<?>[] ifs = listener.getClass().getInterfaces();
			s.proxy = Proxy.newProxyInstance(listener.getClass().getInterfaces()[0].getClassLoader(), ifs, s);
			slots().put(key, s);
			s.target = listener; s.gen = gen;
			if (phase == null) event.register(s.proxy); else event.register(phase, s.proxy);
		} else { s.target = listener; s.gen = gen; }
	}

	static Map<String, Map<String, Integer>> pending;
	static Map<String, Integer> pendingCounts(String id, int gen) {
		if (pending == null) pending = new HashMap<>();
		return pending.computeIfAbsent(id + "#" + gen, k -> new HashMap<>());
	}

	static final class Slot implements InvocationHandler {
		final String id;
		volatile Object target;
		volatile int gen;
		Object proxy;
		Slot(String id) { this.id = id; }

		@Override public Object invoke(Object p, Method mt, Object[] a) throws Throwable {
			if (mt.getDeclaringClass() == Object.class) return switch (mt.getName()) {
				case "hashCode" -> System.identityHashCode(p);
				case "equals" -> p == a[0];
				default -> "Gens.Slot[" + id + "]";
			};
			Object t = target;
			if (t == null) return pass(mt.getReturnType());
			try { return mt.invoke(t, a); } catch (InvocationTargetException e) { throw e.getCause(); }
		}
	}

	/** What a listener that does nothing returns: void, true (allow), 0, PASS, null. */
	static Object pass(Class<?> r) {
		if (r == void.class) return null;
		if (r == boolean.class) return true;
		if (r.isPrimitive()) return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(r, 1), 0);
		try { Field f = r.getField("PASS"); if (Modifier.isStatic(f.getModifiers())) return f.get(null); } catch (Throwable ignored) {}
		return null;
	}
}
