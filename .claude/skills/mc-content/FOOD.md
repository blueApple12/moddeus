# Food, drinks, feasts, crops, cooking (tools/food.mjs + live/Food.java + client/FoodTooltip.java)

Every food is vanilla components (`minecraft:food` + `minecraft:consumable` + extras), so it runs live, in datapacks (`--give`) and in exported jars. Values below come from the 26.3 game itself. Researched against Farmer's Delight (feasts, meals, bowls), AppleSkin (tooltip values), Spice of Life (variety) and Croptopia (crop loop, `c:` tags).

## Numbers that matter
- `nutrition` = hunger points (1 drumstick = 2). `saturation` is ABSOLUTE in 26.3 components. Vanilla builds it as nutrition x modifier x 2: modifier 0.1 poor, 0.3 low (apple), 0.6 normal (bread), 0.8 good (steak), 1.2 great (golden carrot).
- `mc item --food 6,0.8` and `mc crop --food 4,0.3` take the MODIFIER (6,0.8 = 6 hunger, 9.6 sat). `mc food --saturation` is absolute, `--sat-mod` is the modifier.
- Reference: cookie 2/0.4, apple 4/2.4, bread 5/6, cooked salmon 6/9.6, steak 8/12.8, golden carrot 6/14.4, stews 6/7.2, rabbit stew 10/12. Eat time 1.6 s (kelp 0.8 s, honey 2 s).
- Balance: a crop food sits at apple-to-bread level; a cooked meal at steak level; anything above 10 hunger or 15 saturation needs a cost (rare drop, many ingredients, a feast).

## Basic food (fruit, bread, meat, fish, snack)
- Good: hunger and saturation fit how hard it is to get; an appetizing sprite; a cooked version that beats the raw one.
- `mc food tomato --nutrition 4 --sat-mod 0.3 --texture <src>` (or `--color "#e33"` for a placeholder sprite). `--like <kind>` (snack fruit bread meat fish raw poison fast soup meal drink honey milk golden enchanted chorus) = sample values only: read them in `tools/food.mjs`, then set this food's own numbers and effects.
- JSON: `components["minecraft:food"] = {nutrition, saturation, can_always_eat}`, `components["minecraft:consumable"] = {consume_seconds, animation: "eat", sound, has_consume_particles, on_consume_effects}`.
- Pitfall: `minecraft:food` alone does nothing in 26.3; the item needs `consumable` too (`mc food` always writes both). The old content key `"food"` still works; `mc food` replaces it with components.

## Fast food
- Good: short eat time for small values (kelp: 1 hunger in 0.8 s). Keep values low, or it outclasses everything.
- `mc food snack_bar --like fast` or `--fast` / `--eat 0.8` on any food.

## Raw and risky food
- Good: a chance of a bad effect makes cooking worth it (raw chicken: 30% Hunger 30 s; spider eye: Poison 5 s).
- `mc food raw_eel --like raw` | `--effects "hunger:30@0.3,nausea:8@0.3"` (`id:seconds:amplifier@chance`; same chance = one group).

## Soup, stew, meal (bowl)
- Good: returns the bowl, stacks to 1 like vanilla stews; Farmer's Delight meals stack to 16 and give more.
- `mc food pumpkin_soup --like soup --color "#e08a2a"` | `--like meal` | `--bowl` on any food.
- JSON: `"minecraft:use_remainder": {"id": "minecraft:bowl", "count": 1}`, `"minecraft:max_stack_size": 1`.
- Pitfall: with a stack above 1, each eaten one still returns a bowl (vanilla handles it); the bowl drops if the inventory is full.

## Drink (bottle, juice, potion-like)
- Good: drink animation (arm to mouth, no crumbs), the drink sound, the empty bottle back, stack 16 like honey. A drink without nutrition can be drunk at full hunger.
- `mc food apple_juice --like drink --color "#f2b33d" --effects speed:20` | `--drink --bottle` on any food.
- JSON: `consumable.animation: "drink"`, `sound: "minecraft:entity.generic.drink"`, `has_consume_particles: false`, remainder `glass_bottle`.
- Pitfall: an `eat` animation on a drink looks wrong (crumb particles). `mc food --drink` turns particles off.

