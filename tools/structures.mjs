// Structures, terrain and worldgen for Moddeus (both kinds). Core: src/main/java/dev/moddeus/live/Structures.java. Guide: .claude/skills/mc-content/STRUCTURES.md
//   struct <tower|castle|dungeon|ruins|temple|house|bridge|arena|statue|portal|maze|chest|spawner> [size=N height=N palette=stone decay=0.3 seed=N
//          at=front|look|here|x,y,z facing=north dist=N mob=zombie,skeleton loot=minecraft:chests/simple_dungeon roof=cone|flat pose=stand|raise|wide style=nether|ring ...]
//   struct palettes | undo [n] | redo | ops | pos1 | pos2 [at=look] | sel set|walls|outline|hollow <block> | sel replace <a> <b> | sel copy | paste [rotate=90 mirror=lr]
//   struct sel save <name> [--datapack <Id>] | place <ns:name> [rotate= mirror= at=] | list [filter] | feature <ns:feature|placed:ns:id> [at=] | vanilla <radius> <command...>
//   terrain <sphere|cylinder|crater|mountain|hill|river|flatten|noise|smooth|overlay|replace|drain|island> [r=N height= depth= width= length= amp= scale= block= from= to= y= hollow at=]
//   worldgen ore <id> --block <b> [--deepslate <b>] [--in stone|deepslate|both|netherrack|end_stone|<block>|#tag] [--size 8] [--count 6] [--y -64..32]
//   worldgen tree <id> --log <b> --leaves <b> [--foliage blob|fancy|pine|spruce|acacia] [--height 5] [--count 1 | --chance 8] [--sapling oak_sapling]
//   worldgen plant <id> --block <b>[,b2*3] [--chance 8] [--tries 32] | lake <id> [--fluid water|lava] [--barrier stone] [--chance 40]
//   worldgen geode <id> [--outer --middle --inner --budding --crystals a,b] [--chance 24] | rock <id> --block <b> [--count 2] | spring <id> [--fluid lava]
//   worldgen structure <id> --build <kind> [--args "size=7 palette=stone"] | --template <ns:name>  [--chance 48] [--water] [--dy N]
//   worldgen feature <id> --json '<vanilla placed_feature JSON>'      all: [--biomes "#minecraft:is_overworld,minecraft:plains"] [--step <decoration step>] [--datapack <Id>]
//   worldgen list | sync | test <id> | remove <id> [--datapack <Id>]     live now in NEW chunks; --datapack also writes vanilla worldgen files (used after world reopen / without Moddeus)
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RULES = path.join(ROOT, 'src/main/resources/moddeus/worldgen');
export const HELP = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(1).map(l => l.slice(3)).join('\n');
const die = m => { console.error(m); process.exit(1); };
const snake = s => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^A-Za-z0-9]+/g, '_').toLowerCase();
const ns = s => !s ? s : s.startsWith('#') || s.includes(':') ? s : 'minecraft:' + s;
const writeJson = (f, o) => { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2)); console.log('wrote ' + path.relative(ROOT, f).replace(/\\/g, '/')); };

function java(line) {
  const r = spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'game', 'run', 'print(dev.moddeus.live.Structures.run(' + JSON.stringify(line) + '));'], { encoding: 'utf8' });
  const out = ((r.stdout || '') + (r.stderr || '')).replace(/\ndone in .*$/s, '').trim();
  if (r.status) process.exitCode = 1;
  return out;
}

function parse(args) {
  const pos = [], opt = {};
  for (let i = 0; i < args.length; i++) {
    if (!args[i].startsWith('--')) { pos.push(args[i]); continue; }
    const k = args[i].slice(2), v = args[i + 1];
    if (v === undefined || v.startsWith('--')) opt[k] = true; else { opt[k] = v; i++; }
  }
  return { pos, opt };
}

function world() {
  const d = path.join(ROOT, 'run/saves');
  const ws = fs.existsSync(d) ? fs.readdirSync(d).map(w => path.join(d, w)).filter(w => fs.existsSync(path.join(w, 'level.dat'))) : [];
  ws.sort((a, b) => fs.statSync(path.join(b, 'level.dat')).mtimeMs - fs.statSync(path.join(a, 'level.dat')).mtimeMs);
  return ws[0] || die('no world in run/saves');
}
const packDir = Id => path.join(world(), 'datapacks', Id + '-DP');

