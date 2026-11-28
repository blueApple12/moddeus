# Effects, potions, enchantments, food, attribute gear (tools/effects.mjs + live/Effects.java)

All live (~1 s), exportable (content JSON in moddeus/content, owned by --tab), no restart. The commands are PLUMBING (registry entry, icon, attributes, brewing, enchant tags) plus a small action library for simple stats. A signature effect (how it feels, looks and sounds) is the mod's own Java via `Effects.on` (below). Test on your stage, then `mc remove <id>` test ids.

## Commands
- `mc effect <id> --name "Frostbite" --color "#7fd8ff" --category harmful --attr "speed:-0.15:add_multiplied_total" --every 20 --tick "freeze:40; particle:snowflake:4" --attack "apply:slowness:3:1"`
  Status effect (live MobEffect). Attributes scale with amplifier (vanilla). Events: tick (every N ticks) start end attack hurt + `--dodge 0.1`/level.
  Icon: placeholder gem from --color; draw the real one: `mc pixels mob_effect <id>` (18x18). Give from Java: `Effects.give(entity, "<ns>:<id>", 30, 0)`.
- `mc potion <id> --effect "moddeus:frost:90,speed:60:1" --name Frost --brew awkward+blue_ice --long redstone --strong glowstone_dust`
  Potion + long_/strong_ variants; brewing recipes for potion, splash (gunpowder), lingering (dragon_breath); tipped arrows come free (crafting).
- `mc brew <id> --from awkward --with feather --to moddeus:float` | `--from glass_bottle --with x --to moddeus:item` any brewing-stand recipe.
- `mc enchant <id> --name Vampiric --items sword --max 3 --attack "lifesteal:0.15" --damage 1`
  Data-driven 26.3 enchantment, injected live into server + client registries with its tags (table, loot, trades; `--treasure`, `--curse`).
  --items: sword axe sharp melee weapon mining pickaxe bow crossbow trident mace fishing armor helmet chest legs boots durability spear | #tag | item.
  Vanilla parts: `--attr` (x level, while equipped), `--damage` (bonus x level), `--exclusive "#minecraft:exclusive_set/damage"`, `--effects '<raw effect components>'`.
  Java parts (x level): `--attack` `--hurt` (armor) `--tick` (every 10 ticks while in its slot) `--mine` `--dodge`.
  Datapack mod: `--dp <ModId>` writes `<ModId>-DP/data/<ns>/enchantment/<id>.json` + tags, vanilla-only (`--fn-attack ns:fn`, `--fn-tick ns:fn` run functions), injected live + /reload.
- Food (effects on eat, drinks, cures, teleport, golden foods): `mc food`, see FOOD.md.
- `mc attr <item> --attr "speed:0.1:add_multiplied_base@feet,reach:2,step:1,scale:-0.3,gravity:-0.04,jump:0.2"`
  attribute_modifiers (tool/armor stats are kept). Aliases: speed reach(block+entity) block_reach entity_reach step jump scale gravity health damage
  attack_speed armor toughness knockback_res luck safe_fall fall_damage mining mining_eff oxygen water_speed sneak burn, or any attribute id.
  Ops: add_value (default) add_multiplied_base add_multiplied_total. Slots: any mainhand offhand hand head chest legs feet armor body.
- `mc itemfx <item> --attack "25%lightning; apply:wither:3:1" --hurt "thorns:0.3" --held "magnet:6" --worn "self_apply:night_vision:12" --mine "area:1" --dodge 0.1`
  Behaviour on ANY item (vanilla too): lifesteal swords, thorns armor, passive held/worn powers, hammers (area), vein miners.
- Armor sets: `mc armor` + `mc setbonus` (GAMEPLAY.md). Trims: not covered yet.

## Actions (one string each, ";" separated; "25%" prefix = chance; *L = x level)
damage:N*L heal:N*L lifesteal:F*L thorns:F*L ignite:S*L freeze:T*L apply:effect:secs[:amp] self_apply:.. cleanse immune:poison,wither
levitate:V*L launch:V*L knockback:V*L pull:R repel:R magnet:R aura:N*L:R chain:N:R lightning explode:P fly no_fall feed:N xp:N teleport:R
vein:N area:R particle:id[:count] vfx:<mc vfx id> sound:id[:pitch] cmd:<command> (as self; {level} {amount} {other} = uuid).
Harmful actions hit the other entity (victim on attack, attacker on hurt), else self. The library is for plain stats (lifesteal %, thorns, a slow); the same two strings in every mod feel the same, so the effect's identity goes in Java.

