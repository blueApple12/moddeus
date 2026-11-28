// Dimensions for Moddeus (both kinds). Used via node tools/mc.mjs dimension ...
//   dimension <id> [--kind datapack|java] [--mod <Id>] [--preset void|flat|islands|caves|noise]
//        [--block minecraft:stone] [--fluid minecraft:water] [--biome minecraft:plains] [--layers "minecraft:bedrock,minecraft:dirt*3,minecraft:grass_block"]
//        [--sky "#78a7ff"] [--fog "#c0d8ff"] [--clouds "#ccffffff"|none] [--ambient 0..1] [--skybox overworld|end|none] [--min-y -64] [--height 384]
//        [--portal minecraft:crying_obsidian]   (datapack: sneak on that block = travel there / back)
//        datapack -> run/saves/<world>/datapacks/<Id>-DP/data/<ns>/{dimension,dimension_type}/<id>.json + function <ns>:<id>/go|back
//        java     -> src/main/resources/data/moddeus/{dimension,dimension_type}/<snake_id>_<id>.json (Java: Dimensions.go(player, "moddeus:<file>"))
//        Vanilla loads new dimensions only at world load; Moddeus loads them LIVE on the reload this runs (live/Dimensions.syncData).
//   dimension go <ns:id> | back | list | remove <id> [--kind ..] [--mod ..]
// Guide: .claude/skills/mc-content/DIMENSIONS.md
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LIB = path.join(ROOT, 'tools/library/dimension');
const RES = path.join(ROOT, 'src/main/resources/data/moddeus');
const die = m => { console.error('dimension: ' + m); process.exit(1); };
const snake = s => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^A-Za-z0-9]+/g, '_').toLowerCase();
const pascal = s => s.split(/[^A-Za-z0-9]+/).filter(Boolean).map(w => w[0].toUpperCase() + w.slice(1)).join('');
const lib = n => JSON.parse(fs.readFileSync(path.join(LIB, n + '.json'), 'utf8'));
const write = (f, s) => { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, typeof s === 'string' ? s : JSON.stringify(s, null, 2)); console.log('wrote ' + path.relative(ROOT, f)); };
const mc = (...a) => { const r = spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), ...a], { encoding: 'utf8' }); return (r.stdout || '') + (r.stderr || ''); };
const live = () => fs.existsSync(path.join(ROOT, 'run/moddeus/game.json'));
const java = code => mc('game', 'run', code);

function parse(args) {
  const pos = [], opt = {};
  for (let i = 0; i < args.length; i++) {
    if (!args[i].startsWith('--')) { pos.push(args[i]); continue; }
    const k = args[i].slice(2), v = args[i + 1];
    if (v === undefined || v.startsWith('--')) opt[k] = true; else { opt[k] = v; i++; }
  }
  return { pos, opt };
}

function saves() {
  const d = path.join(ROOT, 'run/saves');
  return !fs.existsSync(d) ? [] : fs.readdirSync(d).map(w => path.join(d, w)).filter(w => fs.existsSync(path.join(w, 'level.dat')))
    .sort((a, b) => fs.statSync(path.join(b, 'level.dat')).mtimeMs - fs.statSync(path.join(a, 'level.dat')).mtimeMs);
}

/** Where the files go: { dir (data root), ns, file (id inside ns), kind }. */
function target(id, opt) {
  const kind = opt.kind || 'datapack';
  const mod = opt.mod || pascal(id);
  if (kind === 'java') { const s = snake(mod); return { kind, mod, ns: 'moddeus', file: snake(id).startsWith(s) ? snake(id) : s + '_' + snake(id), dir: RES.replace(/[\\/]moddeus$/, '') }; }
  if (kind !== 'datapack') die('--kind datapack|java');
  const world = saves().find(w => fs.existsSync(path.join(w, 'datapacks', mod + '-DP'))) || saves()[0];
  if (!world) die('no world in run/saves');
  const dp = path.join(world, 'datapacks', mod + '-DP'), data = path.join(dp, 'data');
  const nsDir = fs.existsSync(data) && fs.readdirSync(data).find(n => n !== 'minecraft');
  return { kind, mod, dp, ns: opt.ns || nsDir || snake(mod), file: snake(id), dir: data };
}

function dimensionType(o, preset) {
  const t = lib('type_overworld');
  if (preset === 'caves') t.has_ceiling = true;
  if (o['min-y'] !== undefined) t.min_y = Number(o['min-y']);
  if (o.height !== undefined) t.height = t.logical_height = Number(o.height);
  if (o.ambient !== undefined) t.ambient_light = Number(o.ambient);
  if (o.skybox) t.skybox = o.skybox;
  const a = t.attributes;
  if (o.sky) a['minecraft:visual/sky_color'] = o.sky;
  if (o.fog) a['minecraft:visual/fog_color'] = o.fog;
  if (o.clouds === 'none' || preset === 'caves') { delete a['minecraft:visual/cloud_height']; a['minecraft:visual/cloud_color'] = '#00000000'; } // cloud_height alone may come from defaults: transparent clouds = none
  else if (o.clouds) a['minecraft:visual/cloud_color'] = o.clouds;
  return t;
}

