import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import { Bridge, frames, offsetPacket } from '../aiui/voice-relay/lib/bridge.js';
import { visibleRows, keyAction, receiptTitle } from '../aiui/voice-relay/lib/ui.js';
let passed = 0;
async function test(name, body) { await body(); passed++; process.stdout.write('PASS ' + name + '\n'); }
const root = path.resolve('aiui/voice-relay');
const ink = fs.readFileSync(path.join(root, 'pages/index/index.ink'), 'utf8');
const script = ink.match(/<script setup>([\s\S]*?)<\/script>/)[1];
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'app.json')));
const storage = new Map();
let stopped = 0;
class FakeRecorder {
  static isTypeSupported(mime) { return mime.includes('ogg'); }
  constructor() { this.state = 'inactive'; }
  start() { this.state = 'recording'; }
  stop() { this.state = 'inactive'; this.ondataavailable({ data: new Blob(['OggS test audio']) }); queueMicrotask(() => this.onstop()); }
}
const wx = { getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k), exitMiniProgram() {} };
const ctx = vm.createContext({ console, setTimeout, clearTimeout, setInterval, clearInterval, Date, Promise, Blob, TextEncoder, TextDecoder, Uint8Array, ArrayBuffer, DataView, crypto: webcrypto, MediaRecorder: FakeRecorder,
  navigator: { bluetooth: null, mediaDevices: { async getUserMedia() { return { getTracks: () => [{ stop() { stopped++; } }] }; } } } });
