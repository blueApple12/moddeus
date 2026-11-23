// Moddeus bridge: runs REAL interactive Claude Code sessions (pseudo-terminals, normal subscription
// usage, never -p/SDK) and exposes them to the in-game UI over a local WebSocket. Several sessions can run at once.
//
//  claude (TUI in a PTY) --HTTP hooks (?s=key)--> bridge --WebSocket--> Minecraft UI
//                        <--keystrokes----------         <--commands--
//
// Hooks give structure: MessageDisplay (streaming text), Pre/PostToolUse (tool cards), PermissionRequest
// (Allow/Deny held until the player answers), Stop (idle)... A headless xterm per session mirrors the screen.
// The bridge outlives the game; open chats are restored from run/moddeus/sessions.json.
import http from 'node:http';
import readline from 'node:readline';
import * as Projects from '../projects.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import pty from '@lydell/node-pty';
import xterm from '@xterm/headless';
import { WebSocketServer } from 'ws';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const DIR = path.join(ROOT, 'run/moddeus');
const PORT = Number(process.env.MODDEUS_PORT || 47821);
const TOKEN = crypto.randomBytes(16).toString('hex');
const TRANSCRIPTS = path.join(os.homedir(), '.claude/projects', ROOT.replace(/[^A-Za-z0-9]/g, '-'));
fs.mkdirSync(path.join(DIR, 'hooks'), { recursive: true });
const log = (...a) => fs.appendFileSync(path.join(DIR, 'bridge.log'), new Date().toISOString() + ' ' + a.join(' ') + '\n');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const short = (v, n = 4000) => { const s = typeof v === 'string' ? v : JSON.stringify(v, null, 2); return s && s.length > n ? s.slice(0, n) + '\n…' : s; };
const stripCtx = s => (s ?? '').replace(/\s*<minecraft_context>[\s\S]*?<\/minecraft_context>\s*/g, '').replace(/<\/?pasted_content[^>]*>/g, '').trim();
const toolSummary = (name, i = {}) => i.command || i.file_path || i.path || i.pattern || i.url || i.query || i.description || i.prompt?.slice?.(0, 80) || i.skill || '';
const claudeExe = (() => { try { return execFileSync('where', ['claude'], { encoding: 'utf8' }).split(/\r?\n/)[0].trim(); } catch { return 'claude'; } })();

// ---------- global (meta) state ----------
const meta = {
  skills: [], recent: [],
  models: [{ id: 'default', label: 'Default' }, { id: 'fable', label: 'Fable 5.2' }, { id: 'opus', label: 'Opus 5.5' },
    { id: 'sonnet', label: 'Sonnet 5.5' }, { id: 'haiku', label: 'Haiku 5.5' }, { id: 'opusplan', label: 'Opus Plan' }],
};

