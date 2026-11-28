// Gameplay systems for Moddeus mods, as one-command content (data in src/main/resources/moddeus/content/<id>.json,
// run by core content/Gameplay.java, live, exportable). Dispatched from tools/mc.mjs. Guide: .claude/skills/mc-content/GAMEPLAY.md
//   node tools/mc.mjs boss <id> --mob <entity id> [--name "The Lich"] [--color purple|red|blue|green|yellow|pink|white]
//        [--overlay progress|6|10|12|20] [--health 300] [--radius 48] [--darken] [--music] [--fog] [--tagged]
//        [--phase "<at 0-1> [color] [Bar Name]: cmd; attack <spec>"]...  [--every "<ticks>[@phaseIndex]: attack <spec>; cmd"]...  [--death ".."] [--spawn]
//        boss bar + phases + timed lines on every <mob> (--tagged: only ones from --spawn / spawn(id)). Vanilla commands run as/at the boss;
//        "attack <spec>" = Bosses.attack(boss, spec) (JSON "attack"). Built-in attack names are samples: give the boss its OWN attacks in
//        content/<Mod>*.java (guide BOSSES.md) and call them from there.
//   node tools/mc.mjs setbonus <id> [--pieces a,b,c,d (default moddeus:<id>_helmet..boots)] [--effects "speed:1,night_vision"]
//        [--attr "attack_damage:4,max_health:10"] [--every "<ticks>: cmd; cmd"] [--on "cmd"] [--off "cmd"] [--name "Set bonus: ..."]
//   node tools/mc.mjs drop <id> --from zombie|block:stone|chest:simple_dungeon|<ns:table/path> --item <item> [--count 1-3]
//        [--chance 0.25] [--looting] [--player]   | --pool '<vanilla 26.3 loot pool JSON>'   (adds a pool, keeps vanilla drops)
//   node tools/mc.mjs npc <id> --name "Merlin" [--entity villager|mannequin|<entity id>] [--skin moddeus:entity/<tex>] [--profession librarian]
//        [--line "..."]... [--trade "3 emerald + book -> moddeus:wand"]... [--cmd "cmd; cmd" (runs as the player after the last line)] [--spawn]
//   all: [--tab "<Mod Name>"] (owns it for Export) [--json '{...}'] (merged raw)   spawn later: game run 'spawn("<id>");'
import fs from 'node:fs';
import path from 'node:path';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(2).map(l => l.slice(3)).join('\n');
const CMDS = ['boss', 'setbonus', 'drop', 'npc'];
/** Boss lines: "attack <spec>" -> {attack: [...]} (Bosses.attack, no command), the rest -> {cmd: [...]} (vanilla commands). */
const lines = list => { const r = {}; for (const l of list) { const m = l.match(/^(?:boss)?attack\s+(.+)$/); (r[m ? 'attack' : 'cmd'] ??= []).push(m ? m[1] : l); } return r; };

