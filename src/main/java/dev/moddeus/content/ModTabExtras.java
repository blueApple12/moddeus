package dev.moddeus.content;

import dev.moddeus.live.LiveRegistry;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

/**
 * What a mod's content brings into its creative tab besides plain items (ModTab.stacks): a mob's spawn egg, a potion in
 * every form (potion, splash, lingering, tipped arrow; long_/strong_ variants too) and an enchantment's book (max level).
 */
public final class ModTabExtras {
	private ModTabExtras() {}

	static final Item[] POTION_FORMS = {Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW};

	public static void add(List<ItemStack> out, Identifier id) {
		try {
			BuiltInRegistries.ITEM.getOptional(id.withSuffix("_spawn_egg")).ifPresent(i -> out.add(new ItemStack(i)));
			for (String pre : new String[] {"", "long_", "strong_"})
				BuiltInRegistries.POTION.get(id.withPrefix(pre)).ifPresent(h -> { for (Item it : POTION_FORMS) out.add(PotionContents.createItemStack(it, h)); });
			var s = LiveRegistry.server;
			if (s != null) s.registryAccess().lookup(Registries.ENCHANTMENT).flatMap(r -> r.get(ResourceKey.create(Registries.ENCHANTMENT, id)))
				.ifPresent(h -> out.add(EnchantmentHelper.createBook(new EnchantmentInstance(h, h.value().getMaxLevel()))));
		} catch (Throwable t) {
			dev.moddeus.Moddeus.LOG.warn("tab extras {}: {}", id, t.toString());
		}
	}
}
