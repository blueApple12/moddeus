package dev.moddeus.client.content;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.moddeus.content.PhysicsBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Draws a physics body: every block of its grid with real block models and world lighting, rotated as one piece. No shadow. */
public class PhysicsRenderer extends EntityRenderer<PhysicsBlock, PhysicsRenderer.State> {
	public static class State extends EntityRenderState {
		JsonObject spec = new JsonObject();
		org.joml.Quaternionf rot = new org.joml.Quaternionf();
		double cx, cy, cz;
		int n;
		int[] x = new int[0], y = new int[0], z = new int[0];
		List<MovingBlockRenderState> blocks = new ArrayList<>();
		int crackX, crackY, crackZ, crackStage = -1;
	}

	public PhysicsRenderer(EntityRendererProvider.Context ctx) { super(ctx); shadowRadius = 0; shadowStrength = 0; }

	@Override public State createRenderState() { return new State(); }

	@Override
	public void extractRenderState(PhysicsBlock e, State s, float partial) {
		super.extractRenderState(e, s, partial);
		shadowRadius = 0;
		s.spec = e.spec();
		(e.rotO == null ? e.rot : e.rotO).slerp(e.rot, partial, s.rot);
		PhysicsBlock.Grid g = e.grid();
		s.cx = g.com.x; s.cy = g.com.y; s.cz = g.com.z;
		s.n = g.n;
		if (s.x.length < g.n) { s.x = new int[g.n]; s.y = new int[g.n]; s.z = new int[g.n]; }
		while (s.blocks.size() < g.n) s.blocks.add(new MovingBlockRenderState());
		ClientLevel lvl = (ClientLevel) e.level();
		Vec3 base = e.getPosition(partial);
		for (int i = 0; i < g.n; i++) {
			s.x[i] = g.x[i]; s.y[i] = g.y[i]; s.z[i] = g.z[i];
			MovingBlockRenderState b = s.blocks.get(i);
			b.blockState = g.st[i];
			if (g.st[i] == null) continue;
			Vec3 l = g.cellCenterLocal(i);
			org.joml.Vector3f r = s.rot.transform(new org.joml.Vector3f((float) l.x, (float) l.y, (float) l.z));
			BlockPos p = BlockPos.containing(base.x + r.x, base.y + r.y, base.z + r.z);
			b.blockPos = p; b.randomSeedPos = p;
			b.biome = lvl.getBiome(p); b.cardinalLighting = lvl.cardinalLighting(); b.lightEngine = lvl.getLightEngine();
		}
		s.crackStage = -1;
		if (PhysicsMining.entity == e.getId() && PhysicsMining.stage >= 0) {
			s.crackStage = PhysicsMining.stage; s.crackX = PhysicsMining.cx; s.crackY = PhysicsMining.cy; s.crackZ = PhysicsMining.cz;
		}
	}

	@Override
	protected float getShadowRadius(State s) { return 0; }

	static Identifier tex(String t) {
		if (t == null) return Identifier.withDefaultNamespace("textures/block/stone.png");
		if (t.contains("textures/")) return Identifier.parse(t);
		Identifier id = t.contains(":") ? Identifier.parse(t) : Identifier.fromNamespaceAndPath("moddeus", "block/" + t);
		String path = id.getPath().contains("/") ? id.getPath() : "block/" + id.getPath();
		return Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/" + path + ".png");
	}

	private static String str(JsonObject o, String k, String d) { return o.has(k) ? o.get(k).getAsString() : d; }

	@Override
	public void submit(State s, PoseStack ps, SubmitNodeCollector out, CameraRenderState cam) {
		ps.pushPose();
		ps.rotate(s.rot);
		ps.translate(-s.cx, -s.cy, -s.cz);
		for (int i = 0; i < s.n; i++) {
			ps.pushPose();
			ps.translate(s.x[i], s.y[i], s.z[i]);
			MovingBlockRenderState b = s.blocks.get(i);
			if (b.blockState != null) out.submitMovingBlock(ps, b, s.outlineColor);
			else textured(s, ps, out);
			ps.popPose();
		}
		if (s.crackStage >= 0) {
			ps.pushPose();
			ps.translate(s.crackX, s.crackY, s.crackZ);
			Identifier crack = Identifier.withDefaultNamespace("textures/block/destroy_stage_" + Math.min(9, s.crackStage) + ".png");
			out.submitCustomGeometry(ps, RenderTypes.crumbling(crack), (pose, vc) -> { for (int f = 0; f < 6; f++) face(pose, vc, f, -0.002f, 1.002f, 0xF000F0); });
			ps.popPose();
		}
		ps.popPose();
	}

	/** A custom-textured spec block (texture / texture_top / texture_bottom). */
	void textured(State s, PoseStack ps, SubmitNodeCollector out) {
		boolean glow = s.spec.has("glow") && s.spec.get("glow").getAsBoolean();
		boolean translucent = s.spec.has("translucent") && s.spec.get("translucent").getAsBoolean();
		int light = glow ? 0xF000F0 : s.lightCoords;
		String side = str(s.spec, "texture", null);
		String[] faceTex = {str(s.spec, "texture_bottom", side), str(s.spec, "texture_top", side), side, side, side, side};
		for (int f = 0; f < 6; f++) {
			final int face = f;
			Identifier t = tex(faceTex[f]);
			out.submitCustomGeometry(ps, translucent ? RenderTypes.entityTranslucent(t) : RenderTypes.entityCutout(t), (pose, vc) -> face(pose, vc, face, 0, 1, light));
		}
	}

	/** Face f (0 down, 1 up, 2 north, 3 south, 4 west, 5 east) of the box a..b on every axis. */
	static void face(PoseStack.Pose pose, VertexConsumer vc, int f, float a, float b, int light) {
		float[][] q = switch (f) {
			case 0 -> new float[][] {{a, a, b}, {a, a, a}, {b, a, a}, {b, a, b}};
			case 1 -> new float[][] {{a, b, a}, {a, b, b}, {b, b, b}, {b, b, a}};
			case 2 -> new float[][] {{b, a, a}, {a, a, a}, {a, b, a}, {b, b, a}};
			case 3 -> new float[][] {{a, a, b}, {b, a, b}, {b, b, b}, {a, b, b}};
			case 4 -> new float[][] {{a, a, a}, {a, a, b}, {a, b, b}, {a, b, a}};
			default -> new float[][] {{b, a, b}, {b, a, a}, {b, b, a}, {b, b, b}};
		};
		float[] n = switch (f) { case 0 -> new float[] {0, -1, 0}; case 1 -> new float[] {0, 1, 0}; case 2 -> new float[] {0, 0, -1}; case 3 -> new float[] {0, 0, 1}; case 4 -> new float[] {-1, 0, 0}; default -> new float[] {1, 0, 0}; };
		float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
		for (int i = 0; i < 4; i++)
			vc.addVertex(pose, q[i][0], q[i][1], q[i][2]).setColor(-1).setUv(uv[i][0], uv[i][1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, n[0], n[1], n[2]);
	}
}
