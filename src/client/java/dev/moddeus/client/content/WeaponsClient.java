package dev.moddeus.client.content;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.Weapons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Client half of live/Weapons: renderers for custom arrows (arrow model + own texture), thrown items and tridents (the item's own model, point-first). */
public final class WeaponsClient {
	static Set<Object> done;
	/** Item-model projectiles: degrees added after aligning the model's diagonal (sprite/weapon convention) with the flight. */
	public static float tipFix = 0;

	@OnReload
	public static void hook() {
		Weapons.clientListener = WeaponsClient::sync;
		sync();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static synchronized void sync() {
		if (done == null) done = ConcurrentHashMap.newKeySet();
		boolean any = false;
		for (var e : Weapons.types().entrySet()) {
			if (!done.add(e.getValue())) continue;
			Identifier id = e.getKey();
			EntityRendererProvider p = switch (Weapons.kinds().getOrDefault(id, "arrow")) {
				case "arrow" -> new ArrowProvider(Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/entity/projectiles/" + id.getPath() + ".png"));
				case "thrown" -> new ThrownProvider();
				default -> new ItemProvider();
			};
			EntityRenderers.register((EntityType) e.getValue(), p);
			any = true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (any && mc != null && mc.getResourceManager() != null)
			mc.execute(() -> mc.getEntityRenderDispatcher().onResourceManagerReload(mc.getResourceManager()));
	}

	record ArrowProvider(Identifier tex) implements EntityRendererProvider<AbstractArrow> {
		public EntityRenderer<AbstractArrow, ?> create(Context c) { return new ArrowR(c, tex); }
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	record ThrownProvider() implements EntityRendererProvider {
		public EntityRenderer create(Context c) { return new ThrownItemRenderer(c, 1.0f, true); }
	}
	record ItemProvider() implements EntityRendererProvider<AbstractArrow> {
		public EntityRenderer<AbstractArrow, ?> create(Context c) { return new ItemR(c); }
	}

	static final class ArrowR extends ArrowRenderer<AbstractArrow, ArrowRenderState> {
		final Identifier tex;
		ArrowR(EntityRendererProvider.Context c, Identifier tex) { super(c); this.tex = tex; }
		@Override protected Identifier getTextureLocation(ArrowRenderState s) { return tex; }
		@Override public ArrowRenderState createRenderState() { return new ArrowRenderState(); }
	}

	public static final class ItemState extends EntityRenderState {
		public final ItemStackRenderState item = new ItemStackRenderState();
		public float xRot, yRot;
	}

	/** Draws the item's own model (3D weapon) along the flight direction, like a thrown trident. */
	static final class ItemR extends EntityRenderer<AbstractArrow, ItemState> {
		final ItemModelResolver items;
		ItemR(EntityRendererProvider.Context c) { super(c); items = c.getItemModelResolver(); }
		@Override public ItemState createRenderState() { return new ItemState(); }
		@Override public void extractRenderState(AbstractArrow e, ItemState s, float pt) {
			super.extractRenderState(e, s, pt);
			s.yRot = e.getYRot(pt); s.xRot = e.getXRot(pt);
			items.updateForNonLiving(s.item, new ItemStack(BuiltInRegistries.ITEM.getValue(EntityType.getKey(e.getType()))), ItemDisplayContext.NONE, e);
		}
		@Override public void submit(ItemState s, PoseStack ps, SubmitNodeCollector c, CameraRenderState cam) {
			ps.pushPose();
			ps.rotateDegrees(Axis.YP, s.yRot - 90);
			ps.rotateDegrees(Axis.ZP, s.xRot - 45 + tipFix); // model diagonal (1,1) -> flight (+x)
			ps.scale(1.6f, 1.6f, 1.6f);
			s.item.submit(ps, c, s.lightCoords, OverlayTexture.NO_OVERLAY, s.outlineColor);
			ps.popPose();
			super.submit(s, ps, c, cam);
		}
	}
}