function ensurePack(dir, Id) {
  const mc = path.join(dir, 'pack.mcmeta');
  if (!fs.existsSync(mc)) writeJson(mc, { pack: { description: Id + ' (Moddeus)', min_format: [121, 0], max_format: [121, 0] } });
  const ns0 = snake(Id), un = path.join(dir, 'data', ns0, 'function', 'uninstall.mcfunction');
  if (!fs.existsSync(un)) { fs.mkdirSync(path.dirname(un), { recursive: true }); fs.writeFileSync(un, '# worldgen has nothing to clean up at runtime\n'); }
}

const range = s => { const m = String(s).match(/^(-?\d+)\.\.(-?\d+)$/) || die('--y wants min..max, e.g. -64..32'); return [Number(m[1]), Number(m[2])]; };
const heightRange = (y, shape = 'uniform') => { const [a, b] = range(y); return { type: 'minecraft:height_range', height: { type: 'minecraft:' + shape, min_inclusive: { absolute: a }, max_inclusive: { absolute: b } } }; };
const every = o => o.chance ? [{ type: 'minecraft:rarity_filter', chance: Number(o.chance) }] : [{ type: 'minecraft:count', count: Number(o.count || 1) }];
const weighted = s => ({ type: 'minecraft:weighted', entries: String(s).split(',').map(x => { const [b, w] = x.trim().split('*'); return { data: ns(b), weight: Number(w || 1) }; }) });
const target = (inn, block) => inn.startsWith('#') ? { predicate_type: 'minecraft:tag_match', tag: inn.slice(1) }
  : inn === 'stone' ? { predicate_type: 'minecraft:tag_match', tag: 'minecraft:stone_ore_replaceables' }
  : inn === 'deepslate' ? { predicate_type: 'minecraft:tag_match', tag: 'minecraft:deepslate_ore_replaceables' }
  : inn === 'netherrack' ? { predicate_type: 'minecraft:tag_match', tag: 'minecraft:base_stone_nether' }
  : { predicate_type: 'minecraft:block_match', block: ns(inn) };