function generator(o, preset) {
  const biome = o.biome || ({ void: 'minecraft:the_void', caves: 'minecraft:dripstone_caves' }[preset] || 'minecraft:plains');
  if (preset === 'void' || preset === 'flat') {
    const layers = preset === 'void' ? [] : (o.layers || 'minecraft:bedrock,minecraft:dirt*2,minecraft:grass_block').split(',').map(s => {
      const [block, n] = s.trim().split('*'); return { block: block.includes(':') ? block : 'minecraft:' + block, height: Number(n || 1) };
    });
    return { type: 'minecraft:flat', settings: { biome, features: preset === 'void', lakes: false, layers, structure_overrides: [] } };
  }
  const base = { islands: 'floating_islands', caves: 'caves', noise: 'overworld' }[preset];
  if (!base) die('--preset void|flat|islands|caves|noise');
  let settings = 'minecraft:' + base;
  if (o.block || o.fluid) { // inline copy of the vanilla settings with other blocks
    settings = lib('noise_' + base);
    if (o.block) settings.default_block = o.block.includes(':') ? o.block : 'minecraft:' + o.block;
    if (o.fluid) settings.default_fluid = o.fluid.includes(':') ? o.fluid : 'minecraft:' + o.fluid;
  }
  const biome_source = preset === 'noise' && !o.biome ? { type: 'minecraft:multi_noise', preset: 'minecraft:overworld' } : { type: 'minecraft:fixed', biome };
  return { type: 'minecraft:noise', biome_source, settings };
}

function functions(t, type, o) {
  const fn = path.join(t.dir, t.ns, 'function', t.file), id = t.ns + ':' + t.file, st = t.ns + ':dim ' + t.file;
  const cap = type.min_y + (type.has_ceiling ? type.logical_height - 2 : type.height - 1);
  write(path.join(fn, 'go.mcfunction'), [
    '# go to ' + id + ' (remembers where you were for back; one slot per dimension = singleplayer)',
    'execute if dimension ' + id + ' run return fail',
    'data modify storage ' + st + '.dim set from entity @s Dimension',
    ...['x', 'y', 'z'].map((k, i) => 'data modify storage ' + st + '.' + k + ' set from entity @s Pos[' + i + ']'),
    'data modify storage ' + st + '.yaw set from entity @s Rotation[0]',
    'data modify storage ' + st + '.pitch set from entity @s Rotation[1]',
    'execute in ' + id + ' run spreadplayers 0 0 0 64 under ' + cap + ' false @s',
    'execute at @s unless dimension ' + id + ' run tellraw @s {"text":"No safe ground near 0,0 in ' + id + '","color":"red"}', ''].join('\n'));
  write(path.join(fn, 'back.mcfunction'), [
    '# back to exactly where go was used',
    'execute unless data storage ' + st + ' run return fail',
    'function ' + id + '/back_at with storage ' + st, 'data remove storage ' + st, ''].join('\n'));
  write(path.join(fn, 'back_at.mcfunction'), '$execute in $(dim) run tp @s $(x) $(y) $(z) $(yaw) $(pitch)\n');
  if (!o.portal) return;
  const tag = t.ns + '_' + t.file + '_tp', block = o.portal.includes(':') ? o.portal : 'minecraft:' + o.portal;
  write(path.join(t.dir, t.ns, 'predicate', t.file + '_sneak.json'), { type: 'minecraft:entity_properties', entity: 'this', predicate: { 'minecraft:type_specific/player': { input: { sneak: true } } } });
  write(path.join(fn, 'portal.mcfunction'), [
    '# sneak on ' + block + ' = travel to ' + id + ' / back (tag stops ping-pong until you stop sneaking)',
    'execute as @a[tag=' + tag + '] unless predicate ' + t.ns + ':' + t.file + '_sneak run tag @s remove ' + tag,
    'execute as @a[tag=!' + tag + '] at @s if block ~ ~-1 ~ ' + block + ' if predicate ' + t.ns + ':' + t.file + '_sneak run function ' + id + '/travel', ''].join('\n'));
  write(path.join(fn, 'travel.mcfunction'), ['tag @s add ' + tag, 'execute if dimension ' + id + ' run return run function ' + id + '/back', 'function ' + id + '/go', ''].join('\n'));
  const tick = path.join(t.dir, 'minecraft/tags/function/tick.json');
  const j = fs.existsSync(tick) ? JSON.parse(fs.readFileSync(tick, 'utf8')) : { values: [] };
  if (!j.values.includes(id + '/portal')) j.values.push(id + '/portal');
  write(tick, j);
}

