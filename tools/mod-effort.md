# Mods effort levels (G/K screen "Mods:" pill). The bridge injects the block for the chat's level on every prompt.
# Edit freely: read on every prompt, no restart. Sections start with "## <level>".

## fast
MODS EFFORT = FAST (player picked speed). Build the core of the request in one pass: the 2-3 most important features, each working and textured, reusing engine building blocks. One quick web search only if the subject is unknown. Test each feature once. When done, say in one line that "Mods: Quality" builds the full researched version.

## balanced
MODS EFFORT = BALANCED. Before coding, research the subject briefly (1-3 web searches: what it is, its signature powers/items/rules). Build 4-5 features per main item/power, each with its OWN VFX and its real mechanic (no potion-effect stand-ins). Test every control. End with what was skipped and that "Mods: Quality" adds the full researched version.

## quality
MODS EFFORT = QUALITY (the level of the top AI-mod YouTubers, Roch Dev and Andrew Vikuk: about 1-2 hours of work for one mod; that is expected). Do ALL of these, in order:
1. RESEARCH before any code. Web-search and READ the source (wiki/fandom pages, episode/lore summaries) and write run/moddeus/research/<ModId>.md: the FULL roster (every character/alien/hero/stone/element the fans expect, e.g. Ben 10 = the original 10 aliens, X-Men = each hero), every power/move/item/place, the RULES of the world (what each thing really does), signature looks (download reference images and match silhouette + colors), sounds, iconic moments and famous quotes. Also search the best existing Minecraft mods/plugins of the same theme (Modrinth, CurseForge, Spigot; e.g. bending = ProjectKorra move lists) and AI-mod showcase videos; note their best ideas and plan to beat them.
2. PLAN in that file before coding: a checklist with, per feature, its canon reference, controls, real mechanic and VFX signature (VFX-DESIGN.md). Minimums:
   - Every power/element/character gets 6+ distinct moves (mobility, attack, ranged, defense, control, ultimate/combo), including hold-to-charge moves ("hold until the ring breaks, release") and a huge ultimate with real terrain destruction and a cinematic touch (letterbox bars, camera shake, custom sound).
   - Canon rules become REAL mechanics: energybending takes the target's bending away FOREVER (saved per player); a glider = real gliding flight; a "state"/transformation = its own unique mechanic + transformation animation + new look, never just a stat buff. A potion effect, buff or debuff is never the whole of an ability whose canon effect is something else.
   - Survival progression woven into vanilla: key items drop from fitting vanilla bosses/mobs/structures (a stone from the Wither, the Warden, the Ender Dragon...), altars/rituals, craftable main item from mod materials; the source's own items as extras.
   - Non-combat subjects (sports, GTA, cozy games) become PLAYABLE game loops: rules, scoring, missions (chase, heist...), NPCs, a HUD.
   - A training dummy (and an optional placeable showcase/practice arena item, never auto-built near the player) so every move can be tried.
3. Build everything on the checklist: drawn textures, real 3D models, own sounds, own VFX per move.
4. Test each feature on your Stage, then SELF-REVIEW against the research file: tick off every item; anything missing, weaker than canon or looking generic gets fixed before you say done.
5. Final reply: what you made, how to get each thing in survival, controls in short lines, and anything from canon still missing.

## max
MODS EFFORT = MAX. Everything in QUALITY, plus:
- Research deeper: several sources per subject, multiple versions (armored/unarmored, movie vs comic), fan-favourite moments and memes the player will expect. Subagents may research in parallel.
- Scope like a big standalone mod: extra bosses and rematches, NPCs, structures/biomes or a dimension, loot, a guide book or GUI, achievements, boss-vs-boss fights where the source has rivals.
- After everything works, run a "wow pass": list the 3 moments that would make a viewer say "no way" and build them. Then a polish pass: Studio renders of every model, sound for every action, no leftover VFX, lag check (node tools/mc.mjs game lag).
