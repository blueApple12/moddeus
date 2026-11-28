package dev.moddeus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.moddeus.live.Gear;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom 3D armor = the armor MODEL is replaced (Fabric ArmorRenderer) for every item with content "worn" cubes (tools/gear.mjs worn).
 * Draws the normal equipment texture layer on the vanilla armor model, then a HumanoidModel whose head/body/arms/legs carry the extra
 * cubes as CHILD parts. Both pose themselves from the render state (HumanoidModel.setupAnim, like vanilla armor), so sneaking,
 * swimming, crawling, riding, gliding and swings follow exactly. Cube JSON (model space, px, y down, front = -z):
 * {bone, pivot:[x,y,z], from:[x,y,z] (relative to pivot), size:[w,h,d], rot:[x,y,z] deg, uv:[u,v], tex:"ns:textures/..png", tw, th, glow, spin (deg/s about y), bob (px)}
 */
public final class GearArmor {
	static Map<Item, Object> REGISTERED;
	static Map<String, Models> MODELS;
	static EntityRendererProvider.Context CTX;
	static int lastHash;
	static boolean pendingReload;

	@OnReload
	public static void hook() {
		if (REGISTERED == null) REGISTERED = new HashMap<>();
		MODELS = new HashMap<>(); // JSON may have changed: rebuild models lazily
		if (System.getProperties().get("moddeus.gear.armor") == null) {
			System.getProperties().put("moddeus.gear.armor", Boolean.TRUE);
			ClientTickEvents.END_CLIENT_TICK.register(GearArmor::tick);
		}
		lastHash = 0;
		if (Gear.WORN != null) for (Identifier id : Gear.WORN.keySet()) register(id); // at launch: before the first resource load builds the renderers
	}

	static boolean register(Identifier id) {
		Item it = BuiltInRegistries.ITEM.getValue(id);
		if (it == null || it == net.minecraft.world.item.Items.AIR || REGISTERED.get(it) != null) return false;
		if (!put(it)) return false;
		REGISTERED.put(it, Boolean.TRUE); // only after success: a failed put retries next tick
		return true;
	}

	/** Registers new worn items (content can add them any time) and drops cached models when the JSON changes. */
	static void tick(Minecraft mc) {
		if (Gear.WORN == null) return;
		int h = Gear.WORN.hashCode();
		if (h != lastHash) { lastHash = h; MODELS = new HashMap<>(); } // JSON changed: rebuild models
		for (Identifier id : Gear.WORN.keySet()) pendingReload |= register(id); // every tick (few items): picks up items registered late or re-registered
		if (pendingReload && mc.level != null) { // Fabric builds armor renderers on the entity renderer reload
			pendingReload = false;
			mc.getEntityRenderDispatcher().onResourceManagerReload(mc.getResourceManager());
		}
	}

	@SuppressWarnings("unchecked")
	static boolean put(Item it) {
		try {
			var f = Class.forName("net.fabricmc.fabric.impl.client.rendering.ArmorRendererRegistryImpl").getDeclaredField("FACTORIES");
			f.setAccessible(true);
			((Map<Item, ArmorRenderer.Factory>) f.get(null)).put(it, GearArmor::factory); // put (not register): re-registered items replace the old entry
			return true;
		} catch (Throwable e) { dev.moddeus.Moddeus.LOG.error("GearArmor: can't register {}", it, e); return false; }
	}

	static ArmorRenderer factory(EntityRendererProvider.Context ctx) { CTX = ctx; return new Renderer(ctx); }

