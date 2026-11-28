package dev.moddeus.live;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * Undoable builds from code (game run snippets, statues, landmarks, "turn X into diamond"): every block set through
 * b.set(...) records what was there, exactly like mc struct, so "remove it" / "revert" = node tools/mc.mjs struct undo
 * puts the old terrain back (trees, grass, water, chests). Guide: .claude/skills/mc-content/GAME-RUN.md
 *   Builds.build("eiffel", b -> { for (int y = 0; y < 80; y++) b.set(x, y0 + y, z, Blocks.IRON_BLOCK.defaultBlockState()); });
 */
public final class Builds {
	private Builds() {}

	/** Handle passed to the job: set/fill/clear record the old blocks first. */
	public static final class B {
		final Structures.Op op;
		public final ServerLevel level;
		B(Structures.Op op, ServerLevel level) { this.op = op; this.level = level; }
		public B set(BlockPos p, BlockState s) { op.record(p); level.setBlock(p, s, Structures.FLAGS); return this; }
		public B set(int x, int y, int z, BlockState s) { return set(new BlockPos(x, y, z), s); }
		public B fill(BlockPos a, BlockPos c, BlockState s) {
			for (BlockPos p : BlockPos.betweenClosed(a, c)) set(p.immutable(), s);
			return this;
		}
		public B clear(BlockPos a, BlockPos c) { return fill(a, c, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()); }
		public int count() { return op.old.size(); }
	}

	/** In the player's dimension. */
	public static String build(String name, Consumer<B> job) { return build(Quick.world(), name, job); }

	public static String build(ServerLevel level, String name, Consumer<B> job) {
		Structures.Op op = new Structures.Op(name + "#" + (++Structures.opCount), level);
		B b = new B(op, level);
		try { job.accept(b); }
		catch (RuntimeException e) { if (!op.old.isEmpty()) Structures.restore(op); return name + " failed, rolled back: " + e; }
		op.rebound();
		op.markDry(4);
		Structures.push(op);
		return op.name + ": " + op.old.size() + " blocks, box " + op.box() + "  (undo: node tools/mc.mjs struct undo)";
	}
}
