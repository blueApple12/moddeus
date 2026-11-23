package dev.moddeus.content;

import com.google.gson.JsonObject;
import dev.moddeus.live.LiveRegistry;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Custom particle types. Behaviour (lifetime, gravity, size, glow, spin...) is read from SPECS per particle, so edits are live. */
public final class Particles {
	public static final Map<Identifier, JsonObject> SPECS = new ConcurrentHashMap<>();
	public static final Map<Identifier, SimpleParticleType> TYPES = new ConcurrentHashMap<>();
	public static volatile Runnable clientListener;

	public static void register(String id, JsonObject o) {
		Identifier rl = LiveRegistry.id(id);
		SPECS.put(rl, o);
		TYPES.put(rl, (SimpleParticleType) LiveRegistry.<net.minecraft.core.particles.ParticleType<?>>register(BuiltInRegistries.PARTICLE_TYPE, rl, k -> FabricParticleTypes.simple(true)));
		Runnable l = clientListener;
		if (l != null) l.run();
	}
}
