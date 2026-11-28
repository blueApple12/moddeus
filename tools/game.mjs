// Game tools for Claude Code: see and act in the running world. Used via: node tools/mc.mjs game <sub> ...
//   context                      player, position, looking at, inventory, nearby entities, biome, time
//   cmd "<command>" [...]        run commands as the player with full permissions (prints their output)
//   blocks x1 y1 z1 x2 y2 z2 [--counts]   blocks in a box (max 64^3); --counts = palette only
//   shot [--ui]                  screenshot of what the player sees -> prints PNG path (Read it to look); --ui keeps the Claude screen
//   say "<text>"                 message the player in chat as Claude
//   log [lines] [--grep re]      tail of run/logs/latest.log (hotswap errors, crashes, chat)
//   quick <action> [args]          instant prebuilt action, no compile (launch, safe_landing, tame_hostiles, ...); no args = list
//   eval|run "<java>"              run Java in the live game: compiled in memory, ~0.3s (--gradle = old hotswap path)
//                                In scope: server, player, level, mc (client), print(obj). Expression or statements.
//   stage <id> [report|clear|wait <ticks>|list]   private test stage far from the player (waits until loaded); Java: Stage.of("<id>")
//   studio entity|item|armor|block <id> [--angles 4] [--anim idle|walk|attack|hurt|death|pose1] [--t 0.5] [--pose stand,crouch,swim]
//          [--view gui,fp,hand,ground] [--nbt '{..}' entity NBT / item components] [--pitch 15] [--size 384]   offscreen renders -> contact sheet PNG (no camera use)
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const die = m => { console.error('game: ' + m); process.exit(1); };

