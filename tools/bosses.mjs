// Bosses & mobs (core live/Bosses.java, guide .claude/skills/mc-content/BOSSES.md). Dispatched from tools/mc.mjs.
//   node tools/mc.mjs mobai <id> --mob <entity id> [--flying "height=6 speed=0.3"] [--caster "projectile=fireball every=40 keep=6"]
//        [--leaper "range=12"] [--burrower "every=120"] [--pack "name=wolves"] [--pet "item=bone chance=0.3"] [--mount "speed=0.35 fly=1 saddle=1"]
//        [--variant "fiery,giant"|random] [--variant-chance 0.15]      behaviours/elite affixes for EVERY mob of that type (spawned or loaded)
//   node tools/mc.mjs spawnrule <id> --mob <entity id> [--biomes "desert,#minecraft:is_forest"] [--time night|day|any] [--light 0..7]
//        [--y -64..40] [--cave] [--water] [--dimension overworld] [--chance 0.1] [--max 4] [--group 1-3] [--nbt '{...}']
//        [--behave "flying height=6" ...] [--variant random --variant-chance 0.1] [--cmd "cmd; cmd"]   natural spawning, checked every 2 s near players
//   node tools/mc.mjs bossattacks          lists the engine's sample attacks / behaviours / affixes (Java API)
//   all: [--tab "<Mod Name>"]  (owns it for Export)
import fs from 'node:fs';
import path from 'node:path';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(1).map(l => l.slice(3)).join('\n');
const CMDS = ['mobai', 'spawnrule', 'bossattacks'];
const BEH = ['flying', 'caster', 'leaper', 'burrower', 'pack', 'pet', 'mount'];
const PRESETS = 'slam charge leap meteors summon laser teleport blades spikes shield enrage grab roar telegraph cycle random intro music lock death';
const AFFIXES = 'fiery frost vampiric swift armored giant tiny explosive thorns teleporter summoner regen wither';

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  if (cmd === 'bossattacks') {
    console.log('Java (mod code or mc game run; no commands):\n  Bosses.attack(boss, "<name> [k=v ...]")   samples: ' + PRESETS + '   (boss JSON "attack" lines / mc boss "attack <spec>")'
      + '\n  Bosses.behave(mob, "<' + BEH.join('|') + '|clear>", "k=v ...")\n  Bosses.variant(mob, "<affix,...|random> [scale=1.5]")   affixes: ' + AFFIXES
      + '\n  Bosses.arena(level, pos, Bosses.args("r=12 h=4 style=stone|nether|end|ice|jungle name=x")) | Bosses.arenaUndo("x")\n  Bosses.chest(level, pos, "minecraft:chests/end_city_treasure", "#ffd040")\nDetails: .claude/skills/mc-content/BOSSES.md');
    console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/BOSSES.md)");
    return true;
  }
  const { pos, opt } = parse(rest);
  const id = pos[0];
  if (!/^[a-z0-9_]+$/.test(id || '')) ctx.die('usage: ' + cmd + ' <id> ...  (id: a-z 0-9 _)\n' + HELP);
  const cf = s => path.join(ctx.CONTENT, s + '.json'); // a bare id of our content -> its mod's namespace
  const ns = s => (s.includes(':') ? s : (fs.existsSync(cf(s)) ? JSON.parse(fs.readFileSync(cf(s), 'utf8')).namespace || 'moddeus' : 'minecraft') + ':' + s);
  const mob = opt.mob ? ns(one(opt.mob)) : ctx.die(cmd + ': --mob <entity id> (zombie, moddeus:my_mob, ...)');
  const o = { type: cmd, mob };
  const behave = {};
  for (const b of BEH) if (opt[b] !== undefined) behave[b] = opt[b] === true ? '' : one(opt[b]);
  for (const s of [].concat(opt.behave || [])) { const [b, ...a] = String(s).trim().split(/\s+/); if (!BEH.includes(b)) ctx.die('behaviours: ' + BEH.join(' ')); behave[b] = a.join(' '); }
  if (Object.keys(behave).length || opt.variant) console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/BOSSES.md)");
  if (Object.keys(behave).length) o.behave = behave;
  if (opt.variant) o.variant = one(opt.variant);
  if (opt['variant-chance']) o.variant_chance = Number(one(opt['variant-chance']));
  if (cmd === 'spawnrule') {
    if (opt.biomes) o.biomes = one(opt.biomes).split(',').map(s => s.trim()).filter(Boolean);
    for (const k of ['time', 'light', 'y', 'dimension', 'group', 'nbt']) if (opt[k] !== undefined) o[k] = String(one(opt[k]));
    for (const k of ['chance', 'max']) if (opt[k] !== undefined) o[k] = Number(one(opt[k]));
    for (const k of ['cave', 'water']) if (opt[k]) o[k] = true;
    if (opt.light && !String(o.light).includes('..')) o.light = '0..' + o.light;
    if (opt.cmd) o.cmd = one(opt.cmd).split(';').map(s => s.trim().replace(/^\//, '')).filter(Boolean);
  }
  if (opt.tab) o.tab = one(opt.tab);
  if (opt.json) Object.assign(o, JSON.parse(one(opt.json)));
  fs.mkdirSync(ctx.CONTENT, { recursive: true });
  ctx.stamp?.(path.join(ctx.CONTENT, id + '.json'), o);
  fs.writeFileSync(path.join(ctx.CONTENT, id + '.json'), JSON.stringify(o, null, 2) + '\n');
  console.log(cmd + ' ' + id + ' -> moddeus/content/' + id + '.json');
  await ctx.apply();
  return true;
}

const one = v => Array.isArray(v) ? v[v.length - 1] : v;
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
