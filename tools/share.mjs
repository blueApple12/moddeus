// Share mods made with Moddeus: export a mod project to one <Name>.moddeus.zip, import one from someone else.
// A mod = Java classes <Id>*.java (content/, client/content/), resources named after it (assets/data/content files starting
// with its snake_case id), its datapack <Id>-DP (world) and resource pack <Id>-RP. The creator keeps ALL rights to it.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';
import { projects, save } from './projects.mjs';

const JAVA = { main: 'src/main/java/dev/moddeus/content', client: 'src/client/java/dev/moddeus/client/content' };
const RES = 'src/main/resources';
export const RIGHTS = 'Made with Moddeus. This mod belongs to its creator, who holds all rights to it and may share, sell or license it however they like. Moddeus claims no rights over mods made with it.';

// ---------------------------------------------------------------- zip (no dependencies)
const CRC = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc32 = b => { let c = 0xffffffff; for (const x of b) c = CRC[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };

export function zip(entries) { // [[name, Buffer]] -> Buffer
  const parts = [], central = [];
  let off = 0;
  for (const [name, data] of entries) {
    const n = Buffer.from(name), z = zlib.deflateRawSync(data), crc = crc32(data);
    const h = Buffer.alloc(30); h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8);
    h.writeUInt16LE(0, 10); h.writeUInt16LE(0x21, 12); h.writeUInt32LE(crc, 14); h.writeUInt32LE(z.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(n.length, 26);
    const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
    c.writeUInt16LE(0, 12); c.writeUInt16LE(0x21, 14); c.writeUInt32LE(crc, 16); c.writeUInt32LE(z.length, 20); c.writeUInt32LE(data.length, 24); c.writeUInt16LE(n.length, 28); c.writeUInt32LE(off, 42);
    parts.push(h, n, z); central.push(c, n); off += 30 + n.length + z.length;
  }
  const cd = Buffer.concat(central), e = Buffer.alloc(22);
  e.writeUInt32LE(0x06054b50, 0); e.writeUInt16LE(entries.length, 8); e.writeUInt16LE(entries.length, 10); e.writeUInt32LE(cd.length, 12); e.writeUInt32LE(off, 16);
  return Buffer.concat([...parts, cd, e]);
}

export function unzip(buf) { // Buffer -> Map(name -> Buffer)
  let e = buf.length - 22;
  while (e >= 0 && buf.readUInt32LE(e) !== 0x06054b50) e--;
  if (e < 0) throw new Error('not a zip file');
  const n = buf.readUInt16LE(e + 10), out = new Map();
  let p = buf.readUInt32LE(e + 16);
  for (let i = 0; i < n; i++) {
    const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20), nl = buf.readUInt16LE(p + 28), xl = buf.readUInt16LE(p + 30), cl = buf.readUInt16LE(p + 32), lo = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nl);
    const ds = lo + 30 + buf.readUInt16LE(lo + 26) + buf.readUInt16LE(lo + 28), raw = buf.subarray(ds, ds + csize);
    if (!name.endsWith('/')) out.set(name, method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw));
    p += 46 + nl + xl + cl;
  }
  return out;
}

// ---------------------------------------------------------------- helpers
const snake = id => id.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
const walk = d => !fs.existsSync(d) ? [] : fs.readdirSync(d, { withFileTypes: true }).flatMap(f => f.isDirectory() ? walk(path.join(d, f.name)) : [path.join(d, f.name)]);
const rel = (a, b) => path.relative(a, b).split(path.sep).join('/');
const ownPack = (folder, id) => folder === id || folder.startsWith(id + '-');
function saves(root) { const d = path.join(root, 'run/saves'); return !fs.existsSync(d) ? [] : fs.readdirSync(d).map(w => path.join(d, w)).filter(w => fs.statSync(w).isDirectory()).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs); }
function version(root) {
  for (const f of ['.workspace-version', 'gradle.properties']) try { const t = fs.readFileSync(path.join(root, f), 'utf8'); const m = t.match(/mod_version=(.+)/) || [null, t.trim()]; if (m[1]) return m[1].trim(); } catch {}
  return '?';
}
export const exportsDir = root => path.join(root, 'exports');
const find = (root, q) => projects(root).find(p => [p.id, p.name].some(s => s.toLowerCase() === String(q).toLowerCase()));