const modules = new Map();
async function moduleFor(spec, referencing) {
  if (spec === 'wx') { const m = new vm.SyntheticModule(['default'], function () { this.setExport('default', wx); }, { context: ctx }); return m; }
  if (spec === 'audio') { return new vm.SyntheticModule(['AudioPlayer'], function () { this.setExport('AudioPlayer', class {}); }, { context: ctx }); }
  const filename = path.resolve(path.dirname(referencing.identifier), spec);
  if (!modules.has(filename)) modules.set(filename, new vm.SourceTextModule(fs.readFileSync(filename, 'utf8'), { context: ctx, identifier: filename }));
  return modules.get(filename);
}
const pageModule = new vm.SourceTextModule(script, { context: ctx, identifier: path.join(root, 'pages/index/index.js') });
await pageModule.link(moduleFor); await pageModule.evaluate();
function page() {
  const definition = pageModule.namespace.default;
  const p = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch); } };
  p.onLoad(); return p;
}
await test('AIUI manifest, blocks, handlers and imports resolve', () => {
  JSON.parse(ink.match(/<script def>([\s\S]*?)<\/script>/)[1]);
  for (const route of manifest.pages) assert.ok(fs.existsSync(path.join(root, route + '.ink')));
  assert.ok(manifest.permissions.includes('RECORD_AUDIO'));
  assert.equal((ink.match(/<page>/g) || []).length, 1);
  assert.ok(!/\b(?:Page|App|Widget)\s*\(/.test(script));
  for (const match of ink.matchAll(/bind\w+="(\w+)"/g)) assert.equal(typeof pageModule.namespace.default[match[1]], 'function');
});
await test('Startup renders without Bluetooth or a microphone request', () => {
  const p = page(); assert.equal(p.screen, 'offline'); assert.equal(p.menu[0].id, 'connect'); p.cleanup();
});
await test('Swipe navigation and key-up duplicate suppression', () => {
  const p = page(); const e = code => ({ code, preventDefault() {}, stopPropagation() {} });
  p.onKeyDown(e('ArrowDown')); p.onKeyUp(e('ArrowDown')); assert.equal(p.selection, 1);
  p.onKeyDown(e('Enter')); p.onKeyUp(e('Enter')); assert.equal(p.screen, 'help'); p.cleanup();
  assert.equal(keyAction('Backspace'), 'back'); assert.equal(keyAction('GlobalHook'), 'select');
});
await test('Visible selection stays in a three-row window', () => {
  const rows = Array.from({ length: 30 }, (_, i) => ({ id: String(i) }));
  for (let index = 0; index < rows.length; index++) {
    const visible = visibleRows(rows, index); assert.ok(visible.length <= 3);
    assert.equal(visible.filter(r => r.selected).length, 1); assert.equal(visible.find(r => r.selected).index, index);
  }
});
await test('All settings save the requested value on the phone', async () => {
  const p = page(); let sent;
  p.bridge.rpc = async q => { sent = q; return { telegram_enabled: false, whatsapp_enabled: true, respect_dnd: true, respect_phone_silent: true, hide_when_phone_unlocked: false, nexus_notices: true }; };
  p.options = { telegram_enabled: true }; p.renderSettings(); p.selection = 0; await p.activate();
  assert.equal(sent.op, 'setting'); assert.equal(sent.key, 'telegram_enabled'); assert.equal(sent.enabled, false); assert.match(p.menu[0].label, /OFF/); p.cleanup();
});
await test('Recording keeps the selected recipient and requires confirmation', async () => {
  const p = page(); p.current = { id: 'one', sender: 'Alice', app: 'Telegram' };
  await p.record(); assert.equal(p.screen, 'recording');
  p.current = { id: 'two', sender: 'Bob', app: 'WhatsApp' }; p.finishCapture();
  await new Promise(r => setTimeout(r, 10));
  assert.equal(p.screen, 'preview'); assert.equal(p.draft.target.id, 'one');
  p.selection = 1; p.activate(); assert.equal(p.screen, 'confirm'); assert.match(p.data.title, /Alice/); assert.ok(stopped > 0); p.cleanup();
});
await test('Back discards recording without creating a send', async () => {
  const p = page(); p.current = { id: 'one', sender: 'Alice', app: 'Telegram', text: 'Hi' };
  await p.record(); p.back(); await new Promise(r => setTimeout(r, 10));
  assert.equal(p.screen, 'message'); assert.equal(p.draft, null); p.cleanup();
});
await test('Lost send does not retry delivery and keeps the operation ID', async () => {
  const p = page(); p.current = { id: 'one', sender: 'Alice', app: 'Telegram' };
  p.draft = { target: p.current, text: 'Hello' }; p.sendMode = 'text'; let sends = 0;
  p.bridge.rpc = async q => { if (q.op === 'send') { sends++; throw new Error('Link lost'); } return { state: 'pending', detail: 'Check phone' }; };
  await assert.rejects(() => p.send(), /Link lost/); const id = p.operation;
  assert.equal(storage.get('voice-relay-pending-send'), id);
  await p.send(); assert.equal(sends, 1); assert.equal(p.operation, id); assert.equal(p.screen, 'receipt'); p.cleanup();
});
await test('WhatsApp handoff is never reported as sent', () => {
  assert.notEqual(receiptTitle('phone'), 'Sent'); assert.notEqual(receiptTitle('handed'), 'Sent'); assert.equal(receiptTitle('sent'), 'Sent');
});
function fakeBridge(chunkSize) {
  const b = new Bridge(null, wx); b.server = { connected: true, async disconnect() {} };
  let command = [], sequence = 0, offset = 0, media = false, result = [], uploaded = [], lastOperation;
  const audio = new TextEncoder().encode('OggS العربية voice payload ✓');
  const hash = async bytes => Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex');
  async function handle(q) {
    lastOperation = q.op;
    let value;
    if (q.op === 'download') value = { size: audio.length, sha256: await hash(audio), mime: 'audio/ogg' };
    else if (q.op === 'begin') { uploaded = []; value = { upload: 'test-upload' }; }
    else if (q.op === 'seal') { assert.equal(q.sha256, await hash(new Uint8Array(uploaded))); value = {}; }
    else value = { text: 'مرحبا '.repeat(30), request: q.op };
    const body = new TextEncoder().encode(JSON.stringify({ id: q.id, ok: true, value }));
    const size = new Uint8Array(4); new DataView(size.buffer).setUint32(0, body.length, true); result = [...size, ...body];
  }
  b.control = { async writeValueWithResponse(data) {
    const kind = data[0];
    if (kind === 16 || kind === 17) { offset = new DataView(new Uint8Array(data.slice(1)).buffer).getUint32(0, true); media = kind === 17; return; }
    if (kind === 0) { command = []; sequence = 0; }
    assert.equal(data[1] + 256 * data[2], sequence++); command.push(...data.slice(3));
    if (kind === 2) { media = false; await handle(JSON.parse(new TextDecoder().decode(new Uint8Array(command)))); }
  } };
  b.response = { async readValue() { return Array.from((media ? audio : result).slice(offset, offset + chunkSize)); } };
  b.audio = { async writeValueWithResponse(data) {
    const i = new DataView(new Uint8Array(data.slice(0, 4)).buffer).getUint32(0, true);
    assert.equal(i, uploaded.length); uploaded.push(...data.slice(4));
  } };
  return { b, audio, uploaded: () => uploaded, lastOperation: () => lastOperation };
}
await test('BLE frame golden vector and little-endian offsets', () => {
  assert.deepEqual(frames(new TextEncoder().encode('{}')), [[0, 0, 0, 123, 125], [2, 1, 0]]);
  assert.deepEqual(offsetPacket(17, 0x12345678), [17, 0x78, 0x56, 0x34, 0x12]);
});
await test('Fragmented Bluetooth replies preserve Arabic at MTU 23', async () => {
  const { b } = fakeBridge(22); const answer = await b.rpc({ op: 'inbox' }); assert.equal(answer.text, 'مرحبا '.repeat(30)); await b.close();
});
await test('Audio upload/download integrity at both small and large MTUs', async () => {
  for (const mtu of [23, 517]) {
    const { b, audio, uploaded } = fakeBridge(mtu === 23 ? 22 : 512); b.mtu = mtu;
    const bytes = new Uint8Array(1430).map((_, i) => i % 251);
    const upload = await b.upload('recipient-one', bytes.buffer); assert.equal(upload, 'test-upload'); assert.deepEqual(uploaded(), [...bytes]);
    const result = await b.downloadAudio('recipient-one', 'exact-message-id'); assert.deepEqual(new Uint8Array(result.data), audio); await b.close();
  }
});
await test('Bad audio hash is rejected, not played', async () => {
  const { b } = fakeBridge(512); const read = b.readAt.bind(b);
  b.readAt = async (offset, media) => { const data = await read(offset, media); if (media && offset === 0) data[0] ^= 1; return data; };
  await assert.rejects(() => b.downloadAudio('one', 'voice'), /integrity/); await b.close();
});
process.stdout.write(`${passed} checks passed. Device rendering, Bluetooth pairing and messaging services require hardware testing.\n`);
