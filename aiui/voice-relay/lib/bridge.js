export const SERVICE = '8f1b9000-8c77-4a7a-9e52-018260091600';
const CONTROL = '8f1b9001-8c77-4a7a-9e52-018260091600';
const RESPONSE = '8f1b9002-8c77-4a7a-9e52-018260091600';
const AUDIO = '8f1b9003-8c77-4a7a-9e52-018260091600';
const MAX_AUDIO = 12 * 1024 * 1024;
export function identifier() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}
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
  return Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(message)), ms); })]).finally(() => clearTimeout(timer));
}
async function checksum(data) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', data)), b => b.toString(16).padStart(2, '0')).join('');
}
export class Bridge {
  constructor(bluetooth, storage) {
    this.bluetooth = bluetooth; this.storage = storage; this.tail = Promise.resolve(); this.generation = 0; this.mtu = 23;
  }
  connected() { return !!this.control && !!this.server && this.server.connected; }
  exclusive(fn) {
    const generation = this.generation;
    const task = this.tail.then(() => { if (generation !== this.generation) throw new Error('Connection closed'); this.operationGeneration = generation; return fn(); });
    this.tail = task.catch(() => {}); return task;
  }
  async connect() {
    await this.close();
    const generation = this.generation;
    if (!this.bluetooth || !await this.bluetooth.getAvailability()) throw new Error('Bluetooth is unavailable in this preview or on this device. Open the agent on your glasses.');
    let saved;
    try { saved = this.storage.getStorageSync('voice-relay-phone'); } catch (_) {}
    let device;
    const known = await this.bluetooth.getDevices();
    device = (known || []).find(d => d.id === saved);
    if (!device) {
      this.scan = await this.bluetooth.scanDevices({ filters: [{ services: [SERVICE] }], optionalServices: [SERVICE] });
      try {
        device = await timeout(new Promise(resolve => this.scan.onDeviceFound(event => {
          if (event.device && (!saved || event.device.id === saved)) resolve(event.device);
        })), 12000, 'Phone not found. Start the Voice Relay bridge and tap Pair glasses on your phone.');
      } finally { if (this.scan) this.scan.stop(); this.scan = null; }
    }
    if (generation !== this.generation) throw new Error('Connection cancelled');
    this.device = device;
    const connecting = device.gatt.connect();
    connecting.then(server => { if (generation !== this.generation) server.disconnect(); }, () => {});
    this.server = await timeout(connecting, 18000, 'Bluetooth pairing timed out. Confirm the pairing prompt on your phone.');
    const service = await timeout(this.server.getPrimaryService(SERVICE), 8000, 'Voice Relay service is unavailable');
    this.control = await service.getCharacteristic(CONTROL);
    this.response = await service.getCharacteristic(RESPONSE);
    this.audio = await service.getCharacteristic(AUDIO);
    const hello = await this.rpc({ op: 'hello' });
    if (hello.version !== 1) throw new Error('Update the phone app and AIUI package together');
    this.mtu = Math.max(23, Math.min(517, Number(hello.mtu) || 23));
    if (!hello.approved) throw new Error('On your phone: confirm Bluetooth pairing, tap Approve glasses, then tap Connect here.');
    try { this.storage.setStorageSync('voice-relay-phone', device.id); } catch (_) {}
    return hello;
  }
  async write(characteristic, data) {
    if (this.operationGeneration !== this.generation) throw new Error('Connection changed; transfer cancelled');
    if (!this.connected()) throw new Error('Phone disconnected');
    const work = typeof characteristic.writeValueWithResponse === 'function' ? characteristic.writeValueWithResponse(Array.from(data)) : characteristic.writeValue(Array.from(data));
    return timeout(work, 10000, 'Bluetooth transfer stopped. Reconnect before trying again.');
  }
  async readAt(offset, media) {
    await this.write(this.control, offsetPacket(media ? 17 : 16, offset));
    const value = await timeout(this.response.readValue(), 10000, 'Bluetooth read timed out');
    if (!Array.isArray(value) && !(value instanceof Uint8Array)) throw new Error('Unexpected Bluetooth response');
    return new Uint8Array(value);
  }
  async reply() {
    let first = await this.readAt(0, false);
    if (first.length < 4) throw new Error('Incomplete bridge response');
    const length = new DataView(first.buffer, first.byteOffset, first.byteLength).getUint32(0, true);
    if (length < 2 || length > 128000) throw new Error('Invalid bridge response length');
    const output = new Uint8Array(length + 4); output.set(first.subarray(0, output.length));
    let offset = Math.min(first.length, output.length);
    while (offset < output.length) {
      const block = await this.readAt(offset, false);
      if (!block.length) throw new Error('Incomplete bridge response');
      output.set(block.subarray(0, output.length - offset), offset); offset += Math.min(block.length, output.length - offset);
    }
    return JSON.parse(new TextDecoder().decode(output.subarray(4)));
  }
  rpc(query) { return this.exclusive(() => this.request(query)); }
  async request(query) {
    const id = identifier();
    const bytes = new TextEncoder().encode(JSON.stringify({ ...query, id }));
    if (bytes.length > 8192) throw new Error('Reply is too long');
    for (const frame of frames(bytes)) await this.write(this.control, frame);
    const deadline = Date.now() + 90000;
    while (Date.now() < deadline) {
      const result = await this.reply();
      if (result.id !== id) throw new Error('Reply does not match the current request');
      if (result.pending) { await sleep(250); continue; }
      if (!result.ok) throw new Error(result.error || 'Phone could not complete the action');
      return result.value;
    }
    throw new Error('Phone did not confirm. Check the conversation before sending again.');
  }
  upload(target, data, progress) {
    return this.exclusive(async () => {
      if (data.byteLength < 1 || data.byteLength > MAX_AUDIO) throw new Error('Recording is empty or too large');
      const sha256 = await checksum(data);
      const start = await this.request({ op: 'begin', target, size: data.byteLength });
      const bytes = new Uint8Array(data); const step = Math.min(480, this.mtu - 7);
      for (let offset = 0; offset < bytes.length; offset += step) {
        const head = offsetPacket(0, offset).slice(1);
        await this.write(this.audio, head.concat(Array.from(bytes.subarray(offset, offset + step))));
        if (progress) progress(Math.min(100, Math.round((offset + step) * 100 / bytes.length)));
      }
      await this.request({ op: 'seal', upload: start.upload, sha256 });
      return start.upload;
    });
  }
  downloadAudio(target, message, progress) {
    return this.exclusive(async () => {
      const meta = await this.request({ op: 'download', target, message });
      if (!Number.isInteger(meta.size) || meta.size < 1 || meta.size > MAX_AUDIO) throw new Error('Audio is empty or too large');
      const output = new Uint8Array(meta.size);
      let offset = 0;
      while (offset < meta.size) {
        const block = await this.readAt(offset, true);
        if (!block.length || block.length > meta.size - offset) throw new Error('Incomplete audio transfer');
        output.set(block, offset); offset += block.length;
        if (progress) progress(Math.round(offset * 100 / meta.size));
      }
      if (await checksum(output.buffer) !== meta.sha256) throw new Error('Audio failed its integrity check. Tap Listen to retry.');
      return { data: output.buffer, mime: meta.mime };
    });
  }
  async close() {
    this.generation++;
    if (this.scan) { try { this.scan.stop(); } catch (_) {} }
    this.scan = null;
    const server = this.server;
    this.server = null; this.control = null; this.response = null; this.audio = null;
    if (server) { try { await timeout(server.disconnect(), 2000, 'Disconnected'); } catch (_) {} }
  }
  forget() { try { this.storage.removeStorageSync('voice-relay-phone'); } catch (_) {} return this.close(); }
}