// ---------------------------------------------------------------- export
function collect(root, q, strict = false) { // the mod's files, named like inside the .moddeus.zip; strict (delete) = no guesses that can hit other mods' files
  const p = find(root, q);
  if (!p) throw new Error('no mod named "' + q + '" (see: node tools/mc.mjs projects)');
  const files = [], kinds = new Set(), sn = snake(p.id), lo = p.id.toLowerCase();
  for (const [side, dir] of Object.entries(JAVA))
    for (const f of walk(path.join(root, dir))) if (path.basename(f).startsWith(p.id) && f.endsWith('.java')) { files.push(['java/' + side + '/' + rel(path.join(root, dir), f), fs.readFileSync(f)]); kinds.add('java'); }
  // resources: named after the mod, or content (mc item/block/...) whose creative tab is the mod, plus files named after that content
  const owned = new Set([sn, lo]);
  // the mod's own namespace (snake_case of its name): content with "namespace": ns, and everything under assets/<ns>/ + data/<ns>/
  const ns = String(p.name).trim().replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  const inNs = f => { const r = rel(path.join(root, RES), f).split('/'); return (r[0] === 'assets' || r[0] === 'data') && r[1] === ns; };
  const rivals = new Set(), otherNs = new Set(); // strict: other mods' names/namespaces ("meteor" must not take another mod's "meteor_bracelet")
  const nsOf = s => String(s).trim().replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  for (const o of projects(root)) if (o.id !== p.id) { rivals.add(snake(o.id)); rivals.add(o.id.toLowerCase()); otherNs.add(nsOf(o.name)); }
  for (const f of walk(path.join(root, RES, 'moddeus/content'))) if (f.endsWith('.json')) try { const c = JSON.parse(fs.readFileSync(f, 'utf8')); if ([p.name, p.id].includes(c.tab) || c.namespace === ns) owned.add(path.basename(f, '.json')); else rivals.add(path.basename(f, '.json')); } catch {}
  // + resources the mod's Java code names in a string literal ("textures/entity/hero_skin.png", "sonic_wave", ...)
  const lits = new Set();
  for (const [n, d] of files) if (n.endsWith('.java')) for (const m of d.toString().matchAll(/"([a-z0-9_./:-]{4,})"/g)) lits.add(m[1].replace(/^moddeus:/, ''));
  const named = f => { if (strict) return false; const r = rel(path.join(root, RES), f).split('/'); const inNs = r.slice(2).join('/'); return r[1] === 'moddeus' && (lits.has(inNs) || lits.has(path.basename(f).replace(/\.[^.]+$/, ''))); };
  const longest = (set, s) => Math.max(0, ...[...set].filter(o => o.length >= 4 && s.startsWith(o)).map(o => o.length));
  const mine = (s, f) => {
    if (strict) { const r = f && rel(path.join(root, RES), f).split('/'); if (r && (r[0] === 'assets' || r[0] === 'data') && otherNs.has(r[1])) return false; if (r && r[0] === 'moddeus' && r[1] === 'content' && owned.has(s.replace(/\.json$/, ''))) return true; /* its own content, even short ids */ const o = longest(owned, s); return o > 0 && o > longest(rivals, s); }
    return [...owned].some(o => o.length >= 4 && s.startsWith(o)) || (f && named(f));
  };
  for (const top of ['assets', 'data', 'moddeus/content', 'moddeus/worldgen'])
    for (const f of walk(path.join(root, RES, top))) if (inNs(f) || mine(path.basename(f).toLowerCase(), f)) files.push(['resources/' + rel(path.join(root, RES), f), fs.readFileSync(f)]);
  if (!strict) for (const [n, data] of [...files]) if (/^resources\/assets\/[a-z0-9_.-]+\/models\/.*\.json$/.test(n)) { // textures the models use (any name)
    let tex = {}; try { tex = JSON.parse(data.toString()).textures || {}; } catch {}
    for (const t of Object.values(tex)) if (typeof t === 'string' && /^(moddeus|[a-z0-9_.-]+):/.test(t) && !t.startsWith('minecraft:')) {
      const i = t.indexOf(':'), zp = 'resources/assets/' + t.slice(0, i) + '/textures/' + t.slice(i + 1) + '.png', f = path.join(root, RES, zp.slice(10));
      if (!files.some(x => x[0] === zp) && fs.existsSync(f)) files.push([zp, fs.readFileSync(f)]);
    }
  }
  for (const f of ['assets/moddeus/lang/en_us.json', 'assets/moddeus/sounds.json']) { // shared files: only this mod's keys, merged on import
    let j; try { j = JSON.parse(fs.readFileSync(path.join(root, RES, f), 'utf8')); } catch { continue; }
    const part = Object.fromEntries(Object.entries(j).filter(([k]) => [...owned].some(o => o.length >= 4 && k.toLowerCase().includes(o))));
    if (Object.keys(part).length) files.push(['merge/' + f, Buffer.from(JSON.stringify(part, null, 2))]);
  }
  const world = saves(root).find(w => fs.existsSync(path.join(w, 'datapacks')) && fs.readdirSync(path.join(w, 'datapacks')).some(d => ownPack(d, p.id)));
  if (world) for (const d of fs.readdirSync(path.join(world, 'datapacks')).filter(d => ownPack(d, p.id))) {
    const src = path.join(world, 'datapacks', d);
    for (const f of fs.statSync(src).isDirectory() ? walk(src) : [src]) files.push(['datapacks/' + rel(path.join(world, 'datapacks'), f), fs.readFileSync(f)]);
    kinds.add('datapack');
  }
  const rpDir = path.join(root, 'run/resourcepacks');
  if (fs.existsSync(rpDir)) for (const d of fs.readdirSync(rpDir).filter(d => ownPack(d, p.id))) {
    const src = path.join(rpDir, d);
    for (const f of fs.statSync(src).isDirectory() ? walk(src) : [src]) files.push(['resourcepacks/' + rel(rpDir, f), fs.readFileSync(f)]);
    kinds.add('resourcepack');
  }
  if (!files.length && !strict) throw new Error('mod "' + p.name + '" has no files yet');
  return { p, files, kinds, owned, ns, mine };
}

