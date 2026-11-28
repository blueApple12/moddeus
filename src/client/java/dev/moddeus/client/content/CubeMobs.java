package dev.moddeus.client.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.moddeus.content.Mobs;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven 3D cube models for custom mobs (spec key "model", written by tools/life.mjs):
 * bones with pivots, box-UV cubes, procedural animation roles (head/leg/arm/wing/tail/jaw/segment/...)
 * and keyframe clips (idle/walk/attack/hurt/death). Edits to the spec rebuild the model live.
 */
public final class CubeMobs {
	private CubeMobs() {}

	public static boolean has(Identifier id) {
		JsonObject o = Mobs.SPECS.get(id);
		return o != null && o.has("model") && o.get("model").isJsonObject();
	}

	@SuppressWarnings("rawtypes")
	public static EntityRendererProvider provider(Identifier id) { return new Provider(id); }

	/** Renderer for a mob: cube model if its spec has one, else the vanilla renderer of its template. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static EntityRendererProvider pick(Identifier id, EntityRendererProvider old) {
		if (has(id)) { if (cube == null) cube = java.util.concurrent.ConcurrentHashMap.newKeySet(); cube.add(id); return provider(id); }
		if (java.util.Arrays.asList(Mobs.TEMPLATES).contains(Mobs.template(id))) return old;
		try {
			var f = net.minecraft.client.renderer.entity.EntityRenderers.class.getDeclaredField("PROVIDERS");
			f.setAccessible(true);
			return (EntityRendererProvider) ((Map) f.get(null)).get(Mobs.vanilla(Mobs.template(id)));
		} catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
	}

	static java.util.Set<Identifier> cube;
	public static void rebindIfNeeded(Identifier id) {
		if (cube == null) cube = java.util.concurrent.ConcurrentHashMap.newKeySet();
		if (has(id) && cube.add(id)) rebind(id);
	}

	/** Re-registers the renderer of an existing mob type (a model was added or removed) and rebuilds renderers. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void rebind(Identifier id) {
		var type = Mobs.TYPES.get(id);
		if (type == null) return;
		net.minecraft.client.renderer.entity.EntityRenderers.register((net.minecraft.world.entity.EntityType) type, pick(id, null));
		var mc = net.minecraft.client.Minecraft.getInstance();
		mc.execute(() -> mc.getEntityRenderDispatcher().onResourceManagerReload(mc.getResourceManager()));
	}

	static final class Provider implements EntityRendererProvider<Mob> {
		final Identifier id;
		Provider(Identifier id) { this.id = id; }
		public Renderer create(EntityRendererProvider.Context ctx) { return new Renderer(ctx, id); }
	}

	public static final class State extends LivingEntityRenderState {
		public float swing, hurt, sit;
		public boolean aggressive, ground, ridden;
		public float[] pose;
	}

	static float f(JsonObject o, String k, float d) { return o != null && o.has(k) ? o.get(k).getAsFloat() : d; }
	static float[] v3(JsonObject o, String k) {
		if (o == null || !o.has(k)) return new float[3];
		JsonArray a = o.getAsJsonArray(k);
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	/** One animation role on a bone: name[:phase][*amp]. */
	record Role(String name, float phase, float amp) {
		static List<Role> parse(String s) {
			List<Role> out = new ArrayList<>();
			if (s == null) return out;
			for (String w : s.trim().split("\\s+")) {
				if (w.isEmpty()) continue;
				float amp = 1, ph = 0;
				int st = w.indexOf('*');
				if (st > 0) { amp = Float.parseFloat(w.substring(st + 1)); w = w.substring(0, st); }
				int c = w.indexOf(':');
				if (c > 0) { ph = Float.parseFloat(w.substring(c + 1)); w = w.substring(0, c); }
				out.add(new Role(w, ph, amp));
			}
			return out;
		}
	}

	public static final class Model extends EntityModel<State> {
		final JsonObject spec;
		final Map<String, ModelPart> parts = new HashMap<>();
		final Map<String, List<Role>> roles = new HashMap<>();
		final JsonObject clips;
		final float scale;
		final boolean deathClip;