	public static final class Renderer implements ArmorRenderer {
		final ArmorModelSet<HumanoidModel<HumanoidRenderState>> armor;
		final EquipmentLayerRenderer equipment;
		Renderer(EntityRendererProvider.Context ctx) {
			armor = ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, ctx.getModelSet(), HumanoidModel::new);
			equipment = ctx.getEquipmentRenderer();
		}
		@Override public void render(PoseStack ps, SubmitNodeCollector c, ItemStack stack, HumanoidRenderState s, EquipmentSlot slot, int light, HumanoidModel<HumanoidRenderState> ctxModel) {
			try { GearArmor.render(this, ps, c, stack, s, slot, light); } catch (Throwable e) { /* a bad worn model must never crash rendering */ }
		}
	}

	static void render(Renderer r, PoseStack ps, SubmitNodeCollector c, ItemStack stack, HumanoidRenderState s, EquipmentSlot slot, int light) {
		var eq = stack.get(DataComponents.EQUIPPABLE);
		if (eq != null && eq.assetId().isPresent()) { // the normal textured armor layer
			var layer = slot == EquipmentSlot.LEGS ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS : EquipmentClientInfo.LayerType.HUMANOID;
			r.equipment.renderLayers(layer, eq.assetId().get(), r.armor.get(slot), s, stack, ps, c, light, s.outlineColor);
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		JsonArray cubes = Gear.WORN == null ? null : Gear.WORN.get(id);
		if (cubes == null || cubes.isEmpty()) return;
		if (MODELS == null) MODELS = new HashMap<>();
		Models m = MODELS.computeIfAbsent(id.toString(), k -> build(cubes));
		if (m.solid != null) { m.solid.setupAnim(s); c.submitModel(m.solid, s, ps, RenderTypes.armorCutoutNoCull(m.tex), light, OverlayTexture.NO_OVERLAY, s.outlineColor); }
		if (m.glow != null) { m.glow.setupAnim(s); c.submitModel(m.glow, s, ps, RenderTypes.entityTranslucentEmissive(m.tex), 0xF000F0, OverlayTexture.NO_OVERLAY, s.outlineColor); }
	}

	static final class Models { PartsModel solid, glow; Identifier tex; }

	static Models build(JsonArray cubes) {
		Models m = new Models();
		JsonObject first = cubes.get(0).getAsJsonObject();
		m.tex = Identifier.parse(first.get("tex").getAsString());
		int tw = first.get("tw").getAsInt(), th = first.get("th").getAsInt();
		m.solid = model(cubes, false, tw, th);
		m.glow = model(cubes, true, tw, th);
		return m;
	}

	static PartsModel model(JsonArray cubes, boolean glow, int tw, int th) {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		Map<String, PartDefinition> bones = new HashMap<>();
		PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.offset(0, 0, 0));
		head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
		bones.put("head", head);
		bones.put("body", root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0, 0, 0)));
		bones.put("right_arm", root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5, 2, 0)));
		bones.put("left_arm", root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5, 2, 0)));
		bones.put("right_leg", root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9f, 12, 0)));
		bones.put("left_leg", root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9f, 12, 0)));
		List<float[]> anim = new ArrayList<>();
		List<String> names = new ArrayList<>();
		int n = 0, used = 0;
		for (JsonElement e : cubes) {
			JsonObject o = e.getAsJsonObject();
			boolean g = o.has("glow") && o.get("glow").getAsBoolean();
			if (g != glow) continue;
			PartDefinition bone = bones.get(o.get("bone").getAsString());
			if (bone == null) continue;
			float[] p = f3(o, "pivot"), from = f3(o, "from"), sz = f3(o, "size"), rot = f3(o, "rot");
			int[] uv = {o.getAsJsonArray("uv").get(0).getAsInt(), o.getAsJsonArray("uv").get(1).getAsInt()};
			String name = "g" + (n++);
			bone.addOrReplaceChild(name, CubeListBuilder.create().texOffs(uv[0], uv[1]).addBox(from[0], from[1], from[2], sz[0], sz[1], sz[2]),
				PartPose.offsetAndRotation(p[0], p[1], p[2], (float) Math.toRadians(rot[0]), (float) Math.toRadians(rot[1]), (float) Math.toRadians(rot[2])));
			float spin = o.has("spin") ? o.get("spin").getAsFloat() : 0, bob = o.has("bob") ? o.get("bob").getAsFloat() : 0;
			if (spin != 0 || bob != 0) { names.add(o.get("bone").getAsString() + "/" + name); anim.add(new float[]{spin, bob, n}); }
			used++;
		}
		if (used == 0) return null;
		PartsModel pm = new PartsModel(LayerDefinition.create(mesh, tw, th).bakeRoot());
		for (int i = 0; i < names.size(); i++) {
			String[] bn = names.get(i).split("/");
			ModelPart bone = switch (bn[0]) { case "head" -> pm.head; case "body" -> pm.body; case "right_arm" -> pm.rightArm; case "left_arm" -> pm.leftArm; case "right_leg" -> pm.rightLeg; default -> pm.leftLeg; };
			pm.anim.add(new Anim(bone.getChild(bn[1]), anim.get(i)[0], anim.get(i)[1], anim.get(i)[2]));
		}
		return pm;
	}

	static float[] f3(JsonObject o, String k) {
		if (!o.has(k)) return new float[3];
		JsonArray a = o.getAsJsonArray(k);
		return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	record Anim(ModelPart part, float spin, float bob, float phase) {}

	/** HumanoidModel posed from the state like vanilla armor; spinning/bobbing parts run on the real clock (keep moving in /tick freeze). */
	public static final class PartsModel extends HumanoidModel<HumanoidRenderState> {
		final List<Anim> anim = new ArrayList<>();
		PartsModel(ModelPart root) { super(root); }
		@Override public void setupAnim(HumanoidRenderState s) {
			super.setupAnim(s);
			double t = System.nanoTime() / 1e9;
			for (Anim a : anim) {
				if (a.spin() != 0) a.part().yRot += (float) Math.toRadians((t * a.spin()) % 360);
				if (a.bob() != 0) a.part().y += (float) (Math.sin(t * 2.2 + a.phase()) * a.bob());
			}
		}
	}
}