## Special effects (cure, milk, chorus, heal, buffs)
- Honey: `--like honey` (2 s, honey sound, cures poison, bottle). Milk: `--like milk` (clears all effects, bucket). Chorus: `--like chorus` (random teleport 16, 1 s cooldown).
- Any food: `--remove poison,wither`, `--clear`, `--teleport 16`, `--cooldown 2` (`minecraft:use_cooldown`), heal = `--effects instant_health:0:1`.
- Vanilla commands on eat: `--run "summon lightning_bolt ~ ~ ~; effect give @s glowing 5"` writes a `consume_item` advancement + function (runs as the eater, revokes itself so it fires every time). Files: `data/<ns>/advancement/eat/<id>.json`, `function/eat/<id>.mcfunction`. Vanilla commands only (Moddeus has no slash commands); a real ability on eat is Java (next section).
- Pitfall: on-eat commands run as the player at their position; use `execute as @s at @s` only for nested selectors.

## On-eat abilities in Java (write the food's own)
In the mod's class (`content/<Id>.java`, skeleton GAME-RUN.md), from its server tick: the bite lands the tick after `getUseItemRemainingTicks() == 1`.
```java
for (ServerPlayer p : s.getPlayerList().getPlayers()) {
  if (!p.isUsingItem() || p.getUseItemRemainingTicks() != 1) continue;
  switch (BuiltInRegistries.ITEM.getKey(p.getUseItem().getItem()).toString()) {
    case "spice_mod:dragon_pepper" -> breathe(p);   // 3 s fire cone where the player looks
    case "spice_mod:echo_berry" -> Combat.enemies(p, p.position(), 24).forEach(e -> e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200), p));
    case "spice_mod:pop_rock" -> { for (int k = 1; k <= 4; k++) Quick.after(k * 7, () -> Combat.push(p, new Vec3(0, 0.35, 0))); } // crackling hops
  }
}
static void breathe(ServerPlayer p) {
  Bosses.job(p, 60, (j, age) -> {
    Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
    for (LivingEntity e : Combat.enemies(p, eye.add(look.scale(3)), 3)) { e.igniteForSeconds(3); if (age % 10 == 0) Combat.damage(e, p, 2, "fire"); }
  });
}
```
**Make it unique to this mod:** [ ] numbers balanced for this mod's loop, not copied from a `--like` sample [ ] its own sprite (PIXEL-ART.md) and eat/drink sound [ ] an on-eat effect that fits the food (a pepper burns, a berry reveals) [ ] its own VFX look (VFX-DESIGN.md) [ ] two foods never share an effect.

## Golden tier
- Good: always edible, strong short buffs, rarity color, glint on the top tier, a costly recipe (8 gold around it).
- `mc food golden_berry --like golden` (Regeneration II 5 s + Absorption 2 min, like the golden apple) | `--like enchanted` (Regen II 20 s, Resistance 5 min, Fire Res 5 min, Absorption IV 2 min, glint, rare).
- JSON: `"minecraft:enchantment_glint_override": true`, `"minecraft:rarity": "rare"`.

