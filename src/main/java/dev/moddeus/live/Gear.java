package dev.moddeus.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Armor, tools, blocks and other gear for content JSON (tools/gear.mjs writes it). ContentLoader delegates here with one line each
 * in item() / block(); null = not ours, ContentLoader goes on as before.
 *  item: "tool": {type: sword|pickaxe|axe|shovel|hoe|paxel|hammer|excavator, tier, durability, mining_speed, damage_bonus, enchant, repair, damage, speed}
 *        "armor": {type, material, asset, defense, toughness, knockback, enchant, durability (x vanilla per-piece base), repair, sound}
 *        "gear": {kind: book} (WrittenBookItem: right-click reads its written_book_content)
 *        "worn": [{bone: head|body|right_arm|left_arm|right_leg|left_leg, model: "moddeus:x"}] (3D parts drawn on the wearer, client/GearClient)
 *  block shape: lantern | torch (+ <id>_wall) | barrel (container with the vanilla 27-slot GUI)
 */
public final class Gear {
	/** item id -> worn 3D parts; read by client/GearClient every frame. */
	public static Map<Identifier, JsonArray> WORN = new HashMap<>();

	/** Content types that register nothing: "reskin" = a vanilla item with our item_model (gear.mjs reskin; given via recipe or /give). */
	public static boolean load(String id, JsonObject o) { return str(o, "type", "").matches("reskin|gear_part"); } // gear_part = keeps a helper block (torch <id>_wall) registered

	public static Item item(String id, JsonObject o, Item.Properties props) {
		if (WORN == null) WORN = new HashMap<>();
		Identifier key = LiveRegistry.id(id);
		if (o.has("worn") && o.get("worn").isJsonArray()) WORN.put(key, o.getAsJsonArray("worn")); else WORN.remove(key);
		String kind = o.has("gear") ? str(o.getAsJsonObject("gear"), "kind", "") : "";
		if (kind.equals("book")) return LiveRegistry.register(BuiltInRegistries.ITEM, key, k -> new net.minecraft.world.item.WrittenBookItem(props.setId(k)));
		JsonObject t = obj(o, "tool"), a = obj(o, "armor");
		boolean tool = t != null && (t.has("tier") || t.has("durability") || t.has("mining_speed") || t.has("enchant") || t.has("repair") || t.has("damage_bonus")
			|| str(t, "type", "").matches("paxel|hammer|excavator"));
		boolean armor = a != null && (a.has("defense") || a.has("toughness") || a.has("knockback") || a.has("enchant") || a.has("repair") || a.has("durability") || a.has("sound"));
		if (!tool && !armor) return null;
		return LiveRegistry.register(BuiltInRegistries.ITEM, key, k -> LiveRegistry.thawed(BuiltInRegistries.BLOCK, () -> {
			if (tool) tool(props, t); else armor(props, a);
			return new Item(props.setId(k));
		}));
	}