function frontmatter(file) {
  try {
    const m = fs.readFileSync(file, 'utf8').match(/^---\s*\n([\s\S]*?)\n---/);
    const o = {};
    if (m) for (const line of m[1].split('\n')) { const kv = line.match(/^(\w[\w-]*):\s*(.*)$/); if (kv) o[kv[1]] = kv[2].replace(/^["']|["']$/g, ''); }
    return o;
  } catch { return {}; }
}
const safeDir = d => { try { return fs.readdirSync(d); } catch { return []; } };
function scanSkills() {
  const out = new Map();
  const add = (name, file, source) => { const fm = frontmatter(file); if (fm['user-invocable'] !== 'false') out.set(name, { name, description: (fm.description || '').slice(0, 300), source }); };
  for (const [d, src] of [[path.join(os.homedir(), '.claude/skills'), 'user'], [path.join(ROOT, '.claude/skills'), 'project']])
    for (const s of safeDir(d)) { const f = path.join(d, s, 'SKILL.md'); if (fs.existsSync(f)) add(s, f, src); }
  for (const [d, src] of [[path.join(os.homedir(), '.claude/commands'), 'user'], [path.join(ROOT, '.claude/commands'), 'project']])
    for (const f of safeDir(d)) if (f.endsWith('.md')) add(f.slice(0, -3), path.join(d, f), src);
  const cache = path.join(os.homedir(), '.claude/plugins/cache');
  for (const mk of safeDir(cache)) for (const pl of safeDir(path.join(cache, mk))) {
    const v = safeDir(path.join(cache, mk, pl)).sort().pop();
    if (!v) continue;
    const base = path.join(cache, mk, pl, v);
    for (const s of safeDir(path.join(base, 'skills'))) { const f = path.join(base, 'skills', s, 'SKILL.md'); if (fs.existsSync(f)) add(pl + ':' + s, f, 'plugin'); }
    for (const c of safeDir(path.join(base, 'commands'))) if (c.endsWith('.md')) add(pl + ':' + c.slice(0, -3), path.join(base, 'commands', c), 'plugin');
  }
  // every built-in command (tools/bridge/commands.json, from code.claude.com/docs/en/commands.md)
  try {
    for (const c of JSON.parse(fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'commands.json'), 'utf8')))
      if (!out.has(c.name)) out.set(c.name, { name: c.name, description: c.description, args: c.args, interactive: c.interactive, source: 'command' });
  } catch (e) { log('commands.json', e.message); }
  // MCP servers (user + project scope) so they show up under /mcp
  const servers = new Set();
  try { const cj = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.claude.json'), 'utf8'));
    Object.keys(cj.mcpServers ?? {}).forEach(n => servers.add(n));
    Object.keys(cj.projects?.[ROOT]?.mcpServers ?? cj.projects?.[ROOT.replace(/\\/g, '/')]?.mcpServers ?? {}).forEach(n => servers.add(n)); } catch {}
  try { Object.keys(JSON.parse(fs.readFileSync(path.join(ROOT, '.mcp.json'), 'utf8')).mcpServers ?? {}).forEach(n => servers.add(n)); } catch {}
  if (servers.size && out.has('mcp')) out.get('mcp').description = 'MCP servers: ' + [...servers].join(', ') + '. Connect, authenticate, see tools';
  meta.mcpServers = [...servers];
  meta.skills = [...out.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/** Past conversations of this project (newest first), titled by their first prompt. */
function scanRecent() {
  const files = safeDir(TRANSCRIPTS).filter(f => f.endsWith('.jsonl'))
    .map(f => { try { return { f, m: fs.statSync(path.join(TRANSCRIPTS, f)).mtimeMs }; } catch { return null; } })
    .filter(Boolean).sort((a, b) => b.m - a.m).slice(0, 40);
  const open = new Set([...sessions.values()].map(s => s.st.sessionId));
  for (const h of hidden()) open.add(h);
  meta.recent = [];
  for (const { f, m } of files) {
    const id = f.slice(0, -6);
    if (open.has(id)) continue;
    let title = '';
    try {
      const fd = fs.openSync(path.join(TRANSCRIPTS, f), 'r');
      const buf = Buffer.alloc(256 * 1024);
      const n = fs.readSync(fd, buf, 0, buf.length, 0);
      fs.closeSync(fd);
      for (const line of buf.toString('utf8', 0, n).split('\n')) {
        let e; try { e = JSON.parse(line); } catch { continue; }
        if (e.type === 'summary' && e.summary) { title = e.summary; break; }
        const c = e.message?.content;
        const t = typeof c === 'string' ? c : Array.isArray(c) ? c.find(p => p.type === 'text')?.text : null;
        if (e.type === 'user' && t && !e.isMeta && !t.startsWith('<')) { title = stripCtx(t); break; }
      }
    } catch {}
    title = title.replace(/<\/?[a-z_]+[^>]*>/g, ' ').trim();
    if (title) meta.recent.push({ id, title: title.replace(/\s+/g, ' ').slice(0, 80), at: m, live: Date.now() - m < 3 * 60e3 });
  }
}

// closed chats never come back in Recent
const hiddenFile = path.join(DIR, 'hidden.json');
const hidden = () => { try { return JSON.parse(fs.readFileSync(hiddenFile, 'utf8')); } catch { return []; } };
const hide = id => { if (!id) return; const h = new Set(hidden()); h.add(id); fs.writeFileSync(hiddenFile, JSON.stringify([...h])); };

// ---------- sessions ----------
const sessions = new Map();
const COLS = 140, ROWS = 45;
let keySeq = 0;

// New chats default to Opus 5.5, high effort, auto mode (the player's choice); each chat remembers its own.
function makeSession({ sessionId = null, fork = false, mirror = false, title = '', model = null, effort = null, ultracode = false, mode = null, voice = false, project = null, brain = false, quick = false } = {}) {
  const key = 's' + Date.now().toString(36) + (++keySeq);
  const s = {
    key, fork, proc: null, seq: 0, dirty: true, screenDirty: false,
    term: new xterm.Terminal({ cols: COLS, rows: ROWS, allowProposedApi: true, scrollback: 2000 }),
    pending: new Map(), byMsg: new Map(), tools: new Map(),
    st: { key, title: title || 'New chat', status: 'paused', sessionId, mirror, model: model ?? 'claude-opus-5-5', modelLabel: null, effort: effort ?? 'high', ultracode, mode: mode ?? 'auto', notice: '', items: [], voice: voice || undefined, project: project ?? undefined, brain: brain || undefined, quick: quick || undefined },
  };
  sessions.set(key, s);
  s.origin = sessionId;
  if (sessionId) loadHistory(s, sessionId);
  persist();
  return s;
}

/** The voice's smart Opus chat for requests that aren't about one mod. */
function brainChat() {
  let t = [...sessions.values()].find(x => x.st.brain);
  if (!t) { t = makeSession({ title: 'Voice brain', brain: true }); metaDirty = true; }
  start(t);
  return t;
}

/** A new chat inside a mod project ('' = a new mod that names itself). */
function projectChat(id) {
  const p = Projects.projects(ROOT).find(x => x.id === id);
  const t = makeSession({ title: p ? p.name : 'New mod', project: id });
  start(t); metaDirty = true;
  return t;
}

function persist() {
  fs.writeFileSync(path.join(DIR, 'sessions.json'), JSON.stringify([...sessions.values()].filter(s => !s.test).map(s =>
    ({ sessionId: s.st.sessionId, mirror: s.st.mirror || undefined, fork: s.fork || s.forkPending || undefined, title: s.st.title, model: s.st.model, effort: s.st.effort, ultracode: s.st.ultracode, mode: s.st.mode, voice: s.st.voice, project: s.st.project, quick: s.st.quick, brain: s.st.brain })), null, 1));
}

function push(s, item) {
  item.id ??= 'i' + (++s.seq);
  item.at = Date.now();
  s.st.items.push(item);
  if (s.st.items.length > 400) s.st.items.splice(0, s.st.items.length - 400);
  return item;
}

function loadHistory(s, id) {
  try {
    const out = [];
    for (const line of fs.readFileSync(path.join(TRANSCRIPTS, id + '.jsonl'), 'utf8').split('\n')) {
      let e; try { e = JSON.parse(line); } catch { continue; }
      const c = e.message?.content;
      const ut = typeof c === 'string' ? c : Array.isArray(c) && !c.some(p => p.type === 'tool_result') ? c.filter(p => p.type === 'text').map(p => p.text).join('\n') : null;
      if (e.type === 'user' && ut && !e.isMeta && !ut.startsWith('<')) out.push({ kind: 'user', text: stripCtx(ut).replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '').trim() });
      else if (e.type === 'assistant' && Array.isArray(c)) for (const p of c) {
        if (p.type === 'text' && p.text?.trim()) out.push({ kind: 'assistant', text: p.text, done: true });
        else if (p.type === 'tool_use') out.push({ kind: 'tool', name: p.name, summary: short(toolSummary(p.name, p.input), 200), input: short(p.input, 3000), state: 'ok' });
      }
    }
    s.st.items = [];
    s.seq = 0;
    for (const it of out.slice(-200)) push(s, it);
    if (s.st.title === 'New chat' || s.st.title.startsWith('<')) { const u = out.find(x => x.kind === 'user'); if (u) s.st.title = u.text.replace(/<\/?[a-z_]+[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60); }
  } catch (e) { log('history', e.message); }
}

function hookSettings(s) {
  const url = ev => `http://127.0.0.1:${PORT}/hook/${ev}?t=${TOKEN}&s=${s.key}`;
  const events = ['SessionStart', 'UserPromptSubmit', 'MessageDisplay', 'PreToolUse', 'PostToolUse', 'PostToolUseFailure', 'PermissionRequest',
    'PermissionDenied', 'Notification', 'Stop', 'StopFailure', 'SubagentStart', 'SubagentStop', 'PreCompact', 'PostCompact', 'PostModelSwitch', 'SessionEnd'];
  const hooks = {};
  for (const ev of events) hooks[ev] = [{ hooks: [{ type: 'http', url: url(ev), timeout: ev === 'PermissionRequest' ? 3600 : 10 }] }];
  // Main chats: remind Claude of the project's own tools (vfx, physics, 3D models...) on every prompt. Not the voice chat (speed).
  if (!s.st.voice) hooks.UserPromptSubmit[0].hooks.push({ type: 'command', command: 'node "' + path.join(ROOT, 'tools', 'toolbox.mjs').replace(/\\/g, '/') + '"', timeout: 5 });
  const f = path.join(DIR, 'hooks', s.key + '.json');
  // Voice chat only: Claude Code's own dictation (tap mode), so the player's normal terminal settings stay untouched.
  fs.writeFileSync(f, JSON.stringify(s.st.voice ? { hooks, voice: { enabled: true, mode: 'tap' } } : { hooks }, null, 2));
  return f;
}

function start(s) {
  if (s.proc) return;
  if (s.st.mirror) {
    if (s.follow) return;
    const f = path.join(TRANSCRIPTS, s.st.sessionId + '.jsonl');
    let last = 0;
    const tick = () => { try { const m = fs.statSync(f).mtimeMs; if (m !== last) { last = m; loadHistory(s, s.st.sessionId); touch(s); } } catch {} };
    s.follow = setInterval(tick, 1500);
    tick();
    s.st.status = 'live';
    s.st.notice = 'Live view of a session running in your terminal. Send a message to continue it here.';
    touch(s);
    return;
  }
  // auto mode: Claude approves its own tools, so approval cards only show for what auto mode escalates. Manual (default) shows a card for every write.
  const args = ['--settings', hookSettings(s), '--permission-mode', s.st.mode || 'auto'];
  if (s.st.effort && s.st.effort !== 'ultracode') args.push('--effort', s.st.effort);
  if (s.st.sessionId) { args.push('--resume', s.st.sessionId); if (s.fork) args.push('--fork-session'); }
  if (s.st.model) args.push('--model', s.st.model);
  // Voice = speed: own short system prompt, Bash only, no MCP servers, no user hooks/plugins (their per-prompt context slowed Haiku down).
  // Every chat gets Claude's body in the world (tools/buddy-mcp.mjs): walk, mine, build, fight, see through its eyes.
  const buddyCfg = path.join(ROOT, 'run', 'moddeus', 'buddy-mcp.json'); // generated: holds this machine's absolute path
  fs.writeFileSync(buddyCfg, JSON.stringify({ mcpServers: { buddy: { command: 'node', args: [path.join(ROOT, 'tools', 'buddy-mcp.mjs')] } } }, null, 2));
  args.push('--mcp-config', buddyCfg);
  if (s.st.voice) args.push('--system-prompt', voicePrompt(), '--tools', 'Bash,Read', '--strict-mcp-config', '--setting-sources', 'project,local', '--allowedTools', 'Bash(node tools/mc.mjs:*) mcp__buddy');
  else args.push('--allowedTools', 'mcp__buddy');
  if (!s.st.voice && s.st.project) { // a mod project's chat: knows its mod; a fresh "New mod" names itself first
    const p = Projects.projects(ROOT).find(x => x.id === s.st.project);
    if (p) args.push('--append-system-prompt', Projects.prompt(p));
  }
  if (s.fork) s.forkPending = true; // until the fork reports its own session id, a restart must fork again
  s.fork = false;
  s.st.status = 'starting';
  s.st.notice = 'Starting Claude Code…';
  s.term.reset();
  // Drop markers inherited when the game was launched from another Claude Code session.
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(CLAUDECODE|CLAUDE_CODE_|CLAUDE_AGENT|CLAUDE_PROJECT_DIR)/.test(k)));
  log('spawn', s.key, args.join(' '));
  const p = pty.spawn(claudeExe, args, { name: 'xterm-256color', cols: s.term.cols, rows: s.term.rows, cwd: ROOT, env: { ...env, MODDEUS: '1' } });
  s.proc = p;
  p.onData(d => { s.term.write(d); s.screenDirty = true; });
  p.onExit(({ exitCode }) => {
    log('claude exited', s.key, exitCode, '|', screenText(s).filter(l => l.trim()).slice(-6).join(' / ').replace(/\s+/g, ' ').slice(0, 600));
    if (s.proc !== p) return;
    s.proc = null;
    s.st.status = 'stopped';
    s.st.notice = 'Claude Code exited (' + exitCode + '). Send a message to restart.';
    touch(s);
  });
  setTimeout(() => { if (s.st.status === 'starting') { s.st.status = 'idle'; s.st.notice = ''; touch(s); } }, 6000);
  touch(s);
}

function kill(s) { const p = s.proc; s.proc = null; try { p?.kill(); } catch {} }

const write = (s, data) => s.proc?.write(data);
// Typed raw, not bracketed-paste: Claude Code wraps pastes in <pasted_content> and treats them as untrusted. LF = newline in the prompt.
async function typeLine(s, text) { write(s, text.replace(/\r\n?/g, '\n').replace(/[\x00-\x08\x0b-\x1f\x7f]/g, '')); await sleep(120); write(s, '\r'); }
async function slash(s, cmd) { write(s, cmd); await sleep(250); write(s, '\r'); }
const waitFor = async (fn, ms) => { const end = Date.now() + ms; while (!fn() && Date.now() < end) await sleep(100); };
async function ready(s) {
  if (s.st.mirror) { clearInterval(s.follow); s.follow = null; s.st.mirror = false; s.fork = true; loadHistory(s, s.st.sessionId); s.st.notice = ''; }
  if (!s.proc) start(s); if (s.st.status === 'starting') await waitFor(() => s.st.status !== 'starting', 15000); }

function screenText(s) {
  const b = s.term.buffer.active, lines = [];
  for (let i = 0; i < s.term.rows; i++) lines.push(b.getLine(b.viewportY + i)?.translateToString(true) ?? '');
  return lines;
}
// Fallback: Claude Code showed its own approval prompt in the terminal (the hook missed, e.g. bridge restarted mid-request).
// Turn it into a card; Allow presses Enter (option 1), Deny presses Esc.
function terminalDialog(s) {
  const tail = screenText(s).filter(l => l.trim()).slice(-25);
  const at = tail.findLastIndex(l => /do you want to|would you like to/i.test(l));
  const dlg = at >= 0 && tail.slice(at).some(l => /❯\s*1\.\s*yes/i.test(l));
  const hasCard = s.st.items.some(i => i.kind === 'permission' && i.state === 'pending' && !i.terminal);
  if (dlg && !hasCard && !s.termCard) {
    s.dlgSince ??= Date.now();
    if (Date.now() - s.dlgSince < 1500) return; // give the PermissionRequest hook time to arrive first
    const text = tail.slice(Math.max(0, at - 8), at + 1).map(l => l.replace(/[│╭╮╰╯─]/g, '').trim()).filter(Boolean).join('\n');
    const it = push(s, { kind: 'permission', name: (text.match(/\b(Bash|Edit|Write|Read|WebFetch|WebSearch|REPL|Update|Create)\b/) || ['this'])[0], summary: text, state: 'pending', terminal: true });
    s.termCard = it;
    s.pending.set(it.id, v => { write(s, v.behavior === 'allow' ? '\r' : '\x1b'); it.state = v.behavior === 'allow' ? 'allowed' : 'denied'; s.st.status = 'busy'; touch(s); });
    s.st.status = 'waiting';
    touch(s);
  } else if (!dlg) {
    s.dlgSince = null;
    if (s.termCard) {
      if (s.termCard.state === 'pending') { s.termCard.state = 'allowed'; s.pending.delete(s.termCard.id); if (s.st.status === 'waiting') s.st.status = 'busy'; }
      s.termCard = null;
      touch(s);
    }
  }
}

function modeFromScreen(s) {
  const t = screenText(s).filter(l => l.trim()).slice(-8).join('\n').toLowerCase(); // skip blank bottom rows
  if (t.includes('accept edits on')) return 'acceptEdits';
  if (t.includes('plan mode on')) return 'plan';
  if (t.includes('bypass permissions on')) return 'bypassPermissions';
  if (t.includes('auto mode on')) return 'auto';
  if (t.includes('manual mode on')) return 'default';
  return t.includes('shift+tab') ? s.st.mode : 'default';
}
function headerInfo(s) {
  const b = s.term.buffer.active;
  for (let i = 0; i < Math.min(8, b.length); i++) {
    const m = (b.getLine(i)?.translateToString(true) ?? '').match(/([A-Z][\w. ]+?) with (\w+) effort/);
    if (m) { s.st.modelLabel = m[1].trim(); if (!s.st.effort) s.st.effort = m[2]; return; }
  }
}
async function setMode(s, target) {
  // cycle: auto -> manual(default) -> acceptEdits -> plan. Wait for the footer to redraw after each press.
  for (let i = 0; i < 6 && modeFromScreen(s) !== target; i++) {
    const before = modeFromScreen(s);
    write(s, '\x1b[Z');
    for (let w = 0; w < 15 && modeFromScreen(s) === before; w++) await sleep(100);
  }
  s.st.mode = modeFromScreen(s);
  touch(s);
}

// ---------- hooks ----------
async function onHook(s, ev, b) {
  const st = s.st;
  // mode comes from the terminal footer (modeFromScreen): hooks report "default" even in auto mode
  if (b.session_id && b.session_id !== st.sessionId) { st.sessionId = b.session_id; s.forkPending = false; persist(); }
  switch (ev) {
    case 'SessionStart':
      if (b.model) st.model = b.model;
      st.status = 'idle'; st.notice = '';
      if (b.source === 'clear') { st.items = []; st.title = 'New chat'; push(s, { kind: 'system', text: 'New conversation' }); }
      break;
    case 'UserPromptSubmit': {
      const text = stripCtx(b.prompt);
      push(s, { kind: 'user', text });
      if (st.title === 'New chat' && text && !text.startsWith('/')) { st.title = text.replace(/\s+/g, ' ').slice(0, 60); persist(); metaDirty = true; }
      st.status = 'busy'; st.notice = '';
      break;
    }
    case 'MessageDisplay': {
      let it = s.byMsg.get(b.message_id);
      if (!it) { it = push(s, { kind: 'assistant', text: '', done: false }); s.byMsg.set(b.message_id, it); }
      it.text += b.delta ?? '';
      if (b.final) it.done = true;
      break;
    }
    case 'PreToolUse': {
      const it = push(s, { kind: 'tool', name: b.tool_name, summary: short(toolSummary(b.tool_name, b.tool_input), 200), input: short(b.tool_input, 3000), state: 'running' });
      s.tools.set(b.tool_use_id, it);
      st.status = 'busy';
      break;
    }
    case 'PostToolUse':
    case 'PostToolUseFailure': {
      const it = s.tools.get(b.tool_use_id);
      if (it) { it.state = ev === 'PostToolUse' ? 'ok' : 'error'; it.output = short(b.tool_response ?? b.error ?? '', 3000); }
      break;
    }
    case 'PermissionRequest': {
      const it = push(s, { kind: 'permission', name: b.tool_name, summary: short(toolSummary(b.tool_name, b.tool_input), 300), input: short(b.tool_input, 3000), state: 'pending',
        canAlways: Array.isArray(b.permission_suggestions) && b.permission_suggestions.length > 0,
        raw: ['AskUserQuestion', 'ExitPlanMode'].includes(b.tool_name) ? b.tool_input : undefined });
      st.status = 'waiting';
      touch(s);
      const prev = st.status;
      const verdict = await new Promise(res => s.pending.set(it.id, res));
      s.pending.delete(it.id);
      if (s.key === 'selftest') sessions.delete('selftest');
      if (b.tool_name === 'ModdeusSelfTest') { st.items.splice(st.items.indexOf(it), 1); st.status = prev; touch(s); return { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: verdict.behavior } } }; }
      it.state = verdict.behavior === 'allow' ? 'allowed' : 'denied';
      st.status = 'busy';
      touch(s);
      const decision = { behavior: verdict.behavior };
      if (verdict.behavior === 'allow' && verdict.always && b.permission_suggestions) decision.updatedPermissions = b.permission_suggestions;
      if (verdict.behavior === 'allow' && verdict.answers) decision.updatedInput = { ...b.tool_input, answers: verdict.answers };
      else if (verdict.behavior === 'allow' && b.tool_name === 'ExitPlanMode') decision.updatedInput = b.tool_input;
      if (verdict.behavior === 'deny') decision.message = verdict.message || 'The player denied this in-game.';
      return { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision } };
    }
    case 'PermissionDenied': push(s, { kind: 'system', text: 'Denied: ' + b.tool_name }); break;
    case 'Notification':
      st.notice = b.message || '';
      if (b.notification_type === 'idle_prompt') { st.status = 'idle'; st.notice = ''; }
      break;
    case 'Stop':
      st.status = 'idle'; st.notice = '';
      for (const it of st.items) if (it.kind === 'assistant') it.done = true;
      speak(s);
      break;
    case 'StopFailure': st.status = 'idle'; push(s, { kind: 'system', text: 'Error: ' + (b.error || b.message || 'request failed') }); break;
    case 'SubagentStart': push(s, { kind: 'tool', name: 'Agent', summary: b.agent_type || b.description || 'subagent', state: 'running' }); break;
    case 'SubagentStop': { const it = [...st.items].reverse().find(x => x.name === 'Agent' && x.state === 'running'); if (it) it.state = 'ok'; break; }
    case 'PreCompact': st.notice = 'Compacting conversation…'; break;
    case 'PostCompact': st.notice = ''; push(s, { kind: 'system', text: 'Conversation compacted' }); break;
    case 'PostModelSwitch': st.model = b.model || b.new_model || b.to_model || st.model; persist(); break;
    case 'SessionEnd': st.status = 'stopped'; break;
  }
  return {};
}

