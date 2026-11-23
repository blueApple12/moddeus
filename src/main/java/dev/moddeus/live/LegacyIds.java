package dev.moddeus.live;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/** Worlds made before the rename store ids as "claudecraft:x": registry lookups map them to "moddeus:x" (LegacyIdMixin). */
public final class LegacyIds {
	static final String OLD = "claude" + "craft"; // split so project-wide renames never touch it

	private LegacyIds() {}

	public static Identifier fix(Identifier id) {
		return id != null && OLD.equals(id.getNamespace()) ? Identifier.fromNamespaceAndPath("moddeus", id.getPath()) : id;
	}

	public static <T> ResourceKey<T> fix(ResourceKey<T> k) {
		return k != null && OLD.equals(k.identifier().getNamespace()) ? ResourceKey.create(k.registryKey(), fix(k.identifier())) : k;
	}
}