const MARK = id => ['# >>> dimension ' + id, '# <<< dimension ' + id];

function uninstall(t, add) {
  const f = path.join(t.dir, t.ns, 'function/uninstall.mcfunction'), id = t.ns + ':' + t.file, [a, b] = MARK(id);
  let s = fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : '';
  s = s.replace(new RegExp('\\n?' + a.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[\\s\\S]*?' + b + '\\n?'), '\n');
  if (add) s = [a, 'execute as @a at @s if dimension ' + id + ' run function ' + id + '/back', 'tag @a remove ' + t.ns + '_' + t.file + '_tp', 'data remove storage ' + t.ns + ':dim ' + t.file, b].join('\n') + '\n' + s.replace(/^\n+/, '');
  if (s.trim() || fs.existsSync(f)) write(f, s);
}

function create(id, o) {
  const t = target(id, o), preset = o.preset || 'void';
  const type = dimensionType(o, preset), dimId = t.ns + ':' + t.file;
  if (t.kind === 'datapack' && !fs.existsSync(path.join(t.dp, 'pack.mcmeta')))
    write(path.join(t.dp, 'pack.mcmeta'), { pack: { min_format: [121, 0], max_format: [121, 0], description: t.mod } });
  write(path.join(t.dir, t.ns, 'dimension_type', t.file + '.json'), type);
  write(path.join(t.dir, t.ns, 'dimension', t.file + '.json'), { type: dimId, generator: generator(o, preset) });
  if (t.kind === 'datapack') { functions(t, type, o); uninstall(t, true); }
  if (o['no-live'] || !live()) return console.log('dimension ' + dimId + ' written (loads at next world load; game not running)');
  if (t.kind === 'datapack') console.log(mc('game', 'cmd', 'reload').trim());
  else console.log(mc('build').trim()); // copies resources; data/ changes do a datapack reload by themselves
  for (let i = 0; i < 20; i++) { // the live load runs when the reload finishes
    const r = java('print(dev.moddeus.live.Dimensions.get("' + dimId + '") != null);');
    if (r.includes('true')) return console.log('LIVE: ' + dimId + (t.kind === 'datapack' ? '  (function ' + dimId + '/go, ' + dimId + '/back' + (o.portal ? ', sneak on ' + o.portal : '') + ')' : '  (Java: Dimensions.go(player, "' + dimId + '") / Dimensions.back(player))'));
    spawnSync(process.execPath, ['-e', 'setTimeout(()=>{},300)']);
  }
  console.log('not live yet: check node tools/mc.mjs game log --grep dimension');
}

function remove(id, o) {
  const t = target(id.split(':').pop(), o), dimId = t.ns + ':' + t.file;
  if (live()) console.log(java('print(dev.moddeus.live.Dimensions.remove("' + dimId + '", true));').trim()); // players come home, files deleted
  for (const f of [path.join(t.dir, t.ns, 'dimension', t.file + '.json'), path.join(t.dir, t.ns, 'dimension_type', t.file + '.json'), path.join(t.dir, t.ns, 'predicate', t.file + '_sneak.json')])
    if (fs.existsSync(f)) { fs.rmSync(f); console.log('removed ' + path.relative(ROOT, f)); }
  if (t.kind === 'datapack') {
    fs.rmSync(path.join(t.dir, t.ns, 'function', t.file), { recursive: true, force: true });
    const tick = path.join(t.dir, 'minecraft/tags/function/tick.json');
    if (fs.existsSync(tick)) { const j = JSON.parse(fs.readFileSync(tick, 'utf8')); j.values = j.values.filter(v => v !== dimId + '/portal'); if (j.values.length) write(tick, j); else fs.rmSync(tick); }
    uninstall(t, false);
    if (live()) console.log(mc('game', 'cmd', 'reload').trim());
  } else if (live()) console.log(mc('build').trim());
}

export async function run(args) {
  const { pos, opt } = parse(args);
  const [a, b] = pos;
  if (!a) die('usage: dimension <id> [--kind datapack|java] [--preset void|flat|islands|caves|noise] ... | go <ns:id> | back | list | remove <id>');
  if (a === 'list') return console.log(java('print(dev.moddeus.live.Dimensions.list());').trim());
  if (a === 'go') return console.log(java('dev.moddeus.live.Dimensions.go(me(), "' + b + '"); print("in " + me().level().dimension().identifier());').trim());
  if (a === 'back') return console.log(java('dev.moddeus.live.Dimensions.back(me()); print("back in " + me().level().dimension().identifier());').trim());
  if (a === 'remove') { if (!b) die('remove <id>'); return remove(b, opt); }
  if (!/^[a-z0-9_]+$/.test(a)) die('id: lowercase letters, digits, _');
  create(a, opt);
}
