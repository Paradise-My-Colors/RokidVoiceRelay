import { identifier, checksum, errorMessage } from './runtime.js';
export { identifier } from './runtime.js';
export const SERVICE = '8f1b9000-8c77-4a7a-9e52-018260091600';
const CONTROL = '8f1b9001-8c77-4a7a-9e52-018260091600';
const RESPONSE = '8f1b9002-8c77-4a7a-9e52-018260091600';
const AUDIO = '8f1b9003-8c77-4a7a-9e52-018260091600';
const HELLO = '8f1b9004-8c77-4a7a-9e52-018260091600';
const MAX_AUDIO = 12 * 1024 * 1024;
export function offsetPacket(kind, offset) {
  return [kind, offset & 255, (offset >>> 8) & 255, (offset >>> 16) & 255, (offset >>> 24) & 255];
}
export function frames(bytes) {
  const result = [];
  let sequence = 0;
  for (let i = 0; i < bytes.length; i += 17) {
    result.push([i === 0 ? 0 : 1, sequence & 255, sequence >>> 8, ...Array.from(bytes.slice(i, i + 17))]);
    sequence++;
  }
  result.push([2, sequence & 255, sequence >>> 8]);
  return result;
}
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
export function timeout(promise, ms, message) {
  let timer;
  return Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => { const e = new Error(message); e.timedOut = true; reject(e); }, ms); })]).finally(() => clearTimeout(timer));
}
async function nativeCall(action, ms, message) {
  try { return await timeout(Promise.resolve().then(action), ms, message); }
  catch (cause) { throw safeError(cause, message); }
}
function safeError(cause, fallback) {
  const error = new Error(errorMessage(cause, fallback)); error.linkFailure = true;
  try { error.timedOut = cause.timedOut === true; } catch (_) {}
  return error;
}
async function quiet(action, ms) { try { await timeout(Promise.resolve().then(action), ms || 2000, 'Closing Bluetooth'); } catch (_) {} }
export function connectionMessage(error) {
  const text = errorMessage(error, 'Bluetooth connection failed');
  if (/service.*not found|primary service|service is unavailable/i.test(text)) return 'Phone service not found. Start the phone bridge, restart glasses once, then reopen Voice Relay. See Connection details.';
  const status = text.match(/status\s*[:=]?\s*(\d+)/i);
  if (status) return 'Bluetooth link failed (status ' + status[1] + '). Phone: Restart Bluetooth bridge, then Pair glasses. Here: Connect once.';
  if (/quickjs|unknown error|illegalstateexception/i.test(text)) return 'Bluetooth runtime failed. Close Voice Relay, restart glasses once, then reconnect. Connection details keeps the failed step.';
  return text;
}
export class Bridge {
  constructor(bluetooth, storage, timing) {
    this.bluetooth = bluetooth; this.storage = storage; this.tail = Promise.resolve(); this.generation = 0; this.mtu = 23;
    this.timing = Object.assign({ scan: 12000, connect: 20000, discovery: 8000, discoveryDelay: 1200, discoveryAttempts: 3, approval: 150000, poll: 1000, retry: 1600, settle: 750, disconnect: 3000 }, timing || {});
  }
  remember(patch) {
    this.diagnostic = Object.assign({}, this.diagnostic || {}, patch);
    try { this.storage.setStorageSync('voice-relay-connection-details', this.diagnostic); } catch (_) {}
  }
  details() {
    if (this.diagnostic) return this.diagnostic;
    try { return this.storage.getStorageSync('voice-relay-connection-details') || {}; } catch (_) { return {}; }
  }
  connected() { try { return !!this.control && !!this.server && !!this.server.connected; } catch (_) { return false; } }
  check(generation) { if (generation !== this.generation) throw new Error('Connection cancelled'); }
  exclusive(fn) {
    const generation = this.generation;
    const task = this.tail.then(() => { this.check(generation); return fn(generation); });
    this.tail = task.catch(() => {}); return task;
  }
  connect(progress) {
    if (this.connecting) return this.connecting;
    if (this.stalled) return Promise.reject(new Error('Bluetooth timed out. Restart the glasses, keep the phone bridge running, then open Voice Relay.'));
    const task = this.open(progress || (() => {})); this.connecting = task;
    const clear = () => { if (this.connecting === task) this.connecting = null; };
    task.then(clear, clear); return task;
  }
  async findPhone(generation) {
    let saved;
    try { saved = this.storage.getStorageSync('voice-relay-phone'); } catch (_) {}
    // A fresh scan avoids reusing a stale native GATT wrapper after a failed connection.
    if (typeof this.bluetooth.scanDevices !== 'function') {
      const known = await nativeCall(() => this.bluetooth.getDevices(), this.timing.scan, 'Phone list unavailable');
      this.check(generation);
      const device = (known || []).find(d => d.id === saved);
      if (!device) throw new Error('Open this agent on the glasses with Bluetooth scanning available.');
      return device;
    }
    const scanWork = Promise.resolve().then(() => this.bluetooth.scanDevices({ filters: [{ services: [SERVICE] }], optionalServices: [SERVICE] }));
    let expired = false, stopped = false;
    const stopScan = async scan => {
      if (stopped) return; stopped = true;
      await quiet(() => { if (typeof scan.offDeviceFound === 'function') scan.offDeviceFound(); });
      await quiet(() => scan.stop());
    };
    scanWork.then(scan => { if (expired || generation !== this.generation) return stopScan(scan); }, () => {}).catch(() => {});
    let scan;
    let lease;
    try {
      scan = await timeout(scanWork, this.timing.scan, 'Bluetooth scan could not start'); this.check(generation);
      lease = { stop: () => stopScan(scan) }; this.scan = lease;
      const device = await timeout(new Promise((resolve, reject) => {
        try { scan.onDeviceFound(event => {
          try { if (event.device && (!saved || event.device.id === saved)) resolve(event.device); }
          catch (_) { reject(new Error('Bluetooth discovery failed. Restart the bridge and try again.')); }
        }); } catch (e) { reject(new Error(errorMessage(e, 'Bluetooth discovery unavailable'))); }
      }), this.timing.scan, 'Phone not found. Restart the phone bridge and tap Pair glasses. If the phone changed, choose a different phone here.');
      this.check(generation); return device;
    } finally { expired = true; if (scan) await stopScan(scan); if (this.scan === lease) this.scan = null; }
  }
  async discover(server, generation, progress) {
    let lastError = new Error('Voice Relay service is unavailable');
    for (let pass = 0; pass < this.timing.discoveryAttempts; pass++) {
      this.check(generation);
      progress('Checking phone service… ' + (pass + 1) + '/' + this.timing.discoveryAttempts);
      this.remember({ stage: 'Service discovery', discoveryPass: pass + 1 });
      // Enumerate explicitly: a connected native wrapper can still have an old/empty service list.
      // Only the exact Voice Relay UUID is accepted; never pick an unrelated first service.
      if (typeof server.getPrimaryServices === 'function') {
        try {
          const services = await nativeCall(() => server.getPrimaryServices(), this.timing.discovery, 'Service discovery timed out');
          this.check(generation);
          const list = Array.isArray(services) ? services : [];
          this.remember({ services: list.map(s => String(s.uuid).toLowerCase()).slice(0, 24) });
          const match = list.find(s => String(s.uuid).toLowerCase() === SERVICE);
          if (match) return match;
        } catch (error) {
          this.check(generation); lastError = error;
          if (error.timedOut) throw error; // Do not overlap a still-running native request.
        }
      }
      try {
        const service = await nativeCall(() => server.getPrimaryService(SERVICE), this.timing.discovery, 'Voice Relay service is unavailable');
        this.check(generation);
        if (!service || (service.uuid && String(service.uuid).toLowerCase() !== SERVICE)) throw new Error('Voice Relay service not found');
        return service;
      } catch (error) {
        this.check(generation); lastError = error;
        this.remember({ lastError: errorMessage(error) });
        if (error.timedOut) throw error;
      }
      if (pass + 1 < this.timing.discoveryAttempts) { await sleep(this.timing.discoveryDelay); this.check(generation); }
    }
    throw lastError;
  }
  async open(progress) {
    const generation = ++this.generation;
    await this.release();
    try {
      this.check(generation);
      this.diagnostic = {}; this.remember({ version: '0.9.2', stage: 'Bluetooth availability', services: [], lastError: '' });
      if (!this.bluetooth || !await nativeCall(() => this.bluetooth.getAvailability(), 5000, 'Bluetooth unavailable')) throw new Error('Bluetooth is unavailable. Open the agent on your glasses.');
      this.check(generation);
      let service;
      for (let attempt = 0; attempt < 2; attempt++) {
        progress(attempt ? 'Retrying Bluetooth once…' : 'Finding your phone…');
        this.remember({ stage: 'Scanning', attempt: attempt + 1 });
        const device = await this.findPhone(generation); this.check(generation);
        this.device = device; const gatt = device.gatt; this.gatt = gatt;
        this.remember({ stage: 'Bluetooth link', phone: device.id });
        await sleep(this.timing.settle); this.check(generation);
        progress('Connecting to your phone…');
        let expired = false;
        const connecting = Promise.resolve().then(() => gatt.connect());
        connecting.then(server => { if (expired || generation !== this.generation) return quiet(() => server.disconnect()); }, () => {}).catch(() => {});
        try {
          const server = await timeout(connecting, this.timing.connect, 'Bluetooth connection timed out');
          this.check(generation); this.server = server;
          await sleep(this.timing.settle); this.check(generation);
          service = await this.discover(server, generation, progress); this.check(generation); break;
        } catch (cause) {
          const error = safeError(cause, 'Bluetooth connection failed');
          expired = true; this.check(generation);
          this.remember({ lastError: errorMessage(error) });
          await this.release();
          if (attempt || error.timedOut) throw error;
          await sleep(this.timing.retry); this.check(generation);
        }
      }
      this.check(generation);
      this.remember({ stage: 'Pairing status' });
      let helloCharacteristic;
      try { helloCharacteristic = await nativeCall(() => service.getCharacteristic(HELLO), this.timing.discovery, 'Pairing status unavailable'); }
      catch (_) { this.check(generation); throw new Error('Install phone companion 0.9.2, restart its bridge, and update the AIUI package too.'); }
      const deadline = Date.now() + this.timing.approval;
      let approved = false;
      while (Date.now() < deadline) {
        this.check(generation);
        const state = new Uint8Array(await nativeCall(() => helloCharacteristic.readValue(), this.timing.discovery, 'Could not read phone approval'));
        this.check(generation);
        if (state.length !== 6 || state[0] !== 86 || state[1] !== 82 || state[2] !== 2) throw new Error('Update both Voice Relay packages to 0.9.2.');
        this.mtu = Math.max(23, Math.min(517, state[4] | (state[5] << 8)));
        if (state[3] & 1) { approved = true; break; }
        progress('Phone: tap Approve glasses. Confirm any Bluetooth pairing prompt. Keep this page open.');
        await sleep(this.timing.poll);
      }
      if (!approved) throw new Error('Approval timed out. Phone: Pair glasses. Here: Connect, then approve on the phone.');
      for (const pair of [['control', CONTROL], ['response', RESPONSE], ['audio', AUDIO]]) {
        const characteristic = await nativeCall(() => service.getCharacteristic(pair[1]), this.timing.discovery, 'Voice Relay service is incomplete');
        this.check(generation); this[pair[0]] = characteristic;
      }
      progress('Opening the secure connection…');
      this.remember({ stage: 'Secure handshake' });
      const hello = await this.rpc({ op: 'hello' }); this.check(generation);
      if (hello.version !== 2 || !hello.approved) throw new Error('Phone approval changed. Reconnect and approve the glasses.');
      try { this.storage.setStorageSync('voice-relay-phone', this.device.id); } catch (_) {}
      this.remember({ stage: 'Connected securely', lastError: '' });
      return hello;
    } catch (cause) {
      const error = safeError(cause, 'Bluetooth connection failed');
      if (generation === this.generation) {
        this.remember({ lastError: errorMessage(error) });
        if (error.timedOut) this.stalled = true;
        await this.close();
      }
      throw new Error(connectionMessage(error));
    }
  }
  async write(characteristic, data, generation = this.generation) {
    this.check(generation);
    if (!this.connected()) throw new Error('Phone disconnected');
    await nativeCall(() => typeof characteristic.writeValueWithResponse === 'function' ? characteristic.writeValueWithResponse(Array.from(data)) : characteristic.writeValue(Array.from(data)), 10000, 'Bluetooth transfer stopped. Reconnect before trying again.');
    this.check(generation);
  }
  async readAt(offset, media, generation = this.generation) {
    await this.write(this.control, offsetPacket(media ? 17 : 16, offset), generation);
    const value = await nativeCall(() => this.response.readValue(), 10000, 'Bluetooth read timed out');
    this.check(generation);
    if (!Array.isArray(value) && !(value instanceof Uint8Array)) throw new Error('Unexpected Bluetooth response');
    return new Uint8Array(value);
  }
  async reply(generation = this.generation) {
    let first = await this.readAt(0, false, generation);
    if (first.length < 4) throw new Error('Incomplete bridge response');
    const length = new DataView(first.buffer, first.byteOffset, first.byteLength).getUint32(0, true);
    if (length < 2 || length > 128000) throw new Error('Invalid bridge response length');
    const output = new Uint8Array(length + 4); output.set(first.subarray(0, output.length));
    let offset = Math.min(first.length, output.length);
    while (offset < output.length) {
      const block = await this.readAt(offset, false, generation);
      if (!block.length) throw new Error('Incomplete bridge response');
      output.set(block.subarray(0, output.length - offset), offset); offset += Math.min(block.length, output.length - offset);
    }
    return JSON.parse(new TextDecoder().decode(output.subarray(4)));
  }
  rpc(query) { return this.exclusive(generation => this.request(query, generation)); }
  async request(query, generation = this.generation) {
    this.check(generation);
    const id = identifier();
    const bytes = new TextEncoder().encode(JSON.stringify({ ...query, id }));
    if (bytes.length > 8192) throw new Error('Reply is too long');
    for (const frame of frames(bytes)) await this.write(this.control, frame, generation);
    const deadline = Date.now() + 90000;
    while (Date.now() < deadline) {
      const result = await this.reply(generation);
      if (result.id !== id) throw new Error('Reply does not match the current request');
      if (result.pending) { await sleep(250); continue; }
      if (!result.ok) throw new Error(result.error || 'Phone could not complete the action');
      return result.value;
    }
    throw new Error('Phone did not confirm. Check the conversation before sending again.');
  }
  upload(target, data, progress) {
    return this.exclusive(async generation => {
      if (data.byteLength < 1 || data.byteLength > MAX_AUDIO) throw new Error('Recording is empty or too large');
      const sha256 = await checksum(data);
      this.check(generation);
      const start = await this.request({ op: 'begin', target, size: data.byteLength }, generation);
      const bytes = new Uint8Array(data); const step = Math.min(480, this.mtu - 7);
      for (let offset = 0; offset < bytes.length; offset += step) {
        const head = offsetPacket(0, offset).slice(1);
        await this.write(this.audio, head.concat(Array.from(bytes.subarray(offset, offset + step))), generation);
        if (progress) progress(Math.min(100, Math.round((offset + step) * 100 / bytes.length)));
      }
      await this.request({ op: 'seal', upload: start.upload, sha256 }, generation);
      return start.upload;
    });
  }
  downloadAudio(target, message, progress) {
    return this.exclusive(async generation => {
      const meta = await this.request({ op: 'download', target, message }, generation);
      if (!Number.isInteger(meta.size) || meta.size < 1 || meta.size > MAX_AUDIO) throw new Error('Audio is empty or too large');
      const output = new Uint8Array(meta.size);
      let offset = 0;
      while (offset < meta.size) {
        const block = await this.readAt(offset, true, generation);
        if (!block.length || block.length > meta.size - offset) throw new Error('Incomplete audio transfer');
        output.set(block, offset); offset += block.length;
        if (progress) progress(Math.round(offset * 100 / meta.size));
      }
      if (await checksum(output.buffer) !== meta.sha256) throw new Error('Audio failed its integrity check. Tap Listen to retry.');
      this.check(generation);
      return { data: output.buffer, mime: meta.mime };
    });
  }
  release() {
    const scan = this.scan, server = this.server, gatt = this.gatt;
    this.scan = null;
    this.server = null; this.gatt = null; this.device = null; this.control = null; this.response = null; this.audio = null;
    this.tail = Promise.resolve();
    const previous = this.releasing || Promise.resolve();
    this.releasing = previous.then(async () => {
      if (scan) await quiet(() => scan.stop(), this.timing.disconnect);
      // connect() may return a different JS wrapper for the SAME native connection.
      // Disconnect once, and finish cleanup before the next scan/connect starts.
      const connection = server || gatt;
      if (connection) await quiet(() => connection.disconnect(), this.timing.disconnect);
    });
    return this.releasing;
  }
  async close() { this.generation++; await this.release(); }
  forget() { try { this.storage.removeStorageSync('voice-relay-phone'); } catch (_) {} return this.close(); }
}
