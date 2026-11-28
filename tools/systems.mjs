// Blocks & game systems for Moddeus mods: one command each, live, exportable. Dispatched from tools/mc.mjs.
// Java side: src/main/java/dev/moddeus/live/Systems.java (+ client/SystemsClient.java). Guide: .claude/skills/mc-content/SYSTEMS.md
//   recipe <id> --result <item> [--count N] + one of: --shaped "AAA, B ,..." --key "A=diamond,B=stick" | --shapeless "a,b,#tag"
//        | --smelt|--blast|--smoke|--campfire <ingredient> [--xp 0.7] [--time 200] | --stonecut <in> | --smithing "template,base,addition"
//        | --brew "awkward + sugar" [--potion swiftness | --result <any item>] [--bottle potion|splash_potion|lingering_potion]   [--pack <Id>] (datapack)
//   quest <id> --title "Get wood" [--desc ..] --icon oak_log --trigger obtain:oak_log*8|kill:zombie|enter:the_nether|biome:desert|place:x|eat:x|craft:<recipe>|tame|breed|manual
//        [--trigger ...]... [--parent <quest id>] [--root --background stone] [--frame task|goal|challenge] [--reward "xp:50,item:diamond*3,coins:10,cmd:say hi"] [--hidden] [--tab <folder>] [--pack <Id>]
//   disc <id> --sound SRC --title "Artist - Song" [--length secs (auto)] [--comparator 1-15] [--color "#e04040"]       music disc + jukebox song, live
//   pad <id> --action launch|elevator|teleport|conveyor|trap|cmd [--block <existing>] [--power 1.4 --forward 0.8] [--range 32] [--speed 0.15]
//        [--damage 3 --fire 2] [--effects "poison:1:60"] [--cmd "..."] [--sound <id>] [--mobs] [--name ..] [--color "#hex"] [--cooldown ticks]
//   crop <id> [--stages 4] [--color "#e33"] [--food 4,0.3 | --produce <item>] [--count 1-3]   -> <id>_seeds (plant on farmland) + <id> produce
//   ore <id> --xp 3-7 [--drop <item> (made if missing)] [--count 1-2] [--color "#e0115f"] [--hardness 3]   (spawning in the world: structures/worldgen tool)
//   machine <id> --recipe "[n] <in> -> [n] <out> [ticks] [xp]"... [--fuel] [--fuels "coal=1600"] [--block <existing>] [--name ..] [--color]   furnace-style GUI
//   economy [<objective>=coins] [--name Coins] [--symbol "$"] [--start 0] [--earn "killed:zombie=2,mined:#minecraft:diamond_ores=10,fish=1"]  Java: Systems.coins(p), addCoins(p, n)
//   shop <id> --item "diamond buy 50 sell 20"... [--block <existing> | --no-block] [--name ..]   right-click block, or Java Systems.openShop(player, id) (from an NPC/item in the mod's code)
//   skill <id> [--name Mining] --earn "mined:#minecraft:mineable/pickaxe=1,killed:zombie=5,crafted:bread=2,fish=10,walk=0.01" [--max 50] [--base 50] [--growth 1.15]
//        [--attr "mining_efficiency:0.2,max_health:0.5"] (per level) [--perk "10: give @s diamond 2"]... [--color "#5ce08a"]   Java: Systems.skillLevel(p, id)
//   hud <id> [--title ..] --line "Coins: {coins}" ... [--pos top_left|top_right|middle_left] [--color "#e0b040"]
//        placeholders {coins} {symbol} {score:obj} {skill:id} {skillxp:id} {day} {time} {x} {y} {z} {biome} {dim} {health} {food} {player} {sky}
//   skyevent <id> --name "Blood Moon" [--when night|day|thunder|manual] [--chance 0.15] [--tint "#a00010"] [--alpha 0.22] [--mob-effects "strength:1,speed:0"]
//        [--player-effects ..] [--spawn zombie --spawn-count 2 --spawn-every 200] [--start "cmd"] [--end "cmd"] [--weather thunder] [--subtitle ..]   Java: Systems.startSky(server, id) / stopSky(server)
//   secret <id> [--block <existing> (default: new block <id> that looks like stone bricks)] [--ticks 100] [--key <item>] [--max 48]   right-click opens the wall
//   all: [--tab "<Mod Name>"] (Export ownership)   remove: mc remove <id>  (quest: mc quest <id> --remove)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(2).map(l => l.slice(3)).join('\n');
const CMDS = ['recipe', 'quest', 'disc', 'pad', 'crop', 'ore', 'machine', 'economy', 'shop', 'skill', 'hud', 'skyevent', 'secret'];
let ROOT, RES, A, CONTENT, die, opt, NS = 'moddeus', NSP = 'moddeus:', stamp = () => {}; // NS: the mod's namespace (mc.mjs NS), stamp: mc.mjs nsContent

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  ({ ROOT, CONTENT, die } = ctx); NS = ctx.NS || 'moddeus'; NSP = NS + ':'; stamp = ctx.stamp || stamp; RES = path.join(ROOT, 'src/main/resources'); A = path.join(RES, 'assets/' + NS);
  const p = parse(rest); opt = p.opt;
  const id = p.pos[0] || (cmd === 'economy' ? 'coins' : '');
  if (!/^[a-z0-9_]+$/.test(id)) die('usage: ' + cmd + ' <id> ...  (id: a-z 0-9 _)\n' + HELP.split('\n').filter(l => l.trim().startsWith(cmd + ' ') || l.startsWith('     ')).join('\n'));
  const tab = one(opt.tab);
  let reload = false;
  switch (cmd) {
    case 'recipe': reload = recipe(id); break;
    case 'quest': reload = quest(id); break;
    case 'disc': disc(id, tab); break;
    case 'pad': pad(id, tab); break;
    case 'crop': crop(id, tab); break;
    case 'ore': ore(id, tab); break;
    case 'machine': machine(id, tab); break;
    case 'economy': spec(id + '_economy', { type: 'economy', objective: id, name: one(opt.name) || title(id), symbol: one(opt.symbol) || one(opt.name) || title(id), start: Number(one(opt.start) || 0), earn: earn(one(opt.earn)) }, tab); break;
    case 'shop': shop(id, tab); break;
    case 'skill': {
      const o = { type: 'skill', id, name: one(opt.name) || title(id), earn: earn(one(opt.earn) || one(opt.xp)), max: Number(one(opt.max) || 50), base: Number(one(opt.base) || 50), growth: Number(one(opt.growth) || 1.15) };
      if (!Object.keys(o.earn).length) die('skill: --earn "mined:#minecraft:mineable/pickaxe=1,killed:zombie=5"');
      if (opt.attr) o.attributes = Object.fromEntries(one(opt.attr).split(',').map(s => { const [k, v] = s.trim().split(':'); return [k, Number(v)]; }));
      if (opt.perk) o.perks = Object.fromEntries([].concat(opt.perk).map(s => { const i = s.indexOf(':'); return [String(parseInt(s)), cmds(s.slice(i + 1))]; }));
      if (opt.color) o.bar_color = one(opt.color);
      spec(id + '_skill', o, tab); break;
    }
    case 'hud': spec(id + '_hud', { type: 'hud', id, title: one(opt.title) || '', lines: [].concat(opt.line || []), pos: one(opt.pos) || 'top_right', ...(opt.color ? { color: one(opt.color) } : {}) }, tab); break;
    case 'skyevent': {
      const o = { type: 'skyevent', id, name: one(opt.name) || title(id), when: one(opt.when) || 'night', chance: Number(one(opt.chance) || 0.15) };
      for (const k of ['tint', 'subtitle', 'weather', 'sound', 'color']) if (opt[k]) o[k] = one(opt[k]);
      if (opt.alpha) o.alpha = Number(one(opt.alpha));
      if (opt['mob-effects']) o.mob_effects = one(opt['mob-effects']);
      if (opt['player-effects']) o.player_effects = one(opt['player-effects']);
      if (opt.spawn) { o.spawn = ns(one(opt.spawn)); o.spawn_count = Number(one(opt['spawn-count']) || 2); o.spawn_every = Number(one(opt['spawn-every']) || 200); }
      if (opt.start) o.start = cmds(one(opt.start));
      if (opt.end) o.end = cmds(one(opt.end));
      spec(id + '_sky', o, tab); break;
    }
    case 'secret': secret(id, tab); break;
  }
  if (reload) { await (await import('./game.mjs')).run(['cmd', 'reload']); }
  else await ctx.apply();
  return true;
}