## Feast, pie, cake (placeable, slices)
- Good (Farmer's Delight): a block you place on a table; each right-click eats a slice, or with `--serving` a bowl takes a meal portion (4 servings is the norm); a leftover plate you clear with a click; the model shrinks with every slice; breaking an untouched one gives it back, a started one gives nothing (like cake).
- `mc feast berry_pie --style pie --servings 4 --food 3,0.6 --color "#8e1f3d" --base "#d99a4e"` (eat slices).
- `mc feast roast_feast --servings 4 --food 8,0.8 --serving roast_serving --container bowl --leftover --leftover-drop bone_meal` (FD feast: makes the `roast_serving` meal item).
- JSON (content type `feast`, re-read on every click): `servings` 1-7 (1-6 with leftover), `food {nutrition, saturation, always}`, `effects [{id, duration, amplifier, probability}]`, `actions` ("heal:2; vfx:x", Effects actions), `serving`, `container`, `leftover`, `leftover_drop`.
- Files: `<id>_top/_side/_inner/_bottom(/_plate).png`; the model shows the side texture's bottom rows (cake 8 px, pie 5 px), so the frosting/crust band goes on row 8 (cake) or 11 (pie).
- Pitfalls: the hitbox is the vanilla cake's (8 px); it needs a solid block below; it stacks to 1 (`"stack"` in JSON changes that); a NEW id is needed to change `servings` beyond 7.

## Crops, seeds, farming loop
- Good (Croptopia/FD): seeds plant on farmland, grow in light, bone meal works, ripe drops 1-3 produce + seeds with Fortune bonus, unripe gives the seed back; farmer villagers plant it, chickens breed on the seeds, bees speed it up, the produce composts (65%), other mods see it via `c:seeds`.
- `mc crop tomato --stages 4 --color "#e33" --food 4,0.3 --count 1-3` then `mc cook tomato --result roasted_tomato` and a food for the result.
- Tags written: block `minecraft:crops`, `maintains_farmland`, `bee_growables`; item `minecraft:villager_plantable_seeds`, `chicken_food`, `c:seeds`.
- Pitfalls: seeds are block items, so content `components` (compost) don't apply to them; seeds don't drop from grass yet (`mc drop` on `minecraft:short_grass` if wanted).

## Cooking recipes
- Good: every cookable food works in furnace, smoker AND campfire, with the vanilla food XP (0.35).
- `mc cook moddeus:raw_eel --result cooked_eel [--xp 0.35] [--time 200] [--only smelt,smoke,campfire]`: 200 / 200 / 600 ticks (the 26.3 data values), category `food`, files `data/moddeus/recipe/<result>_from_<type>.json`.
- Crafted meals: `mc recipe <id> --shapeless "bowl,carrot,potato,moddeus:tomato" --result moddeus:veggie_stew`.

## Compost, stack, tooltip
- `--compost 0.3|0.5|0.65|0.85|1` = `minecraft:compostable` (`{"layers": "minecraft:compostable/medium"}`; vanilla: seeds 0.3, crops 0.65, pie 1).
- `--stack N` = `minecraft:max_stack_size` (live, unlike `mc item --stack`).
- Tooltips: every food (vanilla and ours) shows `+N Hunger +S Saturation`, eat time when not 1.6 s, each consume effect with its chance, cures, teleport, and feast servings (`client/FoodTooltip`, AppleSkin-style, on by default).

## Textures (food must look tasty)
- Warm, saturated ramps (3-4 shades + highlight); light from the top-left; a glossy 1-2 px highlight on wet or shiny food (fruit skin, stew surface, glaze).
- Outline with the darkest shade of the food, never black. Cooked = browner, darker edges, grill marks; raw = pinker, flatter.
- Bowls: rim highlight row, contents slightly above the rim, 2-3 chunks; drinks: glass outline, liquid below the shoulder, a white glint on the left; golden: yellow ramp + 1-2 white sparkles.
- `mc food` draws a procedural placeholder (bowl / bottle / round food); redraw it with `mc pixels item <id>` (PIXEL-ART.md). Check the shape with `mc template item/apple`, `item/mushroom_stew`, `item/honey_bottle`.

## Make it amazing (checklist per kind)
- Basic: cooked > raw values; a recipe; the right creative tab (`--tab food` or the mod's).
- Drink: drink animation + sound, no crumbs, bottle back, stack 16.
- Soup/meal: bowl back, stack 1 (stew) or 16 (meal).
- Golden: glint or rarity color, always edible, buffs in the tooltip.
- Feast: model shrinks per slice, serving sound, plate leftover, untouched drops itself.
- Crop: 4+ distinct stage textures (sprout -> leaves -> flowers -> fruit), produce visible on the last stage, seeds look like seeds.
- Test on your Stage (TESTING.md): `s.hold("moddeus:<id>").use()` / `s.release(32)`, read `s.actor().getFoodData()`, the remainder in hand; look with `game studio item <id>`. Never give it to the player. Then check hunger, the remainder and the tooltip; screenshot a feast after each slice.