export function exportMod(root, q) {
  const { p, files, kinds } = collect(root, q);
  const manifest = { format: 1, id: p.id, name: p.name, description: p.desc || '', kinds: [...kinds], minecraft: '26.3', madeWith: 'Moddeus ' + version(root), exportedAt: new Date().toISOString(), rights: RIGHTS, files: files.map(f => f[0]) };
  const hasDp = kinds.has('datapack'), hasRp = kinds.has('resourcepack');
  const readme = [
    '# ' + p.name, '', p.desc || '', '',
    'Made with [Moddeus](https://github.com/blueApple12/moddeus): Minecraft mods you build by talking to your game.', '',
    '## Install', '',
    '- **With Moddeus** (Minecraft 26.3 + Fabric): press G, then Mods > Import, and pick this file. Or run `node tools/mc.mjs import "<this file>"`.',
    hasDp ? '- **Datapack only** (vanilla, no Moddeus): copy the folder in `datapacks/` into your world\'s `datapacks` folder, then run `/reload`.' : '',
    hasRp ? '- **Resource pack only**: copy the folder in `resourcepacks/` into `.minecraft/resourcepacks`.' : '',
    '- **Without Moddeus**: use the .jar exported next to this file, a normal Fabric mod (needs Fabric + Fabric API only).', '',
    '## Rights', '', RIGHTS, '',
    '> A mod is code that runs on your computer. Only import mods from people you trust.', ''].filter((l, i, a) => l !== '' || a[i - 1] !== '').join('\n');
  const out = path.join(exportsDir(root), p.name.replace(/[^A-Za-z0-9 _-]+/g, '').trim().replace(/\s+/g, '_') + '.moddeus.zip');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, zip([['moddeus-mod.json', Buffer.from(JSON.stringify(manifest, null, 2))], ['README.md', Buffer.from(readme)], ...files]));
  return { file: out, files: files.length, kinds: [...kinds] };
}