// ---- recipes --------------------------------------------------------------------------------------------
function recipe(id) {
  const item = s => ns(s.trim());
  const ing = s => s.trim().startsWith('#') ? s.trim() : item(s);
  const result = { id: item(one(opt.result) || NSP + id), ...(opt.count ? { count: Number(one(opt.count)) } : {}) };
  const cook = t => ({ type: 'minecraft:' + t, category: 'misc', ingredient: ing(one(opt[{ smelting: 'smelt', blasting: 'blast', smoking: 'smoke', campfire_cooking: 'campfire' }[t]])), result, experience: Number(one(opt.xp) || 0.1), cookingtime: Number(one(opt.time) || (t === 'smelting' ? 200 : t === 'campfire_cooking' ? 600 : 100)) });
  let r;
  if (opt.shaped) r = { type: 'minecraft:crafting_shaped', category: 'misc', pattern: one(opt.shaped).split(','), key: Object.fromEntries(String(one(opt.key) || '').split(',').filter(Boolean).map(kv => { const [k, v] = kv.split('='); return [k.trim(), ing(v)]; })), result };
  else if (opt.shapeless) r = { type: 'minecraft:crafting_shapeless', category: 'misc', ingredients: one(opt.shapeless).split(',').map(ing), result };
  else if (opt.smelt) r = cook('smelting');
  else if (opt.blast) r = cook('blasting');
  else if (opt.smoke) r = cook('smoking');
  else if (opt.campfire) r = cook('campfire_cooking');
  else if (opt.stonecut) r = { type: 'minecraft:stonecutting', ingredient: ing(one(opt.stonecut)), result };
  else if (opt.smithing) { const [t, b, a] = one(opt.smithing).split(',').map(ing); r = { type: 'minecraft:smithing_transform', template: t, base: b, addition: a, result }; }
  else if (opt.brew) {
    const [base, reagent] = one(opt.brew).split('+').map(s => s.trim());
    const bottle = ns(one(opt.bottle) || 'potion');
    const output = opt.potion ? { id: bottle, components: { 'minecraft:potion_contents': { potion: ns(one(opt.potion)) } } } : result;
    r = { type: 'minecraft:brewing', input: { item: bottle, potion_contents: { potions: ns(base) } }, reagent: { item: ing(reagent) }, output };
  } else die('recipe needs --shaped/--shapeless/--smelt/--blast/--smoke/--campfire/--stonecut/--smithing/--brew');
  const { dir, pack } = dataDir();
  write(path.join(dir, 'recipe', id + '.json'), r);
  console.log('recipe ' + (pack ? pack.ns : NS) + ':' + id + ' (' + r.type.replace('minecraft:', '') + ') -> ' + (r.output || r.result).id);
  return !!pack;
}

