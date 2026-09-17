// Request IDs are correlation/deduplication values, never credentials or pairing keys.
// Keep this module usable on glasses builds without a global Web Crypto object.
let sequence = 0;
export function identifier(storage) {
  let next = sequence + 1;
  if (storage) {
    const saved = Number(storage.getStorageSync('voice-relay-operation-counter'));
    if (Number.isSafeInteger(saved) && saved >= 0) next = Math.max(next, saved + 1);
    storage.setStorageSync('voice-relay-operation-counter', next);
  }
  sequence = next;
  const random = Math.floor(Math.random() * 0x100000000).toString(16).padStart(8, '0');
  return Date.now().toString(16).padStart(12, '0') + next.toString(16).padStart(12, '0') + random;
}

export function errorMessage(error, fallback) {
  try { if (error && typeof error.message === 'string' && error.message) return error.message; } catch (_) {}
  try { const value = String(error); if (value && value !== '[object Object]' && value !== 'undefined' && value !== 'null') return value; } catch (_) {}
  return fallback || 'The glasses runtime could not complete this action.';
}

const K = new Uint32Array([
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
  0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
  0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
  0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
  0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
  0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
  0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
]);
function rotate(value, bits) { return (value >>> bits) | (value << (32 - bits)); }

// SHA-256 transfer integrity; Bluetooth bonding remains responsible for encryption.
// Only the final one/two blocks are copied. Yield periodically on larger recordings.
export async function checksum(data) {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  const whole = Math.floor(bytes.length / 64) * 64;
  const tail = new Uint8Array(Math.ceil((bytes.length - whole + 9) / 64) * 64);
  tail.set(bytes.subarray(whole)); tail[bytes.length - whole] = 0x80;
  const end = new DataView(tail.buffer);
  end.setUint32(tail.length - 8, Math.floor(bytes.length / 0x20000000), false);
  end.setUint32(tail.length - 4, (bytes.length * 8) >>> 0, false);
  const input = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const h = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
  const w = new Uint32Array(64);
  for (let offset = 0; offset < whole + tail.length; offset += 64) {
    const view = offset < whole ? input : end; const start = offset < whole ? offset : offset - whole;
    for (let j = 0; j < 16; j++) w[j] = view.getUint32(start + j * 4, false);
    for (let j = 16; j < 64; j++) {
      const x = w[j - 15], y = w[j - 2];
      w[j] = w[j - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3)) + w[j - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10));
    }
    let a=h[0], b=h[1], c=h[2], d=h[3], e=h[4], f=h[5], g=h[6], hh=h[7];
    for (let j = 0; j < 64; j++) {
      const t1 = (hh + (rotate(e,6)^rotate(e,11)^rotate(e,25)) + ((e&f)^((~e)&g)) + K[j] + w[j]) | 0;
      const t2 = ((rotate(a,2)^rotate(a,13)^rotate(a,22)) + ((a&b)^(a&c)^(b&c))) | 0;
      hh=g; g=f; f=e; e=(d+t1)|0; d=c; c=b; b=a; a=(t1+t2)|0;
    }
    h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hh;
    if (offset > 0 && offset % 65536 === 0) await new Promise(resolve => setTimeout(resolve, 0));
  }
  return Array.from(h, value => value.toString(16).padStart(8, '0')).join('');
}