// ---------------------------------------------------------------- delete (mc remove <Mod>)
/** What deleting a mod removes: its files on disk (Java, resources, content JSON) + content ids + shared lang/sounds key matcher. Packs: mc.mjs modPacks. */
export function owned(root, q) {
  const { p, files, ns, mine } = collect(root, q, true);
  const disk = files.filter(([n]) => !n.startsWith('merge/')).map(([n]) => {
    const [top, side, ...r] = n.split('/');
    return top === 'java' ? path.join(root, JAVA[side], ...r) : top === 'resources' ? path.join(root, RES, side, ...r) : null;
  }).filter(Boolean);
  const content = disk.filter(f => rel(path.join(root, RES, 'moddeus/content'), f).match(/^[^./][^/]*\.json$/)).map(f => path.basename(f, '.json'));
  const key = k => k.toLowerCase().split('.').some(seg => mine(seg));
  return { p, ns, disk, content, key };
}

// ---------------------------------------------------------------- export as a normal Fabric mod
/**
 * <Name>.jar: a plain Fabric mod that works WITHOUT Moddeus or Claude (players need Fabric + Fabric API only).
 * Inside: the mod's compiled classes, its resources, its datapack's data/ and resource pack's assets/ (always on), and the
 * Moddeus RUNTIME embedded (jar-in-jar, mod id "moddeus": content, VFX, physics, keys, GUIs; no AI, chat or hot reload).
 * Fabric loads one runtime for any number of these jars; an installed full Moddeus replaces it. runtimeJar = its bytes.
 */