// ---- quests (advancements) --------------------------------------------------------------------------------
function quest(id) {
  const { dir, pack } = dataDir(), nsp = pack ? pack.ns : NS, folder = one(opt.tab) && !opt.pack ? snake(one(opt.tab)) : 'quests';
  const f = path.join(dir, 'advancement', folder, id + '.json'), fn = path.join(dir, 'function', 'quest', id + '.mcfunction');
  if (opt.remove) { for (const x of [f, fn]) if (fs.existsSync(x)) { fs.rmSync(x); console.log('  - ' + path.relative(ROOT, x)); } return !!pack; }
  const crit = {}, req = [];
  for (const t of [].concat(opt.trigger || 'manual')) { const k = 'c' + req.length; crit[k] = trigger(t); req.push([k]); }
  const icon = ns(one(opt.icon) || 'book');
  const a = {
    criteria: crit, requirements: req,
    display: { icon: { id: icon }, title: { text: one(opt.title) || title(id) }, description: { text: one(opt.desc) || '' }, frame: one(opt.frame) || 'task',
      show_toast: !opt['no-toast'], announce_to_chat: !!opt.announce, hidden: !!opt.hidden },
  };
  if (opt.root || !opt.parent) a.display.background = ns(one(opt.background) || 'stone').replace(/^minecraft:(?!gui\/)/, 'minecraft:gui/advancements/backgrounds/');
  if (opt.parent) a.parent = nsp + ':' + folder + '/' + one(opt.parent);
  const lines = [], rw = {};
  for (const r of String(one(opt.reward) || '').split(/,(?![^"]*"\s*$)(?=\s*(?:xp|item|coins|cmd|skill):)/).map(s => s.trim()).filter(Boolean)) {
    const i = r.indexOf(':'), k = r.slice(0, i), v = r.slice(i + 1).trim();
    if (k === 'xp') rw.experience = Number(v);
    else if (k === 'item') { const [it, n] = v.split('*'); lines.push('give @s ' + ns(it) + ' ' + (n || 1)); }
    else if (k === 'coins') lines.push('scoreboard players add @s coins ' + Number(v));
    else if (k === 'skill') { const [s, n] = v.split('*'); lines.push('scoreboard players add @s sk_' + s + ' ' + (n || 1)); }
    else if (k === 'cmd') lines.push(...cmds(v));
  }
  if (lines.length) { lines.unshift('playsound minecraft:ui.toast.challenge_complete master @s ~ ~ ~ 0.6 1.4'); fs.mkdirSync(path.dirname(fn), { recursive: true }); fs.writeFileSync(fn, lines.join('\n') + '\n'); rw.function = nsp + ':quest/' + id; }
  if (Object.keys(rw).length) a.rewards = rw;
  write(f, a);
  console.log('quest ' + nsp + ':' + folder + '/' + id + ' (' + req.length + ' trigger' + (req.length > 1 ? 's' : '') + (rw.function ? ', reward function' : '') + ')  grant: /advancement grant @s only ' + nsp + ':' + folder + '/' + id);
  return !!pack;
}

function trigger(t) {
  const i = t.indexOf(':'), k = i < 0 ? t : t.slice(0, i), v = i < 0 ? '' : t.slice(i + 1);
  const ent = e => ({ type: 'minecraft:entity_properties', entity: 'this', predicate: { 'minecraft:entity_type': e.startsWith('#') ? e : ns(e) } });
  switch (k) {
    case 'obtain': case 'get': { const [it, n] = v.split('*'); return { trigger: 'minecraft:inventory_changed', conditions: { items: [{ items: it.startsWith('#') ? it : ns(it), ...(n ? { count: { min: Number(n) } } : {}) }] } }; }
    case 'kill': return { trigger: 'minecraft:player_killed_entity', conditions: v ? { entity: ent(v) } : {} };
    case 'enter': return { trigger: 'minecraft:changed_dimension', conditions: { to: ns(v) } };
    case 'biome': return { trigger: 'minecraft:location', conditions: { player: { type: 'minecraft:entity_properties', entity: 'this', predicate: { 'minecraft:location': { biomes: ns(v) } } } } };
    case 'place': return { trigger: 'minecraft:placed_block', conditions: { location: { type: 'minecraft:match_block', blocks: ns(v) } } };
    case 'eat': case 'consume': return { trigger: 'minecraft:consume_item', conditions: { item: { items: ns(v) } } };
    case 'craft': return { trigger: 'minecraft:recipe_crafted', conditions: { recipe_id: ns(v) } };
    case 'tame': return { trigger: 'minecraft:tame_animal', ...(v ? { conditions: { entity: ent(v) } } : {}) };
    case 'breed': return { trigger: 'minecraft:bred_animals', ...(v ? { conditions: { child: ent(v) } } : {}) };
    case 'manual': case 'impossible': return { trigger: 'minecraft:impossible' };
    default: die('unknown --trigger ' + t + ' (obtain|kill|enter|biome|place|eat|craft|tame|breed|manual)');
  }
}

// ---- music discs ------------------------------------------------------------------------------------------
function disc(id, tab) {
  const src = one(opt.sound) || die('disc: --sound <file|URL>');
  execFileSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'sound', id, src, '--ns', NS, '--stream', '--mono', '--no-apply'] /* vanilla records: mono (positional jukebox) + streamed */, { stdio: 'inherit' });
  const ogg = path.join(A, 'sounds', id + '.ogg');
  let len = Number(one(opt.length) || 0);
  if (!len) try { len = Math.ceil(Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', ogg]).toString().trim())); } catch { len = 120; }
  const song = { sound_event: NSP + id, description: { text: one(opt.title) || title(id) }, length_in_seconds: len, comparator_output: Number(one(opt.comparator) || 7) };
  // next launches (dynamic registry loads at world start). Inline sound: the live-registered sound event does not exist yet
  // when the world's registries load, and a by-id reference failed the whole world load ("Errors in data packs").
  write(path.join(RES, 'data/' + NS + '/jukebox_song', id + '.json'), { ...song, sound_event: { sound_id: NSP + id } });
  const color = one(opt.color) || '#e04040';
  makeItem(id, { name: 'Music Disc', stack: 1, rarity: 'rare', tab, components: { 'minecraft:jukebox_playable': NSP + id }, tex: texDisc(color),
    extraFiles: ['moddeus/content/_song_' + id + '.json', 'data/' + NS + '/jukebox_song/' + id + '.json'] });
  spec('_song_' + id, { type: 'jukebox_song', id, song }, tab); // '_' sorts before the item: the song exists when the item parses
  console.log('disc ' + NSP + id + ' "' + song.description.text + '" (' + len + ' s): put it in a jukebox');
}

// ---- functional blocks: pads --------------------------------------------------------------------------------
const PAD_LOOK = { launch: ['#2b2f3a', '#7cff4f'], elevator: ['#5f6672', '#4fd8ff'], teleport: ['#2a2238', '#b080ff'], conveyor: ['#262626', '#ffcc33'], trap: ['#4a4a4a', '#d03030'], cmd: ['#30343c', '#e0b040'] };
function pad(id, tab) {
  const action = one(opt.action) || die('pad: --action launch|elevator|teleport|conveyor|trap|cmd');
  if (!PAD_LOOK[action]) die('unknown --action ' + action);
  const o = { type: 'pad', id, action };
  for (const k of ['power', 'forward', 'range', 'speed', 'damage', 'fire', 'cooldown']) if (opt[k] !== undefined) o[k] = Number(one(opt[k]));
  for (const k of ['sound', 'effects', 'name', 'dir']) if (opt[k]) o[k] = one(opt[k]);
  if (opt.cmd) o.cmd = cmds(one(opt.cmd));
  if (opt.mobs || ((action === 'conveyor' || action === 'launch') && !opt['players-only'])) o.mobs = true;
  if (opt.cycle) o.cycle = true;
  if (opt['fall-damage']) o.fall_damage = true;
  const [base, acc] = PAD_LOOK[action], c = one(opt.color) || acc;
  o.block = opt.block ? ns(one(opt.block)) : NSP + id;
  if (!opt.block) makeBlock(id, { name: one(opt.name) || title(id), shape: action === 'conveyor' ? 'facing' : null, top: texPad(action, base, c), side: texSide(base, c), light: action === 'teleport' ? 7 : 0, tab, extraFiles: ['moddeus/content/' + id + '_pad.json'] });
  spec(id + '_pad', o, tab);
  console.log('pad ' + id + ' (' + action + ') on ' + o.block + ': ' + { launch: 'step on it', elevator: 'stack two with a gap, jump = up, sneak = down', teleport: 'step on each to discover, sneak = travel menu', conveyor: 'moves entities the way you faced when placing', trap: 'hurts what steps on it', cmd: 'runs --cmd as whoever steps on it' }[action]);
}

function secret(id, tab) {
  const o = { type: 'secret', id, ticks: Number(one(opt.ticks) || 100), max: Number(one(opt.max) || 48) };
  if (opt.key) o.key = ns(one(opt.key));
  if (opt.sound) o.sound = one(opt.sound);
  o.block = opt.block ? ns(one(opt.block)) : NSP + id;
  if (!opt.block) { const t = texBricks(); makeBlock(id, { name: one(opt.name) || 'Stone Bricks', top: t, side: t, tab, hardness: 1.5, extraFiles: ['moddeus/content/' + id + '_secret.json'] }); }
  spec(id + '_secret', o, tab);
  console.log('secret wall ' + id + ' on ' + o.block + ': build a wall of it, right-click' + (o.key ? ' holding ' + o.key : '') + ' -> it opens for ' + o.ticks / 20 + ' s');
}

function machine(id, tab) {
  const recs = [].concat(opt.recipe || []).map(s => {
    const [l, r] = s.split('->').map(x => x.trim()); if (!r) die('machine --recipe "[n] <in> -> [n] <out> [ticks] [xp]"');
    const lw = l.split(/\s+/), rw = r.split(/\s+/);
    const inC = /^\d+$/.test(lw[0]) ? Number(lw.shift()) : 1, outC = /^\d+$/.test(rw[0]) ? Number(rw.shift()) : 1;
    const x = { in: lw[0].startsWith('#') ? lw[0] : resolve(lw[0]), in_count: inC, out: resolve(rw[0]), count: outC, ticks: Number(rw[1] || 100) };
    if (rw[2]) x.xp = Number(rw[2]);
    return x;
  });
  if (!recs.length) die('machine: at least one --recipe');
  const o = { type: 'machine', id, name: one(opt.name) || title(id), recipes: recs };
  if (opt.fuel || opt.fuels) o.fuel = true;
  if (opt.fuels) o.fuels = Object.fromEntries(one(opt.fuels).split(',').map(s => { const [k, v] = s.split('='); return [k.trim().startsWith('#') ? k.trim() : ns(k.trim()), Number(v)]; }));
  if (opt.sound) o.sound = one(opt.sound);
  o.block = opt.block ? ns(one(opt.block)) : NSP + id;
  if (!opt.block) { const c = one(opt.color) || '#40d0ff'; makeBlock(id, { name: o.name, top: texMachineTop(c), side: texMachine(c), tab, hardness: 3.5, mine: 'pickaxe', extraFiles: ['moddeus/content/' + id + '_machine.json'] }); }
  spec(id + '_machine', o, tab);
  console.log('machine ' + id + ': right-click -> GUI (input / ' + (o.fuel ? 'fuel' : 'no fuel needed') + ' / output); ' + recs.length + ' recipe(s)');
}

function shop(id, tab) {
  const items = [].concat(opt.item || []).map(s => {
    const w = s.trim().split(/\s+/); const cnt = /^\d+$/.test(w[0]) ? Number(w.shift()) : 1; const it = { item: resolve(w.shift()), count: cnt };
    for (let i = 0; i < w.length; i += 2) if (w[i] === 'buy' || w[i] === 'sell') it[w[i]] = Number(w[i + 1]);
    if (!it.buy && !it.sell) die('shop --item "diamond buy 50 sell 20"');
    return it;
  });
  if (!items.length) die('shop: at least one --item');
  const o = { type: 'shop', id, name: one(opt.name) || title(id), items };
  if (!opt['no-block']) {
    o.block = opt.block ? ns(one(opt.block)) : NSP + id;
    if (!opt.block) makeBlock(id, { name: o.name, top: texShop(true), side: texShop(false), tab, hardness: 2, sound: 'wood', mine: 'axe', extraFiles: ['moddeus/content/' + id + '_shop.json'] });
  } else o.block = 'moddeus:__none__';
  spec(id + '_shop', o, tab);
  console.log('shop ' + id + ': ' + items.length + ' offers; open: right-click ' + (opt['no-block'] ? '(no block)' : o.block) + ' or Systems.openShop(player, "' + id + '")');
}

// ---- crops + ores --------------------------------------------------------------------------------------------
function crop(id, tab) {
  const n = Math.max(2, Math.min(8, Number(one(opt.stages) || 4))), color = one(opt.color) || '#e03a30', seeds = id + '_seeds';
  let produce = opt.produce ? resolve(one(opt.produce)) : NSP + id;
  if (!opt.produce) { const [nu, sa] = String(one(opt.food) || '4,0.3').split(','); makeItem(id, { name: one(opt.name) || title(id), tex: texFruit(color), tab, food: { nutrition: Number(nu), saturation: Math.round(Number(nu) * Number(sa || 0.3) * 200) / 100, alwaysEat: false }, components: { 'minecraft:compostable': { layers: 'minecraft:compostable/medium' } } }); } // sa = saturation modifier like vanilla Foods
  const files = [], c = { type: 'block', shape: 'crop', hardness: 0, sound: 'grass', files, tags: ['minecraft:crops', 'minecraft:maintains_farmland', 'minecraft:bee_growables'], itemTags: ['minecraft:villager_plantable_seeds', 'minecraft:chicken_food', 'c:seeds'], ...(tab ? { tab } : {}) };
  const variants = {};
  for (let s = 0; s < n; s++) {
    png(files, 'textures/block/' + seeds + '_stage' + s + '.png', texStage(s, n, color));
    out(files, 'models/block/' + seeds + '_stage' + s + '.json', { parent: 'minecraft:block/crop', textures: { crop: NSP + 'block/' + seeds + '_stage' + s }, render_type: 'minecraft:cutout' });
  }
  for (let age = 0; age < 8; age++) variants['age=' + age] = { model: NSP + 'block/' + seeds + '_stage' + (age === 7 ? n - 1 : Math.min(n - 2, Math.floor(age * (n - 1) / 7))) };
  out(files, 'blockstates/' + seeds + '.json', { variants });
  png(files, 'textures/item/' + seeds + '.png', texSeeds(color));
  out(files, 'models/item/' + seeds + '.json', { parent: 'minecraft:item/generated', textures: { layer0: NSP + 'item/' + seeds } });
  out(files, 'items/' + seeds + '.json', { model: { type: 'minecraft:model', model: NSP + 'item/' + seeds } });
  const [a, b] = String(one(opt.count) || '1-3').split('-').map(Number);
  const ripe = { type: 'minecraft:match_block', blocks: NSP + seeds, state: { age: '7' } };
  write(path.join(RES, 'data/' + NS + '/loot_table/blocks', seeds + '.json'), { type: 'minecraft:block', modifier: { type: 'minecraft:explosion_decay' }, pools: [
    { rolls: 1, entries: [{ type: 'minecraft:alternatives', children: [{ type: 'minecraft:item', condition: ripe, name: produce, modifier: { type: 'minecraft:set_count', count: b ? { type: 'minecraft:uniform', min: a, max: b } : a } }, { type: 'minecraft:item', name: NSP + seeds }] }] },
    { rolls: 1, condition: ripe, entries: [{ type: 'minecraft:item', name: NSP + seeds, modifier: { type: 'minecraft:apply_bonus', enchantment: 'minecraft:fortune', formula: 'minecraft:binomial_with_bonus_count', parameters: { extra: 1, probability: 0.5 } } }] }] });
  files.push('data/' + NS + '/loot_table/blocks/' + seeds + '.json');
  for (const t of c.tags) tag(t, NSP + seeds, true);
  for (const t of c.itemTags) itemTag(t, NSP + seeds); // farmer villagers plant it, chickens breed on it, c: = other mods (mc remove drops these)
  lang('block.' + NS + '.' + seeds, (one(opt.name) || title(id)) + ' Seeds');
  write(path.join(CONTENT, seeds + '.json'), c);
  console.log('crop ' + seeds + ' (' + n + ' stages) -> ' + produce + ': plant on farmland, bone meal works');
}

function ore(id, tab) {
  const [x1, x2] = String(one(opt.xp) || '2-5').split('-').map(Number), color = one(opt.color) || '#e0115f';
  let drop = opt.drop ? resolve(one(opt.drop)) : null;
  if (drop && drop.startsWith('minecraft:') && !one(opt.drop).includes(':') && !vanillaItem(drop.slice(10))) drop = NSP + drop.slice(10);
  if (drop && drop.startsWith(NSP) && !fs.existsSync(path.join(CONTENT, drop.slice(NSP.length) + '.json'))) makeItem(drop.slice(NSP.length), { name: title(drop.slice(NSP.length)), tex: texGem(color), tab });
  const files = ['moddeus/content/' + id + '.json'];
  makeBlock(id, { name: one(opt.name) || title(id), top: texOre(color), side: null, xp: [x1, x2 || x1], requiresTool: true, mine: 'pickaxe', hardness: Number(one(opt.hardness) || 3), tab, noLoot: !!drop });
  if (drop) {
    const [a, b] = String(one(opt.count) || '1').split('-').map(Number);
    write(path.join(RES, 'data/' + NS + '/loot_table/blocks', id + '.json'), { type: 'minecraft:block', pools: [{ rolls: 1, entries: [{ type: 'minecraft:alternatives', children: [
      { type: 'minecraft:item', name: NSP + id, condition: { type: 'minecraft:match_tool', predicate: { predicates: { 'minecraft:enchantments': [{ enchantments: 'minecraft:silk_touch', levels: { min: 1 } }] } } } },
      { type: 'minecraft:item', name: drop, modifier: [{ type: 'minecraft:set_count', count: b ? { type: 'minecraft:uniform', min: a, max: b } : a }, { type: 'minecraft:apply_bonus', enchantment: 'minecraft:fortune', formula: 'minecraft:ore_drops' }, { type: 'minecraft:explosion_decay' }] }] }] }] });
  }
  console.log('ore ' + id + ': ' + x1 + '-' + (x2 || x1) + ' xp, needs a pickaxe' + (drop ? ', drops ' + drop + ' (silk touch: the ore)' : ''));
}

// ---- file writers (same layout as mc.mjs block/item, so mc remove cleans them) --------------------------
function makeBlock(id, o) {
  const files = [...(o.extraFiles || [])], c = { type: 'block', files, tags: [] };
  if (o.tab) c.tab = o.tab;
  if (o.shape) c.shape = o.shape;
  if (o.xp) c.xp = o.xp;
  if (o.light) c.light = o.light;
  if (o.hardness !== undefined) c.hardness = o.hardness;
  if (o.sound) c.sound = o.sound;
  if (o.requiresTool) c.requiresTool = true;
  if (o.mine) c.tags.push('minecraft:mineable/' + o.mine);
  c.tags.forEach(t => tag(t, NSP + id, true));
  png(files, 'textures/block/' + id + '.png', o.top);
  if (o.side) png(files, 'textures/block/' + id + '_side.png', o.side);
  const model = o.side ? { parent: 'minecraft:block/cube_bottom_top', textures: { top: NSP + 'block/' + id, side: NSP + 'block/' + id + '_side', bottom: NSP + 'block/' + id + '_side' } } : { parent: 'minecraft:block/cube_all', textures: { all: NSP + 'block/' + id } };
  out(files, 'models/block/' + id + '.json', model);
  out(files, 'blockstates/' + id + '.json', o.shape === 'facing'
    ? { variants: { 'facing=north': { model: NSP + 'block/' + id, y: 180 }, 'facing=south': { model: NSP + 'block/' + id }, 'facing=east': { model: NSP + 'block/' + id, y: 270 }, 'facing=west': { model: NSP + 'block/' + id, y: 90 } } }
    : { variants: { '': { model: NSP + 'block/' + id } } });
  out(files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'block/' + id } });
  if (!o.noLoot) write(path.join(RES, 'data/' + NS + '/loot_table/blocks', id + '.json'), { type: 'minecraft:block', pools: [{ rolls: 1, condition: { type: 'minecraft:survives_explosion' }, entries: [{ type: 'minecraft:item', name: NSP + id }] }] });
  lang('block.' + NS + '.' + id, o.name);
  write(path.join(CONTENT, id + '.json'), c);
}