function makeRule(kind, o) {
  const B = o.biomes ? String(o.biomes).split(',').map(s => ns(s.trim())) : null;
  const rule = (step, placed, def) => ({ step: o.step || step, biomes: B || def || ['#minecraft:is_overworld'], placed });
  const need = k => o[k] || die('worldgen ' + kind + ': --' + k + ' is required');
  switch (kind) {
    case 'ore': {
      const blk = ns(need('block')), inn = o.in || 'both';
      const targets = inn === 'both' ? [{ target: target('stone'), state: blk }, { target: target('deepslate'), state: ns(o.deepslate || blk) }]
        : [{ target: target(inn), state: blk }];
      const nether = inn === 'netherrack', end = inn === 'end_stone';
      return rule('underground_ores', { feature: { type: 'minecraft:ore', size: Number(o.size || 8), discard_chance_on_air_exposure: Number(o.air || 0), targets },
        placement: [...every({ count: o.count || 6, chance: o.chance }), { type: 'minecraft:in_square' }, heightRange(o.y || (nether ? '10..117' : end ? '0..80' : '-64..48'), o.shape || 'uniform'), { type: 'minecraft:biome' }] },
        nether ? ['#minecraft:is_nether'] : end ? ['#minecraft:is_end'] : null);
    }
    case 'tree': {
      const log = ns(need('log')), leaves = ns(need('leaves')), h = Number(o.height || 5), f = o.foliage || 'blob';
      const P = {
        blob: [{ type: 'minecraft:straight_trunk_placer', base_height: h, height_rand_a: 2, height_rand_b: 0 }, { type: 'minecraft:blob_foliage_placer', radius: 2, offset: 0, height: 3 }],
        fancy: [{ type: 'minecraft:fancy_trunk_placer', base_height: Math.max(3, h - 2), height_rand_a: 11, height_rand_b: 0 }, { type: 'minecraft:fancy_foliage_placer', radius: 2, offset: 4, height: 4 }],
        pine: [{ type: 'minecraft:straight_trunk_placer', base_height: h + 2, height_rand_a: 4, height_rand_b: 0 }, { type: 'minecraft:pine_foliage_placer', radius: 1, offset: 1, height: { type: 'minecraft:uniform', min_inclusive: 3, max_inclusive: 4 } }],
        spruce: [{ type: 'minecraft:straight_trunk_placer', base_height: h, height_rand_a: 3, height_rand_b: 1 }, { type: 'minecraft:spruce_foliage_placer', radius: { type: 'minecraft:uniform', min_inclusive: 2, max_inclusive: 3 }, offset: { type: 'minecraft:uniform', min_inclusive: 0, max_inclusive: 2 }, trunk_height: { type: 'minecraft:uniform', min_inclusive: 1, max_inclusive: 2 } }],
        acacia: [{ type: 'minecraft:forking_trunk_placer', base_height: h, height_rand_a: 2, height_rand_b: 2 }, { type: 'minecraft:acacia_foliage_placer', radius: 2, offset: 0 }],
      }[f] || die('--foliage blob|fancy|pine|spruce|acacia');
      return rule('vegetal_decoration', { feature: { type: 'minecraft:tree', below_trunk_provider: 'minecraft:soil_beneath_tree', decorators: [], ignore_vines: true,
        trunk_provider: { id: log, properties: { axis: 'y' } }, foliage_provider: { id: leaves }, trunk_placer: P[0], foliage_placer: P[1],
        minimum_size: { type: 'minecraft:two_layers_feature_size', limit: 1, lower_size: 0, upper_size: 1 } },
        placement: [...every({ count: o.count, chance: o.chance || (o.count ? undefined : 6) }), { type: 'minecraft:in_square' }, { type: 'minecraft:surface_water_depth_filter', max_water_depth: 0 },
          { type: 'minecraft:heightmap', heightmap: 'OCEAN_FLOOR' }, { type: 'minecraft:block_predicate_filter', predicate: { type: 'minecraft:would_survive', state: { id: ns(o.sapling || 'oak_sapling') } } }, { type: 'minecraft:biome' }] });
    }
    case 'plant': {
      const blocks = need('block'), first = ns(String(blocks).split(',')[0].split('*')[0]), sp = Number(o.spread || 7);
      const tri = m => ({ type: 'minecraft:trapezoid', min: -m, max: m, plateau: 0 });
      return rule('vegetal_decoration', { feature: { type: 'minecraft:simple_block', to_place: weighted(blocks) },
        placement: [{ type: 'minecraft:rarity_filter', chance: Number(o.chance || 8) }, { type: 'minecraft:in_square' }, { type: 'minecraft:heightmap', heightmap: 'MOTION_BLOCKING' }, { type: 'minecraft:biome' },
          { type: 'minecraft:count', count: Number(o.tries || 32) }, { type: 'minecraft:offset', x: tri(sp), y: tri(3), z: tri(sp) },
          { type: 'minecraft:block_predicate_filter', predicate: { type: 'minecraft:all_of', predicates: [{ type: 'minecraft:matching_block_tag', tag: 'minecraft:air' }, { type: 'minecraft:would_survive', state: { id: first } }] } }] });
    }
    case 'lake': return rule('lakes', { feature: { type: 'minecraft:lake', barrier: { id: ns(o.barrier || 'stone') }, can_place_feature: { type: 'minecraft:true' },
        can_replace_with_air_or_fluid: { type: 'minecraft:not', predicate: { type: 'minecraft:matching_block_tag', tag: 'minecraft:features_cannot_replace' } },
        can_replace_with_barrier: { type: 'minecraft:not', predicate: { type: 'minecraft:matching_block_tag', tag: 'minecraft:lava_pool_stone_cannot_replace' } },
        fluid: { id: ns(o.fluid || 'water') } },
      placement: [{ type: 'minecraft:rarity_filter', chance: Number(o.chance || 40) }, { type: 'minecraft:in_square' }, { type: 'minecraft:heightmap', heightmap: 'WORLD_SURFACE_WG' }, { type: 'minecraft:biome' }] });
    case 'geode': return rule('local_modifications', { feature: { type: 'minecraft:geode', blocks: {
        alternate_inner_layer_provider: { id: ns(o.budding || 'budding_amethyst') }, cannot_replace: '#minecraft:features_cannot_replace', filling_provider: { id: 'minecraft:air' },
        inner_layer_provider: { id: ns(o.inner || 'amethyst_block') }, inner_placements: String(o.crystals || 'small_amethyst_bud,medium_amethyst_bud,large_amethyst_bud,amethyst_cluster').split(',').map(ns),
        invalid_blocks: '#minecraft:geode_invalid_blocks', middle_layer_provider: { id: ns(o.middle || 'calcite') }, outer_layer_provider: { id: ns(o.outer || 'smooth_basalt') } },
        crack: { generate_crack_chance: 0.95 }, invalid_blocks_threshold: 1, layers: {}, outer_wall_distance: { type: 'minecraft:uniform', min_inclusive: 4, max_inclusive: 6 }, use_alternate_layer0_chance: 0.083 },
      placement: [{ type: 'minecraft:rarity_filter', chance: Number(o.chance || 24) }, { type: 'minecraft:in_square' }, heightRange(o.y || '-50..30'), { type: 'minecraft:biome' }] });
    case 'rock': return rule('local_modifications', { feature: { type: 'minecraft:block_blob', state: ns(need('block')), can_place_on: { type: 'minecraft:matching_block_tag', tag: 'minecraft:forest_rock_can_place_on' } },
      placement: [...every({ count: o.count || 2, chance: o.chance }), { type: 'minecraft:in_square' }, { type: 'minecraft:heightmap', heightmap: 'MOTION_BLOCKING' }, { type: 'minecraft:biome' }] });
    case 'spring': return rule('fluid_springs', { feature: { type: 'minecraft:spring_feature', state: { id: ns(o.fluid || 'water'), properties: { falling: 'true' } },
        valid_blocks: ['minecraft:stone', 'minecraft:granite', 'minecraft:diorite', 'minecraft:andesite', 'minecraft:deepslate', 'minecraft:tuff', 'minecraft:dirt'] },
      placement: [{ type: 'minecraft:count', count: Number(o.count || 10) }, { type: 'minecraft:in_square' }, heightRange(o.y || '0..120'), { type: 'minecraft:biome' }] });
    case 'feature': return rule(o.step || 'vegetal_decoration', JSON.parse(need('json')));
    case 'structure': {
      const s = o.template ? { template: ns(o.template) } : { build: need('build'), args: o.args || '' };
      if (o.water) s.water = true;
      if (o.dy) s.dy = Number(o.dy);
      return { step: o.step || 'surface_structures', biomes: B || ['#minecraft:is_overworld'], structure: s,
        placement: [{ type: 'minecraft:rarity_filter', chance: Number(o.chance || 48) }, { type: 'minecraft:biome' }] };
    }
  }
  die('worldgen: unknown kind ' + kind + '\n' + HELP);
}

