// Effects, potions, brewing, enchantments, attribute gear, on-hit/held/worn item behaviours (live, exportable).
// Data: src/main/resources/moddeus/content/<id>.json, run by core live/Effects.java. Dispatched from tools/mc.mjs. Guide: .claude/skills/mc-content/EFFECTS.md
//   effect <id> [--name ..] [--color #7fd8ff] [--category beneficial|harmful|neutral] [--attr "movement_speed:-0.15:add_multiplied_total,scale:-0.5"]
//        [--every 20] [--tick "freeze:40; damage:1"] [--start ..] [--end ..] [--attack "lifesteal:0.2"] [--hurt "thorns:0.5"] [--dodge 0.1]
//        -> /effect give @s moddeus:<id>; icon: pixels mob_effect <id> (18x18; a placeholder glyph is made from --color)
//   potion <id> --effect "moddeus:frost:90[:amp],speed:60" [--name "Frost"] [--brew awkward+blue_ice] [--long redstone] [--strong glowstone_dust]
//        -> potion + splash (gunpowder) + lingering (dragon_breath) + tipped arrows, brewing recipes, long_/strong_ variants
//   brew <id> --from <potion|item> --with <reagent> --to <potion|item>       (any brewing-stand recipe; potions: all 3 bottle kinds)
//   enchant <id> --name "Vampiric" [--items sword|axe|sharp|melee|weapon|mining|pickaxe|bow|crossbow|trident|mace|armor|helmet|chest|legs|boots|durability|#tag|item]
//        [--max 3] [--weight 2] [--slots mainhand|armor|feet|hand|any] [--curse] [--treasure] [--anvil 4]
//        [--attr "attack_damage:1:add_value"] (x level) [--damage 2] (bonus dmg x level) [--exclusive "#minecraft:exclusive_set/damage"]
//        [--attack ..] [--hurt ..] [--tick ..] [--mine "vein:24"|"area:1"] [--dodge 0.05]  (Java actions, x level)
//        [--effects '<raw vanilla effect components JSON>']  [--dp <ModId> [--ns <ns>]] (datapack: vanilla JSON only, --fn-attack ns:fn --fn-tick ns:fn)
//   food: see tools/food.mjs (mc food | feast | cook, guide FOOD.md)
//   attr <item> --attr "speed:0.1:add_multiplied_base@feet,reach:2,step:1,scale:-0.3,gravity:-0.04,jump:0.2" [--give]
//        aliases: speed reach block_reach entity_reach step jump scale gravity health damage attack_speed armor toughness knockback_res luck
//        safe_fall fall_damage mining mining_eff oxygen water_speed sneak burn  | any minecraft attribute id. slots: any mainhand offhand hand head chest legs feet armor body
//   itemfx <item> [--attack ..] [--hurt ..] [--held ..] [--worn ..] [--mine ..] [--dodge 0.1]   (any item incl. vanilla; actions: see guide)
//   all: [--tab "<Mod Name>"] (owns it for Export)
// ACTIONS ("; " separated, "25%" prefix = chance): damage heal lifesteal thorns ignite freeze apply self_apply cleanse immune levitate launch
//   knockback pull repel magnet aura chain lightning explode fly no_fall feed xp teleport vein area particle vfx sound cmd:<command>
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import zlib from 'node:zlib';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(2).map(l => l.slice(3)).join('\n');
const CMDS = ['effect', 'potion', 'brew', 'enchant', 'attr', 'itemfx']; // food: tools/food.mjs

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  const { pos, opt } = parse(rest);
  const id = pos[0];
  const R = path.join(ctx.ROOT, 'src/main/resources'), NS = ctx.NS || 'moddeus', A = path.join(R, 'assets', NS), D = path.join(R, 'data');
  const nsOf = s => readJson(path.join(ctx.CONTENT, s + '.json'), {}).namespace || 'moddeus'; // a content id's mod namespace
  const rawId = s => (s || '').replace(/^[a-z0-9_.-]+:/, '');
  if (!/^[a-z0-9_:.\/-]+$/.test(id || '')) ctx.die('usage:\n' + HELP);
  const lang = kv => { const f = path.join(A, 'lang/en_us.json'); const j = readJson(f, {}); Object.assign(j, kv); write(f, JSON.stringify(j, null, 2)); };
  const content = (cid, o) => { if (opt.tab) o.tab = one(opt.tab); if (NS !== 'moddeus') o.namespace = NS; write(path.join(ctx.CONTENT, cid + '.json'), JSON.stringify(o, null, 2)); console.log('wrote moddeus/content/' + cid + '.json'); };
  const actions = o => { for (const k of ['tick', 'start', 'end', 'attack', 'hurt', 'held', 'worn', 'mine']) if (opt[k]) o[k] = cmds(one(opt[k])); if (opt.dodge) o.dodge = Number(one(opt.dodge)); return o; };
  const nm = one(opt.name) || title(rawId(id));

  if (cmd === 'effect') {
    if (!/^[a-z0-9_]+$/.test(id)) ctx.die('effect id: a-z 0-9 _');
    const o = { type: 'effect', name: nm, color: one(opt.color) || '#a070ff', category: one(opt.category) || 'beneficial' };
    if (opt.every) o.every = Number(one(opt.every));
    if (opt.attr) o.attributes = attrs(one(opt.attr)).map(a => ({ attribute: a.type, amount: a.amount, operation: a.operation }));
    actions(o);
    o.files = ['assets/' + NS + '/textures/mob_effect/' + id + '.png']; // mc remove deletes these
    content(id, o);
    lang({ ['effect.' + NS + '.' + id]: nm });
    const icon = path.join(A, 'textures/mob_effect', id + '.png');
    if (!fs.existsSync(icon)) { glyph(icon, o.color); console.log('placeholder icon ' + path.relative(ctx.ROOT, icon) + ' -> draw the real one: mc pixels mob_effect ' + id + ' --palette .. --rows .. (18x18)'); }
    await ctx.apply();
    console.log('Try: mc game cmd "effect give @s ' + NS + ':' + id + ' 30 0"');
  } else if (cmd === 'potion') {
    if (!/^[a-z0-9_]+$/.test(id)) ctx.die('potion id: a-z 0-9 _');
    const eff = one(opt.effect) || ctx.die('potion: --effect "moddeus:frost:90,speed:60"');
    const effects = eff.split(',').map(s => { const p = s.trim().split(':'); let eid, rest;
      if (p.length >= 2 && isNaN(Number(p[1]))) { eid = p[0] + ':' + p[1]; rest = p.slice(2); } else { eid = 'minecraft:' + p[0]; rest = p.slice(1); }
      return { id: eid, duration: Math.round(Number(rest[0] || 180) * 20), amplifier: Number(rest[1] || 0) }; });
    const o = { type: 'potion', name: nm, effects };
    if (opt.long) o.long = {};
    if (opt.strong) o.strong = {};
    const names = {}; for (const [c, w] of [['potion', 'Potion of '], ['splash_potion', 'Splash Potion of '], ['lingering_potion', 'Lingering Potion of '], ['tipped_arrow', 'Arrow of ']]) names['item.minecraft.' + c + '.effect.' + id] = w + nm;
    lang(names);
    const rd = path.join(D, NS, 'recipe/brewing');
    const pot = NS + ':' + id;
    containers(rd, id, pot);
    if (opt.brew) { const [base, reagent] = one(opt.brew).split('+'); brewAll(rd, id + '_brew', potionId(base), item(reagent), pot); }
    if (opt.long) { brewAll(rd, 'long_' + id, pot, item(one(opt.long) === true ? 'redstone' : one(opt.long)), NS + ':long_' + id); containers(rd, 'long_' + id, NS + ':long_' + id); }
    if (opt.strong) { brewAll(rd, 'strong_' + id, pot, item(one(opt.strong) === true ? 'glowstone_dust' : one(opt.strong)), NS + ':strong_' + id); containers(rd, 'strong_' + id, NS + ':strong_' + id); }
    o.files = WRITTEN.filter(f => f.startsWith(rd)).map(f => path.relative(R, f).replace(/\\/g, '/'));
    content(id, o);
    await ctx.apply();
    console.log('Try: mc game cmd "give @s potion[potion_contents={potion:\"' + pot + '\"}]"   (splash_potion / lingering_potion / tipped_arrow too)');
  } else if (cmd === 'brew') {
    const from = one(opt.from), w = one(opt.with), to = one(opt.to);
    if (!from || !w || !to) ctx.die('brew <id> --from <potion|item> --with <reagent> --to <potion|item>');
    const rd = path.join(D, NS, 'recipe/brewing');
    if (isPotion(from) && isPotion(to)) brewAll(rd, id, potionId(from), item(w), potionId(to));
    else write(path.join(rd, id + '.json'), JSON.stringify({ type: 'minecraft:brewing', input: { item: item(from) }, reagent: { item: item(w) }, output: { id: item(to) } }, null, 2));
    console.log('brewing recipe(s) ' + id);
    await ctx.apply();
  } else if (cmd === 'enchant') {
    const dp = opt.dp ? one(opt.dp) : null, ns = one(opt.ns) || (dp ? dp.toLowerCase().replace(/[^a-z0-9_]/g, '') : NS);
    if (!/^[a-z0-9_]+$/.test(id)) ctx.die('enchant id: a-z 0-9 _');
    const lv = Number(one(opt.max) || 1);
    const e = {
      description: { translate: 'enchantment.' + ns + '.' + id, fallback: nm },
      supported_items: items(one(opt.items) || 'sword'),
      weight: Number(one(opt.weight) || (opt.treasure ? 1 : 2)),
      max_level: lv,
      min_cost: { base: 10, per_level_above_first: 10 }, max_cost: { base: 50, per_level_above_first: 10 },
      anvil_cost: Number(one(opt.anvil) || 4),
      slots: [one(opt.slots) || slotFor(one(opt.items) || 'sword')],
      effects: {},
    };
    if (opt.exclusive) e.exclusive_set = one(opt.exclusive);
    if (opt.attr) e.effects['minecraft:attributes'] = attrs(one(opt.attr)).map(a => ({ id: ns + ':enchantment.' + id + '.' + a.type.split(':')[1], attribute: a.type, amount: { type: 'minecraft:linear', base: a.amount, per_level_above_first: a.amount }, operation: a.operation }));
    if (opt.damage) e.effects['minecraft:damage'] = [{ effect: { type: 'minecraft:add', value: { type: 'minecraft:linear', base: Number(one(opt.damage)), per_level_above_first: Number(one(opt.damage)) } } }];
    if (opt['fn-attack']) e.effects['minecraft:post_attack'] = [{ enchanted: 'attacker', affected: 'victim', effect: { type: 'minecraft:run_function', function: one(opt['fn-attack']) } }];
    if (opt['fn-tick']) e.effects['minecraft:tick'] = [{ effect: { type: 'minecraft:run_function', function: one(opt['fn-tick']) } }];
    if (opt.effects) Object.assign(e.effects, JSON.parse(one(opt.effects)));
    const tags = opt.curse ? ['curse', 'treasure', 'on_random_loot'] : opt.treasure ? ['treasure', 'on_random_loot', 'tradeable'] : ['in_enchanting_table', 'non_treasure', 'on_random_loot', 'tradeable', 'on_traded_equipment'];
    if (opt.curse) e.weight = 1;
    lang({ ['enchantment.' + ns + '.' + id]: nm });
    if (dp) { // datapack mod: plain vanilla files in its pack, injected live too
      const dir = worldPack(ctx.ROOT, dp);
      write(path.join(dir, 'data', ns, 'enchantment', id + '.json'), JSON.stringify(e, null, 2));
      for (const t of tags) { const f = path.join(dir, 'data/minecraft/tags/enchantment', t + '.json'); const j = readJson(f, { replace: false, values: [] }); if (!j.values.includes(ns + ':' + id)) j.values.push(ns + ':' + id); write(f, JSON.stringify(j, null, 2)); }
      console.log('wrote ' + path.relative(ctx.ROOT, dir) + '/data/' + ns + '/enchantment/' + id + '.json');
      game(ctx.ROOT, 'print(dev.moddeus.live.Effects.liveEnchant("' + ns + ':' + id + '", ' + JSON.stringify(JSON.stringify(e)) + ', "' + tags.join(',') + '"));');
      game(ctx.ROOT, 'print(cmd("reload"));'); // enables a new pack folder
    } else {
      const o = actions({ type: 'enchant', name: nm, enchantment: e, enchant_tags: tags.map(t => 'minecraft:' + t) });
      content(id, o);
      await ctx.apply();
    }
    console.log('Try: mc game cmd "enchant @s ' + ns + ':' + id + ' ' + lv + '"  (also enchanting table/books' + (opt.curse ? ', curse' : '') + ')');
  } else if (cmd === 'attr') {
    const it = item(id), ours = fs.existsSync(path.join(ctx.CONTENT, rawId(it) + '.json')) && it === nsOf(rawId(it)) + ':' + rawId(it);
    const comp = {};
    {
      if (!opt.attr) ctx.die('attr <item> --attr "speed:0.1:add_multiplied_base@feet,reach:2"');
      comp['minecraft:attribute_modifiers'] = attrs(one(opt.attr)).map(a => ({ type: a.type, id: 'moddeus:gear.' + rawId(it).replace(/[^a-z0-9_]/g, '_') + '.' + a.type.split(':')[1] + (a.slot !== 'any' ? '.' + a.slot : ''), amount: a.amount, operation: a.operation, slot: a.slot }));
    }
    if (ours && !opt.give) {
      const f = path.join(ctx.CONTENT, rawId(it) + '.json'), o = readJson(f, {});
      o.components = Object.assign(o.components || {}, comp);
      write(f, JSON.stringify(o, null, 2));
      console.log('updated moddeus/content/' + rawId(it) + '.json (live: new stacks get it)');
      await ctx.apply();
      game(ctx.ROOT, 'print(cmd("give @s ' + it + '"));');
    } else {
      const give = 'give @s ' + it + '[' + Object.entries(comp).map(([k, v]) => k + '=' + JSON.stringify(v)).join(',') + ']';
      console.log('/' + give + '\n(datapack: use this /give or item_modifier set_components)');
      game(ctx.ROOT, 'print(cmd(' + JSON.stringify(give) + '));');
    }
  } else if (cmd === 'itemfx') {
    const it = item(id), o = actions({ type: 'itemfx', item: it });
    content(rawId(it).replace(/[^a-z0-9_]/g, '_') + '_fx', o);
    await ctx.apply();
  }
  return true;

  function item(s) { s = String(s).trim(); if (s.includes(':')) return s; return fs.existsSync(path.join(ctx.CONTENT, s + '.json')) ? nsOf(s) + ':' + s : 'minecraft:' + s; }
  function isPotion(s) { return /^(moddeus:)?[a-z0-9_]+$/.test(s) && (VANILLA_POTIONS.includes(s.replace(/^minecraft:/, '')) || fs.existsSync(path.join(ctx.CONTENT, rawId(s).replace(/^(long|strong)_/, '') + '.json')) && readJson(path.join(ctx.CONTENT, rawId(s).replace(/^(long|strong)_/, '') + '.json'), {}).type === 'potion'); }
  function potionId(s) { s = s.trim(); if (s.includes(':')) return s; return VANILLA_POTIONS.includes(s) ? 'minecraft:' + s : nsOf(s.replace(/^(long|strong)_/, '')) + ':' + s; }
}

