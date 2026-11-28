package dev.moddeus.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hot swap ({@link Gens#copyStatics}): old-generation objects -> the same class of the new generation, instance fields copied by
 * name (deep: shared objects stay shared, cycles are fine). JDK containers (List, Set, Map, Object[]) are fixed in place; anything
 * else (core objects, lambdas) is kept as is. Throws when an object can't be rebuilt (immutable container, removed enum
 * constant...): the caller then starts that field fresh.
 * Its own top-level class (not nested in Gens: a new nest member blocks Gens' live redefinition) with no Minecraft references,
 * so tools/tests/gens-migrate.java loads it without the game.
 */
final class GenMigrator {
	final Map<Class<?>, Class<?>> next = new HashMap<>();
	final Map<Object, Object> done = new IdentityHashMap<>();
	int budget = 200_000; // containers + mod objects walked per swap

	GenMigrator(List<Class<?>> oldClasses, List<Class<?>> newClasses) {
		Map<String, Class<?>> byName = new HashMap<>();
		for (Class<?> c : newClasses) byName.put(c.getName().replaceFirst("__g\\d+", ""), c);
		for (Class<?> c : oldClasses) { Class<?> n = byName.get(c.getName().replaceFirst("__g\\d+", "")); if (n != null && n != c) next.put(c, n); }
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	Object fix(Object v) throws Exception {
		if (v == null) return null;
		Object d = done.get(v);
		if (d != null) return d;
		Class<?> c = v.getClass(), n = next.get(c);
		if (n == null && !(v instanceof Collection) && !(v instanceof Map) && !(v instanceof Object[])) return v;
		if (--budget < 0) throw new IllegalStateException("more than 200k objects");
		if (n == null) {
			done.put(v, v);
			if (v instanceof List l) {
				for (int i = 0; i < l.size(); i++) { Object o = l.get(i), x = fix(o); if (x != o) l.set(i, x); }
			} else if (v instanceof Collection col) {
				List<Object> xs = new ArrayList<>(); boolean ch = false;
				for (Object o : col) { Object x = fix(o); ch |= x != o; xs.add(x); }
				if (ch) { col.clear(); col.addAll(xs); }
			} else if (v instanceof Map mp) {
				List<Object[]> es = new ArrayList<>(); boolean ch = false;
				for (Object e : mp.entrySet()) {
					Map.Entry en = (Map.Entry) e;
					Object k = fix(en.getKey()), x = fix(en.getValue());
					ch |= k != en.getKey() || x != en.getValue();
					es.add(new Object[]{k, x});
				}
				if (ch) { mp.clear(); for (Object[] e : es) mp.put(e[0], e[1]); }
			} else {
				Object[] a = (Object[]) v;
				for (int i = 0; i < a.length; i++) { Object x = fix(a[i]); if (x != a[i]) a[i] = x; }
			}
			return v;
		}
		Object o;
		if (n.isEnum()) { o = Enum.valueOf((Class) n, ((Enum<?>) v).name()); done.put(v, o); return o; }
		if (n.isRecord()) {
			var rc = n.getRecordComponents();
			Object[] args = new Object[rc.length]; Class<?>[] types = new Class<?>[rc.length];
			for (int i = 0; i < rc.length; i++) {
				types[i] = rc[i].getType();
				Method g = c.getDeclaredMethod(rc[i].getName()); g.setAccessible(true);
				args[i] = fix(g.invoke(v));
			}
			var k = n.getDeclaredConstructor(types); k.setAccessible(true);
			o = k.newInstance(args); done.put(v, o); return o;
		}
		try { var k = n.getDeclaredConstructor(); k.setAccessible(true); o = k.newInstance(); } // new fields get their initializers
		catch (NoSuchMethodException e) {
			Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); uf.setAccessible(true);
			Object u = uf.get(null);
			o = u.getClass().getMethod("allocateInstance", Class.class).invoke(u, n);
		}
		done.put(v, o);
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
			for (Field of : k.getDeclaredFields()) {
				if (Modifier.isStatic(of.getModifiers())) continue;
				Field nf = null;
				for (Class<?> j = n; j != null && j != Object.class && nf == null; j = j.getSuperclass())
					try { nf = j.getDeclaredField(of.getName()); } catch (NoSuchFieldException ignored) {}
				if (nf == null || Modifier.isStatic(nf.getModifiers())) continue;
				of.setAccessible(true); nf.setAccessible(true);
				Object x = fix(of.get(v));
				if (nf.getType().isPrimitive() ? nf.getType() == of.getType() : (x == null || nf.getType().isInstance(x))) nf.set(o, x);
			}
		return o;
	}
}
