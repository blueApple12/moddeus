package dev.moddeus.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Several chats build mods at the same time. One chat's half-written file with a compile error used to fail the WHOLE
 * batch: every other chat's Java (and resources) waited until it was fixed. Now the broken files, plus every file in the
 * batch linked to them (references in either direction, so a mod never goes live half-updated), are HELD: they keep their
 * old classes and are retried on the next save. Everything else compiles and swaps.
 */
public final class BuildSplit {
	private BuildSplit() {}

	/** SourceBuild.compile that never fails the batch: files it couldn't compile go to held. Never null. */
	static Map<String, byte[]> compile(List<Path> files, JsonArray errs, Map<String, Path> origin, Set<Path> held) throws Exception {
		Map<String, byte[]> out = SourceBuild.compile(files, errs, origin);
		if (out != null) return out;
		Set<Path> bad = linked(broken(errs, files), files);
		List<Path> rest = new ArrayList<>(files);
		rest.removeAll(bad);
		if (bad.isEmpty() || rest.isEmpty()) { held.addAll(files); return new HashMap<>(); }
		out = SourceBuild.compile(rest, new JsonArray(), origin);
		if (out == null) { held.addAll(files); return new HashMap<>(); } // the rest needs the broken files: hold all
		held.addAll(bad);
		return out;
	}

	/** Files named in javac's error lines. */
	static Set<Path> broken(JsonArray errs, List<Path> files) {
		Set<Path> bad = new LinkedHashSet<>();
		Path proj = SourceBuild.proj();
		for (Path p : files) {
			String rel = proj.relativize(p).toString().replace('\\', '/'), name = p.getFileName().toString();
			for (JsonElement e : errs) { String s = e.getAsString().replace('\\', '/'); if (s.contains(rel) || s.startsWith(name + ":") || s.contains("/" + name + ":")) { bad.add(p); break; } }
		}
		return bad;
	}

	/** bad + every file of the batch that references or is referenced by one of them (closure). */
	static Set<Path> linked(Set<Path> bad, List<Path> files) {
		Set<Path> out = new LinkedHashSet<>(bad);
		Map<Path, String> text = new HashMap<>();
		for (Path p : files) { try { text.put(p, Files.readString(p)); } catch (Exception e) { text.put(p, ""); } }
		for (boolean grew = !out.isEmpty(); grew; ) {
			grew = false;
			for (Path f : files) {
				if (out.contains(f)) continue;
				Pattern me = word(f);
				// only DEPENDENTS (f uses a held file). Following the other direction too pulled in shared hubs a broken file
				// uses (Superpowers.java), then everything using the hub: with ~15 agents mid-edit one mod never went live.
				for (Path h : out) if (word(h).matcher(text.get(f)).find()) { out.add(f); grew = true; break; }
			}
		}
		return out;
	}

	static Pattern word(Path p) { String n = p.getFileName().toString(); return Pattern.compile("\\b" + Pattern.quote(n.substring(0, n.length() - 5)) + "\\b"); }

	/** After a build: held files keep their old stamp, so the next build retries them. */
	static void keepStale(Map<Path, Long> now, Map<Path, Long> old, Set<Path> held) {
		for (Path h : held) { Long o = old == null ? null : old.get(h); if (o == null) now.remove(h); else now.put(h, o); }
	}
}