		Model(ModelPart root, JsonObject spec, java.util.function.Function<Identifier, RenderType> rt) {
			super(root, rt);
			this.spec = spec;
			JsonObject m = spec.getAsJsonObject("model");
			for (JsonElement b : m.getAsJsonArray("bones")) {
				JsonObject bo = b.getAsJsonObject();
				String n = bo.get("name").getAsString();
				ModelPart p = find(root, n);
				if (p != null) { parts.put(n, p); roles.put(n, Role.parse(bo.has("anim") ? bo.get("anim").getAsString() : null)); }
			}
			clips = m.has("clips") ? m.getAsJsonObject("clips") : new JsonObject();
			scale = f(m, "scale", 1);
			deathClip = clips.has("death");
		}

		static ModelPart find(ModelPart p, String n) {
			for (ModelPart c : p.getAllParts()) if (c != p && c.hasChild(n)) return c.getChild(n);
			return p.hasChild(n) ? p.getChild(n) : null;
		}

		static Model build(JsonObject spec) {
			JsonObject m = spec.getAsJsonObject("model");
			MeshDefinition mesh = new MeshDefinition();
			Map<String, PartDefinition> defs = new HashMap<>();
			Map<String, float[]> piv = new HashMap<>();
			for (JsonElement b : m.getAsJsonArray("bones")) {
				JsonObject bo = b.getAsJsonObject();
				String n = bo.get("name").getAsString(), par = bo.has("parent") ? bo.get("parent").getAsString() : null;
				float[] p = v3(bo, "pivot"), r = v3(bo, "rot");
				PartDefinition parent = par != null && defs.containsKey(par) ? defs.get(par) : mesh.getRoot();
				float[] pp = par != null && piv.containsKey(par) ? piv.get(par) : new float[] {0, 24, 0};
				CubeListBuilder cl = CubeListBuilder.create();
				if (bo.has("cubes")) for (JsonElement c : bo.getAsJsonArray("cubes")) {
					JsonObject co = c.getAsJsonObject();
					float[] a = v3(co, "from"), z = v3(co, "to");
					int u = 0, v = 0;
					if (co.has("uv")) { u = co.getAsJsonArray("uv").get(0).getAsInt(); v = co.getAsJsonArray("uv").get(1).getAsInt(); }
					cl.texOffs(u, v).mirror(co.has("mirror") && co.get("mirror").getAsBoolean());
					cl.addBox(a[0] - p[0], p[1] - z[1], a[2] - p[2], z[0] - a[0], z[1] - a[1], z[2] - a[2], new CubeDeformation(f(co, "inflate", 0)));
				}
				PartPose pose = PartPose.offsetAndRotation(p[0] - pp[0], pp[1] - p[1], p[2] - pp[2],
					r[0] * Mth.DEG_TO_RAD, r[1] * Mth.DEG_TO_RAD, r[2] * Mth.DEG_TO_RAD);
				defs.put(n, parent.addOrReplaceChild(n, cl, pose));
				piv.put(n, p);
			}
			ModelPart root = LayerDefinition.create(mesh, (int) f(m, "tw", 64), (int) f(m, "th", 64)).bakeRoot();
			boolean tl = m.has("translucent") && m.get("translucent").getAsBoolean();
			return new Model(root, spec, tl ? RenderTypes::entityTranslucent : RenderTypes::entityCutout);
		}