/** Vanilla datapack files for a rule (feature + placed_feature [+ jigsaw structure]); biome overrides come from the live registry (Java). */
function datapackFiles(id, r, Id, dir) {
  const n = snake(Id), data = path.join(dir, 'data', n, 'worldgen');
  if (r.placed) {
    const p = { ...r.placed };
    if (typeof p.feature === 'object') { writeJson(path.join(data, 'feature', id + '.json'), p.feature); p.feature = n + ':' + id; }
    writeJson(path.join(data, 'placed_feature', id + '.json'), p);
    return n + ':' + id;
  }
  if (r.structure.template) { // real jigsaw structure from a saved template
    const tpl = r.structure.template, [tns, tp] = tpl.split(':');
    const src = path.join(world(), 'generated', tns, 'structure', tp + '.nbt'), dst = path.join(dir, 'data', tns, 'structure', tp + '.nbt');
    if (fs.existsSync(src)) { fs.mkdirSync(path.dirname(dst), { recursive: true }); fs.copyFileSync(src, dst); console.log('copied ' + tpl + '.nbt into the datapack'); }
    const biomes = r.biomes.length === 1 && r.biomes[0].startsWith('#') ? r.biomes[0] : r.biomes.filter(b => !b.startsWith('#'));
    writeJson(path.join(data, 'template_pool', id + '.json'), { fallback: 'minecraft:empty', elements: [{ weight: 1, element: { element_type: 'minecraft:single_pool_element', location: tpl, processors: 'minecraft:empty', projection: 'rigid' } }] });
    writeJson(path.join(data, 'structure', id + '.json'), { type: 'minecraft:jigsaw', biomes, step: r.step, spawn_overrides: {}, terrain_adaptation: 'beard_thin', start_pool: n + ':' + id, size: 1,
      start_height: { absolute: r.structure.dy || 0 }, project_start_to_heightmap: 'WORLD_SURFACE_WG', max_distance_from_center: 80, use_expansion_hack: false });
    const sp = Math.max(8, Math.round(Math.sqrt(r.placement[0].chance || 48) * 4));
    writeJson(path.join(data, 'structure_set', id + '.json'), { structures: [{ structure: n + ':' + id, weight: 1 }], placement: { type: 'minecraft:random_spread', spacing: sp, separation: Math.max(2, Math.floor(sp / 3)), salt: [...id].reduce((h, ch) => (h * 31 + ch.charCodeAt(0)) | 0, 7) & 0x7fffffff } });
    return n + ':' + id;
  }
  console.log('note: --build structures are live-only (Moddeus); for a vanilla datapack structure save a template (struct sel save) and use --template');
  return null;
}

