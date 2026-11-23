// Undo for live coding: every hot-swap takes a snapshot of the source (src, tools, CLAUDE.md, .claude) into a hidden
// git ref (refs/moddeus/snap) through its own index, so the user's own git index/branches are never touched.
//   mc snap [label]        snapshot now (HotReloader does this after every swap; no-op when nothing changed)
//   mc snaps [n]           list recent snapshots
//   mc undo [filter]       roll back the last change (or the last change touching paths containing filter, e.g. a feature
//                          name like StormStaff = per-project undo), rebuild, hot-swap it back in
//   mc redo                re-apply what the last plain undo rolled back
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const REF = 'refs/moddeus/snap', SCOPE = ['src', 'tools', 'CLAUDE.md', '.claude'];
let ROOT, STATE;
const env = () => ({ ...process.env, GIT_INDEX_FILE: path.join(ROOT, 'run/moddeus/snap.index'), GIT_AUTHOR_NAME: 'moddeus', GIT_AUTHOR_EMAIL: 'snap@moddeus.local', GIT_COMMITTER_NAME: 'moddeus', GIT_COMMITTER_EMAIL: 'snap@moddeus.local' });
const git = (args, opts = {}) => execFileSync('git', ['-c', 'core.autocrlf=false', '-c', 'core.safecrlf=false', ...args], { cwd: ROOT, env: env(), maxBuffer: 1 << 28, stdio: ['ignore', 'pipe', 'pipe'], ...opts });
const gs = args => git(args, { encoding: 'utf8' }).trim();
const tryGs = args => { try { return gs(args); } catch { return null; } };
const head = () => tryGs(['rev-parse', '-q', '--verify', REF]);
const state = () => { try { return JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch { return { redo: [] }; } };
const save = s => fs.writeFileSync(STATE, JSON.stringify(s));

/** Mod packs live under run/ (gitignored): world datapacks and resource packs, added with -f. */
function packScope() {
  const saves = path.join(ROOT, 'run/saves');
  const worlds = fs.existsSync(saves) ? fs.readdirSync(saves).map(w => 'run/saves/' + w + '/datapacks').filter(p => fs.existsSync(path.join(ROOT, p))) : [];
  return [...worlds, ...(fs.existsSync(path.join(ROOT, 'run/resourcepacks')) ? ['run/resourcepacks'] : [])];
}

export function snap(label = 'manual') {
  git(['add', '-A', '--', ...SCOPE.filter(p => fs.existsSync(path.join(ROOT, p)))]);
  const packs = packScope();
  if (packs.length) git(['add', '-A', '-f', '--', ...packs]);
  const tree = gs(['write-tree']), h = head();
  if (h && gs(['rev-parse', h + '^{tree}']) === tree) return null;
  const c = gs(['commit-tree', tree, ...(h ? ['-p', h] : []), '-m', label]);
  gs(['update-ref', REF, c]);
  const s = state(); s.redo = []; save(s); // a new change ends the redo chain
  return c;
}

function changed(a, b) { return gs(['diff', '--name-only', a, b]).split('\n').filter(Boolean); }

function restore(commit, files) {
  for (const f of files) {
    const abs = path.join(ROOT, f);
    if (tryGs(['cat-file', '-e', commit + ':' + f]) !== null) { fs.mkdirSync(path.dirname(abs), { recursive: true }); fs.writeFileSync(abs, git(['show', commit + ':' + f])); }
    else if (fs.existsSync(abs)) fs.rmSync(abs);
  }
}

function rebuild(files) {
  const isWin = process.platform === 'win32';
  const mc = args => spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'game', ...args], { cwd: ROOT, stdio: 'inherit' });
  if (files.some(f => f.includes('/datapacks/'))) mc(['cmd', 'reload']);
  if (files.some(f => f.startsWith('run/resourcepacks/'))) mc(['run', 'mc.execute(mc::reloadResourcePacks);']);
  if (!files.some(f => f.startsWith('src/'))) return; // only packs changed: no Gradle
  const java = files.some(f => f.endsWith('.java'));
  const r = spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), java ? 'build' : 'apply'], { cwd: ROOT, stdio: 'inherit' }); // Gradle (dev) or in-game compile (portable)
  if (r.status !== 0) throw new Error('rebuild failed (files are restored; fix and rebuild)');
}

export function undo(filter) {
  snap('before undo'); // unsnapped edits count as the latest change
  let c = head();
  if (!c) throw new Error('no snapshots yet');
  for (;;) {
    const p = tryGs(['rev-parse', '-q', '--verify', c + '^']);
    if (!p) throw new Error(filter ? 'no change touching "' + filter + '" in the snapshots' : 'nothing to undo');
    const files = changed(p, c).filter(f => !filter || filter.toLowerCase().split('|').some(k => f.toLowerCase().split('/').some(seg => seg.startsWith(k)))); // classes Name*.java, packs Name-DP/...
    if (files.length) {
      const label = gs(['log', '-1', '--format=%s', c]);
      restore(p, files);
      if (filter) snap('undo ' + filter + ': ' + label); // per-project undo is itself an undoable change
      else { gs(['update-ref', REF, p]); const s = state(); s.redo.push(c); save(s); }
      rebuild(files);
      return { label, files };
    }
    if (!filter) { c = p; continue; } // empty snapshot: skip
    c = p;
  }
}

export function redo() {
  const s = state(), c = s.redo.pop();
  if (!c) throw new Error('nothing to redo');
  const files = changed(head(), c);
  restore(c, files);
  gs(['update-ref', REF, c]);
  save(s);
  rebuild(files);
  return { label: gs(['log', '-1', '--format=%s', c]), files };
}

export async function run(cmd, args, root) {
  ROOT = root; STATE = path.join(ROOT, 'run/moddeus/undo.json');
  if (cmd === 'snap') { const c = snap(args.join(' ') || 'manual'); console.log(c ? 'snapshot ' + c.slice(0, 8) : 'no changes'); return; }
  if (cmd === 'snaps') { console.log(tryGs(['log', '-' + (Number(args[0]) || 15), '--format=%h %cr  %s', REF]) || 'no snapshots'); return; }
  const r = cmd === 'redo' ? redo() : undo(args.join(' ').trim() || null);
  console.log((cmd === 'redo' ? 'redid: ' : 'undid: ') + r.label + '\n  ' + r.files.join('\n  '));
}
