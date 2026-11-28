package dev.moddeus.client.content;

import dev.moddeus.content.Mobs;
import dev.moddeus.content.Particles;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.spider.Spider;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Client half of custom mobs/particles: renderers (vanilla body + our texture) and particle providers, registered live. */
public final class ClientContent {
	private static final Set<Object> DONE = ConcurrentHashMap.newKeySet();

	@OnReload
	public static void hook() {
		Mobs.clientListener = ClientContent::sync;
		Particles.clientListener = ClientContent::sync;
		sync();
	}

	public static synchronized void sync() {
		boolean renderers = false;
		for (var e : Mobs.TYPES.entrySet()) {
			if (!DONE.add(e.getValue())) continue;
			register(e.getValue(), CubeMobs.pick(e.getKey(), renderer(Mobs.template(e.getKey()), Identifier.fromNamespaceAndPath(e.getKey().getNamespace(), "textures/entity/" + e.getKey().getPath() + ".png"))));
			renderers = true;
		}
		var phys = dev.moddeus.content.Physics.TYPE;
		if (phys != null && DONE.add(phys)) { register(phys, (EntityRendererProvider<dev.moddeus.content.PhysicsBlock>) PhysicsRenderer::new); renderers = true; }
		for (var e : Particles.TYPES.entrySet()) {
			if (!DONE.add(e.getValue())) continue;
			Identifier id = e.getKey();
			ParticleProviderRegistry.getInstance().register(e.getValue(), sprites ->
				(SimpleParticleType opt, net.minecraft.client.multiplayer.ClientLevel level, double x, double y, double z, double dx, double dy, double dz, net.minecraft.util.RandomSource r) ->
					new CustomParticle(level, x, y, z, dx, dy, dz, sprites, Particles.SPECS.getOrDefault(id, new com.google.gson.JsonObject())));
		}
		Minecraft mc = Minecraft.getInstance();
		if (renderers && mc != null && mc.getResourceManager() != null)
			mc.execute(() -> mc.getEntityRenderDispatcher().onResourceManagerReload(mc.getResourceManager()));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void register(EntityType<?> type, EntityRendererProvider<?> p) {
		EntityRenderers.register((EntityType) type, (EntityRendererProvider) p);
	}

	@SuppressWarnings("rawtypes")
	private static EntityRendererProvider renderer(String tpl, Identifier tex) {
		return switch (tpl) {
			case "skeleton" -> ctx -> new SkeletonRenderer(ctx) { @Override public Identifier getTextureLocation(SkeletonRenderState s) { return tex; } };
			case "spider" -> ctx -> new SpiderRenderer<Spider>(ctx) { @Override public Identifier getTextureLocation(LivingEntityRenderState s) { return tex; } };
			case "creeper" -> ctx -> new CreeperRenderer(ctx) { @Override public Identifier getTextureLocation(CreeperRenderState s) { return tex; } };
			case "blaze" -> ctx -> new BlazeRenderer(ctx) { @Override public Identifier getTextureLocation(LivingEntityRenderState s) { return tex; } };
			case "pig" -> ctx -> new PigRenderer(ctx) { @Override public Identifier getTextureLocation(PigRenderState s) { return tex; } };
			case "chicken" -> ctx -> new ChickenRenderer(ctx) { @Override public Identifier getTextureLocation(ChickenRenderState s) { return tex; } };
			case "iron_golem" -> ctx -> new IronGolemRenderer(ctx) { @Override public Identifier getTextureLocation(IronGolemRenderState s) { return tex; } };
			case "bee" -> ctx -> new BeeRenderer(ctx) { @Override public Identifier getTextureLocation(BeeRenderState s) { return tex; } };
			default -> ctx -> new ZombieRenderer(ctx) { @Override public Identifier getTextureLocation(ZombieRenderState s) { return tex; } };
		};
	}
}