		@Override
		public void setupAnim(State s) {
			resetPose();
			float t = s.ageInTicks, wp = s.walkAnimationPos, ws = Math.min(1f, s.walkAnimationSpeed);
			float atk = s.swing > 0 ? Mth.sin(s.swing * Mth.PI) : 0;
			for (var e : parts.entrySet()) {
				ModelPart p = e.getValue();
				for (Role r : roles.get(e.getKey())) {
					float a = r.amp, ph = r.phase * Mth.PI, side = r.phase == 0 ? 1 : -1;
					switch (r.name) {
						case "head" -> {
							p.yRot += s.yRot * Mth.DEG_TO_RAD * a; p.xRot += s.xRot * Mth.DEG_TO_RAD * a;
							if (s.isBaby) { p.xScale *= 1.35f; p.yScale *= 1.35f; p.zScale *= 1.35f; }
							p.xRot += s.hurt * 0.35f;
						}
						case "look" -> { p.yRot += s.yRot * Mth.DEG_TO_RAD * a; }
						case "leg" -> {
							p.xRot += Mth.cos(wp * 0.6662f + ph) * 1.4f * ws * a;
							if (s.sit > 0) p.xRot -= 1.4f * s.sit;
						}
						case "arm" -> {
							p.xRot += Mth.cos(wp * 0.6662f + ph) * ws * a;
							p.zRot += (Mth.cos(t * 0.09f) * 0.05f + 0.05f) * side;
							p.xRot += Mth.sin(t * 0.067f) * 0.05f;
							if (s.aggressive) p.xRot -= 1.2f;
							p.xRot -= atk * 1.8f;
						}
						case "reach" -> { p.xRot -= (s.aggressive ? 1.5f : 0.15f) * a; p.xRot -= atk * 0.8f; }
						case "wing" -> {
							boolean fly = !s.ground;
							p.zRot += Mth.sin(t * (fly ? 1.3f : 0.25f) + ph * 0) * (fly ? 0.9f : 0.12f) * a * side;
						}
						case "tail" -> {
							p.yRot += Mth.sin(t * 0.25f + ph) * 0.25f * (1 + ws * 2) * a;
							p.xRot += ws * 0.25f * a;
						}
						case "jaw" -> p.xRot += (atk * 0.7f + Mth.sin(t * 0.1f) * 0.03f + s.hurt * 0.4f) * a;
						case "segment" -> p.yRot += Mth.sin(t * 0.2f - r.phase * 0.7f) * 0.25f * (0.4f + ws * 1.5f) * a;
						case "wave" -> p.xRot += Mth.sin(t * 0.2f - r.phase * 0.7f) * 0.2f * a;
						case "breathe" -> { float k = 1 + Mth.sin(t * 0.08f) * 0.025f * a; p.xScale *= k; p.zScale *= k; p.yScale *= 1 + Mth.sin(t * 0.08f) * 0.012f * a; }
						case "bob" -> p.y += Mth.sin(t * 0.1f + ph) * 1.0f * a;
						case "spin" -> p.yRot += t * 0.05f * a;
						case "roll" -> p.zRot += t * 0.05f * a;
						case "crawl" -> { p.yRot += Mth.cos(wp * 1.3324f + ph) * 0.4f * ws * a; p.zRot += Math.abs(Mth.sin(wp * 0.6662f + ph)) * 0.4f * ws * a; }
						case "sway" -> p.zRot += Mth.sin(t * 0.05f + ph) * 0.08f * a;
						case "ear" -> p.zRot += Mth.sin(t * 0.3f + ph) * Mth.sin(t * 0.031f) * 0.15f * a * side;
						case "body" -> { p.xRot += atk * 0.25f * a; p.zRot += Mth.cos(wp * 0.6662f) * 0.05f * ws * a; }
						case "lunge" -> { p.z -= atk * 3f * a; p.xRot += atk * 0.3f * a; }
						default -> {}
					}
				}
			}
			clip("idle", (t % len("idle")) / len("idle"), 1);
			if (ws > 0.05f) clip("walk", (wp * 0.6662f / Mth.TWO_PI) % 1f, ws);
			if (s.swing > 0) clip("attack", s.swing, 1);
			if (s.hurt > 0) clip("hurt", 1 - s.hurt, 1);
			if (s.deathTime > 0) clip("death", Math.min(1f, s.deathTime / 20f), 1);
			if (s.sit > 0) clip("sit", 1, s.sit);
			if (s.pose != null) for (int i = 0; i < s.pose.length; i++) if (s.pose[i] > 0) clip("pose" + (i + 1), (t % len("pose" + (i + 1))) / len("pose" + (i + 1)), s.pose[i]);
		}

		float len(String c) { return clips.has(c) ? Math.max(1, f(clips.getAsJsonObject(c), "len", 20)) : 20; }

		/** Adds clip c at progress u (0..1) times weight w. Channels rx ry rz (deg), x y z (px, y up), s (scale). */
		void clip(String c, float u, float w) {
			if (!clips.has(c)) return;
			JsonObject cl = clips.getAsJsonObject(c), bones = cl.getAsJsonObject("bones");
			if (bones == null) return;
			float time = u * len(c);
			for (var b : bones.entrySet()) {
				ModelPart p = parts.get(b.getKey());
				if (p == null) continue;
				for (var ch : b.getValue().getAsJsonObject().entrySet()) {
					float v = sample(ch.getValue().getAsJsonArray(), time) * w;
					switch (ch.getKey()) {
						case "rx" -> p.xRot += v * Mth.DEG_TO_RAD; case "ry" -> p.yRot += v * Mth.DEG_TO_RAD; case "rz" -> p.zRot += v * Mth.DEG_TO_RAD;
						case "x" -> p.x += v; case "y" -> p.y -= v; case "z" -> p.z += v;
						case "s" -> { float k = 1 + v; p.xScale *= k; p.yScale *= k; p.zScale *= k; }
						default -> {}
					}
				}
			}
		}

