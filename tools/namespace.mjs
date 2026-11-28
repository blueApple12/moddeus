// mc namespace <Mod Name> [--strip <prefix_>] [--dry]: moves an existing mod from moddeus:<id> to its own namespace
// (<snake_case mod name>:<id>, assets/<ns>/, data/<ns>/). Content JSON gets "namespace" + "aliases" (old ids), so the live
// game swaps stacks/blocks to the new ids and saved worlds keep loading (LegacyIds). Prefix strip: abyssal_bow -> bow.
// Guide: .claude/skills/mc-content/NAMESPACES.md
import fs from 'node:fs';
import path from 'node:path';

const snakeNs = s => String(s).trim().replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
const walk = d => !fs.existsSync(d) ? [] : fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
const TEXT = /\.(json|mcmeta|mcfunction|java)$/;

export function migrate(ROOT, modName, opt = {}) {
  const RES = path.join(ROOT, 'src/main/resources'), CONTENT = path.join(RES, 'moddeus/content');
  const projects = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/projects.json'), 'utf8'));
  const p = projects.find(x => [x.name, x.id].some(n => n.toLowerCase() === String(modName).toLowerCase()));
  if (!p) throw new Error('no mod project ' + modName + ' (mc projects)');
  const ns = snakeNs(p.name), idSnake = snakeNs(p.id);
  const all = fs.readdirSync(CONTENT).filter(f => f.endsWith('.json')).map(f => f.slice(0, -5));
  const read = id => JSON.parse(fs.readFileSync(path.join(CONTENT, id + '.json'), 'utf8'));
  const ids = all.filter(id => { const c = read(id); return [p.name, p.id].includes(c.tab) && (!c.namespace || c.namespace === ns); });
  if (!ids.length) throw new Error('no content with tab "' + p.name + '"');
  // prefix: --strip, else the first word of the namespace when every id has it (abyssal_bow -> bow)
  const word = ns.split('_')[0] + '_';
  const strip = opt.strip !== undefined ? String(opt.strip === true ? '' : opt.strip) : ids.every(id => id.replace(/^_song_/, '').startsWith(word)) ? word : '';
  const owned = new Set(ids);
  // dimensions stay where they are (the player may be inside one; their registry only loads at world start)
  const dims = new Set(['dimension', 'dimension_type'].flatMap(d => walk(path.join(RES, 'data/moddeus', d)).map(f => path.basename(f).replace(/\..*$/, ''))));
  const ownedName = b => !dims.has(b) && (owned.has(b) || ids.some(id => b.startsWith(id + '_')) || (strip && b.includes(strip)) || b === idSnake || b.startsWith(idSnake + '_'));
  const taken = new Set(all.filter(id => !owned.has(id)));
  const rename = b => { if (!strip || !b.includes(strip)) return b; const n = b.replace(strip, ''); return /^_?[a-z0-9][a-z0-9_]*$/.test(n) ? n : b; };
  const newId = id => { const n = rename(id); return taken.has(n) ? id : n; };
  // text rewrite: moddeus:[dirs/]name, assets|data/moddeus/dirs/name.ext, <kind>.moddeus.name lang keys
  const fix = s => s
    .replace(/\bmoddeus:((?:long_|strong_)?)((?:[a-z0-9_]+\/)*)([a-z0-9_]+)/g, (m, pre, dirs, b) => ownedName(b) ? ns + ':' + pre + dirs + rename(b) : m)
    .replace(/\b(assets|data)\/moddeus\/((?:[a-z0-9_]+\/)*)([a-z0-9_]+)(\.[a-z.]+)/g, (m, top, dirs, b, ext) => ownedName(b) ? top + '/' + ns + '/' + dirs + rename(b) + ext : m)
    .replace(/\b([a-z_]+)\.moddeus\.([a-z0-9_]+)/g, (m, k, b) => ownedName(b) ? k + '.' + ns + '.' + rename(b) : m);
  // bare content ids ("vfx:abyssal_fx", case "abyssal_bow") in the mod's own content + Java; not after a namespace/path/key
  const renamed = ids.filter(id => newId(id) !== id).sort((a, b) => b.length - a.length);
  const bareRe = renamed.length ? new RegExp('(^|[^a-z0-9_/.])(' + renamed.join('|') + ')(?![a-z0-9_])', 'g') : null;
    const bare = s => bareRe ? s.replace(bareRe, (m, pre, id, off, all) => pre === ':' && /(moddeus|minecraft):$/.test(all.slice(Math.max(0, off - 12), off + 1)) ? m : pre + newId(id)) : s;
  const fixJava = s => bare(fix(s)
    .replace(/getNamespace\(\)\.equals\("moddeus"\)/g, 'getNamespace().equals("' + ns + '")')
    .replace(/fromNamespaceAndPath\("moddeus", (?=[a-zA-Z_(])/g, 'fromNamespaceAndPath("' + ns + '", ') // variable id argument
    .replace(/fromNamespaceAndPath\("moddeus", "([a-z0-9_]+)"\)/g, (m, b) => owned.has(b) ? 'fromNamespaceAndPath("' + ns + '", "' + b + '")' : m));
  const log = [], dry = !!opt.dry, put = (f, s) => { if (!dry) { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, s); } };
  // 1. move owned files out of assets/moddeus + data/moddeus (renamed), rewriting text files
  for (const top of ['assets', 'data']) for (const f of walk(path.join(RES, top, 'moddeus'))) {
    const rel = path.relative(path.join(RES, top, 'moddeus'), f).replace(/\\/g, '/'), base = path.basename(f).replace(/\..*$/, '');
    // vfx textures are looked up by bare name (moddeus:textures/vfx/<name>), entity textures by Java literals: only mob skins (= content id) move
    if (rel.startsWith('textures/vfx/') || (rel.startsWith('textures/entity/') && !rel.startsWith('textures/entity/equipment/') && !owned.has(base))) continue;
    if (/^(lang|sounds\.json)/.test(rel) || !ownedName(base) && !ownedName(base.replace(/^(potion|splash_potion|lingering_potion|tipped_arrow)_(long_|strong_)?/, ''))) continue;
    const to = path.join(RES, top, ns, path.dirname(rel), path.basename(f).replace(/^[^.]+/, n => rename(n)));
    log.push('move ' + top + '/moddeus/' + rel + ' -> ' + path.relative(RES, to).replace(/\\/g, '/'));
    if (!dry) { put(to, TEXT.test(f) ? fix(fs.readFileSync(f, 'utf8')) : fs.readFileSync(f)); fs.rmSync(f); }
  }
  // 2. lang + sounds.json: this mod's keys move to assets/<ns>/
  for (const name of ['lang/en_us.json', 'sounds.json']) {
    const f = path.join(RES, 'assets/moddeus', name); if (!fs.existsSync(f)) continue;
    const j = JSON.parse(fs.readFileSync(f, 'utf8')), to = path.join(RES, 'assets', ns, name), mine = fs.existsSync(to) ? JSON.parse(fs.readFileSync(to, 'utf8')) : {};
    for (const [k, v] of Object.entries(j)) {
      const segs = k.split('.'), own = name === 'sounds.json' ? ownedName(k) : segs.some(s => ownedName(s));
      if (!own) continue;
      const nk = name === 'sounds.json' ? rename(k) : segs.map(s => s === 'moddeus' ? ns : ownedName(s) ? rename(s) : s).join('.');
      mine[nk] = JSON.parse(fix(JSON.stringify(v))); delete j[k]; log.push('key ' + k + ' -> ' + nk);
    }
    put(f, JSON.stringify(j, null, 2) + '\n'); put(to, JSON.stringify(mine, null, 2) + '\n');
  }
  // 3. content JSON: renamed file + namespace + aliases
  for (const id of ids) {
    const c = JSON.parse(bare(fix(fs.readFileSync(path.join(CONTENT, id + '.json'), 'utf8')))), n = newId(id);
    const had = c.namespace === ns;
    c.namespace = ns;
    if (!had) c.aliases = [...new Set([...(c.aliases || []), 'moddeus:' + id])];
    log.push('content ' + id + ' -> ' + ns + ':' + n);
    if (!dry) { fs.writeFileSync(path.join(CONTENT, n + '.json'), JSON.stringify(c, null, 2) + '\n'); if (n !== id) fs.rmSync(path.join(CONTENT, id + '.json')); }
  }
  // 4. references elsewhere: other content, tags, vanilla-namespace data, the mod's Java + packs
  const dirs = [CONTENT, path.join(RES, 'data'), path.join(RES, 'assets'), path.join(ROOT, 'run/resourcepacks', p.id + '-RP'),
    ...(fs.existsSync(path.join(ROOT, 'run/saves')) ? fs.readdirSync(path.join(ROOT, 'run/saves')).map(w => path.join(ROOT, 'run/saves', w, 'datapacks', p.id + '-DP')) : []),
    path.join(ROOT, 'src/main/java/dev/moddeus/content'), path.join(ROOT, 'src/client/java/dev/moddeus/client/content')];
  for (const d of dirs) for (const f of walk(d)) {
    if (!TEXT.test(f) || (f.endsWith('.java') && !path.basename(f).startsWith(p.id))) continue;
    if (path.dirname(f) === CONTENT && ids.some(id => newId(id) + '.json' === path.basename(f))) continue; // done in step 3 (keeps the old ids in "aliases")
    const s = fs.readFileSync(f, 'utf8'), t = f.endsWith('.java') ? fixJava(s) : fix(s);
    if (t !== s) { log.push('refs ' + path.relative(ROOT, f).replace(/\\/g, '/')); put(f, t); if (dry && opt.verbose) { const a = s.split('\n'), b = t.split('\n'); a.forEach((l, i) => { if (l !== b[i]) log.push('  - ' + l.trim().slice(0, 160) + '\n  + ' + (b[i] || '').trim().slice(0, 160)); }); } }
  }
  return { ns, strip, log };
}

export async function run(cmd, rest, ctx) {
  if (cmd !== 'namespace') return false;
  const opt = {}, pos = [];
  for (let i = 0; i < rest.length; i++) if (rest[i].startsWith('--')) { const v = rest[i + 1]; if (v === undefined || v.startsWith('--')) opt[rest[i].slice(2)] = true; else { opt[rest[i].slice(2)] = v; i++; } } else pos.push(rest[i]);
  if (!pos.length) ctx.die('usage: namespace "<Mod Name>" [--strip <prefix_>] [--dry]');
  const r = migrate(ctx.ROOT, pos.join(' '), opt);
  console.log(r.log.join('\n'));
  console.log((opt.dry ? 'DRY RUN: ' : '') + 'namespace ' + r.ns + (r.strip ? ' (stripped "' + r.strip + '")' : '') + ': ' + r.log.length + ' changes');
  if (!opt.dry) await ctx.apply();
  return true;
}