## Java: the effect's own behaviour
`Effects.on("<ns>:<id>", event, (self, other, level, amount) -> ...)` runs after that content's JSON actions (the content must exist: `mc effect` / `mc enchant` / `mc itemfx`). Events: effect `tick start end attack hurt`; enchantment `attack hurt tick mine`; itemfx `attack hurt mine` (held/worn behaviour = your own server tick). `level` = amplifier+1 / enchant level, `amount` = damage. Register in your mod's `@OnReload` (re-registering replaces). Helpers: `Effects.give(e, id, secs, amp)`, `Effects.level(stack, "<ns>:<enchant>")`, `Effects.attr(e, "scale", 0.5, "add_multiplied_base")`, `Effects.run("chain:3:6", self, other, level, amount, null)` (one library action).
```java
@OnReload public static void hook() {
	enabled = true;
	// 1. Frostbite (effect tick): frost creeps in with the amplifier, shards crust on the body; at full freeze it cracks for damage
	Effects.on(NS + ":frostbite", "tick", (self, other, level, amount) -> {
		self.setTicksFrozen(Math.min(self.getTicksRequiredToFreeze() + 40, self.getTicksFrozen() + 6 * level));
		Bosses.fx(self.position().add(0, self.getBbHeight() * 0.5, 0), Vec3.ZERO, self, Bosses.L("shards", "rime_flake", "size", 0.2, "count", 3 + level, "duration", 12, "color", "#cff6ff"));
		if (self.isFullyFrozen() && self.tickCount % 40 == 0) Combat.damage(self, null, 2 * level, "frost");
	});
	// 2. Vampiric (enchant attack): heal a share of the hit, a blood thread drawn from victim to attacker
	Effects.on(NS + ":vampiric", "attack", (self, other, level, amount) -> {
		if (other == null) return;
		self.heal(amount * 0.08f * level);
		Vec3 a = other.getBoundingBox().getCenter(), b = self.getBoundingBox().getCenter();
		Bosses.fx(a, b.subtract(a), null, Bosses.L("beam", "blood_thread", "size", 0.06, "duration", 10, "color", "#a0102a", "fit", true, "alpha_end", 0));
	});
	// 3. Briar plate (itemfx hurt on a chestplate): 30% to yank the attacker in and wrap it in thorns
	Effects.on(NS + ":briar_chestplate", "hurt", (self, attacker, level, amount) -> {
		if (attacker == null || self.getRandom().nextFloat() > 0.3f) return;
		Combat.velocity(attacker, self.position().subtract(attacker.position()).normalize().scale(0.9));
		Combat.damage(attacker, self, amount * 0.5f, "shrapnel");
		Bosses.fx(attacker.position(), new Vec3(0, 1, 0), attacker, Bosses.L("spiral", "briar_vine", "size", 1.2, "duration", 20, "color", "#4a7a2a", "blend", "alpha"));
	});
}
```
(3 needs an itemfx entry: `mc itemfx <ns>:briar_chestplate --hurt "thorns:0.05"`.) Draw the textures (`mc pixels vfx rime_flake`), give each its own sound (SOUNDS.md).

## Make it unique to this mod
- Own icon (`mc pixels mob_effect`), own potion color, own VFX texture per effect, own sound on start/hit.
- Own mechanic: what does THIS effect change in play (creeping freeze, a tether, a yank), not only a number.
- Own timing (build-up, tick rate, ending beat); no two effects or enchants in the mod share a look.

## Test (never on the player)
`mc game stage <ModId>`, then `game run`: `Stage s = Stage.of("<ModId>"); var z = (LivingEntity) s.spawn("minecraft:husk", 0, 0, 5, "{}"); dev.moddeus.live.Effects.give(z, "<ns>:frostbite", 10, 1);`, `game stage <ModId> wait 60` (report: hp, effects). Enchants/itemfx: `s.hold(stack)` with an enchanted stack, `s.hit(z)`. Finish: `game stage <ModId> clear`.

## Limits
- Removing an effect/potion/enchantment stops its behaviour at once; the registry entry stays until restart. Clear stacks that carry it first (`clear @s <item>[enchantments=...]`).
- Enchant tags are bound live (table/loot/trades); villager trade pools refresh as vanilla does.
- Component edits reach NEW stacks only (old stacks keep their copy).
