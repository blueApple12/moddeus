package dev.moddeus.client.content;

import dev.moddeus.content.PhysicsBlock;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hold left-click on a physics body = mine the block you look at, like a normal block: real break time for your tool,
 * crack overlay, hit sounds, then the server breaks that cell (drops + split). Bodies are never punched.
 */
public final class PhysicsMining {
	/** What is being mined (read by PhysicsRenderer for the crack overlay). */
	public static volatile int entity = -1, cx, cy, cz, stage = -1;
	static float progress;
	static int ticks, delay;

	@OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.physmining", Boolean.TRUE) != null) return;
		ClientTickEvents.END_CLIENT_TICK.register(PhysicsMining::tick);
	}

	static void reset() { entity = -1; stage = -1; progress = 0; }

	public static void tick(Minecraft mc) {
		try { step(mc); } catch (RuntimeException e) { reset(); }
	}

	static void step(Minecraft mc) {
		if (delay > 0) delay--;
		if (mc.player == null || mc.level == null || mc.gui.screen() != null || !mc.options.keyAttack.isDown()
			|| !(mc.hitResult instanceof EntityHitResult eh) || !(eh.getEntity() instanceof PhysicsBlock b)) { reset(); return; }
		double reach = mc.player.blockInteractionRange() + 1;
		Vec3 eye = mc.player.getEyePosition();
		BlockHitResult h = b.rayCell(eye, eye.add(mc.player.getLookAngle().scale(reach)), 0);
		if (h == null) { reset(); return; }
		BlockPos c = h.getBlockPos();
		if (entity != b.getId() || c.getX() != cx || c.getY() != cy || c.getZ() != cz) { reset(); entity = b.getId(); cx = c.getX(); cy = c.getY(); cz = c.getZ(); }
		PhysicsBlock.Grid g = b.grid();
		int i = g.cell(cx, cy, cz);
		if (i < 0) { reset(); return; }
		BlockState st = g.st[i];
		Vec3 at = b.toWorld(g.cellCenterLocal(i));
		BlockPos wp = BlockPos.containing(at);
		if (mc.player.isCreative()) { if (delay == 0) { finish(mc, b); delay = 5; } return; }
		progress += st == null ? 0.1f : st.getDestroyProgress(mc.player, mc.level, wp);
		stage = (int) (progress * 10) - 1;
		if (++ticks % 4 == 0) {
			mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			if (st != null) mc.level.playLocalSound(at.x, at.y, at.z, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.25f, st.getSoundType().getPitch() * 0.5f, false);
		}
		if (progress >= 1) { finish(mc, b); delay = 5; }
	}

	/** Singleplayer: hand the break to the server thread (same JVM). */
	static void finish(Minecraft mc, PhysicsBlock b) {
		var server = mc.getSingleplayerServer();
		if (server == null) { reset(); return; }
		java.util.UUID id = mc.player.getUUID();
		int e = b.getId(), x = cx, y = cy, z = cz;
		mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		server.execute(() -> dev.moddeus.content.Physics.mine(server, id, e, x, y, z));
		reset();
	}
}
