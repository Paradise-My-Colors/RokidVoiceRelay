import { sessionKey, seal, open, base64, unbase64 } from './secure.js';
import { checksum, identifier, errorMessage } from '../voice-relay/lib/runtime.js';
export { identifier, errorMessage } from '../voice-relay/lib/runtime.js';
const MAX_AUDIO = 12 * 1024 * 1024;
const CHUNK = 24576;
export function timeout(work, ms, text) {
  let timer; return Promise.race([work, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(text)), ms); })]).finally(() => clearTimeout(timer));
}
export function allowedEndpoint(value) {
  if (typeof value !== 'string') return false;
  const m = value.match(/^http:\/\/(\d+)\.(\d+)\.(\d+)\.(\d+):8766$/);
  if (!m) return false;
  const n = m.slice(1).map(Number);
  return n.every(x => x >= 0 && x < 256) && (n[0] === 10 || (n[0] === 172 && n[1] >= 16 && n[1] <= 31) || (n[0] === 192 && n[1] === 168));
}
export class NetworkBridge {
  constructor(wx, profile) {
    this.wx = wx; this.profile = profile; this.generation = 0; this.tasks = new Set(); this.tail = Promise.resolve();
    this.diagnostic = { version: 'LINK 1.0.0', stage: 'Ready', lastError: '' };
  }
  configured() { return !!this.profile && typeof this.profile === 'object' && /^[a-f0-9]{64}$/.test(this.profile.key) && Array.isArray(this.profile.endpoints) && this.profile.endpoints.some(allowedEndpoint); }
  remember(patch) { Object.assign(this.diagnostic, patch); try { this.wx.setStorageSync('voice-link-details', this.diagnostic); } catch (_) {} }
  details() { return this.diagnostic; }
  connected() { return !!this.session; }
  check(generation) { if (generation !== this.generation) throw new Error('Connection cancelled'); }
  http(url, body, generation, ms = 12000) {
    return new Promise((resolve, reject) => {
      let nativeTask, timer, finished = false;
      const lease = { cancel: () => finish(new Error('Connection cancelled'), null, true) };
      const finish = (error, value, abort) => {
        if (finished) return; finished = true; clearTimeout(timer); this.tasks.delete(lease);
        if (abort && nativeTask) { try { const result = nativeTask.abort(); if (result && typeof result.catch === 'function') result.catch(() => {}); } catch (_) {} }
        if (error) reject(error); else { try { this.check(generation); resolve(value); } catch (e) { reject(e); } }
      };
      this.tasks.add(lease);
      timer = setTimeout(() => finish(new Error('Phone did not respond. Check its link status and Wi-Fi.'), null, true), ms);
      try {
        nativeTask = this.wx.request({ url, method: body ? 'POST' : 'GET', header: { 'content-type': 'application/json' },
          data: body ? JSON.stringify(body) : undefined, responseType: 'text', dataType: 'json', timeout: ms,
          success: res => {
            try {
              if (res.statusCode !== 200) throw new Error(res.statusCode === 401 ? 'Phone setup no longer matches. Export a new glasses package from the phone.' : 'Phone link returned ' + res.statusCode + '. Reconnect.');
              const data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
              finish(null, data);
            } catch (e) { finish(new Error(errorMessage(e))); }
          }, fail: err => {
            let message = 'Network request failed'; try { message = err.errMsg || errorMessage(err); } catch (_) {}
            finish(new Error(message));
          }
        });
      } catch (e) { finish(new Error(errorMessage(e))); }
    });
  }
  connect(progress = () => {}) {
    if (this.connecting) return this.connecting;
    const work = this.start(progress); this.connecting = work;
    const clear = () => { if (this.connecting === work) this.connecting = null; }; work.then(clear, clear); return work;
  }
  async start(progress) {
    await this.close(); const generation = this.generation;
    if (!this.configured()) throw new Error('Phone setup needed. On the phone tap Export glasses setup ZIP, then import that ZIP into AIUI.');
    if (!this.wx || typeof this.wx.request !== 'function') throw new Error('This AIUI runtime has no network request API.');
    let last;
    const endpoints = [...new Set(this.profile.endpoints.filter(allowedEndpoint))].slice(0, 6);
    for (const endpoint of endpoints) {
      this.check(generation); progress('Finding phone on Wi-Fi…'); this.remember({ stage: 'Network connection', phone: endpoint, lastError: '' });
      try {
        const challenge = await this.http(endpoint + '/v1/challenge', null, generation, 5000); this.check(generation);
        if (challenge.protocol !== 1 || !/^[a-f0-9]{32}$/.test(challenge.sid)) throw new Error('This address is not Voice Relay Link.');
        this.remember({ stage: 'Checking private link' }); progress('Checking your phone…');
        const key = await sessionKey(this.profile.key, challenge.salt); this.check(generation);
        const session = { endpoint, sid: challenge.sid, key, seq: 1 };
        const packet = seal(key, session.sid, 1, { op: 'hello', id: identifier(this.wx) });
        const answer = await this.http(endpoint + '/v1/open', packet, generation);
        const result = open(key, session.sid, 1, answer); this.check(generation);
        if (result.requestHash !== await checksum(unbase64(packet.box))) throw new Error('Phone handshake is stale. Reconnect.');
        this.check(generation);
        if (!result.ok || result.value.protocol !== 1) throw new Error('Update the phone companion to Voice Relay Link 1.0.');
        this.session = session; this.remember({ stage: 'Connected by Wi-Fi', lastError: '' }); return result.value;
      } catch (e) { this.check(generation); last = e; this.remember({ lastError: errorMessage(e) }); }
    }
    throw new Error('Phone not reachable or setup changed. Use the same Wi-Fi / phone hotspot. Check Connection details; export a new setup ZIP if the phone address changed.');
  }
  exclusive(fn) {
    const generation = this.generation;
    const task = this.tail.then(() => { this.check(generation); return fn(generation); }); this.tail = task.catch(() => {}); return task;
  }
  rpc(query) { return this.exclusive(generation => this.request(query, generation)); }
  async request(query, generation) {
    this.check(generation); const s = this.session; if (!s) throw new Error('Connect to your phone first.');
    const seq = ++s.seq;
    try {
      const packet = seal(s.key, s.sid, seq, { ...query, id: identifier(this.wx) });
      const reply = await this.http(s.endpoint + '/v1/call', packet, generation, 95000);
      const value = open(s.key, s.sid, seq, reply); this.check(generation);
      if (value.requestHash !== await checksum(unbase64(packet.box))) throw new Error('Phone response belongs to a different request');
      this.check(generation);
      if (!value.ok) { const e = new Error(value.error || 'Phone could not complete this action'); e.business = true; throw e; }
      return value.value;
    } catch (e) {
      this.check(generation); this.remember({ stage: 'Request failed: ' + query.op, lastError: errorMessage(e) });
      if (!e.business) this.session = null; // Uncertain delivery is checked by receipt after reconnect, never resent.
      throw e;
    }
  }
  upload(target, data, progress) {
    return this.exclusive(async generation => {
      const bytes = new Uint8Array(data); if (!bytes.length || bytes.length > MAX_AUDIO) throw new Error('Recording is empty or too large');
      const hash = await checksum(bytes); this.check(generation);
      const result = await this.request({ op: 'begin', target, size: bytes.length }, generation);
      for (let offset = 0; offset < bytes.length; offset += CHUNK) {
        await this.request({ op: 'upload_chunk', upload: result.upload, offset, data: base64(bytes.subarray(offset, offset + CHUNK)) }, generation);
        if (progress) progress(Math.min(100, Math.round((offset + CHUNK) * 100 / bytes.length)));
      }
      await this.request({ op: 'seal', upload: result.upload, sha256: hash }, generation); return result.upload;
    });
  }
  downloadAudio(target, message, progress) {
    return this.exclusive(async generation => {
      const meta = await this.request({ op: 'download', target, message }, generation);
      if (!Number.isInteger(meta.size) || meta.size < 1 || meta.size > MAX_AUDIO) throw new Error('Audio is empty or too large');
      const bytes = new Uint8Array(meta.size); let offset = 0;
      while (offset < meta.size) {
        const part = await this.request({ op: 'download_chunk', download: meta.download, offset }, generation);
        const chunk = unbase64(part.data);
        if (!chunk.length || chunk.length > CHUNK || offset + chunk.length > meta.size) throw new Error('Audio transfer was incomplete');
        bytes.set(chunk, offset); offset += chunk.length; if (progress) progress(Math.round(offset * 100 / meta.size));
      }
      if (await checksum(bytes) !== meta.sha256) throw new Error('Audio integrity check failed');
      this.check(generation); return { data: bytes.buffer, mime: meta.mime };
    });
  }
  async close() {
    this.generation++; this.session = null; this.tail = Promise.resolve();
    for (const task of Array.from(this.tasks)) task.cancel();
  }
  forget() { return this.close(); }
}
