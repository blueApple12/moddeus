package dev.moddeus.client.content;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Pose weights for cube-model mobs: a mod tells the model which named pose clips ("pose1", "pose2", ...) to blend in,
 * e.g. a boss casting or charging. Call from the mod's client @OnReload hook:
 *   MobPoses.set("moddeus:my_boss", e -> new float[] {casting(e) ? 1 : 0, charging(e) ? 1 : 0});
 * Weights 0..1 (ease them yourself for smooth blends). Read by CubeMobs every frame.
 */
public final class MobPoses {
	private MobPoses() {}
	static Map<Identifier, Function<Entity, float[]>> sources;

	public static void set(String id, Function<Entity, float[]> f) {
		if (sources == null) sources = new ConcurrentHashMap<>();
		sources.put(Identifier.parse(id), f);
	}

	public static void clear(String id) { if (sources != null) sources.remove(Identifier.parse(id)); }

	public static float[] of(Entity e) {
		if (sources == null || sources.isEmpty()) return null;
		Function<Entity, float[]> f = sources.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
		try { return f == null ? null : f.apply(e); } catch (RuntimeException ex) { return null; }
	}
}
