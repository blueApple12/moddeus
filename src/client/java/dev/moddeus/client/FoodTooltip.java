package dev.moddeus.client;

import dev.moddeus.live.Food;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.consume_effects.RemoveStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;

import java.util.List;

/** AppleSkin-style food line on every food tooltip (vanilla + mods): hunger, saturation, eat time, consume effects with chances; feasts: servings. */
public final class FoodTooltip {
	public static volatile boolean enabled = true;

	@OnReload
	public static void hook() {
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.foodtooltip", Boolean.TRUE) == null)
			net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register(FoodTooltip::tip);
	}

	@OnUnload
	public static void unhook() { enabled = false; }

	public static void tip(ItemStack stack, Item.TooltipContext ctx, TooltipFlag flag, List<Component> lines) {
		if (!enabled) return;
		try {
			var food = stack.get(DataComponents.FOOD);
			var use = stack.get(DataComponents.CONSUMABLE);
			if (food != null) lines.add(values(food.nutrition(), food.saturation(), food.canAlwaysEat()));
			if (use != null && (food != null || !use.onConsumeEffects().isEmpty())) {
				if (Math.abs(use.consumeSeconds() - 1.6f) > 0.01f)
					lines.add(Component.literal((use.animation() == net.minecraft.world.item.ItemUseAnimation.DRINK ? "Drink" : "Eat") + " time " + trim(use.consumeSeconds()) + "s" + (use.consumeSeconds() < 1.6f ? " (fast)" : "")).withStyle(ChatFormatting.GRAY));
				for (ConsumeEffect e : use.onConsumeEffects()) effect(e, lines);
			}
			if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof Food.FeastBlock fb) {
				var s = fb.spec();
				int n = Food.FeastBlock.servings(s);
				lines.add(Component.literal("Place it: " + n + " serving" + (n == 1 ? "" : "s") + (s.has("serving") ? " (use a bowl)" : "")).withStyle(ChatFormatting.GRAY));
				if (!s.has("serving") && s.has("food")) { var f = s.getAsJsonObject("food"); lines.add(values(f.has("nutrition") ? f.get("nutrition").getAsInt() : 2, f.has("saturation") ? f.get("saturation").getAsFloat() : 0.4f, false).append(Component.literal(" each").withStyle(ChatFormatting.GRAY))); }
			}
		} catch (Throwable t) { /* tooltips must never crash a screen */ }
	}

	static MutableComponent values(int nutrition, float saturation, boolean always) {
		MutableComponent c = Component.literal("+" + nutrition + " Hunger").withStyle(ChatFormatting.GOLD)
			.append(Component.literal("  +" + trim(saturation) + " Saturation").withStyle(ChatFormatting.YELLOW));
		if (always) c.append(Component.literal("  always edible").withStyle(ChatFormatting.DARK_GREEN));
		return c;
	}

	static void effect(ConsumeEffect e, List<Component> lines) {
		if (e instanceof ApplyStatusEffectsConsumeEffect a) {
			String chance = a.probability() < 1f ? Math.round(a.probability() * 100) + "% " : "";
			for (MobEffectInstance i : a.effects()) {
				MutableComponent c = Component.literal(chance).append(Component.translatable(i.getDescriptionId()));
				if (i.getAmplifier() > 0) c.append(" ").append(Component.translatable("potion.potency." + i.getAmplifier()));
				if (!i.getEffect().value().isInstantaneous()) c.append(" (" + (i.getDuration() / 20 / 60) + ":" + String.format("%02d", i.getDuration() / 20 % 60) + ")");
				lines.add(c.withStyle(i.getEffect().value().getCategory() == MobEffectCategory.HARMFUL ? ChatFormatting.RED : ChatFormatting.BLUE));
			}
		} else if (e instanceof RemoveStatusEffectsConsumeEffect r) {
			MutableComponent c = Component.literal("Cures ");
			boolean first = true;
			for (var h : r.effects()) { if (!first) c.append(", "); c.append(Component.translatable(h.value().getDescriptionId())); first = false; }
			lines.add(c.withStyle(ChatFormatting.GREEN));
		} else if (e instanceof ClearAllStatusEffectsConsumeEffect) lines.add(Component.literal("Clears all effects").withStyle(ChatFormatting.GREEN));
		else if (e instanceof TeleportRandomlyConsumeEffect t) lines.add(Component.literal("Teleports you up to " + trim(t.diameter() / 2) + " blocks").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	static String trim(float f) { return f == Math.round(f) ? String.valueOf(Math.round(f)) : String.format(java.util.Locale.ROOT, "%.1f", f); }

	private FoodTooltip() {}
}
