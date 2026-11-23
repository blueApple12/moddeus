package dev.moddeus.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Block with a custom 3D model ("cubes"); its hitbox/collision follows the cubes (looked up live, so edits apply). */
public class ModelBlock extends Block {
	public static Map<Identifier, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	public ModelBlock(Properties p) { super(p); }

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		if (SHAPES == null) SHAPES = new ConcurrentHashMap<>();
		return SHAPES.getOrDefault(BuiltInRegistries.BLOCK.getKey(this), Shapes.block());
	}

	/** cubes: [[x1,y1,z1,x2,y2,z2], ...] in 0..16 pixels. */
	public static void setCubes(Identifier id, com.google.gson.JsonArray cubes) {
		if (SHAPES == null) SHAPES = new ConcurrentHashMap<>();
		VoxelShape s = Shapes.empty();
		for (var e : cubes) {
			var a = e.getAsJsonArray();
			s = Shapes.or(s, Block.box(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble(), a.get(4).getAsDouble(), a.get(5).getAsDouble()));
		}
		SHAPES.put(id, s.isEmpty() ? Shapes.block() : s);
	}
}
