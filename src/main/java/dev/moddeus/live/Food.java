package dev.moddeus.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.moddeus.Moddeus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Food family runtime (tools/food.mjs, guide mc-content FOOD.md).
 * - type "feast": placeable cake / pie / Farmer's Delight style feast. JSON: servings 1-7, food {nutrition, saturation, always},
 *   effects [{id, duration ticks, amplifier, probability}], actions "heal:4; vfx:x" (Effects.run), serving "moddeus:x" + container
 *   "minecraft:bowl" (right-click with the container takes a serving instead of eating), leftover true (last state = plate,
 *   right-click clears it and drops leftover_drop).
 * Specs are re-read on every use, so JSON edits apply live.
 */
public final class Food {
	static Map<String, JsonObject> feasts;

	public static Map<String, JsonObject> feasts() { if (feasts == null) feasts = new HashMap<>(); return feasts; }

	/** ContentLoader: content entries of type "feast". */
	public static boolean load(String id, JsonObject o) {
		if (!"feast".equals(Effects.str(o, "type", ""))) return false;
		feasts().put(id, o);
		Block b = LiveRegistry.register(BuiltInRegistries.BLOCK, LiveRegistry.id(id), k -> new FeastBlock(BlockBehaviour.Properties.of()
			.forceSolidOn().strength(0.5f).sound(SoundType.WOOL).pushReaction(net.minecraft.world.level.material.PushReaction.POPPED).setId(k)));
		LiveRegistry.register(BuiltInRegistries.ITEM, LiveRegistry.id(id), k -> new BlockItem(b, new Item.Properties().stacksTo(Effects.num(o, "stack", 1)).useBlockDescriptionPrefix().setId(k)));
		return true;
	}

	static Item itemOf(String id) { return BuiltInRegistries.ITEM.getValue(Identifier.parse(Effects.ns(id))); }

	/** Cake-like block: BITES 0..servings-1 = servings left, BITES = servings = leftover plate (when "leftover"). */
	public static final class FeastBlock extends CakeBlock {
		public FeastBlock(BlockBehaviour.Properties p) { super(p); }

		public JsonObject spec() { return feasts().getOrDefault(BuiltInRegistries.BLOCK.getKey(this).getPath(), new JsonObject()); }

		public static int servings(JsonObject s) { return Math.max(1, Math.min(s.has("leftover") ? 6 : 7, Effects.num(s, "servings", 4))); }

		boolean isLeftover(BlockState state, JsonObject s) { return s.has("leftover") && state.getValue(BITES) >= servings(s); }

		@Override
		protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
			JsonObject s = spec();
			if (!s.has("serving") || isLeftover(state, s) || !stack.is(itemOf(Effects.str(s, "container", "minecraft:bowl")))) return InteractionResult.TRY_WITH_EMPTY_HAND;
			if (!level.isClientSide()) {
				ItemStack out = new ItemStack(itemOf(Effects.str(s, "serving", "")));
				stack.consume(1, player);
				if (!player.getInventory().add(out)) Block.popResource(level, pos, out);
				level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.BLOCKS, 1f, 1f); // Farmer's Delight serving sound
				next(level, pos, state, s);
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			JsonObject s = spec();
			if (isLeftover(state, s)) { // clear the plate
				if (!level.isClientSide()) {
					level.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 0.8f, 0.8f);
					level.destroyBlock(pos, false);
					if (s.has("leftover_drop")) Block.popResource(level, pos, new ItemStack(itemOf(Effects.str(s, "leftover_drop", "minecraft:bowl"))));
				}
				return InteractionResult.SUCCESS;
			}
			if (s.has("serving")) {
				if (level.isClientSide()) return InteractionResult.SUCCESS;
				player.sendOverlayMessage(Component.translatableWithFallback("moddeus.feast.use_container", "Use a %s to take a serving", Component.translatable(itemOf(Effects.str(s, "container", "minecraft:bowl")).getDescriptionId())));
				return InteractionResult.CONSUME;
			}
			JsonObject f = s.has("food") ? s.getAsJsonObject("food") : new JsonObject();
			if (!player.canEat(f.has("always") && f.get("always").getAsBoolean())) return InteractionResult.PASS;
			if (!level.isClientSide()) {
				player.getFoodData().eat(new net.minecraft.world.food.FoodProperties(Effects.num(f, "nutrition", 2), (float) Effects.dnum(f, "saturation", 0.4), false));
				if (s.has("effects")) for (JsonElement e : s.getAsJsonArray("effects")) {
					JsonObject eo = e.getAsJsonObject();
					var h = Effects.effect(Effects.str(eo, "id", ""));
					if (h != null && level.getRandom().nextFloat() < Effects.dnum(eo, "probability", 1)) player.addEffect(new MobEffectInstance(h, Effects.num(eo, "duration", 200), Effects.num(eo, "amplifier", 0)));
				}
				if (s.has("actions")) for (String a : Effects.str(s, "actions", "").split(";")) Effects.run(a.trim(), player, null, 1, 0, pos);
				level.playSound(null, pos, SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 1f, 0.9f + level.getRandom().nextFloat() * 0.2f);
				level.gameEvent(player, GameEvent.EAT, pos);
				player.awardStat(net.minecraft.stats.Stats.EAT_CAKE_SLICE);
				next(level, pos, state, s);
			}
			return InteractionResult.SUCCESS;
		}

		static void next(Level level, BlockPos pos, BlockState state, JsonObject s) {
			int b = state.getValue(BITES) + 1;
			if (b < servings(s) || s.has("leftover")) level.setBlock(pos, state.setValue(BITES, b), 3);
			else { level.removeBlock(pos, false); level.gameEvent(null, GameEvent.BLOCK_DESTROY, pos); }
		}
	}

	private Food() {}
}