export function exportJar(root, q, runtimeJar) {
  const { p, files, kinds } = collect(root, q);
  const entries = new Map(), warn = [];
  const add = (n, d) => { if (!entries.has(n)) entries.set(n, d); };
  const classes = [], clientClasses = [];
  for (const [side, dir] of Object.entries(JAVA)) { // compiled output of <Id>*.java (+ inner classes)
    const base = path.join(root, 'build/classes/java', side), out = path.join(base, dir.slice(dir.indexOf('dev/')));
    for (const f of walk(out)) if (path.basename(f).startsWith(p.id) && f.endsWith('.class')) {
      const n = rel(base, f);
      add(n, fs.readFileSync(f));
      if (!n.includes('$')) (side === 'main' ? classes : clientClasses).push(n.slice(0, -6).replace(/\//g, '.'));
    }
  }
  if (kinds.has('java') && !classes.length && !clientClasses.length) throw new Error('no compiled classes for ' + p.name + ': run node tools/mc.mjs build first');
  for (const [n, d] of files) {
    let m;
    if (n.startsWith('resources/')) add(n.slice(10), d);
    else if (n.startsWith('merge/')) add(n.slice(6), d); // only this mod's lang / sound keys: Minecraft merges these files across mods
    else if ((m = n.match(/^datapacks\/[^/]+\/(data\/.+)$/))) add(m[1], d);
    else if ((m = n.match(/^resourcepacks\/[^/]+\/(assets\/.+)$/))) add(m[1], d);
    else if (/^(datapacks|resourcepacks)\/[^/]+\.zip$/.test(n)) { for (const [zn, zd] of unzip(d)) if (/^(data|assets)\//.test(zn)) add(zn, zd); }
    else if (/^datapacks\/[^/]+\/moddeus\.json$/.test(n)) warn.push("the datapack's moddeus.json (creative tab / keys for datapack mods) is not in the jar yet");
  }
  try { // key-bind labels (Keys writes them for every mod into one shared file)
    const kl = JSON.parse(fs.readFileSync(path.join(root, RES, 'assets/moddeus_keys/lang/en_us.json'), 'utf8'));
    const keys = [snake(p.id), p.id.toLowerCase(), p.name.toLowerCase().replace(/[^a-z0-9]+/g, '_')];
    const part = Object.fromEntries(Object.entries(kl).filter(([k, v]) => keys.some(s => k.toLowerCase().includes(s)) || String(v) === p.name));
    if (Object.keys(part).length) add('assets/moddeus_keys/lang/en_us.json', Buffer.from(JSON.stringify(part, null, 2)));
  } catch {}
  let modid = snake(p.id).replace(/[^a-z0-9_]/g, '_');
  if (['moddeus', 'minecraft', 'java', 'fabricloader', 'fabric'].includes(modid) || modid.length < 2) modid += '_mod';
  const made = 'Moddeus ' + version(root);
  const fmj = {
    schemaVersion: 1, id: modid, version: '1.0.0', name: p.name, description: (p.desc || p.name) + ' (made with Moddeus)',
    authors: [], license: 'All-Rights-Reserved', environment: '*',
    depends: { fabricloader: '>=0.20.1', minecraft: '~26.3', java: '>=25', 'fabric-api': '*', moddeus: '*' },
    jars: [{ file: 'META-INF/jars/moddeus-runtime.jar' }],
    custom: { moddeus: { madeWith: made, rights: RIGHTS } },
  };
  const manifest = { format: 1, id: p.id, modid, name: p.name, description: p.desc || '', madeWith: made, rights: RIGHTS, classes, clientClasses };
  const readme = ['# ' + p.name, '', p.desc || '', '', 'A Fabric mod for Minecraft 26.3, made with [Moddeus](https://github.com/blueApple12/moddeus).', '',
    '## Install', '', 'Put this jar in your mods folder next to Fabric API. Moddeus is not needed: the small Moddeus runtime it uses is inside.', '',
    '## Rights', '', RIGHTS, ''].join('\n');
  const out = path.join(exportsDir(root), p.name.replace(/[^A-Za-z0-9 _-]+/g, '').trim().replace(/\s+/g, '_') + '.jar');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, zip([['META-INF/MANIFEST.MF', Buffer.from('Manifest-Version: 1.0\r\n\r\n')], ['fabric.mod.json', Buffer.from(JSON.stringify(fmj, null, 2))],
    ['moddeus-mod.json', Buffer.from(JSON.stringify(manifest, null, 2))], ['README.md', Buffer.from(readme)],
    ['META-INF/jars/moddeus-runtime.jar', runtimeJar], ...entries]));
  return { file: out, modid, classes: classes.length + clientClasses.length, files: entries.size, warn };
}

/** The runtime jar. Dev: assembled from build/classes (kept fresh by the game's auto-build; no Gradle run, which would
 *  rewrite every class file under the running game) by src/runtime/include.txt, like Gradle's runtimeJar. Portable: the
 *  copy inside the installed Moddeus jar. */
export function runtimeJar(root) {
  const list = path.join(root, 'src/runtime/include.txt');
  if (fs.existsSync(list) && fs.existsSync(path.join(root, 'build/classes/java/main/dev/moddeus/live/Standalone.class'))) {
    const pats = fs.readFileSync(list, 'utf8').split(/\r?\n/).map(s => s.trim()).filter(s => s && !s.startsWith('#'));
    const esc = s => s.replace(/[.+^$(){}|[\]\\]/g, '\\$&');
    const re = g => new RegExp('^' + g.split('**').map(a => a.split('*').map(esc).join('[^/]*')).join('.*') + '$');
    const inc = pats.filter(s => !s.startsWith('!')).map(re), exc = pats.filter(s => s.startsWith('!')).map(s => re(s.slice(1)));
    const ok = n => inc.some(r => r.test(n)) && !exc.some(r => r.test(n));
    const e = new Map();
    for (const base of ['build/classes/java/main', 'build/classes/java/client', RES].map(d => path.join(root, d)))
      for (const f of walk(base)) { const n = rel(base, f); if (ok(n) && !e.has(n)) e.set(n, fs.readFileSync(f)); }
    const VER = '$' + '{version}';
    for (const f of ['fabric.mod.json', 'moddeus.mixins.json']) e.set(f, Buffer.from(fs.readFileSync(path.join(root, 'src/runtime', f), 'utf8').split(VER).join(version(root))));
    try { e.set('LICENSE', fs.readFileSync(path.join(root, 'LICENSE'))); } catch {}
    return zip([['META-INF/MANIFEST.MF', Buffer.from('Manifest-Version: 1.0\r\n\r\n')], ...e]);
  }
  const mods = path.join(root, 'run/mods');
  for (const f of fs.existsSync(mods) ? fs.readdirSync(mods).filter(f => /^moddeus.*\.jar$/i.test(f)) : []) {
    const r = unzip(fs.readFileSync(path.join(mods, f))).get('META-INF/moddeus/moddeus-runtime.jar');
    if (r) return r;
  }
  throw new Error('Moddeus runtime jar not found (dev: build once; portable: update Moddeus)');
}

// ---------------------------------------------------------------- import
export function importMod(root, zipFile, force = false) {
  const z = unzip(fs.readFileSync(zipFile)), m = JSON.parse(z.get('moddeus-mod.json')?.toString() || 'null');
  if (!m || !/^[A-Z][A-Za-z0-9]*$/.test(m.id || '')) throw new Error('not a Moddeus mod export (no valid moddeus-mod.json)');
  const world = saves(root)[0], plan = [], merges = [];
  for (const [name, data] of z) {
    if (name === 'moddeus-mod.json' || name === 'README.md') continue;
    if (name.includes('..') || name.includes('\\') || name.startsWith('/')) throw new Error('unsafe path in zip: ' + name);
    let dst = null;
    const j = name.match(/^java\/(main|client)\/([A-Za-z0-9_$]+\.java)$/);
    if (j) { if (!j[2].startsWith(m.id)) throw new Error('class ' + j[2] + ' is not named after the mod ' + m.id); dst = path.join(root, JAVA[j[1]], j[2]); }
    else if (/^resources\/(assets|data|moddeus\/content|moddeus\/worldgen)\//.test(name)) dst = path.join(root, RES, name.slice('resources/'.length));
    else if (name === 'merge/assets/moddeus/lang/en_us.json' || name === 'merge/assets/moddeus/sounds.json') { merges.push([path.join(root, RES, name.slice('merge/'.length)), JSON.parse(data.toString())]); continue; }
    else if (/^datapacks\/[^/]+/.test(name) && ownPack(name.split('/')[1].replace(/\.zip$/, ''), m.id)) { if (!world) throw new Error('open a world once before importing a datapack mod'); dst = path.join(world, name); }
    else if (/^resourcepacks\/[^/]+/.test(name) && ownPack(name.split('/')[1].replace(/\.zip$/, ''), m.id)) dst = path.join(root, 'run', name);
    else throw new Error('unexpected file in zip: ' + name);
    plan.push([dst, data]);
  }
  const list = projects(root), clash = list.some(p => p.id === m.id) || plan.some(([d]) => fs.existsSync(d));
  if (clash && !force) throw new Error('a mod "' + m.id + '" (or its files) already exists here: delete it first, or import with --force to overwrite');
  for (const [dst, data] of plan) { fs.mkdirSync(path.dirname(dst), { recursive: true }); fs.writeFileSync(dst, data); }
  for (const [dst, part] of merges) { let j = {}; try { j = JSON.parse(fs.readFileSync(dst, 'utf8')); } catch {} fs.mkdirSync(path.dirname(dst), { recursive: true }); fs.writeFileSync(dst, JSON.stringify({ ...j, ...part }, null, 2) + '\n'); }
  if (!list.some(p => p.id === m.id)) { list.push({ id: m.id, name: m.name || m.id, desc: m.description || '', enabled: true }); save(root, list); }
  const kinds = new Set(plan.map(([d]) => d.includes(path.join('datapacks', '')) ? 'datapack' : d.includes(path.join('resourcepacks', '')) ? 'resourcepack' : d.endsWith('.java') ? 'java' : 'resources'));
  return { id: m.id, name: m.name, files: plan.length, kinds: [...kinds], datapacks: [...new Set(plan.filter(([d]) => d.includes(path.join('datapacks', ''))).map(([d]) => d.split(/[\\/]datapacks[\\/]/)[1].split(/[\\/]/)[0]))] };
}

/** Mod files to offer in the Import list: exports/ and the Downloads folder, newest first. */
export function candidates(root) {
  return [exportsDir(root), path.join(os.homedir(), 'Downloads')].flatMap(d => fs.existsSync(d) ? fs.readdirSync(d).filter(f => f.endsWith('.moddeus.zip')).map(f => path.join(d, f)) : [])
    .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs).slice(0, 12);
}
