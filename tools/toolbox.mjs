#!/usr/bin/env node
// UserPromptSubmit hook for the in-game chats (bridge.mjs hookSettings): prints the toolbox reminder + the player's own
// lessons (tools/lessons.md, written by `mc lesson`; kept across updates), which Claude Code adds as context.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const dir = path.dirname(fileURLToPath(import.meta.url));
process.stdout.write(fs.readFileSync(path.join(dir, 'toolbox.md'), 'utf8'));
try { process.stdout.write('\n' + fs.readFileSync(path.join(dir, 'lessons.md'), 'utf8')); } catch {}
