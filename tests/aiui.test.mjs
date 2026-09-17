import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { webcrypto, createHash } from 'node:crypto';
import { Bridge, SERVICE, frames, offsetPacket } from '../aiui/voice-relay/lib/bridge.js';
import { visibleRows, keyAction, receiptTitle } from '../aiui/voice-relay/lib/ui.js';
import { checksum, identifier, errorMessage } from '../aiui/voice-relay/lib/runtime.js';
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
const ctx = vm.createContext({ console, setTimeout, clearTimeout, setInterval, clearInterval, Date, Promise, Blob, TextEncoder, TextDecoder, Uint8Array, ArrayBuffer, DataView, MediaRecorder: FakeRecorder,
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
    if (q.op === 'hello') value = { version: 2, approved: true, mtu: 23 };
    else if (q.op === 'download') value = { size: audio.length, sha256: await hash(audio), mime: 'audio/ogg' };
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

await test('Cancelled recorder callback cannot overwrite a new recording', async () => {
  const p = page(); p.current = { id: 'one', sender: 'Alice', app: 'Telegram', text: 'Hi' };
  await p.record(); const old = p.recorder;
  p.back(); p.current = { id: 'two', sender: 'Bob', app: 'Telegram', text: 'Hi' };
  await p.record();
  // The cancelled recorder is allowed to deliver late events, but not to change the new session.
  old.ondataavailable({ data: new Blob(['late']) }); await old.onstop();
  assert.equal(p.screen, 'recording'); assert.equal(p.draft, null);
  p.finishCapture(); await new Promise(r => setTimeout(r, 10));
  assert.equal(p.draft.target.id, 'two'); p.cleanup();
});
await test('Text draft has full paginated review and explicit send confirmation', () => {
  const p = page(); p.draft = { target: { id: 'one', sender: 'Alice', app: 'Telegram' }, text: 'Long message '.repeat(45) };
  p.draftTextPage = 0; p.readDraft(); assert.equal(p.screen, 'readDraft'); assert.equal(p.menu[1].id, 'mode:text');
  p.selection = 0; p.activate(); assert.equal(p.draftTextPage, 1);
  p.selection = 1; p.activate(); assert.equal(p.screen, 'confirm'); assert.equal(p.sendMode, 'text'); p.cleanup();
});

await test('Late microphone permission cannot replace the current capture', async () => {
  const original = ctx.navigator.mediaDevices.getUserMedia;
  const requests = [];
  ctx.navigator.mediaDevices.getUserMedia = () => new Promise((resolve, reject) => requests.push({ resolve, reject }));
  const p = page(); p.current = { id: 'one', sender: 'Alice', app: 'Telegram', text: 'Hi' };
  try {
    const oldCapture = p.record(); p.back();
    p.current = { id: 'two', sender: 'Bob', app: 'Telegram', text: 'Hi' };
    const newCapture = p.record();
    const currentStream = { getTracks: () => [{ stop() {} }] };
    requests[1].resolve(currentStream); await newCapture;
    const currentRecorder = p.recorder; let oldStopped = false;
    requests[0].resolve({ getTracks: () => [{ stop() { oldStopped = true; } }] }); await oldCapture;
    assert.equal(p.stream, currentStream); assert.equal(p.recorder, currentRecorder); assert.ok(oldStopped);
    p.finishCapture(); await new Promise(r => setTimeout(r, 10)); assert.equal(p.draft.target.id, 'two');
  } finally { ctx.navigator.mediaDevices.getUserMedia = original; p.cleanup(); }
});

await test('Page and send IDs work with no global crypto in the glasses VM', async () => {
  assert.equal(vm.runInContext('typeof crypto', ctx), 'undefined');
  const p = page(); p.current = { id: 'one', sender: 'Alice', app: 'Telegram' };
  p.draft = { target: p.current, text: 'Review me' }; p.sendMode = 'text';
  p.bridge.rpc = async q => { assert.match(q.operation, /^[a-f0-9]{32,}$/); return { state: 'sent', detail: 'Sent' }; };
  await p.send(); assert.equal(p.screen, 'receipt'); p.cleanup();
});
await test('Portable audio hashing matches standard SHA-256 without Web Crypto', async () => {
  const portableChecksum = modules.get(path.join(root, 'lib/runtime.js')).namespace.checksum;
  for (const n of [0, 1, 3, 55, 56, 63, 64, 65, 127, 128, 4096, 131073]) {
    const input = new Uint8Array(n).map((_, i) => i % 251);
    assert.equal(await portableChecksum(input), createHash('sha256').update(input).digest('hex'));
  }
  assert.equal(await checksum(new TextEncoder().encode('abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  const ids = new Set(Array.from({ length: 1000 }, () => identifier(wx))); assert.equal(ids.size, 1000);
});

const fastTiming = { scan: 100, connect: 25, discovery: 100, discoveryDelay: 1, approval: 120, poll: 2, retry: 1, settle: 1, disconnect: 10 };
async function until(condition) {
  for (let i = 0; i < 100; i++) { if (condition()) return; await new Promise(resolve => setTimeout(resolve, 2)); }
  throw new Error('Fixture did not reach expected state');
}
function connectionFixture(options = {}) {
  const wire = fakeBridge(22);
  const counts = { scans: 0, connects: 0, disconnects: 0, reads: 0, secureWrites: 0, stops: 0, discoveries: 0, lists: 0 };
  const servers = []; const pending = [];
  let allowed = false;
  const bluetooth = {
    async getAvailability() { return true; },
    async getDevices() { throw new Error('A stale device list must not be reused'); },
    async scanDevices() {
      counts.scans++;
      const server = {
        connected: false,
        async connect() {
          const attempt = ++counts.connects;
          if (attempt <= (options.failures || 0)) throw options.failure || new Error('java.lang.IllegalStateException: Bluetooth connection failed with status 8');
          if (options.lateFirst && attempt === 1) return new Promise(resolve => pending.push(() => { this.connected = true; resolve(this); }));
          this.connected = true;
          if (options.distinctWrapper) return {
            get connected() { return server.connected; },
            disconnect: () => server.disconnect(),
            getPrimaryService: () => server.getPrimaryService(),
            getPrimaryServices: () => server.getPrimaryServices()
          };
          return this;
        },
        async disconnect() { counts.disconnects++; this.connected = false; },
        async getPrimaryServices() {
          counts.lists++;
          if (options.delayList) await new Promise(resolve => pending.push(resolve));
          if (options.listFailure) throw options.listFailure;
          if (options.listAfter && counts.lists >= options.listAfter) return [await this.getPrimaryService(true)];
          return [{ uuid: '0000180f-0000-1000-8000-00805f9b34fb', getCharacteristic() { throw new Error('Wrong service used'); } }];
        },
        async getPrimaryService(fromList) {
          counts.discoveries++;
          if (options.delayDiscovery) await new Promise(resolve => pending.push(resolve));
          if (options.discoveryFailure) throw options.discoveryFailure;
          if (fromList !== true && (options.serviceMissing || counts.discoveries <= (options.missingLookups || 0) || (options.missingFirstServer && servers.indexOf(server) === 0)))
            throw new Error('Failed to get primary service from the remote GATT server: service ' + SERVICE + ' not found on device A8:79:8D:40:A4:B7');
          return { uuid: SERVICE.toUpperCase(), async getCharacteristic(uuid) {
            if (uuid.includes('9004')) return { async readValue() {
              counts.reads++; allowed = counts.reads > (options.approvalReads || 0);
              return [86, 82, 2, allowed ? 3 : 0, 23, 0];
            } };
            if (uuid.includes('9001')) return { async writeValueWithResponse(data) {
              assert.ok(allowed, 'Sensitive command attempted before approval'); counts.secureWrites++;
              return wire.b.control.writeValueWithResponse(data);
            } };
            if (uuid.includes('9002')) return wire.b.response;
            return wire.b.audio;
          } };
        }
      };
      servers.push(server);
      return {
        onDeviceFound(callback) { queueMicrotask(() => callback({ device: { id: 'phone-one', gatt: server } })); },
        stop() { counts.stops++; if (options.rejectStop) return Promise.reject(new Error('Native scan already stopped')); }
      };
    }
  };
  const values = new Map();
  const memory = { getStorageSync: key => values.get(key), setStorageSync: (key, value) => values.set(key, value), removeStorageSync: key => values.delete(key) };
  return { b: new Bridge(bluetooth, memory, fastTiming), counts, servers, pending };
}
await test('Status 8 closes the failed GATT handle and retries with a fresh scan', async () => {
  const f = connectionFixture({ failures: 1, rejectStop: true });
  await f.b.connect(); assert.equal(f.counts.connects, 2); assert.equal(f.counts.scans, 2);
  assert.equal(f.servers[0].connected, false); assert.ok(f.b.connected()); await f.b.close();
});
await test('A failed connection can be retried without a stale server or rejected queue', async () => {
  const f = connectionFixture({ failures: 2 });
  await assert.rejects(f.b.connect(), /status 8/); assert.equal(f.b.server, null);
  await f.b.connect(); assert.equal(f.counts.connects, 3); assert.ok(f.b.connected()); await f.b.close();
});
await test('Duplicate Connect presses share one connection and wait for phone approval', async () => {
  const f = connectionFixture({ approvalReads: 3 }); const progress = [];
  const first = f.b.connect(value => progress.push(value)); const second = f.b.connect();
  assert.equal(first, second); await first;
  assert.equal(f.counts.connects, 1); assert.equal(f.counts.reads, 4);
  assert.ok(progress.some(value => value.includes('Approve glasses'))); assert.ok(f.counts.secureWrites > 0); await f.b.close();
});
await test('A native connect timeout never overlaps another connect, and closes its late result', async () => {
  const f = connectionFixture({ lateFirst: true }); await assert.rejects(f.b.connect(), /timed out/);
  await assert.rejects(f.b.connect(), /Restart the glasses/); assert.equal(f.counts.connects, 1);
  f.pending[0](); await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(f.servers[0].connected, false); assert.equal(f.b.server, null); assert.ok(!f.b.connected()); await f.b.close();
});
await test('Cancellation during discovery cannot restore a closed connection', async () => {
  const f = connectionFixture({ delayDiscovery: true }); const work = f.b.connect();
  await until(() => f.pending.length === 1); const cancelled = assert.rejects(work, /cancelled/);
  await f.b.close(); f.pending[0](); await cancelled;
  assert.equal(f.b.server, null); assert.equal(f.b.control, null);
});
await test('Back cancels approval and late callbacks do not replace the offline screen', async () => {
  const f = connectionFixture({ approvalReads: 999 }); const p = page(); p.bridge = f.b;
  const connecting = p.connect(); await until(() => f.counts.reads > 0); p.back(); await connecting;
  assert.equal(p.screen, 'offline'); assert.equal(p.data.busy, false); assert.equal(f.counts.secureWrites, 0); p.cleanup();
});
await test('Native exceptions with unreadable message fields become readable errors', async () => {
  const hostile = new Proxy({}, { get() { throw new Error('Native property failed'); } });
  assert.equal(errorMessage(hostile, 'Bluetooth failed'), 'Bluetooth failed');
  const f = connectionFixture({ failures: 2, failure: hostile });
  await assert.rejects(f.b.connect(), /Bluetooth connection failed/); assert.equal(f.b.server, null);
});
await test('Old in-flight commands cannot continue on a newly opened connection', async () => {
  const { b } = fakeBridge(22); const original = b.control; let release, writes = 0;
  b.control = { async writeValueWithResponse(data) { writes++; await new Promise(resolve => { release = resolve; }); return original.writeValueWithResponse(data); } };
  const old = b.rpc({ op: 'inbox', text: 'long message '.repeat(20) }); await until(() => !!release);
  const rejected = assert.rejects(old, /cancelled/); await b.close();
  const fresh = fakeBridge(22).b; b.server = fresh.server; b.control = fresh.control; b.response = fresh.response; b.audio = fresh.audio;
  assert.equal((await b.rpc({ op: 'hello' })).approved, true);
  release(); await rejected; assert.equal(writes, 1); await b.close();
});

await test('Delayed service discovery recovers before reconnecting', async () => {
  const f = connectionFixture({ missingLookups: 2 }); await f.b.connect();
  assert.equal(f.counts.discoveries, 3); assert.equal(f.counts.connects, 1); assert.equal(f.b.details().stage, 'Connected securely'); await f.b.close();
});
await test('Enumeration finds only the exact Voice Relay UUID when direct lookup is stale', async () => {
  const f = connectionFixture({ serviceMissing: true, listAfter: 2 }); await f.b.connect();
  assert.equal(f.counts.lists, 2); assert.equal(f.counts.connects, 1); assert.ok(f.b.connected()); await f.b.close();
});
await test('Missing service reconnects once with a fresh scan and discovers again', async () => {
  const f = connectionFixture({ missingFirstServer: true }); await f.b.connect();
  assert.equal(f.counts.scans, 2); assert.equal(f.counts.discoveries, 4); assert.equal(f.servers[0].connected, false); await f.b.close();
});
await test('Persistent missing service is bounded, sends nothing and saves useful diagnostics', async () => {
  const f = connectionFixture({ serviceMissing: true }); await assert.rejects(f.b.connect(), /Phone service not found/);
  assert.equal(f.counts.connects, 2); assert.equal(f.counts.discoveries, 6); assert.equal(f.counts.secureWrites, 0);
  assert.equal(f.b.details().stage, 'Service discovery'); assert.match(f.b.details().lastError, /A8:79:8D:40:A4:B7/);
  const restored = new Bridge(null, f.b.storage); assert.equal(restored.details().version, '0.9.2');
  assert.deepEqual(restored.details().services, ['0000180f-0000-1000-8000-00805f9b34fb']);
});
await test('Native enumeration errors fall back to direct discovery without escaping', async () => {
  const f = connectionFixture({ listFailure: new Error('QuickJS library created an unknown error') });
  await f.b.connect(); assert.ok(f.b.connected()); assert.equal(f.counts.connects, 1); await f.b.close();
});
await test('Two JS wrappers for the same native connection disconnect exactly once', async () => {
  const f = connectionFixture({ distinctWrapper: true }); await f.b.connect();
  assert.notEqual(f.b.server, f.b.gatt); await f.b.close(); assert.equal(f.counts.disconnects, 1); assert.equal(f.counts.stops, 1);
});
await test('Discovery timeout aborts without starting overlapping native lookups', async () => {
  const f = connectionFixture({ delayList: true }); f.b.timing.discovery = 10;
  await assert.rejects(f.b.connect(), /timed out/); assert.equal(f.counts.connects, 1); assert.equal(f.counts.discoveries, 0);
  await assert.rejects(f.b.connect(), /Restart the glasses/); f.pending[0](); await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(f.b.server, null); assert.equal(f.counts.secureWrites, 0);
});
await test('Connection details survive cleanup and show the entire error in short pages', () => {
  const p = page(); const raw = 'Native error '.repeat(30);
  p.bridge.remember({ stage: 'Service discovery', lastError: raw, services: [SERVICE], phone: 'phone-one' });
  p.connectionDetails(false);
  assert.equal(p.screen, 'diagnostics'); assert.ok(p.connectionDetailPages.every(s => s.length <= 130));
  assert.ok(p.connectionDetailPages.join('').includes(raw));
  p.connectionDetails(true); assert.match(p.data.detail, /Service discovery/); p.cleanup();
});

process.stdout.write(`${passed} checks passed. Device rendering, Bluetooth pairing and messaging services require hardware testing.\n`);