// ---------- voice (hold K in game) ----------
// The voice helper's system prompt: tools/bridge/voice-prompt.md (read at each session start, so edits need no bridge restart).
const voicePrompt = () => fs.readFileSync(path.join(ROOT, 'tools', 'bridge', 'voice-prompt.md'), 'utf8');
let lastMain = null;
function voiceSession() {
  let v = [...sessions.values()].find(s => s.st.voice);
  if (!v) { v = makeSession({ title: 'Voice', model: 'haiku', effort: 'low', mode: 'auto', voice: true }); metaDirty = true; }
  if (!v.proc && !v.fresh) { // new conversation each bridge launch: old turns made Haiku copy its past hand-offs
    v.fresh = true; v.st.sessionId = null; v.st.items = []; v.st.model = 'haiku'; v.st.effort = 'low'; v.st.mode = 'auto';
  }
  if (!v.proc) start(v);
  return v;
}
/** The text in Claude Code's prompt box (live dictation shows up there). */
function inputLine(s) {
  const l = screenText(s).map(x => x.match(/^[│|\s]*[>❯]\s(.*?)[\s│|]*$/)).filter(Boolean).pop();
  const t = l ? l[1].replace(/[▁▂▃▄▅▆▇█]/g, '').trim() : '';
  return /^Try "/.test(t) ? '' : t; // placeholder hint, not text
}
// ---------- TTS: read Claude's replies aloud (voice chat + main chats) ----------
// Local neural voice (Kokoro-82M, tools/bridge/tts.py, kept loaded: first words in ~0.5 s, no internet, free).
// Settings: run/moddeus/tts.json {"enabled": true, "voice": "af_heart", "speed": 1.1, "max": 500}
// Voices: af_heart af_bella am_michael am_fenrir bf_emma bm_george ... Pressing G cuts speech off.
const TTS = { py: [path.join(os.homedir(), 'AppData/Local/Programs/Python/Python314/python.exe')].find(p => fs.existsSync(p)) || 'python', proc: null };
function ttsConf() {
  const d = { enabled: true, voice: 'af_heart', speed: 1.1, max: 500 };
  try { return { ...d, ...JSON.parse(fs.readFileSync(path.join(DIR, 'tts.json'), 'utf8')) }; } catch { return d; }
}
function ttsDaemon() {
  if (TTS.proc && TTS.proc.exitCode === null) return TTS.proc;
  TTS.proc = spawn(TTS.py, [path.join(ROOT, 'tools', 'bridge', 'tts.py')], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  // envelope lines -> game: the orb grows/shrinks with the voice
  readline.createInterface({ input: TTS.proc.stdout }).on('line', l => {
    if (!l.startsWith('{')) return;
    try { const m = JSON.parse(l); toGames(JSON.stringify(m.env ? { type: 'speak', env: m.env, hz: m.hz } : { type: 'speak' })); } catch {}
  });
  TTS.proc.stderr.on('data', d => log('tts: ' + String(d).trim().slice(-300)));
  TTS.proc.on('exit', c => log('tts daemon exited ' + c));
  return TTS.proc;
}
function speakable(t, max) {
  t = t.replace(/```[\s\S]*?```/g, ' ').replace(/`([^`]*)`/g, '$1').replace(/https?:\/\/\S+/g, ' ').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_#>|]/g, ' ').replace(/^\s*[-•]\s+/gm, '').replace(/[A-Za-z]:[\\/][^\s]*/g, ' ').replace(/\s+/g, ' ').trim();
  if (t.length > max) { const cut = t.slice(0, max); const end = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('! '), cut.lastIndexOf('? ')); t = end > max / 3 ? cut.slice(0, end + 1) : cut; }
  return t;
}
function toGames(msg) { for (const c of clients) if (c.readyState === 1) c.send(msg); }
function ttsStop() { if (TTS.proc && TTS.proc.exitCode === null) TTS.proc.stdin.write('{"stop":true}\n'); }
function speak(s) {
  const c = ttsConf();
  if (!c.enabled) return;
  const it = [...s.st.items].reverse().find(i => i.kind === 'assistant' && i.text);
  if (!it || it.spoken) return;
  it.spoken = true;
  const text = speakable(it.text, s.st.voice ? 200 : c.max);
  if (!text || text === '?') return;
  try { ttsDaemon().stdin.write(JSON.stringify({ text, voice: c.voice, speed: c.speed }) + '\n'); } catch (e) { log('tts error ' + e.message); }
}

// ---------- voice (hold G in game) ----------
// Claude Code /voice tap mode: Space toggles recording, the footer shows "● REC" while it records. Every toggle is
// checked against the screen (and retried once), because Claude Code can also stop by itself (silence) and the screen
// lags the key by a few hundred ms. Press/release run in order; the "press Enter for short transcripts" wait runs on
// its own so the next press never waits behind it.
const tailLines = (v, n) => screenText(v).filter(l => l.trim()).slice(-n); // the terminal's bottom rows are often blank
let voiceChain = Promise.resolve(), voiceGen = 0;
function voice(on) {
  voiceChain = voiceChain.then(() => voiceNow(on)).catch(e => log('voice error ' + (e && e.stack || e)));
  return voiceChain;
}

// G held = recording, G released = stop. One Space tap each way (Claude Code /voice tap mode), no screen detection.
async function voiceNow(on) {
  const v = voiceSession();
  await ready(v);
  if (on) {
    if (v.recording) return;
    ttsStop(); // talking over Claude cuts it off
    voiceGen++; // cancels the pending Enter of the previous release
    // A Claude Code dialog (e.g. auto-mode setup) would swallow Space: close it, clear leftover text.
    if (/Esc to cancel|to navigate|Enter to confirm/.test(tailLines(v, 10).join('\n'))) { write(v, '\x1b'); await sleep(300); }
    if (inputLine(v)) write(v, '\x15');
    write(v, ' ');
    v.recording = true; v.st.dictation = ''; v.st.listening = true; touch(v);
    clearInterval(v.dictTimer);
    v.dictTimer = setInterval(() => { const t = inputLine(v); if (t !== v.st.dictation) { v.st.dictation = t; touch(v); } }, 150);
    log('voice on');
    return;
  }
  if (!v.recording) return;
  write(v, ' ');
  v.recording = false; v.st.listening = false; touch(v);
  log('voice off');
  settleAndSend(v, voiceGen); // not awaited
}

// Shorter transcripts are only inserted: press Enter once the text has settled (cancelled by the next press).
async function settleAndSend(v, gen) {
  let prev = null, same = 0;
  for (let i = 0; i < 40 && v.st.status !== 'busy' && gen === voiceGen; i++) {
    await sleep(200);
    const t = inputLine(v), busyScreen = /REC|listening|transcrib|processing/i.test(tailLines(v, 4).join(' '));
    same = t && t === prev && !busyScreen ? same + 1 : 0; prev = t;
    if (same >= 3) { if (gen === voiceGen) write(v, '\r'); break; }
  }
  if (gen === voiceGen) { clearInterval(v.dictTimer); v.st.dictation = ''; touch(v); }
}

// ---------- WebSocket fan-out ----------
const clients = new Set();
let metaDirty = true, bTimer = null;
const touch = s => { s.dirty = true; schedule(); };
function summary() {
  return [...sessions.values()].filter(s => !s.test).map(s => ({ key: s.key, title: s.st.title, status: s.st.status, sessionId: s.st.sessionId, project: s.st.project, voice: s.st.voice, brain: s.st.brain, quick: s.st.quick }));
}
function schedule() {
  if (bTimer) return;
  bTimer = setTimeout(() => {
    bTimer = null;
    const msgs = [];
    for (const s of sessions.values()) if (s.dirty && !s.test) { s.dirty = false; msgs.push(JSON.stringify({ type: 'state', key: s.key, state: s.st })); metaDirty = true; }
    if (metaDirty) { metaDirty = false; msgs.unshift(JSON.stringify({ type: 'meta', meta: { ...meta, sessions: summary(), projects: Projects.projects(ROOT) } })); }
    for (const c of clients) if (c.readyState === 1) for (const m of msgs) c.send(m);
  }, 40);
}
setInterval(() => {
  for (const s of sessions.values()) {
    if (!s.screenDirty) continue;
    s.screenDirty = false;
    const before = s.st.mode + s.st.modelLabel;
    if (s.st.status !== 'starting') s.st.mode = modeFromScreen(s);
    headerInfo(s);
    if (before !== s.st.mode + s.st.modelLabel) touch(s);
    terminalDialog(s);
    const msg = JSON.stringify({ type: 'screen', key: s.key, lines: screenText(s) });
    for (const c of clients) if (c.readyState === 1 && c.watch === s.key) c.send(msg);
  }
}, 150);

function snapshot(ws) {
  ws.send(JSON.stringify({ type: 'meta', meta: { ...meta, sessions: summary(), projects: Projects.projects(ROOT) } }));
  for (const s of sessions.values()) ws.send(JSON.stringify({ type: 'state', key: s.key, state: s.st }));
}

function open(sessionId, fork) {
  for (const s of sessions.values()) if (s.st.sessionId === sessionId) return s;
  scanRecent();
  const r = meta.recent.find(x => x.id === sessionId);
  const live = !!r?.live || fork === true;
  const s = makeSession({ sessionId, mirror: live, title: r?.title });
  scanRecent();
  metaDirty = true;
  return s;
}

async function onCommand(ws, m) {
  const s = sessions.get(m.s);
  if (!['key', 'resize', 'screen', 'focus'].includes(m.type)) log('cmd', m.type, m.s ?? '', ws.http ? 'http' : 'ws');
  switch (m.type) {
    case 'new': { const n = makeSession(); if (m.model) n.st.model = m.model; if (m.mode) n.st.mode = m.mode; if (m.test) n.test = true; start(n); metaDirty = true; ws.send(JSON.stringify({ type: 'focus', key: n.key })); break; }
    case 'open': { const n = open(m.sessionId, m.fork); start(n); ws.send(JSON.stringify({ type: 'focus', key: n.key })); break; }
    case 'close':
      if (s) { kill(s); clearInterval(s.follow); hide(s.st.sessionId); hide(s.origin); sessions.delete(s.key); persist(); scanRecent(); metaDirty = true; if (!sessions.size) { const n = makeSession(); ws.send(JSON.stringify({ type: 'focus', key: n.key })); } }
      break;
    case 'skills': scanSkills(); metaDirty = true; break;
    case 'recent': scanRecent(); metaDirty = true; break;
    case 'voice': await voice(!!m.on); break;
    case 'forward': { // voice helper hands a request on: to the mod it is about, a new mod project, or the last main chat
      const list = Projects.projects(ROOT);
      const p = m.newProject ? null : (m.project ? list.find(x => x.id === m.project || x.name.toLowerCase() === String(m.project).toLowerCase()) : null) ?? (m.smart ? null : Projects.match(list, m.text));
      let t;
      if (m.newProject) t = projectChat(Projects.add(ROOT, m.newProject, String(m.text).slice(0, 200)).id);
      else if (p) t = [...sessions.values()].filter(x => x.st.project === p.id && !x.test).pop() ?? projectChat(p.id);
      else t = brainChat(); // not about a mod: the voice's own Opus chat ("Voice brain")
      if (!m.quiet) { // quiet = automatic report (lag guard): don't pull the player's screen to that chat
        lastMain = t.key;
        for (const c of clients) if (c.readyState === 1) c.send(JSON.stringify({ type: 'focus', key: t.key }));
      }
      await sessionCommand(t, { type: 'send', text: m.text });
      break;
    }
    case 'quick-new': { const t = makeSession({ title: 'Quick chat', quick: true }); start(t); metaDirty = true; ws.send(JSON.stringify({ type: 'focus', key: t.key })); break; }
    case 'project-new': { const t = projectChat(Projects.add(ROOT, m.name || 'New mod', m.desc || '', !m.name).id); /* the mod exists at once */ ws.send(JSON.stringify({ type: 'focus', key: t.key })); if (m.text) await sessionCommand(t, { type: 'send', text: m.text }); break; }
    case 'brain': { const t = brainChat(); ws.send(JSON.stringify({ type: 'focus', key: t.key })); break; }
    case 'project-chat': { const t = projectChat(m.id); ws.send(JSON.stringify({ type: 'focus', key: t.key })); break; }
    case 'project-name': { // names a project (id, or the project of chat key m.s); an unnamed "New mod" also gets its class prefix
      const list = Projects.projects(ROOT);
      const id = list.some(x => x.id === m.id) ? m.id : sessions.get(m.s)?.st.project || list.filter(x => x.fresh).pop()?.id;
      const r = id && Projects.rename(ROOT, id, m.name, m.desc || '');
      if (!r) { ws.send(JSON.stringify({ ok: false, error: 'no such project' })); break; }
      for (const t of sessions.values()) if (t.st.project === r.oldId) { t.st.project = r.p.id; t.st.title = r.p.name; touch(t); }
      persist(); metaDirty = true; ws.send(JSON.stringify({ ok: true, id: r.p.id }));
      break;
    }
    case 'project-set': { const list = Projects.projects(ROOT), p = list.find(x => x.id === m.id); if (p) { p.enabled = m.enabled === true || m.enabled === 'true'; Projects.save(ROOT, list); } metaDirty = true; break; }
    case 'project-delete': { // the game already removed the code (mc remove); close the mod's chats and forget it
      for (const x of [...sessions.values()]) if (x.st.project === m.id && x.st.project) { kill(x); clearInterval(x.follow); sessions.delete(x.key); }
      Projects.save(ROOT, Projects.projects(ROOT).filter(x => x.id !== m.id));
      persist(); metaDirty = true;
      break;
    }
    case 'focus': if (s && !s.st.voice) lastMain = s.key; if (s) { ws.watch = s.key; if (!s.proc) start(s); ws.send(JSON.stringify({ type: 'screen', key: s.key, lines: screenText(s) })); } break;
    case 'screen': if (s) ws.watch = m.on ? s.key : null; break;
    default: if (s) await sessionCommand(s, m);
  }
  schedule();
}

async function sessionCommand(s, m) {
  switch (m.type) {
    case 'send': await ready(s); await typeLine(s, m.context ? `${m.text}\n\n<minecraft_context>\n${m.context}\n</minecraft_context>` : m.text); break;
    case 'slash': await ready(s); await slash(s, m.text); break;
    case 'model': s.st.model = m.value; persist(); await ready(s); await slash(s, '/model ' + m.value); break;
    case 'effort': s.st.effort = m.value; persist(); await ready(s); await slash(s, '/effort ' + m.value); break;
    case 'ultracode': s.st.ultracode = !!m.on; persist(); await ready(s); await slash(s, m.on ? '/effort ultracode' : '/effort ultracode off'); break;
    case 'mode': await ready(s); await setMode(s, m.value); break;
    case 'interrupt': write(s, '\x1b'); break;
    case 'permission': s.pending.get(m.id)?.({ behavior: m.behavior, always: m.always, message: m.message, answers: m.answers }); break;
    case 'clear': await ready(s); await slash(s, '/clear'); break;
    case 'restart': kill(s); await sleep(500); if (m.fresh) { s.st.sessionId = null; s.st.items = []; s.st.title = 'New chat'; } start(s); break;
    case 'key': write(s, m.data); break;
    case 'resize': {
      const c = Math.max(40, Math.min(300, m.cols | 0)), r = Math.max(10, Math.min(120, m.rows | 0));
      if (c !== s.term.cols || r !== s.term.rows) { s.term.resize(c, r); s.proc?.resize(c, r); }
      break;
    }
  }
  touch(s);
}

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.searchParams.get('t') !== TOKEN) { res.writeHead(403).end(); return; }
  const body = async () => { let b = ''; for await (const c of req) b += c; return b; };
  if (req.method === 'POST' && u.pathname.startsWith('/hook/')) {
    const raw = await body();
    let s = sessions.get(u.searchParams.get('s'));
    if (!s && u.searchParams.get('s') === 'selftest') { // mc test: a throwaway hidden chat, never a real one
      s = makeSession(); sessions.delete(s.key); s.key = s.st.key = 'selftest'; s.test = true; s.st.status = 'idle'; sessions.set('selftest', s);
    }
    let out = {};
    if (s) { try { out = await onHook(s, u.pathname.slice(6), JSON.parse(raw || '{}')); } catch (e) { log('hook error', e.stack); } touch(s); }
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(out ?? {}));
    return;
  }
  if (req.method === 'POST' && u.pathname === '/cmd') {
    const m = JSON.parse((await body()) || '{}');
    if (!m.s && m.type !== 'new' && m.type !== 'open') m.s = [...sessions.keys()][0];
    const sent = [];
    await onCommand({ http: true, send: x => sent.push(x) }, m);
    res.writeHead(200, { 'content-type': 'application/json' }).end('[' + sent.join(',') + ']');
    return;
  }
  const s = sessions.get(u.searchParams.get('s')) ?? [...sessions.values()][0];
  if (u.pathname === '/screen') { res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' }).end(s ? screenText(s).join('\n') : ''); return; }
  if (u.pathname === '/state') { res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ meta: { ...meta, sessions: summary(), projects: Projects.projects(ROOT) }, state: s?.st })); return; }
  res.writeHead(404).end();
});

const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, sock, head) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname !== '/ws' || u.searchParams.get('t') !== TOKEN) { sock.destroy(); return; }
  wss.handleUpgrade(req, sock, head, ws => wss.emit('connection', ws));
});
wss.on('connection', ws => {
  clients.add(ws);
  ws.on('close', () => clients.delete(ws));
  snapshot(ws);
  ws.on('message', async raw => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    try { await onCommand(ws, m); } catch (e) { log('cmd error', e.stack); }
  });
});

server.on('error', e => { if (e.code === 'EADDRINUSE') { log('another bridge already owns port', PORT); process.exit(0); } throw e; });
server.listen(PORT, '127.0.0.1', () => {
  fs.writeFileSync(path.join(DIR, 'bridge.json'), JSON.stringify({ port: PORT, token: TOKEN, pid: process.pid }));
  if (ttsConf().enabled) ttsDaemon(); // warm: model load ~2 s
  log('bridge up on', PORT, 'claude =', claudeExe);
  scanSkills();
  let saved = [];
  try { saved = JSON.parse(fs.readFileSync(path.join(DIR, 'sessions.json'), 'utf8')); } catch {}
  if (!saved.length) { try { const old = JSON.parse(fs.readFileSync(path.join(DIR, 'session.json'), 'utf8')); if (old.sessionId) saved = [old]; } catch {} }
  for (const o of saved) makeSession(o);
  for (const s of sessions.values()) { // chats live inside mods: an old free chat becomes its own mod
    if (s.st.voice || s.st.brain || s.st.quick || s.st.project) continue;
    if (!s.st.sessionId) { sessions.delete(s.key); continue; } // never used: drop
    const name = String(s.st.title || 'Mod').replace(/^(please |can you |could you )?(create|make|add|build|do|give me|i want)( me)?( an?| the)? /i, '').split(/\s+/).slice(0, 4).join(' ');
    const fresh = /^new mod/i.test(s.st.title || '');
    s.st.project = Projects.add(ROOT, fresh ? 'New mod' : name.replace(/^./, c => c.toUpperCase()), '', fresh).id;
  }
  persist();
  if (![...sessions.values()].some(s => s.st.voice)) {} // voice session is made by voiceSession()
  scanRecent();
  start([...sessions.values()].find(s => !s.st.voice) ?? [...sessions.values()][0]); // warm the first chat; others start when opened
  voiceSession(); // warm the voice helper so hold-K works instantly
  schedule();
});
process.on('uncaughtException', e => log('uncaught', e.stack));
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP', 'exit']) process.on(sig, () => { for (const s of sessions.values()) { try { s.proc?.kill(); } catch {} } if (sig !== 'exit') process.exit(0); });