function makeItem(id, o) {
  const files = [...(o.extraFiles || [])], c = { type: 'item', files };
  if (o.tab) c.tab = o.tab;
  for (const k of ['stack', 'rarity', 'components', 'food']) if (o[k] !== undefined) c[k] = o[k];
  png(files, 'textures/item/' + id + '.png', o.tex);
  out(files, 'models/item/' + id + '.json', { parent: 'minecraft:item/generated', textures: { layer0: NSP + 'item/' + id } });
  out(files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'item/' + id } });
  lang('item.' + NS + '.' + id, o.name);
  write(path.join(CONTENT, id + '.json'), c);
}

function spec(file, o, tab) { if (tab) o.tab = tab; write(path.join(CONTENT, file + '.json'), o); console.log('  ' + o.type + ' -> moddeus/content/' + file + '.json'); }
function out(files, rel, json) { write(path.join(A, rel), json); files.push('assets/' + NS + '/' + rel); }
function png(files, rel, canvas) { const f = path.join(A, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, encode(canvas)); files.push('assets/' + NS + '/' + rel); }
function write(f, j) { stamp(f, j); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(j, null, 2) + '\n'); }
const cns = s => readJson(path.join(CONTENT, s + '.json'), {}).namespace || 'moddeus'; // a content id's mod namespace (per-mod namespaces)
function readJson(f, d) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } }
function lang(k, v) { const f = path.join(A, 'lang/en_us.json'), l = readJson(f, {}); l[k] = v; write(f, Object.fromEntries(Object.entries(l).sort())); }
function itemTag(t, v) { const [n, name] = t.split(':'), f = path.join(RES, 'data', n, 'tags/item', name + '.json'), j = readJson(f, { replace: false, values: [] }); if (!j.values.includes(v)) j.values.push(v); write(f, j); }
function tag(t, v, add) { const [n, name] = t.split(':'), f = path.join(RES, 'data', n, 'tags/block', name + '.json'), j = readJson(f, { replace: false, values: [] }); j.values = j.values.filter(x => x !== v); if (add) j.values.push(v); write(f, j); }

