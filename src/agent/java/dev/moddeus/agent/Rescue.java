package dev.moddeus.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Out-of-band hotswap for when the in-game HotReloader itself is broken.
 * Attach to the running game:  jcmd <pid> JVMTI.agent_load build/agent/moddeus-rescue.jar <projectDir>
 * Redefines every already-loaded class found in build/classes/java/{main,client}.
 */
public final class Rescue {
	public static void agentmain(String projectDir, Instrumentation inst) throws Exception {
		Map<String, Path> files = new HashMap<>();
		for (String set : new String[] {"main", "client"}) {
			Path root = Path.of(projectDir, "build", "classes", "java", set);
			if (!Files.isDirectory(root)) continue;
			try (Stream<Path> s = Files.walk(root)) {
				s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
					String rel = root.relativize(p).toString().replace('\\', '/');
					files.put(rel.substring(0, rel.length() - 6).replace('/', '.'), p);
				});
			}
		}
		List<ClassDefinition> defs = new ArrayList<>();
		for (Class<?> c : inst.getAllLoadedClasses()) {
			Path p = files.get(c.getName());
			if (p != null && c.getClassLoader() != null) defs.add(new ClassDefinition(c, Files.readAllBytes(p)));
		}
		inst.redefineClasses(defs.toArray(ClassDefinition[]::new));
		System.out.println("[Moddeus Rescue] redefined " + defs.size() + " classes");
	}
}
