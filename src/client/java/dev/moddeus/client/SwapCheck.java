package dev.moddeus.client;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Crash-safe swaps without DCEVM. Standard redefinition can only change method bodies, so:
 * schemaChanged() says from the bytes whether a new class version adds/removes/changes fields, methods or supertypes
 * (that class waits for the next restart), and missing() lists members a body-only class would call that the live
 * classes do not have yet (it must wait too, or it would throw NoSuchMethodError/NoSuchFieldError when it runs).
 */
final class SwapCheck {
	private SwapCheck() {}

	static final int MM = Modifier.methodModifiers(), FM = Modifier.fieldModifiers();

	static boolean schemaChanged(Class<?> live, byte[] bytes) {
		Set<String> now = new HashSet<>(), was = new HashSet<>();
		String[] sup = new String[1];
		List<String> itf = new ArrayList<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public void visit(int v, int access, String name, String sig, String superName, String[] interfaces) {
				sup[0] = superName;
				if (interfaces != null) itf.addAll(Arrays.asList(interfaces));
			}
			@Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
				now.add("F " + (access & FM) + " " + name + " " + desc);
				return null;
			}
			@Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
				if (!name.equals("<clinit>")) now.add("M " + (access & MM) + " " + name + desc);
				return null;
			}
		}, ClassReader.SKIP_CODE);
		String liveSup = live.getSuperclass() == null ? null : Type.getInternalName(live.getSuperclass());
		if (!java.util.Objects.equals(sup[0], liveSup)) return true;
		List<String> liveItf = new ArrayList<>();
		for (Class<?> i : live.getInterfaces()) liveItf.add(Type.getInternalName(i));
		if (!liveItf.equals(itf)) return true;
		for (Field f : live.getDeclaredFields()) was.add("F " + (f.getModifiers() & FM) + " " + f.getName() + " " + Type.getDescriptor(f.getType()));
		for (Method m : live.getDeclaredMethods()) was.add("M " + (m.getModifiers() & MM) + " " + m.getName() + Type.getMethodDescriptor(m));
		for (var c : live.getDeclaredConstructors()) was.add("M " + (c.getModifiers() & MM) + " <init>" + Type.getConstructorDescriptor(c));
		return !now.equals(was);
	}

	/** Member references in these bytes to classes in {@code pending} (binary names) that the live classes lack. */
	static List<String> missing(byte[] bytes, Set<String> pending, ClassLoader loader) {
		List<String> out = new ArrayList<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) { check(owner, n, d, true); }
					@Override public void visitFieldInsn(int op, String owner, String n, String d) { check(owner, n, d, false); }
				};
			}
			void check(String owner, String n, String d, boolean method) {
				String cn = owner.replace('/', '.');
				if (!pending.contains(cn)) return;
				try {
					Class<?> c = Class.forName(cn, false, loader);
					if (!has(c, n, d, method)) out.add(c.getSimpleName() + "." + n);
				} catch (Throwable t) { out.add(cn + "." + n); }
			}
		}, 0);
		return out;
	}

	static boolean has(Class<?> c, String n, String d, boolean method) {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			if (method) {
				if (n.equals("<init>")) { for (var x : k.getDeclaredConstructors()) if (Type.getConstructorDescriptor(x).equals(d)) return true; return false; }
				for (Method m : k.getDeclaredMethods()) if (m.getName().equals(n) && Type.getMethodDescriptor(m).equals(d)) return true;
				for (Class<?> i : k.getInterfaces()) if (has(i, n, d, true)) return true;
			} else {
				for (Field f : k.getDeclaredFields()) if (f.getName().equals(n) && Type.getDescriptor(f.getType()).equals(d)) return true;
			}
		}
		return false;
	}
}
