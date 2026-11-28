// Builds for both setups.
// Game running (dev or portable): GameApi "build" = SourceBuild, an incremental in-game compile + direct swap (~0.2-0.5 s).
// The game also auto-builds on every save by itself; "mc build" is the explicit version that reports compile errors.
// Game closed (dev): Gradle compiles + copies resources.
// Portable (the mod jar in any launcher, workspace without Gradle): the game compiles the sources itself (GameApi "build",
// javac or bundled ECJ) and reads resources straight from src/main/resources, so "resources" is a no-op.
// Usage from code: import { build, resources, portable } from './build.mjs'.  CLI: node tools/mc.mjs build
import fs from 'node:fs';
import { hints } from './runhints.mjs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { lock, unlock } from './lock.mjs';

export const portable = root => !fs.existsSync(path.join(root, 'gradlew.bat')) && !fs.existsSync(path.join(root, 'gradlew'));

function gradle(root, args) {
  const isWin = process.platform === 'win32';
  lock(root);
  try {
    const r = spawnSync(path.join(root, isWin ? 'gradlew.bat' : 'gradlew'), [...args, '-q'], { cwd: root, encoding: 'utf8', shell: isWin, env: { ...process.env, JAVA_HOME: path.join(root, '.tools/jbr') } });
    const out = (r.stdout || '') + (r.stderr || '');
    if (r.status === 0) return { ok: true };
    const errs = out.split(/\r?\n/).filter(l => /error:|\^|symbol|location/.test(l) && l.trim()).slice(0, 30);
    return { ok: false, errors: errs.length ? errs : out.trim().split(/\r?\n/).slice(-12) };
  } finally { unlock(); }
}

async function gameBuild(root, java = true) {
  let info;
  try { info = JSON.parse(fs.readFileSync(path.join(root, 'run/moddeus/game.json'), 'utf8')); }
  catch { return { ok: false, errors: ['the game is not running: start Minecraft (sources compile inside the game)'] }; }
  lock(root);
  try {
    const r = await fetch(`http://127.0.0.1:${info.port}/build?t=${info.token}`, { method: 'POST', body: JSON.stringify({ java }) });
    const j = await r.json();
    if (j.error) return { ok: false, errors: [j.error] };
    return j;
  } catch (e) { return { ok: false, errors: ['game not reachable: ' + e.message] }; }
  finally { unlock(); }
}

/** Compile Java (+ copy resources in dev). {ok, errors?}. The running game swaps the result in by itself. */
export async function build(root, { java = true, gradle: useGradle = false } = {}) {
  // fast path (dev + portable): the running game compiles only what changed and swaps it at once (~0.2-0.5 s)
  if (!useGradle && gameUp(root)) {
    const r = await gameBuild(root, java);
    if (r.ok || !/not reachable/.test((r.errors || [])[0] || '')) return r;
  }
  if (portable(root)) return java ? gameBuild(root, java) : { ok: true };
  return gradle(root, java ? ['classes', 'clientClasses', 'processResources'] : ['processResources']); // game closed: Gradle
}

function gameUp(root) { try { return fs.existsSync(path.join(root, 'run/moddeus/game.json')); } catch { return false; } }

/** Resources only (textures, models, content JSON...). */
export async function resources(root) { return build(root, { java: false }); }

/** Prints the result like a compiler; returns true when it built. */
export function report(r, what = 'build') {
  if (r.ok) { console.log(what + ' ok' + (r.ms != null ? ` (${r.compiled ?? r.classes} compiled, ${r.changed} files swapped, ${r.ms} ms${r.compiler && r.compiler !== '-' ? ', ' + r.compiler : ''})` : '') + (r.ms != null ? ': live now' : ': the game swaps it in within ~2s')); return true; }
  const errs = (r.errors || []).join('\n'), h = hints(errs);
  console.error(what + ' FAILED:\n' + errs + (h ? '\n' + h : ''));
  process.exitCode = 1;
  return false;
}
