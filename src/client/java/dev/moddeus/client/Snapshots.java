package dev.moddeus.client;

import dev.moddeus.Moddeus;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** After every hot-swap: snapshot the source for undo (node tools/mc.mjs snap, async, ~0.2 s, no-op when nothing changed). */
public final class Snapshots {
	static long last;

	public static void after(List<Class<?>> redefined, List<String> fresh, boolean assets, boolean data) {
		try {
			Set<String> names = new LinkedHashSet<>();
			for (Class<?> c : redefined) if (!c.getName().contains("$")) names.add(c.getSimpleName());
			for (String n : fresh) if (!n.contains("$")) names.add("+" + n.substring(n.lastIndexOf('.') + 1));
			if (assets) names.add("assets");
			if (data) names.add("data");
			String label = names.isEmpty() ? "change" : String.join(" ", names);
			if (label.length() > 200) label = label.substring(0, 200) + "...";
			File root = new File(System.getProperty("moddeus.project", new File(System.getProperty("user.dir")).getParent())); // dev: the game runs in <project>/run
			List<String> cmd = new java.util.ArrayList<>(List.of("node", "tools/mc.mjs", "snap", label.replace("\"", "")));
			if (System.getProperty("os.name", "").startsWith("Windows")) cmd.addAll(0, List.of("cmd.exe", "/c", "start", "/LOW", "/B")); // never steal CPU from the game
			new ProcessBuilder(cmd).directory(root).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			Moddeus.LOG.warn("snapshot failed: {}", e.toString());
		}
	}
}