/** Data destination: mod resources (default) or a world datapack with --pack <Id>. */
function dataDir() {
  if (!opt.pack) return { dir: path.join(RES, 'data/' + NS) };
  const id = one(opt.pack), nsp = snake(id), saves = path.join(ROOT, 'run/saves');
  const worlds = fs.readdirSync(saves).map(w => path.join(saves, w)).filter(w => fs.existsSync(path.join(w, 'level.dat')));
  const world = worlds.find(w => fs.existsSync(path.join(w, 'datapacks', id + '-DP'))) || worlds.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0] || die('no world in run/saves');
  const dp = path.join(world, 'datapacks', id + '-DP');
  if (!fs.existsSync(path.join(dp, 'pack.mcmeta'))) write(path.join(dp, 'pack.mcmeta'), { pack: { description: id, min_format: [121, 0], max_format: [121, 0] } });
  if (!fs.existsSync(path.join(dp, 'data', nsp, 'function/uninstall.mcfunction'))) { fs.mkdirSync(path.join(dp, 'data', nsp, 'function'), { recursive: true }); fs.writeFileSync(path.join(dp, 'data', nsp, 'function/uninstall.mcfunction'), '# recipes/advancements leave with /datapack disable\n'); }
  return { dir: path.join(dp, 'data', nsp), pack: { ns: nsp } };
}