function removeRule(id, opt) {
  const gone = [];
  const rm = f => { if (fs.existsSync(f)) { fs.rmSync(f); gone.push(path.relative(ROOT, f).replace(/\\/g, '/')); } };
  rm(path.join(RULES, id + '.json'));
  if (opt.datapack) {
    const dir = packDir(opt.datapack), n = snake(opt.datapack), data = path.join(dir, 'data', n, 'worldgen'), ref = n + ':' + id;
    rm(path.join(dir, 'moddeus_worldgen', id + '.json'));
    for (const k of ['feature', 'placed_feature', 'structure', 'template_pool', 'structure_set']) rm(path.join(data, k, id + '.json'));
    const walk = d => fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]) : [];
    for (const f of walk(path.join(dir, 'data')).filter(f => /worldgen[\\/]biome[\\/]/.test(f))) {
      const j = JSON.parse(fs.readFileSync(f, 'utf8'));
      j.features = (j.features || []).map(s => s.filter(x => x !== ref));
      fs.writeFileSync(f, JSON.stringify(j, null, 2));
    }
  }
  console.log(gone.length ? 'removed ' + gone.join(', ') : 'nothing to remove for ' + id);
}

export async function run(cmd, rest) {
  if (!['struct', 'terrain', 'worldgen'].includes(cmd)) return false;
  if (!rest.length || rest[0] === 'help') { console.log(HELP); return true; }
  if (cmd === 'struct') {
    const { pos, opt } = parse(rest);
    const out = java(pos.join(' '));
    console.log(out);
    if (pos[0] === 'sel' && pos[1] === 'save' && opt.datapack && /^saved /.test(out)) { // also ship the template inside the mod's datapack
      const id = pos[2].includes(':') ? pos[2] : 'moddeus:' + pos[2], [tns, tp] = id.split(':'), dir = packDir(opt.datapack);
      ensurePack(dir, opt.datapack);
      const dst = path.join(dir, 'data', tns, 'structure', tp + '.nbt');
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(path.join(world(), 'generated', tns, 'structure', tp + '.nbt'), dst);
      console.log('copied into ' + path.relative(ROOT, dst).replace(/\\/g, '/') + ' (/place template ' + id + ' works on any server with the pack)');
    }
    return true;
  }
  if (cmd === 'terrain') { console.log(java('terrain ' + rest.join(' '))); return true; }
  const { pos, opt } = parse(rest);
  const [sub, id] = pos;
  if (['list', 'sync'].includes(sub)) { console.log(java('worldgen ' + sub)); return true; }
  if (sub === 'test') { console.log(java('worldgen test ' + (id || die('worldgen test <id>')) + ' ' + pos.slice(2).join(' '))); return true; }
  if (sub === 'remove') { removeRule(id || die('worldgen remove <id>'), opt); console.log(java('worldgen sync')); return true; }
  if (!/^[a-z0-9_]+$/.test(id || '')) die('worldgen ' + sub + ' <id> ...  (id: a-z 0-9 _, start it with your mod\'s snake id)');
  const r = makeRule(sub, opt);
  if (opt.datapack) {
    const dir = packDir(opt.datapack);
    ensurePack(dir, opt.datapack);
    const vid = datapackFiles(id, r, opt.datapack, dir);
    if (vid) r.vanilla = vid;
    writeJson(path.join(dir, 'moddeus_worldgen', id + '.json'), r);
    spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'game', 'cmd', 'reload'], { encoding: 'utf8' }); // enables a new pack; the reload re-syncs worldgen
    console.log(java('worldgen sync'));
    if (vid && r.placed) console.log(java('worldgen datapack ' + id + ' ' + dir.replace(/\\/g, '/').replace(/ /g, '%20') + ' ' + vid));
  } else {
    writeJson(path.join(RULES, id + '.json'), r);
    console.log(java('worldgen sync'));
  }
  console.log('live in chunks generated from now on. See one now: node tools/mc.mjs worldgen test ' + id + '  (then struct undo)');
  return true;
}
