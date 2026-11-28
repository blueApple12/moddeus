package dev.moddeus.live;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Fabric's mod resource pack lists its namespaces (assets/*, data/*) once at startup, so a NEW mod namespace
 * (mc item --tab "My Mod" -> assets/my_mod/) stayed invisible until a restart. Re-scans them in the live
 * client and server resource managers; call before a resource/data reload.
 */
public final class PackNamespaces {
	private PackNamespaces() {}


	public static void refresh() {
		try {
			var s = LiveRegistry.server;
			if (s != null) patch(s.getResourceManager());
			Class<?> mc = Class.forName("net.minecraft.client.Minecraft"); // common code: client via reflection
			Object inst = mc.getMethod("getInstance").invoke(null);
			if (inst == null) return;
			ResourceManager rm = (ResourceManager) mc.getMethod("getResourceManager").invoke(inst);
			if (rm == null) return; // startup: the client resource manager doesn't exist yet
			patch(rm);
			// the live client manager knows its namespaces from its creation: a new one needs one full resource reload (F3+T)
			Set<String> want = new LinkedHashSet<>();
			try (Stream<PackResources> packs = rm.listPacks()) { packs.forEach(p -> want.addAll(p.getNamespaces(PackType.CLIENT_RESOURCES))); }
			long last = Long.getLong("moddeus.ns.reload", 0L); // no static field: core schema changes wait for a restart
			if (!rm.getNamespaces().containsAll(want) && System.currentTimeMillis() - last > 15_000) {
				System.setProperty("moddeus.ns.reload", Long.toString(System.currentTimeMillis()));
				dev.moddeus.Moddeus.LOG.info("new resource namespace: full resource reload");
				((java.util.concurrent.Executor) inst).execute(new FullResourceReload(inst));
			}
		} catch (ClassNotFoundException ignored) { // dedicated server
		} catch (Throwable t) {
			dev.moddeus.Moddeus.LOG.warn("pack namespace refresh failed: {}", t.toString());
		}
	}

	static void patch(ResourceManager rm) {
		try (Stream<PackResources> packs = rm.listPacks()) { packs.forEach(PackNamespaces::patchPack); }
	}

	@SuppressWarnings("unchecked")
	static void patchPack(PackResources p) {
		if (!p.getClass().getSimpleName().equals("ModNioPackResources")) return;
		try {
			Field nf = p.getClass().getDeclaredField("namespaces"), bf = p.getClass().getDeclaredField("basePaths");
			nf.setAccessible(true);
			bf.setAccessible(true);
			Map<PackType, Set<String>> m = (Map<PackType, Set<String>>) nf.get(p);
			for (PackType t : PackType.values()) {
				Set<String> have = m.get(t), next = new LinkedHashSet<>(have == null ? Set.of() : have);
				for (Path b : (List<Path>) bf.get(p)) {
					Path d = b.resolve(t.getDirectory());
					if (!Files.isDirectory(d)) continue;
					try (Stream<Path> fs = Files.list(d)) {
						fs.filter(Files::isDirectory).map(x -> x.getFileName().toString()).filter(Identifier::isValidNamespace).forEach(next::add);
					}
				}
				if (!next.equals(have)) { m.put(t, next); dev.moddeus.Moddeus.LOG.info("{} pack: namespaces {} {}", p.packId(), t, next); }
			}
		} catch (Throwable t) {
			dev.moddeus.Moddeus.LOG.warn("pack namespaces {}: {}", p.packId(), t.toString());
		}
	}
}