// ---- tiny PNG encoder + procedural 16x16 textures -----------------------------------------------------------
const CRC = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function crc(b) { let c = 0xffffffff; for (const x of b) c = CRC[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(t, d) { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); }
export function encode(cv) {
  const { w, h, d } = cv, raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; d.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
export const hex = s => { const v = parseInt(String(s).replace('#', ''), 16); return [v >> 16 & 255, v >> 8 & 255, v & 255]; };
export const sh = (c, f) => c.map(x => Math.max(0, Math.min(255, Math.round(f >= 0 ? x + (255 - x) * f : x * (1 + f)))));
export function canvas(w = 16, h = 16) {
  const d = Buffer.alloc(w * h * 4); let seed = 7;
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const cv = { w, h, d, rnd,
    px(x, y, c, a = 255) { x |= 0; y |= 0; if (x < 0 || y < 0 || x >= w || y >= h) return; const i = (y * w + x) * 4; d[i] = c[0]; d[i + 1] = c[1]; d[i + 2] = c[2]; d[i + 3] = a; },
    rect(x, y, rw, rh, c) { for (let j = y; j < y + rh; j++) for (let i = x; i < x + rw; i++) cv.px(i, j, c); },
    noise(c, amt) { for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) cv.px(x, y, sh(c, (rnd() - 0.5) * amt)); },
    frame(c) { for (let i = 0; i < w; i++) { cv.px(i, 0, sh(c, -0.45)); cv.px(i, h - 1, sh(c, -0.45)); cv.px(0, i, sh(c, -0.45)); cv.px(w - 1, i, sh(c, -0.45)); cv.px(i, 1, sh(c, 0.18)); cv.px(1, i, sh(c, 0.18)); } for (const [x, y] of [[2, 2], [13, 2], [2, 13], [13, 13]]) cv.px(x, y, sh(c, 0.4)); },
  };
  return cv;
}
function texPad(action, base, accent) {
  const cv = canvas(), b = hex(base), a = hex(accent);
  cv.noise(b, 0.12); cv.frame(b);
  const glow = (x, y) => { cv.px(x, y, sh(a, 0.35)); };
  if (action === 'launch') for (const oy of [3, 7, 11]) for (let i = 0; i < 4; i++) { cv.px(7 - i, oy + i, a); cv.px(8 + i, oy + i, a); glow(7 - i, oy + i - 1 < 0 ? 0 : oy + i - 1); }
  else if (action === 'elevator') { for (let i = 0; i < 4; i++) { cv.rect(7 - i, 2 + i, 2 + 2 * i, 1, a); cv.rect(7 - i, 13 - i, 2 + 2 * i, 1, sh(a, -0.25)); } cv.rect(7, 6, 2, 4, sh(b, 0.25)); }
  else if (action === 'teleport') { for (let t = 0; t < 64; t++) { const r = 5.2, an = t / 64 * Math.PI * 2; cv.px(7.5 + Math.cos(an) * r, 7.5 + Math.sin(an) * r, a); } for (let t = 0; t < 6; t++) { const an = t / 6 * Math.PI * 2; cv.px(7.5 + Math.cos(an) * 3, 7.5 + Math.sin(an) * 3, sh(a, 0.4)); } cv.rect(7, 7, 2, 2, sh(a, 0.6)); }
  else if (action === 'conveyor') { for (let y = 1; y < 15; y += 3) cv.rect(2, y, 12, 1, sh(b, 0.15)); for (let i = 0; i < 5; i++) cv.rect(7 - i, 3 + i, 2 + 2 * i, 1, a); cv.rect(6, 8, 4, 5, a); }
  else if (action === 'trap') { for (const [x, y] of [[3, 3], [9, 3], [6, 8], [3, 12], [11, 11]]) { cv.px(x + 1, y - 1, [220, 220, 225]); cv.rect(x, y, 3, 1, [170, 170, 178]); cv.rect(x - 1, y + 1, 5, 1, sh(a, -0.2)); } }
  else { cv.rect(5, 5, 6, 6, sh(a, -0.3)); cv.rect(6, 6, 4, 4, a); cv.rect(7, 7, 2, 2, sh(a, 0.6)); }
  return cv;
}
function texSide(base, accent) { const cv = canvas(), b = hex(base); cv.noise(b, 0.12); cv.frame(b); cv.rect(1, 3, 14, 2, hex(accent)); cv.rect(1, 4, 14, 1, sh(hex(accent), -0.3)); return cv; }
function texMachine(c) { const cv = canvas(), m = [110, 116, 126], a = hex(c); cv.noise(m, 0.1); cv.frame(m); cv.rect(3, 3, 10, 7, [34, 36, 42]); for (let x = 4; x < 12; x += 2) cv.rect(x, 4, 1, 5, [60, 64, 72]); cv.rect(4, 11, 8, 2, sh(a, -0.35)); cv.rect(5, 11, 3, 2, a); cv.px(11, 12, sh(a, 0.5)); return cv; }
function texMachineTop(c) { const cv = canvas(), m = [100, 106, 116]; cv.noise(m, 0.1); cv.frame(m); for (let y = 4; y < 12; y += 2) cv.rect(4, y, 8, 1, [40, 42, 48]); cv.px(7, 7, hex(c)); cv.px(8, 8, hex(c)); return cv; }
function texShop(top) { const cv = canvas(), w = [138, 90, 43]; cv.noise(w, 0.1); for (let y = 3; y < 16; y += 4) cv.rect(0, y, 16, 1, sh(w, -0.35)); cv.frame(w); if (top) { for (let t = 0; t < 40; t++) { const an = t / 40 * Math.PI * 2; cv.px(7.5 + Math.cos(an) * 3.5, 7.5 + Math.sin(an) * 3.5, [242, 193, 78]); } cv.rect(6, 6, 4, 4, [250, 214, 110]); cv.rect(7, 6, 1, 4, [200, 150, 50]); } else { cv.rect(1, 1, 14, 3, [190, 40, 40]); for (let x = 1; x < 15; x += 4) cv.rect(x, 1, 2, 3, [240, 240, 230]); } return cv; }
function texBricks() { const cv = canvas(), s = [122, 122, 122]; cv.noise(s, 0.1); for (let y = 0; y < 16; y += 4) { cv.rect(0, y + 3, 16, 1, [86, 86, 86]); for (let x = (y / 4) % 2 ? 4 : 0; x < 16; x += 8) cv.rect(x, y, 1, 3, [86, 86, 86]); } cv.px(10, 9, [100, 100, 100]); cv.px(11, 10, [100, 100, 100]); return cv; }
function texOre(c) { const cv = canvas(), s = [125, 125, 125], a = hex(c); cv.noise(s, 0.16); for (const [x, y] of [[3, 3], [10, 2], [6, 8], [12, 10], [2, 12], [8, 13]]) { cv.px(x, y, a); cv.px(x + 1, y, sh(a, 0.35)); cv.px(x, y + 1, sh(a, -0.3)); cv.px(x + 1, y + 1, a); } return cv; }
function texGem(c) { const cv = canvas(), a = hex(c); const rows = ['......####......', '.....#####......', '....########....', '...##########...', '..############..', '...##########...', '....########....', '.....######.....', '......####......', '.......##.......']; rows.forEach((r, y) => [...r].forEach((ch, x) => { if (ch === '#') cv.px(x, y + 3, sh(a, (x + y) % 5 === 0 ? 0.45 : (x < 8 ? 0.1 : -0.2))); })); return cv; }
export function texFruit(c) { const cv = canvas(), a = hex(c); for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) { const dx = x - 7.5, dy = y - 9; const r = dx * dx + dy * dy; if (r < 26) cv.px(x, y, sh(a, r < 6 && dx < 0 && dy < 0 ? 0.45 : (dx + dy) * 0.04)); } cv.rect(7, 2, 2, 3, [70, 140, 50]); cv.rect(9, 3, 3, 1, [90, 170, 60]); return cv; }
function texSeeds(c) { const cv = canvas(), a = sh(hex(c), -0.4); for (const [x, y] of [[4, 6], [9, 4], [7, 9], [11, 10], [5, 12]]) { cv.rect(x, y, 2, 2, [170, 140, 80]); cv.px(x, y, a); } return cv; }
function texStage(s, n, c) {
  const cv = canvas(), g = [70, 150, 50], top = 15 - Math.round(4 + 10 * (s + 1) / n);
  for (const x of [3, 7, 12]) { for (let y = top + (x % 3); y < 16; y++) cv.px(x, y, sh(g, (16 - y) * 0.02)); if (s > 0) { cv.px(x - 1, top + 3, sh(g, 0.2)); cv.px(x + 1, top + 5, sh(g, 0.2)); } }
  if (s === n - 1) for (const [x, y] of [[2, top + 2], [8, top + 4], [11, top + 1], [4, top + 7]]) { cv.rect(x, y, 2, 2, hex(c)); cv.px(x, y, sh(hex(c), 0.4)); }
  return cv;
}
function texDisc(c) { const cv = canvas(), a = hex(c); for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) { const r = Math.hypot(x - 7.5, y - 7.5); if (r < 7.2) cv.px(x, y, r < 2.6 ? (r < 1 ? [20, 20, 20] : a) : (Math.round(r) % 2 ? [30, 30, 36] : [44, 44, 52])); } cv.px(4, 4, [120, 120, 140]); cv.px(5, 3, [90, 90, 110]); return cv; }

