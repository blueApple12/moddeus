#!/usr/bin/env node
// MCP server (stdio) for Claude's orb in the live Minecraft world: a floating Claude spark that follows the player,
// pulses with Claude's voice, and can be told to stay / go somewhere / say something. Talks to GameApi "orb" endpoint.
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/, '$1')), '..');
const TOOLS = [
  { name: 'orb', description: "Move or control YOU: the Moddeus mascot / buddy (floating cube-head that follows the player). action: follow (default behaviour: roams around the player) | stay (hover here until follow) | goto (x/y/z, at=crosshair|player, or blocks=N ahead of the player; then stays) | teleport (to the player now) | spin | size (value, 0.3-4) | hide | show | say (text = speech bubble) | status.", inputSchema: { type: 'object', properties: {
    action: { type: 'string', enum: ['follow', 'stay', 'goto', 'teleport', 'spin', 'size', 'hide', 'show', 'say', 'status'] },
    x: { type: 'number' }, y: { type: 'number' }, z: { type: 'number' },
    at: { type: 'string', enum: ['crosshair', 'player'] }, blocks: { type: 'number' }, value: { type: 'number' }, text: { type: 'string' } }, required: ['action'] } },
  { name: 'status', description: 'Text only: where the mascot and player are, mode (follow/stay).', inputSchema: { type: 'object', properties: {} } },
];

function game() { return JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/game.json'), 'utf8')); }
async function api(endpoint, body) {
  const g = game();
  const r = await fetch(`http://127.0.0.1:${g.port}/${endpoint}?t=${g.token}`, { method: 'POST', body: JSON.stringify(body) });
  const j = await r.json();
  if (j && j.error) throw new Error(j.error);
  return j;
}
const text = t => ({ content: [{ type: 'text', text: String(t) }] });

async function call(name, a = {}) {
  if (name === 'status') return text(await api('orb', { action: 'status' }));
  return text(await api('orb', { ...a, action: a.action || 'status' }));
}

const send = m => process.stdout.write(JSON.stringify(m) + '\n');
readline.createInterface({ input: process.stdin }).on('line', async line => {
  let m;
  try { m = JSON.parse(line); } catch { return; }
  if (m.id === undefined) return; // notifications
  try {
    if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params?.protocolVersion || '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'moddeus-orb', version: '1.0' } } });
    else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools: TOOLS } });
    else if (m.method === 'tools/call') {
      try { send({ jsonrpc: '2.0', id: m.id, result: await call(m.params.name, m.params.arguments) }); }
      catch (e) { send({ jsonrpc: '2.0', id: m.id, result: { ...text('error: ' + e.message), isError: true } }); }
    } else if (m.method === 'ping') send({ jsonrpc: '2.0', id: m.id, result: {} });
    else send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'unknown method ' + m.method } });
  } catch (e) { send({ jsonrpc: '2.0', id: m.id, error: { code: -32603, message: e.message } }); }
});