const VANILLA_POTIONS = 'water mundane thick awkward night_vision invisibility leaping fire_resistance swiftness slowness turtle_master water_breathing healing harming poison regeneration strength weakness luck slow_falling wind_charged weaving oozing infested'.split(' ').flatMap(p => [p, 'long_' + p, 'strong_' + p]);
const BOTTLES = ['potion', 'splash_potion', 'lingering_potion'];
function brewAll(dir, name, from, reagent, to) {
  for (const b of BOTTLES) write(path.join(dir, b + '_' + name + '.json'), JSON.stringify({ type: 'minecraft:brewing', input: { item: 'minecraft:' + b, potion_contents: { potions: from } }, reagent: { item: reagent }, output: { id: 'minecraft:' + b, components: { 'minecraft:potion_contents': { potion: to } } } }, null, 2));
}
function containers(dir, name, pot) { // potion -> splash (gunpowder) -> lingering (dragon breath), like vanilla
  for (const [a, r, b] of [['potion', 'gunpowder', 'splash_potion'], ['splash_potion', 'dragon_breath', 'lingering_potion']])
    write(path.join(dir, b + '_' + name + '_' + r + '.json'), JSON.stringify({ type: 'minecraft:brewing', input: { item: 'minecraft:' + a, potion_contents: { potions: pot } }, reagent: { item: 'minecraft:' + r }, output: { id: 'minecraft:' + b, components: { 'minecraft:potion_contents': { potion: pot } } } }, null, 2));
}
const ATTR = { speed: 'movement_speed', reach: ['entity_interaction_range', 'block_interaction_range'], block_reach: 'block_interaction_range', entity_reach: 'entity_interaction_range', step: 'step_height', jump: 'jump_strength', health: 'max_health', damage: 'attack_damage', toughness: 'armor_toughness', knockback_res: 'knockback_resistance', safe_fall: 'safe_fall_distance', fall_damage: 'fall_damage_multiplier', mining: 'block_break_speed', mining_eff: 'mining_efficiency', oxygen: 'oxygen_bonus', water_speed: 'water_movement_efficiency', sneak: 'sneaking_speed', burn: 'burning_time' };
/** "speed:0.1:add_multiplied_base@feet,reach:2" -> [{type, amount, operation, slot}] */
function attrs(s) {
  return s.split(',').filter(x => x.trim()).flatMap(x => {
    const [body, slot] = x.trim().split('@'), p = body.split(':');
    let name = p[0], rest = p.slice(1);
    if (p.length > 1 && isNaN(Number(p[1]))) { if (!/^add_|^multiply/.test(p[1])) { name = p[0] + ':' + p[1]; rest = p.slice(2); } }
    const amount = Number(rest[0] ?? 1), operation = rest[1] || 'add_value';
    return [].concat(ATTR[name.replace(/^minecraft:/, '')] || name).map(n => ({ type: ns(n), amount, operation, slot: slot || 'any' }));
  });
}
const ITEMS = { sword: 'sweeping', sharp: 'sharp_weapon', melee: 'melee_weapon', weapon: 'weapon', mining: 'mining', pickaxe: 'mining', tool: 'mining_loot', bow: 'bow', crossbow: 'crossbow', trident: 'trident', mace: 'mace', fishing: 'fishing', armor: 'armor', helmet: 'head_armor', chest: 'chest_armor', legs: 'leg_armor', boots: 'foot_armor', durability: 'durability', spear: 'lunge' };
function items(s) { if (s.startsWith('#') || s.includes(':')) return s; if (s === 'axe') return '#minecraft:axes'; if (s === 'pickaxe') return '#minecraft:pickaxes'; return ITEMS[s] ? '#minecraft:enchantable/' + ITEMS[s] : 'minecraft:' + s; }
function slotFor(s) { return /armor|helmet|chest|legs|boots/.test(s) ? ({ helmet: 'head', chest: 'chest', legs: 'legs', boots: 'feet' }[s] || 'armor') : /durability/.test(s) ? 'any' : 'mainhand'; }
function worldPack(root, id) {
  const saves = path.join(root, 'run/saves');
  let w = null; try { w = JSON.parse(fs.readFileSync(path.join(root, 'run/moddeus/game.json'), 'utf8')).world; } catch {}
  if (!w || !fs.existsSync(path.join(saves, w))) w = fs.readdirSync(saves).map(d => [d, fs.statSync(path.join(saves, d)).mtimeMs]).sort((a, b) => b[1] - a[1])[0][0];
  const dir = path.join(saves, w, 'datapacks', id + '-DP');
  if (!fs.existsSync(path.join(dir, 'pack.mcmeta'))) write(path.join(dir, 'pack.mcmeta'), JSON.stringify({ pack: { description: id, min_format: [121, 0], max_format: [121, 0] } }, null, 2));
  return dir;
}
function game(root, java) {
  const r = spawnSync(process.execPath, [path.join(root, 'tools/mc.mjs'), 'game', 'run', java], { cwd: root, encoding: 'utf8' });
  const out = (r.stdout + r.stderr).trim(); if (out) console.log(out.split('\n').filter(l => !/^done in/.test(l)).join('\n'));
}
/** 18x18 placeholder icon: a shaded gem in the effect colour (draw a real one with mc pixels mob_effect). */
function glyph(f, hex) {
  const c = parseInt(hex.replace('#', ''), 16), r0 = c >> 16 & 255, g0 = c >> 8 & 255, b0 = c & 255, N = 18, px = Buffer.alloc(N * N * 4);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const dx = Math.abs(x - 8.5), dy = Math.abs(y - 8.5), d = dx + dy * 1.2;
    if (d > 8.2) continue;
    const edge = d > 7, lit = (x + y < 15 ? 1.35 : 0.8) * (edge ? 0.55 : 1), i = (y * N + x) * 4;
    px[i] = Math.min(255, r0 * lit); px[i + 1] = Math.min(255, g0 * lit); px[i + 2] = Math.min(255, b0 * lit); px[i + 3] = 255;
    if (!edge && x > 5 && x < 8 && y > 4 && y < 7) px[i] = px[i + 1] = px[i + 2] = 255; // highlight
  }
  const raw = Buffer.alloc(N * (N * 4 + 1)); for (let y = 0; y < N; y++) px.copy(raw, y * (N * 4 + 1) + 1, y * N * 4, (y + 1) * N * 4);
  const crcT = []; for (let n = 0; n < 256; n++) { let k = n; for (let j = 0; j < 8; j++) k = k & 1 ? 0xedb88320 ^ (k >>> 1) : k >>> 1; crcT[n] = k >>> 0; }
  const crc = b => { let k = 0xffffffff; for (const v of b) k = crcT[(k ^ v) & 255] ^ (k >>> 8); return (k ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const cr = Buffer.alloc(4); cr.writeUInt32BE(crc(td)); return Buffer.concat([l, td, cr]); };
  const ih = Buffer.alloc(13); ih.writeUInt32BE(N, 0); ih.writeUInt32BE(N, 4); ih[8] = 8; ih[9] = 6;
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
}

const one = v => Array.isArray(v) ? v[v.length - 1] : v;
const ns = s => (s.includes(':') ? s : 'minecraft:' + s);
const title = s => s.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
function readJson(f, def) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return def; } }
const WRITTEN = [];
function write(f, s) { WRITTEN.push(f); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, s); }
/** "a; b" -> ["a","b"], ignoring ; inside [] {} "". */
function cmds(s) {
  const out = []; let cur = '', depth = 0, q = false;
  for (const c of String(s || '')) {
    if (c === '"') q = !q; else if (!q && '[{'.includes(c)) depth++; else if (!q && ']}'.includes(c)) depth--;
    if (c === ';' && !q && depth === 0) { if (cur.trim()) out.push(cur.trim()); cur = ''; } else cur += c;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}
function parse(rest) {
  const pos = [], opt = {};
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (!a.startsWith('--')) { pos.push(a); continue; }
    const k = a.slice(2), v = rest[i + 1] !== undefined && !rest[i + 1].startsWith('--') ? rest[++i] : true;
    opt[k] = opt[k] === undefined ? v : [].concat(opt[k], v);
  }
  return { pos, opt };
}