/** Returns false when cmd isn't one of ours. */
export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  const { pos, opt } = parse(rest);
  const id = pos[0];
  if (!/^[a-z0-9_]+$/.test(id || '')) ctx.die('usage: ' + cmd + ' <id> ...  (id: a-z 0-9 _)\n' + HELP);
  const NS = ctx.NS || 'moddeus', NSP = NS + ':'; // the mod's namespace (mc.mjs NS)
  const item = s => resolve(ctx, s);
  let o, files = [];
  if (cmd === 'boss') {
    o = { type: 'boss', mob: ns(one(opt.mob) || 'zombie'), color: one(opt.color) || 'purple', overlay: one(opt.overlay) || 'progress' };
    for (const k of ['name']) if (opt[k]) o[k] = one(opt[k]);
    for (const k of ['health', 'radius']) if (opt[k]) o[k] = Number(one(opt[k]));
    for (const k of ['darken', 'music', 'fog', 'tagged']) if (opt[k]) o[k] = true; // tagged: only spawned/tagged ones, not every <mob>
    o.phases = (opt.phase || []).map(s => {
      const [head, body] = splitColon(s), w = head.split(/\s+/).filter(Boolean), p = { at: Number(w.shift()) };
      if (p.at > 1) p.at /= 100; // "50" = 50%
      if (w[0] && COLORS.includes(w[0].toLowerCase())) p.color = w.shift().toLowerCase();
      if (w.length) p.name = w.join(' ');
      return Object.assign(p, lines(cmds(body)));
    });
    o.every = (opt.every || []).map(s => { const [head, body] = splitColon(s), [t, ph] = head.split('@'); const e = { ticks: Number(t), ...lines(cmds(body)) }; if (ph !== undefined) e.phase = Number(ph); return e; });
    if (opt.death) { const d = lines(cmds(one(opt.death))); if (d.cmd) o.death = d.cmd; if (d.attack) o.death_attack = d.attack; }
  } else if (cmd === 'setbonus') {
    const pieces = opt.pieces ? one(opt.pieces).split(',').map(s => item(s.trim()))
      : ['helmet', 'chestplate', 'leggings', 'boots'].filter(p => fs.existsSync(path.join(ctx.CONTENT, id + '_' + p + '.json'))).map(p => resolve(ctx, id + '_' + p));
    if (!pieces.length) ctx.die('setbonus: no --pieces and no ' + NSP + id + '_helmet.. armor (make it with: mc armor ' + id + ')');
    o = { type: 'setbonus', pieces, name: one(opt.name) || 'Set bonus active' };
    if (opt.effects) o.effects = Object.fromEntries(one(opt.effects).split(',').map(s => { const [k, v] = s.trim().split(':'); return [ns(k), Number(v || 0)]; }));
    if (opt.attr) o.attributes = Object.fromEntries(one(opt.attr).split(',').map(s => { const [k, v] = s.trim().split(':'); return [ns(k), Number(v)]; }));
    if (opt.every) { const [t, body] = splitColon(one(opt.every)); o.every_ticks = Number(t); o.every = cmds(body); }
    if (opt.on) o.on = cmds(one(opt.on));
    if (opt.off) o.off = cmds(one(opt.off));
  } else if (cmd === 'drop') {
    const from = one(opt.from) || ctx.die('drop: --from zombie|block:stone|chest:simple_dungeon|<ns:path>');
    const m = from.match(/^(block|chest|mob|entity):(.+)$/);
    const table = from.includes('/') ? ns(from) : m ? tableOf({ block: 'blocks', chest: 'chests', mob: 'entities', entity: 'entities' }[m[1]], m[2], ctx) : tableOf('entities', from, ctx);
    let pool = opt.pool ? JSON.parse(one(opt.pool)) : null;
    if (!pool) {
      const it = item(one(opt.item) || ctx.die('drop: --item <item> (or --pool)')), [a, b] = String(one(opt.count) || '1').split('-').map(Number);
      const mod = [{ type: 'minecraft:set_count', count: b === undefined ? a : { type: 'minecraft:uniform', min: a, max: b } }];
      if (opt.looting) mod.push({ type: 'minecraft:enchanted_count_increase', count: { type: 'minecraft:uniform', min: 0.0, max: 1.0 }, enchantment: 'minecraft:looting' });
      pool = { rolls: 1, entries: [{ type: 'minecraft:item', name: it, modifier: mod }] };
      const cond = [];
      if (opt.player) cond.push({ type: 'minecraft:killed_by_player' });
      if (opt.chance) cond.push({ type: 'minecraft:random_chance', chance: Number(one(opt.chance)) });
      if (cond.length) pool.condition = cond.length === 1 ? cond[0] : { type: 'minecraft:all_of', terms: cond };
    }
    o = { type: 'drop', table, pool };
    // a moddeus mob without a loot table file never fires the loot event: give it an empty one
    const [tns, tp] = table.split(':');
    const tf = path.join(ctx.ROOT, 'src/main/resources/data', tns, 'loot_table', tp + '.json');
    if (tns !== 'minecraft' && !fs.existsSync(tf)) {
      fs.mkdirSync(path.dirname(tf), { recursive: true });
      fs.writeFileSync(tf, JSON.stringify({ type: 'minecraft:' + (tp.startsWith('blocks/') ? 'block' : tp.startsWith('chests/') ? 'chest' : 'entity'), pools: [] }, null, 2));
      files.push('data/' + tns + '/loot_table/' + tp + '.json');
    }
  } else if (cmd === 'npc') {
    const name = one(opt.name) || id.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
    const ent = ns(one(opt.entity) || 'villager'), tag = 'Tags:["moddeus_npc_' + id + '"]', nm = 'CustomName:' + JSON.stringify(name) + ',CustomNameVisible:1b,Invulnerable:1b';
    const nbt = ent === 'minecraft:mannequin' ? '{' + (opt.skin ? 'profile:{texture:' + JSON.stringify(one(opt.skin)) + ',model:"wide"},' : '') + nm + ',' + tag + '}'
      : '{NoAI:1b,PersistenceRequired:1b,Silent:1b,' + nm + ',' + (ent === 'minecraft:villager' ? 'VillagerData:{profession:"' + ns(one(opt.profession) || 'librarian') + '",level:5,type:"minecraft:plains"},' : '') + tag + '}';
    o = { type: 'npc', name, summon: ent + ' ' + nbt, lines: opt.line || [] };
    o.trades = (opt.trade || []).map(s => {
      const [l, r] = s.split('->').map(x => x.trim()); if (!r) ctx.die('npc --trade "3 emerald [+ 1 book] -> 1 ' + NSP + 'x"');
      const c = l.split('+').map(x => stackOf(item, x)), t = { cost: c[0], result: stackOf(item, r) };
      if (c[1]) t.cost2 = c[1];
      return t;
    });
    if (opt.cmd) o.cmd = cmds(one(opt.cmd));
  }
  if (opt.tab) o.tab = one(opt.tab);
  if (files.length) o.files = files;
  if (opt.json) Object.assign(o, JSON.parse(one(opt.json)));
  const f = path.join(ctx.CONTENT, id + '.json');
  fs.mkdirSync(ctx.CONTENT, { recursive: true });
  ctx.stamp?.(f, o);
  fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n');
  console.log(cmd + ' ' + id + ' -> moddeus/content/' + id + '.json' + (cmd === 'drop' ? ' (loot reloads by itself, ~0.3s)' : ''));
  await ctx.apply();
  if (opt.spawn && (cmd === 'boss' || cmd === 'npc')) await (await import('./game.mjs')).run(['run', 'var e = dev.moddeus.live.Quick.spawn("' + id + '"); print(e == null ? "spawn failed" : "spawned " + e.getName().getString() + " at " + e.blockPosition().toShortString());']);
  return true;
}