	static void tool(Item.Properties p, JsonObject t) {
		ToolMaterial base = toolMaterial(str(t, "tier", str(t, "material", "iron")));
		TagKey<Item> repair = t.has("repair") ? TagKey.create(Registries.ITEM, Identifier.parse(str(t, "repair", ""))) : base.repairItems();
		ToolMaterial m = new ToolMaterial(base.incorrectBlocksForDrops(), num(t, "durability", base.durability()).intValue(),
			num(t, "mining_speed", base.speed()).floatValue(), num(t, "damage_bonus", base.attackDamageBonus()).floatValue(),
			num(t, "enchant", base.enchantmentValue()).intValue(), repair);
		String type = str(t, "type", "sword");
		float[] d = switch (type) {
			case "pickaxe" -> new float[]{1, -2.8f}; case "axe" -> new float[]{6, -3.1f}; case "shovel" -> new float[]{1.5f, -3}; case "hoe" -> new float[]{-2, -1};
			case "paxel" -> new float[]{5, -3}; case "hammer" -> new float[]{7, -3.4f}; case "excavator" -> new float[]{5.5f, -3.3f}; default -> new float[]{3, -2.4f};
		};
		float dmg = num(t, "damage", d[0]).floatValue(), spd = num(t, "speed", d[1]).floatValue();
		switch (type) {
			case "pickaxe", "hammer" -> p.pickaxe(m, dmg, spd); // hammer = 3x3 via the itemfx "mine area" spec gear.mjs writes
			case "axe" -> p.axe(m, dmg, spd);
			case "shovel", "excavator" -> p.shovel(m, dmg, spd);
			case "hoe" -> p.hoe(m, dmg, spd);
			case "paxel" -> { // pickaxe + axe + shovel blocks (tag data/moddeus/tags/block/mineable/paxel.json), strips logs like an axe
				p.tool(m, TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("moddeus", "mineable/paxel")), dmg, spd, 5);
				p.delayedHolderComponent(DataComponents.BLOCK_TRANSFORMER, net.minecraft.world.item.component.BlockTransformers.AXE);
			}
			default -> p.sword(m, dmg, spd);
		}
	}

	static void armor(Item.Properties p, JsonObject a) {
		ArmorMaterial base = armorMaterial(str(a, "material", "iron"));
		ArmorType type = ArmorType.valueOf(str(a, "type", "helmet").toUpperCase());
		Map<ArmorType, Integer> def = new EnumMap<>(ArmorType.class);
		def.putAll(base.defense());
		if (a.has("defense")) def.put(type, a.get("defense").getAsInt());
		var sound = a.has("sound") ? BuiltInRegistries.SOUND_EVENT.get(Identifier.parse(str(a, "sound", ""))).map(h -> (net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>) h).orElse(base.equipSound()) : base.equipSound();
		TagKey<Item> repair = a.has("repair") ? TagKey.create(Registries.ITEM, Identifier.parse(str(a, "repair", ""))) : base.repairIngredient();
		ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> asset = a.has("asset") ? ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.parse(str(a, "asset", ""))) : base.assetId();
		p.humanoidArmor(new ArmorMaterial(num(a, "durability", base.durability()).intValue(), def, num(a, "enchant", base.enchantmentValue()).intValue(), sound,
			num(a, "toughness", base.toughness()).floatValue(), num(a, "knockback", base.knockbackResistance()).floatValue(), repair, asset), type);
	}

	/** ContentLoader.block() delegate for shapes vanilla needs special classes for. */
	public static Item block(String id, JsonObject o, BlockBehaviour.Properties p, String shape, Item.Properties ip) {
		Identifier key = LiveRegistry.id(id);
		switch (shape) {
			case "lantern" -> {
				Block b = LiveRegistry.register(BuiltInRegistries.BLOCK, key, k -> new net.minecraft.world.level.block.LanternBlock(p.noOcclusion().forceSolidOn().pushReaction(net.minecraft.world.level.material.PushReaction.POPPED).setId(k)));
				return LiveRegistry.register(BuiltInRegistries.ITEM, key, k -> new BlockItem(b, ip.useBlockDescriptionPrefix().setId(k)));
			}
			case "torch" -> {
				var flame = particle(str(o, "particle", "flame"));
				p.noCollision().instabreak().pushReaction(net.minecraft.world.level.material.PushReaction.POPPED);
				Block b = LiveRegistry.register(BuiltInRegistries.BLOCK, key, k -> new net.minecraft.world.level.block.TorchBlock(flame, p.setId(k)));
				Block w = LiveRegistry.register(BuiltInRegistries.BLOCK, LiveRegistry.id(id + "_wall"), k -> new net.minecraft.world.level.block.WallTorchBlock(flame,
					BlockBehaviour.Properties.ofFullCopy(b).overrideLootTable(b.getLootTable()).overrideDescription(b.getDescriptionId()).setId(k)));
				return LiveRegistry.register(BuiltInRegistries.ITEM, key, k -> new net.minecraft.world.item.StandingAndWallBlockItem(b, w, net.minecraft.core.Direction.DOWN, ip.useBlockDescriptionPrefix().setId(k)));
			}
			case "barrel" -> {
				Block b = LiveRegistry.register(BuiltInRegistries.BLOCK, key, k -> new net.minecraft.world.level.block.BarrelBlock(p.setId(k)));
				allowBlockEntity(net.minecraft.world.level.block.entity.BlockEntityTypes.BARREL, b);
				return LiveRegistry.register(BuiltInRegistries.ITEM, key, k -> new BlockItem(b, ip.useBlockDescriptionPrefix().setId(k)));
			}
			default -> { return null; }
		}
	}

	/** A vanilla block entity type only accepts its own blocks: add ours to validBlocks (immutable set: replaced). */
	static void allowBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, Block b) {
		try {
			var f = net.minecraft.world.level.block.entity.BlockEntityType.class.getDeclaredField("validBlocks");
			f.setAccessible(true);
			@SuppressWarnings("unchecked") Set<Block> cur = (Set<Block>) f.get(type);
			if (cur.contains(b)) return;
			Set<Block> next = new HashSet<>(cur);
			next.add(b);
			f.set(type, next);
		} catch (Throwable e) { dev.moddeus.Moddeus.LOG.error("Gear: can't add {} to {}", b, type, e); }
	}

	static net.minecraft.core.particles.SimpleParticleType particle(String n) {
		var t = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(n.contains(":") ? n : "minecraft:" + n));
		return t instanceof net.minecraft.core.particles.SimpleParticleType s ? s : net.minecraft.core.particles.ParticleTypes.FLAME;
	}

	static ToolMaterial toolMaterial(String n) {
		return switch (n) {
			case "wood", "wooden" -> ToolMaterial.WOOD; case "stone" -> ToolMaterial.STONE; case "copper" -> ToolMaterial.COPPER;
			case "gold", "golden" -> ToolMaterial.GOLD; case "diamond" -> ToolMaterial.DIAMOND; case "netherite" -> ToolMaterial.NETHERITE; default -> ToolMaterial.IRON;
		};
	}

	static ArmorMaterial armorMaterial(String n) {
		return switch (n) {
			case "leather" -> ArmorMaterials.LEATHER; case "copper" -> ArmorMaterials.COPPER; case "chainmail" -> ArmorMaterials.CHAINMAIL;
			case "gold", "golden" -> ArmorMaterials.GOLD; case "diamond" -> ArmorMaterials.DIAMOND; case "netherite" -> ArmorMaterials.NETHERITE; default -> ArmorMaterials.IRON;
		};
	}

	static JsonObject obj(JsonObject o, String k) { JsonElement e = o.get(k); return e != null && e.isJsonObject() ? e.getAsJsonObject() : null; }
	static String str(JsonObject o, String k, String d) { JsonElement e = o.get(k); return e == null ? d : e.getAsString(); }
	static Number num(JsonObject o, String k, Number d) { JsonElement e = o.get(k); return e == null ? d : e.getAsNumber(); }
}
