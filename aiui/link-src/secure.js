// AES-GCM-SIV implementation is bundled from pinned @noble/ciphers (MIT).
// Keys and session salts come from Android SecureRandom, never Math.random.
import { gcmsiv } from '../../tools/link-build/node_modules/@noble/ciphers/esm/aes.js';
import { checksum } from '../voice-relay/lib/runtime.js';
export function unhex(text) {
  if (typeof text !== 'string' || !/^(?:[a-f0-9]{2})+$/i.test(text)) throw new Error('Invalid link key');
  return new Uint8Array(text.match(/../g).map(x => parseInt(x, 16)));
}
function join(a, b) { const out = new Uint8Array(a.length + b.length); out.set(a); out.set(b, a.length); return out; }
export async function sessionKey(secret, salt) {
  const key = unhex(secret); if (key.length !== 32 || !/^[a-f0-9]{64}$/.test(salt)) throw new Error('Invalid phone setup');
  const inner = new Uint8Array(64).fill(0x36), outer = new Uint8Array(64).fill(0x5c);
  for (let i = 0; i < key.length; i++) { inner[i] ^= key[i]; outer[i] ^= key[i]; }
  const message = new TextEncoder().encode('voice-relay-link-v1:' + salt);
  return unhex(await checksum(join(outer, unhex(await checksum(join(inner, message))))));
}
const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function base64(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i], b = bytes[i + 1] || 0, c = bytes[i + 2] || 0;
    out += alphabet[a >>> 2] + alphabet[((a & 3) << 4) | (b >>> 4)] + (i + 1 < bytes.length ? alphabet[((b & 15) << 2) | (c >>> 6)] : '=') + (i + 2 < bytes.length ? alphabet[c & 63] : '=');
  }
  return out;
}
export function unbase64(text) {
  if (typeof text !== 'string' || text.length % 4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(text)) throw new Error('Invalid link response');
  const out = new Uint8Array(text.length / 4 * 3 - (text.endsWith('==') ? 2 : text.endsWith('=') ? 1 : 0));
  let n = 0;
  for (let i = 0; i < text.length; i += 4) {
    const value = (alphabet.indexOf(text[i]) << 18) | (alphabet.indexOf(text[i + 1]) << 12) | (Math.max(0, alphabet.indexOf(text[i + 2])) << 6) | Math.max(0, alphabet.indexOf(text[i + 3]));
    if (n < out.length) out[n++] = value >>> 16;
    if (n < out.length) out[n++] = value >>> 8;
    if (n < out.length) out[n++] = value;
  }
  return out;
}
export function nonce(seq, response) {
  if (!Number.isSafeInteger(seq) || seq < 1 || seq > 0xffffffff) throw new Error('Reconnect to your phone');
  const value = new Uint8Array(12); value[0] = response ? 1 : 0;
  new DataView(value.buffer).setUint32(8, seq, false); return value;
}
function aad(sid, seq, response) {
  if (!/^[a-f0-9]{32}$/.test(sid)) throw new Error('Invalid session');
  return new TextEncoder().encode('voice-relay-link-v1:' + sid + ':' + seq + ':' + (response ? 'response' : 'request'));
}
export function seal(key, sid, seq, value, response = false) {
  return { sid, seq, box: base64(gcmsiv(key, nonce(seq, response), aad(sid, seq, response)).encrypt(new TextEncoder().encode(JSON.stringify(value)))) };
}
export function open(key, sid, seq, envelope, response = true) {
  if (!envelope || envelope.sid !== sid || envelope.seq !== seq) throw new Error('Phone response does not match this request');
  return JSON.parse(new TextDecoder().decode(gcmsiv(key, nonce(seq, response), aad(sid, seq, response)).decrypt(unbase64(envelope.box))));
}