const COLORS = ['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white'];
const one = v => Array.isArray(v) ? v[v.length - 1] : v;
const ns = s => (s.includes(':') ? s : 'minecraft:' + s);
function tableOf(dir, name, ctx) { const [n, p] = (ctx ? resolve(ctx, name) : ns(name)).split(':'); return n + ':' + dir + '/' + p; }
function resolve(ctx, s) { // "emerald" -> minecraft:emerald, "wand" -> moddeus:wand if we made it
  if (s.includes(':')) return s;
  const f = path.join(ctx.CONTENT, s + '.json');
  return fs.existsSync(f) ? (JSON.parse(fs.readFileSync(f, 'utf8')).namespace || 'moddeus') + ':' + s : 'minecraft:' + s;
}
function stackOf(item, s) { // "3 emerald" | "emerald*3" | "emerald"
  s = s.trim();
  let m = s.match(/^(\d+)\s*x?\s+(\S+)$/) || s.match(/^(\S+?)\*(\d+)$/);
  if (!m) return { item: item(s), count: 1 };
  return /^\d+$/.test(m[1]) ? { item: item(m[2]), count: Number(m[1]) } : { item: item(m[1]), count: Number(m[2]) };
}
function splitColon(s) { const i = s.indexOf(':'); return i < 0 ? [s.trim(), ''] : [s.slice(0, i).trim(), s.slice(i + 1)]; } // heads ("0.5 red Name", "100@1") never hold ':'
/** "cmd; cmd" -> ["cmd","cmd"], ignoring ; inside [] {} "" (SNBT int arrays [I;1,2]). */
function cmds(s) {
  const out = []; let cur = '', depth = 0, q = false;
  for (const c of s || '') {
    if (c === '"') q = !q;
    else if (!q && '[{'.includes(c)) depth++;
    else if (!q && ']}'.includes(c)) depth--;
    if (c === ';' && !q && depth === 0) { if (cur.trim()) out.push(cur.trim().replace(/^\//, '')); cur = ''; } else cur += c;
  }
  if (cur.trim()) out.push(cur.trim().replace(/^\//, ''));
  return out;
}
/** argv -> positionals + options; a repeated --flag collects an array. */
function parse(rest) {
  const pos = [], opt = {};
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (!a.startsWith('--')) { pos.push(a); continue; }
    const k = a.slice(2), v = rest[i + 1] !== undefined && !rest[i + 1].startsWith('--') ? rest[++i] : true;
    opt[k] = opt[k] === undefined ? v : [].concat(opt[k], v);
  }
  for (const k of ['phase', 'every', 'line', 'trade']) if (opt[k] !== undefined) opt[k] = [].concat(opt[k]);
  return { pos, opt };
}