async function api(endpoint, body = {}) {
  let info;
  try { info = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/game.json'), 'utf8')); } catch { die('game not running (no run/moddeus/game.json)'); }
  let r;
  try { r = await fetch(`http://127.0.0.1:${info.port}/${endpoint}?t=${info.token}`, { method: 'POST', body: JSON.stringify(body) }); }
  catch { die('game not reachable. Is Minecraft running with a world open?'); }
  const j = await r.json();
  if (j && j.error) die(j.error);
  return j;
}

function print(o) {
  if (typeof o !== 'object' || o === null) return console.log(o);
  for (const [k, v] of Object.entries(o)) {
    if (Array.isArray(v)) { console.log(k + ':'); v.forEach(x => console.log('  ' + (typeof x === 'object' ? JSON.stringify(x) : x))); }
    else if (typeof v === 'object' && v) { console.log(k + ':'); for (const [a, b] of Object.entries(v)) console.log('  ' + a + ': ' + b); }
    else console.log(k + ': ' + v);
  }
}

const EVAL_IMPORTS = ['java.util.*', 'net.minecraft.core.*', 'net.minecraft.core.registries.*', 'net.minecraft.core.component.*',
  'net.minecraft.world.item.*', 'net.minecraft.world.item.enchantment.*', 'net.minecraft.world.entity.*', 'net.minecraft.world.level.block.*',
  'net.minecraft.network.chat.Component', 'net.minecraft.resources.*', 'net.minecraft.server.MinecraftServer', 'net.minecraft.server.level.*',
  'net.minecraft.client.Minecraft', 'net.minecraft.world.phys.*', 'net.minecraft.world.level.*', 'net.minecraft.world.entity.player.*',
  'net.minecraft.world.effect.*', 'net.minecraft.sounds.*', 'net.minecraft.core.particles.*', 'net.minecraft.world.entity.monster.*', 'net.minecraft.world.entity.projectile.*',
  'net.minecraft.world.level.block.state.*', 'net.minecraft.world.entity.item.*', 'net.minecraft.world.level.levelgen.Heightmap', 'net.minecraft.server.permissions.*', 'dev.moddeus.live.Builds', 'dev.moddeus.live.Stage',
  // engine APIs (Moddeus has no slash commands: snippets call these directly)
  'dev.moddeus.content.Vfx', 'dev.moddeus.content.Physics', 'dev.moddeus.live.Combat', 'dev.moddeus.live.Bosses', 'dev.moddeus.live.SoundLoops', 'dev.moddeus.live.Systems', 'dev.moddeus.live.WorldFeel'];

/** `import a.b.C;` lines of a snippet go above the class: inside the method body javac says "illegal start of expression". */
function splitImports(code) {
  const imports = [];
  const body = String(code).replace(/^[ \t]*import\s+(static\s+)?[\w.]+(\.\*)?\s*;[ \t]*$/gm, m => { imports.push(m.trim()); return ''; });
  return { imports: imports.join('\n'), code: body };
}

/** Fast path: the game compiles the snippet in memory and runs it now (no Gradle, no files, no hotswap). */
async function runJava(snippet, capture = false) {
  const { imports, code } = splitImports(snippet);
  const name = 'R' + Date.now().toString(36);
  const body = (/;\s*$|[{}]/.test(code.trim()) ? code : `print(${code});`).replace(/\breturn\s*;/g, 'return OUT.toString();'); // snippet = body of a String method
  const src = `package dev.moddeus.eval;

${EVAL_IMPORTS.map(i => 'import ' + i + ';').join('\n')}
import dev.moddeus.live.Quick;
import static dev.moddeus.live.Quick.*;
${imports}

public final class ${name} {
	static final StringBuilder OUT = new StringBuilder();
	static void print(Object o) { OUT.append(o).append('\\n'); }

	public static String run() throws Exception {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
		ServerLevel level = player != null ? (ServerLevel) player.level() : server.overworld();
		try {
${body}
		} catch (Throwable t) {
			java.io.StringWriter w = new java.io.StringWriter();
			t.printStackTrace(new java.io.PrintWriter(w));
			OUT.append("EXCEPTION ").append(w.toString().lines().limit(12).reduce((a, b) -> a + "\\n" + b).orElse(""));
		}
		return OUT.toString();
	}
}
`;
  let info;
  try { info = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/game.json'), 'utf8')); } catch { die('game not running'); }
  const r = await fetch(`http://127.0.0.1:${info.port}/run?t=${info.token}`, { method: 'POST', body: JSON.stringify({ name, src }) }).then(x => x.json()).catch(() => null);
  if (r === null || (r.error && /unknown endpoint|no-compiler/.test(r.error))) return false; // old game build: use the Gradle path
  if (r.error && capture) die(r.error);
  if (r.error) { const e = r.error.replace(/^java\.lang\.IllegalArgumentException: /, ''), h = (await import('./runhints.mjs')).hints(e); console.error('game: ' + e + (h ? '\n' + h : '')); process.exitCode = 1; return true; }
  if (capture) return String(r);
  console.log(String(r).trimEnd() || '(no output; use print(...))');
  return true;
}

async function evalJava(code) {
  if (!code) die('usage: game eval "<java>"');
  if (!process.argv.includes('--gradle') && await runJava(code)) return;
  if (!process.argv.includes('--gradle')) die('game run failed: is the game running? (no Gradle fallback: Gradle under the live game breaks builds; pass --gradle to force the old path)');
  if ((await import('./build.mjs')).portable(ROOT)) die('game run failed (portable mode has no Gradle fallback); see the error above');
  if (!code) die('usage: game eval "<java>"');
  (await import('./lock.mjs')).lock(ROOT); // held until the result is back: a parallel build would delete this eval before it runs
  const { imports, code: evalCode } = splitImports(code); code = evalCode;
  const name = 'E' + Date.now().toString(36);
  const dir = path.join(ROOT, 'src/client/java/dev/moddeus/eval'); // client set sees both client and common classes
  const outFile = path.join(ROOT, 'run/moddeus/eval', name + '.out');
  fs.mkdirSync(dir, { recursive: true });
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  for (const f of fs.readdirSync(dir)) if (/^E\w+\.java$/.test(f)) fs.rmSync(path.join(dir, f), { force: true }); // stale (crashed) evals break the build
  const body = /;\s*$|[{}]/.test(code.trim()) ? code : `print(${code});`;
  const src = `package dev.moddeus.eval;

${EVAL_IMPORTS.map(i => 'import ' + i + ';').join('\n')}
${imports}

/** Generated by \`mc game eval\`; deleted after it runs. */
public final class ${name} {
	static final StringBuilder OUT = new StringBuilder();
	static void print(Object o) { OUT.append(o).append('\\n'); }

	@dev.moddeus.live.OnReload
	static void run() throws Exception {
		MinecraftServer server = dev.moddeus.live.LiveRegistry.server;
		ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
		ServerLevel level = player != null ? (ServerLevel) player.level() : server.overworld();
		Minecraft mc = Minecraft.getInstance(); // client: run UI/render work via mc.execute(() -> ...)
		try {
${body}
		} catch (Throwable t) {
			java.io.StringWriter w = new java.io.StringWriter();
			t.printStackTrace(new java.io.PrintWriter(w));
			OUT.append("EXCEPTION ").append(w.toString().lines().limit(12).reduce((a, b) -> a + "\\n" + b).orElse(""));
		}
		java.nio.file.Files.writeString(java.nio.file.Path.of(${JSON.stringify(outFile)}), OUT.toString());
	}
}
`;
  const file = path.join(dir, name + '.java');
  fs.writeFileSync(file, src);
  try {
    const r = spawnSync(path.join(ROOT, 'gradlew.bat'), ['clientClasses', '-q'], { cwd: ROOT, shell: true, encoding: 'utf8', env: { ...process.env, JAVA_HOME: path.join(ROOT, '.tools/jbr') } });
    if (r.status !== 0) {
      const errs = (r.stdout + r.stderr).split(/\r?\n/).filter(l => /error:|\^|symbol|location/.test(l) && !/^\s*$/.test(l)).slice(0, 20).join('\n');
      console.error('game: compile failed:\n' + (errs || (r.stdout + r.stderr).trim().split(/\r?\n/).slice(-10).join('\n')));
      process.exitCode = 1;
      return;
    }
    for (let i = 0; i < 100; i++) {
      if (fs.existsSync(outFile)) { console.log(fs.readFileSync(outFile, 'utf8').trimEnd() || '(no output; use print(...))'); return; }
      await new Promise(r => setTimeout(r, 200));
    }
    console.error('game: no result after 20s. Check: node tools/mc.mjs game log 30');
    process.exitCode = 1;
  } finally {
    fs.rmSync(file, { force: true });
    fs.rmSync(outFile, { force: true });
  }
}

export async function run(args) {
  const [sub, ...rest] = args;
  const flag = f => { const i = rest.indexOf(f); if (i < 0) return undefined; const v = rest[i + 1]; rest.splice(i, v && !v.startsWith('--') ? 2 : 1); return v && !v.startsWith('--') ? v : true; };
  switch (sub) {
    case 'context': print(await api('context')); break;
    case 'lag': print(await api('lag')); break; // server ms/tick, FPS, which mod/datapack/function costs what
    case 'cmd': case 'command': {
      if (!rest.length) die('usage: game cmd "<command>" [...]');
      for (const r of await api('command', { commands: rest })) console.log('/' + r.command + (r.output ? '\n  ' + r.output.replace(/\n/g, '\n  ') : '  (no output)'));
      if (rest.some(c => /^\/?(reload|datapack)\b/.test(c))) { // datapack edits become undo steps (mc undo <Mod>)
        try { await (await import('./snap.mjs')).run('snap', ['datapack: ' + rest.join('; ').slice(0, 60)], path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')); } catch {}
      }
      break;
    }
    case 'blocks': {
      const counts = flag('--counts');
      const n = rest.map(Number);
      if (n.length !== 6 || n.some(isNaN)) die('usage: game blocks x1 y1 z1 x2 y2 z2 [--counts]');
      print(await api('blocks', { from: n.slice(0, 3), to: n.slice(3), list: !counts }));
      break;
    }
    case 'shot': case 'screenshot': console.log((await api('screenshot', { ui: !!flag('--ui') })).path); break;
    case 'say': await api('say', { text: rest.join(' ') }); console.log('sent'); break;
    case 'log': {
      const grep = flag('--grep');
      const n = Number(rest[0] || 40);
      let lines = fs.readFileSync(path.join(ROOT, 'run/logs/latest.log'), 'utf8').split(/\r?\n/);
      if (grep) lines = lines.filter(l => new RegExp(grep, 'i').test(l));
      console.log(lines.slice(-n).join('\n'));
      break;
    }
    case 'eval': case 'run': { const f = flag('--file'); await evalJava(typeof f === 'string' ? fs.readFileSync(f, 'utf8') : rest.filter(a => a !== '--gradle').join(' ')); break; } // --file <path>: snippet from a file (no shell quoting)
    case 'stage': { // private test stage: see live/Stage.java and skill mc-content TESTING.md
      const [id, act = 'open', n] = rest;
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      const java = c => runJava(c, true).then(o => { if (o === false) die('game not running'); return o.trim(); });
      if (!id || id === 'list') { console.log(await java('print(Stage.list());')); break; }
      if (!['open', 'report', 'clear', 'wait'].includes(act)) die('usage: game stage <id> [report|clear|wait <ticks>|list]');
      const q = JSON.stringify(id);
      let ready = '';
      for (let i = 0; i < 80 && !ready.startsWith('ready'); i++) { // first open generates the chunks (async, a few s)
        ready = await java(`Stage s = Stage.of(${q}); print(s.ready() ? "ready " + s.id + " slot " + s.slot + ", centre " + s.origin.getX() + " " + s.origin.getY() + " " + s.origin.getZ() + ", tag " + s.tag : "loading");`);
        if (!ready.startsWith('ready')) await sleep(400);
      }
      if (!ready.startsWith('ready')) { await java(`print(Stage.of(${q}).clear());`); die('stage ' + id + ' still loading after 30 s (forceload released; try again)'); }
      if (act === 'clear') console.log(await java(`print(Stage.of(${q}).clear());`));
      else if (act === 'report') console.log(await java(`print(Stage.of(${q}).report());`));
      else if (act === 'wait') { await sleep(Number(n || 20) * 50); console.log(await java(`print(Stage.of(${q}).report());`)); }
      else console.log(ready + '\nJava (game run): Stage s = Stage.of(' + q + '); s.spawn("zombie"); s.hold("<item>").use(); print(s.report());');
      break;
    }
    case 'studio': { // offscreen model renders: client/game/Studio.java
      const o = {};
      for (const k of ['angles', 'anim', 't', 'pose', 'size', 'nbt', 'pitch', 'view']) { const v = flag('--' + k); if (v !== undefined && v !== true) o[k] = /^(angles|t|size|pitch)$/.test(k) ? Number(v) : v; }
      const [kind, id] = rest;
      if (!/^(entity|mob|item|armor|block)$/.test(kind || '') || !id) die('usage: game studio entity|item|armor|block <id> [--angles 4] [--anim walk] [--pose stand,crouch,swim] [--size 384]');
      Object.assign(o, { kind: kind === 'mob' ? 'entity' : kind, id });
      const name = (await runJava(`print(dev.moddeus.client.game.Studio.queue(${JSON.stringify(JSON.stringify(o))}));`, true)).trim();
      const done = path.join(ROOT, 'run/moddeus/shots/studio', name + '.json');
      for (let i = 0; i < 300 && !fs.existsSync(done); i++) await new Promise(r => setTimeout(r, 100));
      if (!fs.existsSync(done)) die('studio: no result after 30 s (game log: "studio")');
      const r = JSON.parse(fs.readFileSync(done, 'utf8'));
      if (r.error) die('studio: ' + r.error);
      console.log(r.sheet + '\n  sheet = ' + r.views.join(' | ') + ' (' + r.cols + ' per row; Read the PNG to look)');
      break;
    }
    case 'quick': { // several actions in one call: quick launch 3 + safe_landing
      const groups = rest.join(' ').split(/\s*\+\s*/).map(g => g.split(/\s+/).filter(Boolean));
      for (const g of groups.length ? groups : [[]]) {
        // "cmd <command>": a vanilla command in the chain (a leading "/" gets mangled into a path by Git Bash)
        const c = g[0] === 'cmd' ? g.slice(1).join(' ') : g[0]?.startsWith('/') || /^[A-Z]:\/Program Files\/Git\//.test(g[0] || '') ? g.join(' ').replace(/^([A-Z]:\/Program Files\/Git)?\//, '') : null;
        if (c !== null) { for (const r of await api('command', { commands: [c] })) console.log('/' + r.command + (r.output ? '  ' + r.output : '')); }
        else console.log(await api('quick', { action: g[0] || '', args: g.slice(1) }));
      }
      break;
    }
    default:
      console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n'));
  }
}