		/** keys [[time, value], ...] sorted by time; smoothstep between keys. */
		static float sample(JsonArray keys, float t) {
			if (keys.isEmpty()) return 0;
			JsonArray prev = keys.get(0).getAsJsonArray();
			if (t <= prev.get(0).getAsFloat()) return prev.get(1).getAsFloat();
			for (int i = 1; i < keys.size(); i++) {
				JsonArray k = keys.get(i).getAsJsonArray();
				float t0 = prev.get(0).getAsFloat(), t1 = k.get(0).getAsFloat();
				if (t <= t1) {
					float x = t1 > t0 ? (t - t0) / (t1 - t0) : 1;
					x = x * x * (3 - 2 * x);
					return Mth.lerp(x, prev.get(1).getAsFloat(), k.get(1).getAsFloat());
				}
				prev = k;
			}
			return prev.get(1).getAsFloat();
		}
	}

	public static final class Renderer extends MobRenderer<Mob, State, Model> {
		final Identifier id, tex, glowTex;
		JsonObject built;

		Renderer(EntityRendererProvider.Context ctx, Identifier id) {
			super(ctx, Model.build(Mobs.SPECS.get(id)), 0.5f);
			this.id = id;
			this.built = Mobs.SPECS.get(id);
			this.tex = Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/entity/" + id.getPath() + ".png");
			this.glowTex = Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/entity/" + id.getPath() + "_glow.png");
			addLayer(new Glow(this));
		}

		/** Rebuilds the model when the spec JSON was reloaded (mc mob edits are live). */
		void refresh() {
			JsonObject now = Mobs.SPECS.get(id);
			if (now != null && now != built && now.has("model")) {
				try { model = Model.build(now); } catch (RuntimeException ex) { dev.moddeus.Moddeus.LOG.warn("cube model {}: {}", id, ex.toString()); }
				built = now;
			}
		}

		@Override public State createRenderState() { return new State(); }

		@Override
		public void extractRenderState(Mob e, State s, float pt) {
			super.extractRenderState(e, s, pt);
			refresh();
			s.swing = e.getSwingAnimation(pt);
			s.hurt = e.hurtTime > 0 ? Math.max(0, (e.hurtTime - pt) / 10f) : 0;
			s.aggressive = e.isAggressive();
			s.ground = e.onGround() || e.isPassenger();
			s.ridden = e.isVehicle();
			boolean sitting = (e instanceof net.minecraft.world.entity.TamableAnimal ta && ta.isInSittingPose()) || e.entityTags().contains("mb_sit");
			s.sit = sitting ? 1 : 0;
			shadowRadius = Math.min(3f, e.getBbWidth() * 0.6f);
			s.pose = MobPoses.of(e);
		}

		@Override public Identifier getTextureLocation(State s) { return tex; }

		@Override
		protected void scale(State s, PoseStack ps) {
			float k = model.scale;
			if (k != 1) ps.scale(k, k, k);
		}

		@Override protected float getFlipDegrees() { return model.deathClip ? 0 : 90; }

		boolean hasGlow() { JsonObject m = built == null ? null : built.getAsJsonObject("model"); return m != null && m.has("glow") && m.get("glow").getAsBoolean(); }
	}

	/** Full-bright pass of textures/entity/<id>_glow.png (eyes, runes, cores), like spider eyes. */
	static final class Glow extends EyesLayer<State, Model> {
		final Renderer r;
		Glow(Renderer r) { super(r); this.r = r; }
		@Override public RenderType renderType() { return RenderTypes.eyes(r.glowTex); }
		@Override public void submit(PoseStack ps, net.minecraft.client.renderer.SubmitNodeCollector c, int light, State s, float a, float b) {
			if (r.hasGlow()) super.submit(ps, c, light, s, a, b);
		}
	}
}