function vanillaItem(name) { // client jar has assets/minecraft/items/<name>.json for every vanilla item
  try { const d = path.join(ROOT, '.gradle/loom-cache/minecraftMaven/net/minecraft'); const dir = fs.readdirSync(d).find(x => x.startsWith('minecraft-clientOnly')); const v = fs.readdirSync(path.join(d, dir))[0]; const jar = path.join(d, dir, v, fs.readdirSync(path.join(d, dir, v)).find(f => f.endsWith('.jar') && !f.includes('sources'))); return execFileSync('unzip', ['-l', jar, 'assets/minecraft/items/' + name + '.json']).toString().includes(name + '.json'); } catch { return false; }
}

// ---- helpers -------------------------------------------------------------------------------------------------
function earn(s) { const o = {}; for (const kv of String(s || '').split(',').map(x => x.trim()).filter(Boolean)) { const i = kv.lastIndexOf('='); o[i < 0 ? kv : kv.slice(0, i)] = i < 0 ? 1 : Number(kv.slice(i + 1)); } return o; }
function resolve(s) { s = s.trim(); if (s.includes(':')) return s; return fs.existsSync(path.join(CONTENT, s + '.json')) ? cns(s) + ':' + s : 'minecraft:' + s; }
const one = v => Array.isArray(v) ? v[v.length - 1] : v;
const ns = s => (String(s).includes(':') || String(s).startsWith('#') ? String(s) : 'minecraft:' + s);
const title = s => s.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
const snake = s => String(s).replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^A-Za-z0-9]+/g, '_').toLowerCase();
function cmds(s) {
  const out = []; let cur = '', depth = 0, q = false;
  for (const c of s || '') { if (c === '"') q = !q; else if (!q && '[{'.includes(c)) depth++; else if (!q && ']}'.includes(c)) depth--; if (c === ';' && !q && depth === 0) { if (cur.trim()) out.push(cur.trim().replace(/^\//, '')); cur = ''; } else cur += c; }
  if (cur.trim()) out.push(cur.trim().replace(/^\//, ''));
  return out;
}
function parse(rest) {
  const pos = [], o = {};
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (!a.startsWith('--')) { pos.push(a); continue; }
    const k = a.slice(2), v = rest[i + 1] !== undefined && !rest[i + 1].startsWith('--') ? rest[++i] : true;
    o[k] = o[k] === undefined ? v : [].concat(o[k], v);
  }
  return { pos, opt: o };
}
