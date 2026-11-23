// Fast self-test of the live stack. Every check is hard-capped at 10s; all run in parallel.
//   node tools/mc.mjs test [name ...]     names: game, cmd, blocks, eval, shot, bridge, permission, mode
//   node tools/mc.mjs test approval       opt-in: real Haiku chat asks to run Bash -> card -> allow (a few tokens)
// Needs the game running with a world open (game checks) and the bridge up (bridge checks). No model usage.
import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LIMIT = Number(process.env.TEST_LIMIT || 10_000);
const json = f => JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus', f), 'utf8'));
const assert = (c, m) => { if (!c) throw new Error(m); };

async function game(ep, body = {}) {
  const g = json('game.json');
  const r = await fetch(`http://127.0.0.1:${g.port}/${ep}?t=${g.token}`, { method: 'POST', body: JSON.stringify(body) });
  const j = await r.json();
  if (j?.error) throw new Error(j.error);
  return j;
}
async function bridge(p, body) {
  const b = json('bridge.json');
  const r = await fetch(`http://127.0.0.1:${b.port}${p}${p.includes('?') ? '&' : '?'}t=${b.token}`, body ? { method: 'POST', body: JSON.stringify(body) } : {});
  const t = await r.text();
  try { return JSON.parse(t); } catch { return t; }
}
const mc = args => new Promise((res, rej) => execFile(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), ...args], { cwd: ROOT, timeout: LIMIT },
  (e, out, err) => e ? rej(new Error((err || out || e.message).trim().split('\n').slice(0, 6).join(' | '))) : res(out)));

const tests = {
  async game() { const c = await game('context'); assert(c.player && c.pos, 'no player in context'); return c.player + ' @ ' + c.block_pos; },
  async cmd() { const [r] = await game('command', { commands: ['time query gametime'] }); assert(/\d/.test(r.output), 'no output: ' + r.output); return r.output; },
  async blocks() { const c = await game('context'); const [x, y, z] = c.block_pos.split(', ').map(Number);
    const b = await game('blocks', { from: [x - 2, y - 2, z - 2], to: [x + 2, y, z + 2], list: false }); assert(b.volume === 75, 'volume ' + b.volume); return Object.keys(b.counts).length + ' block types'; },
  async eval() { const out = (await mc(['game', 'eval', '6 * 7'])).split('\n')[0].trim(); assert(out === '42', 'got ' + out); return '6*7 = 42'; },
  async shot() { const p = (await game('screenshot')).path; assert(fs.statSync(p).size > 1000, 'empty png'); fs.rmSync(p); return 'png ok'; },
  async bridge() { const s = await bridge('/state'); assert(s.meta?.skills?.length > 10, 'meta missing'); return s.meta.sessions.length + ' sessions, ' + s.meta.skills.length + ' commands'; },
  async permission() {
    const s = { key: 'selftest' }; // the bridge makes a hidden throwaway chat for this key
    const hook = bridge(`/hook/PermissionRequest?s=${s.key}`, { tool_name: 'ModdeusSelfTest', tool_input: { command: 'echo selftest' } });
    let card;
    for (let i = 0; i < 40 && !card; i++) { await new Promise(r => setTimeout(r, 100)); card = (await bridge('/state?s=' + s.key)).state.items.find(x => x.name === 'ModdeusSelfTest' && x.state === 'pending'); }
    assert(card, 'card never appeared');
    await bridge('/cmd', { type: 'permission', s: s.key, id: card.id, behavior: 'allow' });
    const res = await hook;
    assert(res?.hookSpecificOutput?.decision?.behavior === 'allow', 'hook answered ' + JSON.stringify(res));
    return 'card shown + allow returned';
  },
  /** Opt-in (uses a few Haiku tokens): real Claude asks to run Bash, card appears, allow, command runs. */
  async approval() {
    const [{ key }] = await bridge('/cmd', { type: 'new', model: 'haiku', mode: 'default', test: true }); // test chats are hidden from the game UI
    const st = async () => (await bridge('/state?s=' + key)).state;
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    try {
      for (let i = 0; i < 60 && (await st()).status !== 'idle'; i++) await sleep(100);
      await bridge('/cmd', { type: 'send', s: key, text: 'Use the Bash tool to run: mkdir -p run/moddeus/approval-test' });
      let card;
      for (let i = 0; i < 80 && !card; i++) { await sleep(100); card = (await st()).items.find(x => x.kind === 'permission' && x.state === 'pending'); }
      assert(card, 'no approval card (mode: ' + (await st()).mode + ')');
      await bridge('/cmd', { type: 'permission', s: key, id: card.id, behavior: 'allow' });
      for (let i = 0; i < 60; i++) { await sleep(100); const t = (await st()).items.find(x => x.kind === 'tool' && x.name === 'Bash'); if (t?.state === 'ok') return 'card -> allow -> Bash ran'; }
      throw new Error('allowed, but Bash never finished');
    } finally { await bridge('/cmd', { type: 'close', s: key }); }
  },
  async mode() {
    const out = [];
    for (const s of (await bridge('/state')).meta.sessions.filter(x => x.status !== 'live' && x.status !== 'paused')) {
      const screen = String(await bridge('/screen?s=' + s.key)).toLowerCase();
      const shown = screen.includes('accept edits on') ? 'acceptEdits' : screen.includes('plan mode on') ? 'plan' : screen.includes('bypass permissions on') ? 'bypassPermissions' : screen.includes('auto mode on') ? 'auto' : 'default';
      const st = (await bridge('/state?s=' + s.key)).state.mode;
      assert(st === shown, `${s.key}: state says ${st}, screen shows ${shown}`);
      out.push(shown);
    }
    return out.join(', ') || 'no running sessions';
  },
};

export async function run(names) {
  const pick = names.length ? names : Object.keys(tests).filter(n => n !== 'approval');
  const t0 = Date.now();
  const results = await Promise.all(pick.map(async n => {
    if (!tests[n]) return [n, false, 'unknown test', 0];
    const s = Date.now();
    try {
      const lim = n === 'approval' ? LIMIT + 5000 : LIMIT; // approval: +5s for Claude itself to boot
      const r = await Promise.race([tests[n](), new Promise((_, rej) => setTimeout(() => rej(new Error('TIMEOUT >' + lim / 1000 + 's')), lim))]);
      return [n, true, r, Date.now() - s];
    } catch (e) { return [n, false, e.message, Date.now() - s]; }
  }));
  for (const [n, ok, msg, ms] of results) console.log(`${ok ? 'PASS' : 'FAIL'} ${n.padEnd(10)} ${String(ms).padStart(5)}ms  ${msg}`);
  const failed = results.filter(r => !r[1]).length;
  console.log(`${results.length - failed}/${results.length} passed in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  process.exit(failed ? 1 : 0);
}
