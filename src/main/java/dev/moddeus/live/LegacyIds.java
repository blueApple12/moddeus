package dev.moddeus.live;

import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * Registry lookups of old ids resolve to the current ones (LegacyIdMixin): worlds made before the rename store
 * "claudecraft:x" (-> "moddeus:x"), and content moved to its mod's namespace keeps its old ids as aliases
 * (content JSON "aliases": ["moddeus:abyssal_bow"], registered by ContentLoader), so saved stacks/blocks still load.
 */
public final class LegacyIds {
	static final String OLD = "claude" + "craft"; // split so project-wide renames never touch it
	static volatile Map<Identifier, Identifier> ALIASES; // lazy: a static added by a swap starts null

	private LegacyIds() {}

	/** old -> now for every registry. Add only after the old id is unregistered (else unregister(old) hits the new entry). */
	public static void alias(Identifier old, Identifier now) {
		if (ALIASES == null) ALIASES = new java.util.concurrent.ConcurrentHashMap<>();
		if (!old.equals(now)) ALIASES.put(old, now);
	}

	public static Identifier fix(Identifier id) {
		if (id == null) return null;
		if (OLD.equals(id.getNamespace())) id = Identifier.fromNamespaceAndPath("moddeus", id.getPath());
		Map<Identifier, Identifier> a = ALIASES;
		if (a != null) { Identifier n = a.get(id); if (n != null) return n; }
		return id;
	}

	public static <T> ResourceKey<T> fix(ResourceKey<T> k) {
		if (k == null) return null;
		Identifier f = fix(k.identifier());
		return f == k.identifier() ? k : ResourceKey.create(k.registryKey(), f);
	}
}
