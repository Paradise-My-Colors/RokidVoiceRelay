<script def>
{"navigationBarTitleText":"Voice Relay Link","description":"Private voice-message companion over the phone network link."}
</script>
<script setup>
// aiui/link-src/page.js
import wx from "wx";

// tools/link-build/node_modules/@noble/ciphers/esm/utils.js
/*! noble-ciphers - MIT License (c) 2023 Paul Miller (paulmillr.com) */
function isBytes(a) {
  return a instanceof Uint8Array || ArrayBuffer.isView(a) && a.constructor.name === "Uint8Array";
}
function abool(b) {
  if (typeof b !== "boolean")
    throw new Error(`boolean expected, not ${b}`);
}
function abytes(b, ...lengths) {
  if (!isBytes(b))
    throw new Error("Uint8Array expected");
  if (lengths.length > 0 && !lengths.includes(b.length))
    throw new Error("Uint8Array expected of length " + lengths + ", got length=" + b.length);
}
function aexists(instance, checkFinished = true) {
  if (instance.destroyed)
    throw new Error("Hash instance has been destroyed");
  if (checkFinished && instance.finished)
    throw new Error("Hash#digest() has already been called");
}
function aoutput(out, instance) {
  abytes(out);
  const min = instance.outputLen;
  if (out.length < min) {
    throw new Error("digestInto() expects output buffer of length at least " + min);
  }
}
function u8(arr) {
  return new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
}
function u32(arr) {
  return new Uint32Array(arr.buffer, arr.byteOffset, Math.floor(arr.byteLength / 4));
}
function clean(...arrays) {
  for (let i = 0; i < arrays.length; i++) {
    arrays[i].fill(0);
  }
}
function createView(arr) {
  return new DataView(arr.buffer, arr.byteOffset, arr.byteLength);
}
var isLE = /* @__PURE__ */ (() => new Uint8Array(new Uint32Array([287454020]).buffer)[0] === 68)();
function utf8ToBytes(str) {
  if (typeof str !== "string")
    throw new Error("string expected");
  return new Uint8Array(new TextEncoder().encode(str));
}
function toBytes(data) {
  if (typeof data === "string")
    data = utf8ToBytes(data);
  else if (isBytes(data))
    data = copyBytes(data);
  else
    throw new Error("Uint8Array expected, got " + typeof data);
  return data;
}
function equalBytes(a, b) {
  if (a.length !== b.length)
    return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++)
    diff |= a[i] ^ b[i];
  return diff === 0;
}
var wrapCipher = /* @__NO_SIDE_EFFECTS__ */ (params, constructor) => {
  function wrappedCipher(key, ...args) {
    abytes(key);
    if (!isLE)
      throw new Error("Non little-endian hardware is not yet supported");
    if (params.nonceLength !== void 0) {
      const nonce2 = args[0];
      if (!nonce2)
        throw new Error("nonce / iv required");
      if (params.varSizeNonce)
        abytes(nonce2);
      else
        abytes(nonce2, params.nonceLength);
    }
    const tagl = params.tagLength;
    if (tagl && args[1] !== void 0) {
      abytes(args[1]);
    }
    const cipher = constructor(key, ...args);
    const checkOutput = (fnLength, output) => {
      if (output !== void 0) {
        if (fnLength !== 2)
          throw new Error("cipher output not supported");
        abytes(output);
      }
    };
    let called = false;
    const wrCipher = {
      encrypt(data, output) {
        if (called)
          throw new Error("cannot encrypt() twice with same key + nonce");
        called = true;
        abytes(data);
        checkOutput(cipher.encrypt.length, output);
        return cipher.encrypt(data, output);
      },
      decrypt(data, output) {
        abytes(data);
        if (tagl && data.length < tagl)
          throw new Error("invalid ciphertext length: smaller than tagLength=" + tagl);
        checkOutput(cipher.decrypt.length, output);
        return cipher.decrypt(data, output);
      }
    };
    return wrCipher;
  }
  Object.assign(wrappedCipher, params);
  return wrappedCipher;
};
function getOutput(expectedLength, out, onlyAligned = true) {
  if (out === void 0)
    return new Uint8Array(expectedLength);
  if (out.length !== expectedLength)
    throw new Error("invalid output length, expected " + expectedLength + ", got: " + out.length);
  if (onlyAligned && !isAligned32(out))
    throw new Error("invalid output, must be aligned");
  return out;
}
function setBigUint64(view, byteOffset, value, isLE2) {
  if (typeof view.setBigUint64 === "function")
    return view.setBigUint64(byteOffset, value, isLE2);
  const _32n = BigInt(32);
  const _u32_max = BigInt(4294967295);
  const wh = Number(value >> _32n & _u32_max);
  const wl = Number(value & _u32_max);
  const h = isLE2 ? 4 : 0;
  const l = isLE2 ? 0 : 4;
  view.setUint32(byteOffset + h, wh, isLE2);
  view.setUint32(byteOffset + l, wl, isLE2);
}
function u64Lengths(dataLength, aadLength, isLE2) {
  abool(isLE2);
  const num = new Uint8Array(16);
  const view = createView(num);
  setBigUint64(view, 0, BigInt(aadLength), isLE2);
  setBigUint64(view, 8, BigInt(dataLength), isLE2);
  return num;
}
function isAligned32(bytes) {
  return bytes.byteOffset % 4 === 0;
}
function copyBytes(bytes) {
  return Uint8Array.from(bytes);
}

// tools/link-build/node_modules/@noble/ciphers/esm/_polyval.js
var BLOCK_SIZE = 16;
var ZEROS16 = /* @__PURE__ */ new Uint8Array(16);
var ZEROS32 = u32(ZEROS16);
var POLY = 225;
var mul2 = (s0, s1, s2, s3) => {
  const hiBit = s3 & 1;
  return {
    s3: s2 << 31 | s3 >>> 1,
    s2: s1 << 31 | s2 >>> 1,
    s1: s0 << 31 | s1 >>> 1,
    s0: s0 >>> 1 ^ POLY << 24 & -(hiBit & 1)
    // reduce % poly
  };
};
var swapLE = (n) => (n >>> 0 & 255) << 24 | (n >>> 8 & 255) << 16 | (n >>> 16 & 255) << 8 | n >>> 24 & 255 | 0;
function _toGHASHKey(k) {
  k.reverse();
  const hiBit = k[15] & 1;
  let carry = 0;
  for (let i = 0; i < k.length; i++) {
    const t = k[i];
    k[i] = t >>> 1 | carry;
    carry = (t & 1) << 7;
  }
  k[0] ^= -hiBit & 225;
  return k;
}
var estimateWindow = (bytes) => {
  if (bytes > 64 * 1024)
    return 8;
  if (bytes > 1024)
    return 4;
  return 2;
};
var GHASH = class {
  // We select bits per window adaptively based on expectedLength
  constructor(key, expectedLength) {
    this.blockLen = BLOCK_SIZE;
    this.outputLen = BLOCK_SIZE;
    this.s0 = 0;
    this.s1 = 0;
    this.s2 = 0;
    this.s3 = 0;
    this.finished = false;
    key = toBytes(key);
    abytes(key, 16);
    const kView = createView(key);
    let k0 = kView.getUint32(0, false);
    let k1 = kView.getUint32(4, false);
    let k2 = kView.getUint32(8, false);
    let k3 = kView.getUint32(12, false);
    const doubles = [];
    for (let i = 0; i < 128; i++) {
      doubles.push({ s0: swapLE(k0), s1: swapLE(k1), s2: swapLE(k2), s3: swapLE(k3) });
      ({ s0: k0, s1: k1, s2: k2, s3: k3 } = mul2(k0, k1, k2, k3));
    }
    const W = estimateWindow(expectedLength || 1024);
    if (![1, 2, 4, 8].includes(W))
      throw new Error("ghash: invalid window size, expected 2, 4 or 8");
    this.W = W;
    const bits = 128;
    const windows = bits / W;
    const windowSize = this.windowSize = 2 ** W;
    const items = [];
    for (let w = 0; w < windows; w++) {
      for (let byte = 0; byte < windowSize; byte++) {
        let s0 = 0, s1 = 0, s2 = 0, s3 = 0;
        for (let j = 0; j < W; j++) {
          const bit = byte >>> W - j - 1 & 1;
          if (!bit)
            continue;
          const { s0: d0, s1: d1, s2: d2, s3: d3 } = doubles[W * w + j];
          s0 ^= d0, s1 ^= d1, s2 ^= d2, s3 ^= d3;
        }
        items.push({ s0, s1, s2, s3 });
      }
    }
    this.t = items;
  }
  _updateBlock(s0, s1, s2, s3) {
    s0 ^= this.s0, s1 ^= this.s1, s2 ^= this.s2, s3 ^= this.s3;
    const { W, t, windowSize } = this;
    let o0 = 0, o1 = 0, o2 = 0, o3 = 0;
    const mask = (1 << W) - 1;
    let w = 0;
    for (const num of [s0, s1, s2, s3]) {
      for (let bytePos = 0; bytePos < 4; bytePos++) {
        const byte = num >>> 8 * bytePos & 255;
        for (let bitPos = 8 / W - 1; bitPos >= 0; bitPos--) {
          const bit = byte >>> W * bitPos & mask;
          const { s0: e0, s1: e1, s2: e2, s3: e3 } = t[w * windowSize + bit];
          o0 ^= e0, o1 ^= e1, o2 ^= e2, o3 ^= e3;
          w += 1;
        }
      }
    }
    this.s0 = o0;
    this.s1 = o1;
    this.s2 = o2;
    this.s3 = o3;
  }
  update(data) {
    aexists(this);
    data = toBytes(data);
    abytes(data);
    const b32 = u32(data);
    const blocks = Math.floor(data.length / BLOCK_SIZE);
    const left = data.length % BLOCK_SIZE;
    for (let i = 0; i < blocks; i++) {
      this._updateBlock(b32[i * 4 + 0], b32[i * 4 + 1], b32[i * 4 + 2], b32[i * 4 + 3]);
    }
    if (left) {
      ZEROS16.set(data.subarray(blocks * BLOCK_SIZE));
      this._updateBlock(ZEROS32[0], ZEROS32[1], ZEROS32[2], ZEROS32[3]);
      clean(ZEROS32);
    }
    return this;
  }
  destroy() {
    const { t } = this;
    for (const elm of t) {
      elm.s0 = 0, elm.s1 = 0, elm.s2 = 0, elm.s3 = 0;
    }
  }
  digestInto(out) {
    aexists(this);
    aoutput(out, this);
    this.finished = true;
    const { s0, s1, s2, s3 } = this;
    const o32 = u32(out);
    o32[0] = s0;
    o32[1] = s1;
    o32[2] = s2;
    o32[3] = s3;
    return out;
  }
  digest() {
    const res = new Uint8Array(BLOCK_SIZE);
    this.digestInto(res);
    this.destroy();
    return res;
  }
};
var Polyval = class extends GHASH {
  constructor(key, expectedLength) {
    key = toBytes(key);
    abytes(key);
    const ghKey = _toGHASHKey(copyBytes(key));
    super(ghKey, expectedLength);
    clean(ghKey);
  }
  update(data) {
    data = toBytes(data);
    aexists(this);
    const b32 = u32(data);
    const left = data.length % BLOCK_SIZE;
    const blocks = Math.floor(data.length / BLOCK_SIZE);
    for (let i = 0; i < blocks; i++) {
      this._updateBlock(swapLE(b32[i * 4 + 3]), swapLE(b32[i * 4 + 2]), swapLE(b32[i * 4 + 1]), swapLE(b32[i * 4 + 0]));
    }
    if (left) {
      ZEROS16.set(data.subarray(blocks * BLOCK_SIZE));
      this._updateBlock(swapLE(ZEROS32[3]), swapLE(ZEROS32[2]), swapLE(ZEROS32[1]), swapLE(ZEROS32[0]));
      clean(ZEROS32);
    }
    return this;
  }
  digestInto(out) {
    aexists(this);
    aoutput(out, this);
    this.finished = true;
    const { s0, s1, s2, s3 } = this;
    const o32 = u32(out);
    o32[0] = s0;
    o32[1] = s1;
    o32[2] = s2;
    o32[3] = s3;
    return out.reverse();
  }
};
function wrapConstructorWithKey(hashCons) {
  const hashC = (msg, key) => hashCons(key, msg.length).update(toBytes(msg)).digest();
  const tmp = hashCons(new Uint8Array(16), 0);
  hashC.outputLen = tmp.outputLen;
  hashC.blockLen = tmp.blockLen;
  hashC.create = (key, expectedLength) => hashCons(key, expectedLength);
  return hashC;
}
var ghash = wrapConstructorWithKey((key, expectedLength) => new GHASH(key, expectedLength));
var polyval = wrapConstructorWithKey((key, expectedLength) => new Polyval(key, expectedLength));

// tools/link-build/node_modules/@noble/ciphers/esm/aes.js
var BLOCK_SIZE2 = 16;
var BLOCK_SIZE32 = 4;
var POLY2 = 283;
function mul22(n) {
  return n << 1 ^ POLY2 & -(n >> 7);
}
function mul(a, b) {
  let res = 0;
  for (; b > 0; b >>= 1) {
    res ^= a & -(b & 1);
    a = mul22(a);
  }
  return res;
}
var sbox = /* @__PURE__ */ (() => {
  const t = new Uint8Array(256);
  for (let i = 0, x = 1; i < 256; i++, x ^= mul22(x))
    t[i] = x;
  const box = new Uint8Array(256);
  box[0] = 99;
  for (let i = 0; i < 255; i++) {
    let x = t[255 - i];
    x |= x << 8;
    box[t[i]] = (x ^ x >> 4 ^ x >> 5 ^ x >> 6 ^ x >> 7 ^ 99) & 255;
  }
  clean(t);
  return box;
})();
var rotr32_8 = (n) => n << 24 | n >>> 8;
var rotl32_8 = (n) => n << 8 | n >>> 24;
function genTtable(sbox2, fn) {
  if (sbox2.length !== 256)
    throw new Error("Wrong sbox length");
  const T0 = new Uint32Array(256).map((_, j) => fn(sbox2[j]));
  const T1 = T0.map(rotl32_8);
  const T2 = T1.map(rotl32_8);
  const T3 = T2.map(rotl32_8);
  const T01 = new Uint32Array(256 * 256);
  const T23 = new Uint32Array(256 * 256);
  const sbox22 = new Uint16Array(256 * 256);
  for (let i = 0; i < 256; i++) {
    for (let j = 0; j < 256; j++) {
      const idx = i * 256 + j;
      T01[idx] = T0[i] ^ T1[j];
      T23[idx] = T2[i] ^ T3[j];
      sbox22[idx] = sbox2[i] << 8 | sbox2[j];
    }
  }
  return { sbox: sbox2, sbox2: sbox22, T0, T1, T2, T3, T01, T23 };
}
var tableEncoding = /* @__PURE__ */ genTtable(sbox, (s) => mul(s, 3) << 24 | s << 16 | s << 8 | mul(s, 2));
var xPowers = /* @__PURE__ */ (() => {
  const p = new Uint8Array(16);
  for (let i = 0, x = 1; i < 16; i++, x = mul22(x))
    p[i] = x;
  return p;
})();
function expandKeyLE(key) {
  abytes(key);
  const len = key.length;
  if (![16, 24, 32].includes(len))
    throw new Error("aes: invalid key size, should be 16, 24 or 32, got " + len);
  const { sbox2 } = tableEncoding;
  const toClean = [];
  if (!isAligned32(key))
    toClean.push(key = copyBytes(key));
  const k32 = u32(key);
  const Nk = k32.length;
  const subByte = (n) => applySbox(sbox2, n, n, n, n);
  const xk = new Uint32Array(len + 28);
  xk.set(k32);
  for (let i = Nk; i < xk.length; i++) {
    let t = xk[i - 1];
    if (i % Nk === 0)
      t = subByte(rotr32_8(t)) ^ xPowers[i / Nk - 1];
    else if (Nk > 6 && i % Nk === 4)
      t = subByte(t);
    xk[i] = xk[i - Nk] ^ t;
  }
  clean(...toClean);
  return xk;
}
function apply0123(T01, T23, s0, s1, s2, s3) {
  return T01[s0 << 8 & 65280 | s1 >>> 8 & 255] ^ T23[s2 >>> 8 & 65280 | s3 >>> 24 & 255];
}
function applySbox(sbox2, s0, s1, s2, s3) {
  return sbox2[s0 & 255 | s1 & 65280] | sbox2[s2 >>> 16 & 255 | s3 >>> 16 & 65280] << 16;
}
function encrypt(xk, s0, s1, s2, s3) {
  const { sbox2, T01, T23 } = tableEncoding;
  let k = 0;
  s0 ^= xk[k++], s1 ^= xk[k++], s2 ^= xk[k++], s3 ^= xk[k++];
  const rounds = xk.length / 4 - 2;
  for (let i = 0; i < rounds; i++) {
    const t02 = xk[k++] ^ apply0123(T01, T23, s0, s1, s2, s3);
    const t12 = xk[k++] ^ apply0123(T01, T23, s1, s2, s3, s0);
    const t22 = xk[k++] ^ apply0123(T01, T23, s2, s3, s0, s1);
    const t32 = xk[k++] ^ apply0123(T01, T23, s3, s0, s1, s2);
    s0 = t02, s1 = t12, s2 = t22, s3 = t32;
  }
  const t0 = xk[k++] ^ applySbox(sbox2, s0, s1, s2, s3);
  const t1 = xk[k++] ^ applySbox(sbox2, s1, s2, s3, s0);
  const t2 = xk[k++] ^ applySbox(sbox2, s2, s3, s0, s1);
  const t3 = xk[k++] ^ applySbox(sbox2, s3, s0, s1, s2);
  return { s0: t0, s1: t1, s2: t2, s3: t3 };
}
function ctr32(xk, isLE2, nonce2, src, dst) {
  abytes(nonce2, BLOCK_SIZE2);
  abytes(src);
  dst = getOutput(src.length, dst);
  const ctr = nonce2;
  const c32 = u32(ctr);
  const view = createView(ctr);
  const src32 = u32(src);
  const dst32 = u32(dst);
  const ctrPos = isLE2 ? 0 : 12;
  const srcLen = src.length;
  let ctrNum = view.getUint32(ctrPos, isLE2);
  let { s0, s1, s2, s3 } = encrypt(xk, c32[0], c32[1], c32[2], c32[3]);
  for (let i = 0; i + 4 <= src32.length; i += 4) {
    dst32[i + 0] = src32[i + 0] ^ s0;
    dst32[i + 1] = src32[i + 1] ^ s1;
    dst32[i + 2] = src32[i + 2] ^ s2;
    dst32[i + 3] = src32[i + 3] ^ s3;
    ctrNum = ctrNum + 1 >>> 0;
    view.setUint32(ctrPos, ctrNum, isLE2);
    ({ s0, s1, s2, s3 } = encrypt(xk, c32[0], c32[1], c32[2], c32[3]));
  }
  const start = BLOCK_SIZE2 * Math.floor(src32.length / BLOCK_SIZE32);
  if (start < srcLen) {
    const b32 = new Uint32Array([s0, s1, s2, s3]);
    const buf = u8(b32);
    for (let i = start, pos = 0; i < srcLen; i++, pos++)
      dst[i] = src[i] ^ buf[pos];
    clean(b32);
  }
  return dst;
}
function computeTag(fn, isLE2, key, data, AAD) {
  const aadLength = AAD ? AAD.length : 0;
  const h = fn.create(key, data.length + aadLength);
  if (AAD)
    h.update(AAD);
  const num = u64Lengths(8 * data.length, 8 * aadLength, isLE2);
  h.update(data);
  h.update(num);
  const res = h.digest();
  clean(num);
  return res;
}
var limit = (name, min, max) => (value) => {
  if (!Number.isSafeInteger(value) || min > value || value > max) {
    const minmax = "[" + min + ".." + max + "]";
    throw new Error("" + name + ": expected value in range " + minmax + ", got " + value);
  }
};
var gcmsiv = /* @__PURE__ */ wrapCipher({ blockSize: 16, nonceLength: 12, tagLength: 16, varSizeNonce: true }, function aessiv(key, nonce2, AAD) {
  const tagLength = 16;
  const AAD_LIMIT = limit("AAD", 0, 2 ** 36);
  const PLAIN_LIMIT = limit("plaintext", 0, 2 ** 36);
  const NONCE_LIMIT = limit("nonce", 12, 12);
  const CIPHER_LIMIT = limit("ciphertext", 16, 2 ** 36 + 16);
  abytes(key, 16, 24, 32);
  NONCE_LIMIT(nonce2.length);
  if (AAD !== void 0)
    AAD_LIMIT(AAD.length);
  function deriveKeys() {
    const xk = expandKeyLE(key);
    const encKey = new Uint8Array(key.length);
    const authKey = new Uint8Array(16);
    const toClean = [xk, encKey];
    let _nonce = nonce2;
    if (!isAligned32(_nonce))
      toClean.push(_nonce = copyBytes(_nonce));
    const n32 = u32(_nonce);
    let s0 = 0, s1 = n32[0], s2 = n32[1], s3 = n32[2];
    let counter = 0;
    for (const derivedKey of [authKey, encKey].map(u32)) {
      const d32 = u32(derivedKey);
      for (let i = 0; i < d32.length; i += 2) {
        const { s0: o0, s1: o1 } = encrypt(xk, s0, s1, s2, s3);
        d32[i + 0] = o0;
        d32[i + 1] = o1;
        s0 = ++counter;
      }
    }
    const res = { authKey, encKey: expandKeyLE(encKey) };
    clean(...toClean);
    return res;
  }
  function _computeTag(encKey, authKey, data) {
    const tag = computeTag(polyval, true, authKey, data, AAD);
    for (let i = 0; i < 12; i++)
      tag[i] ^= nonce2[i];
    tag[15] &= 127;
    const t32 = u32(tag);
    let s0 = t32[0], s1 = t32[1], s2 = t32[2], s3 = t32[3];
    ({ s0, s1, s2, s3 } = encrypt(encKey, s0, s1, s2, s3));
    t32[0] = s0, t32[1] = s1, t32[2] = s2, t32[3] = s3;
    return tag;
  }
  function processSiv(encKey, tag, input) {
    let block = copyBytes(tag);
    block[15] |= 128;
    const res = ctr32(encKey, true, block, input);
    clean(block);
    return res;
  }
  return {
    encrypt(plaintext) {
      PLAIN_LIMIT(plaintext.length);
      const { encKey, authKey } = deriveKeys();
      const tag = _computeTag(encKey, authKey, plaintext);
      const toClean = [encKey, authKey, tag];
      if (!isAligned32(plaintext))
        toClean.push(plaintext = copyBytes(plaintext));
      const out = new Uint8Array(plaintext.length + tagLength);
      out.set(tag, plaintext.length);
      out.set(processSiv(encKey, tag, plaintext));
      clean(...toClean);
      return out;
    },
    decrypt(ciphertext) {
      CIPHER_LIMIT(ciphertext.length);
      const tag = ciphertext.subarray(-tagLength);
      const { encKey, authKey } = deriveKeys();
      const toClean = [encKey, authKey];
      if (!isAligned32(ciphertext))
        toClean.push(ciphertext = copyBytes(ciphertext));
      const plaintext = processSiv(encKey, tag, ciphertext.subarray(0, -tagLength));
      const expectedTag = _computeTag(encKey, authKey, plaintext);
      toClean.push(expectedTag);
      if (!equalBytes(tag, expectedTag)) {
        clean(...toClean);
        throw new Error("invalid polyval tag");
      }
      clean(...toClean);
      return plaintext;
    }
  };
});

// aiui/voice-relay/lib/runtime.js
var sequence = 0;
function identifier(storage) {
  let next = sequence + 1;
  if (storage) {
    const saved = Number(storage.getStorageSync("voice-relay-operation-counter"));
    if (Number.isSafeInteger(saved) && saved >= 0) next = Math.max(next, saved + 1);
    storage.setStorageSync("voice-relay-operation-counter", next);
  }
  sequence = next;
  const random = Math.floor(Math.random() * 4294967296).toString(16).padStart(8, "0");
  return Date.now().toString(16).padStart(12, "0") + next.toString(16).padStart(12, "0") + random;
}
function errorMessage(error, fallback) {
  try {
    if (error && typeof error.message === "string" && error.message) return error.message;
  } catch (_) {
  }
  try {
    const value = String(error);
    if (value && value !== "[object Object]" && value !== "undefined" && value !== "null") return value;
  } catch (_) {
  }
  return fallback || "The glasses runtime could not complete this action.";
}
var K = new Uint32Array([
  1116352408,
  1899447441,
  3049323471,
  3921009573,
  961987163,
  1508970993,
  2453635748,
  2870763221,
  3624381080,
  310598401,
  607225278,
  1426881987,
  1925078388,
  2162078206,
  2614888103,
  3248222580,
  3835390401,
  4022224774,
  264347078,
  604807628,
  770255983,
  1249150122,
  1555081692,
  1996064986,
  2554220882,
  2821834349,
  2952996808,
  3210313671,
  3336571891,
  3584528711,
  113926993,
  338241895,
  666307205,
  773529912,
  1294757372,
  1396182291,
  1695183700,
  1986661051,
  2177026350,
  2456956037,
  2730485921,
  2820302411,
  3259730800,
  3345764771,
  3516065817,
  3600352804,
  4094571909,
  275423344,
  430227734,
  506948616,
  659060556,
  883997877,
  958139571,
  1322822218,
  1537002063,
  1747873779,
  1955562222,
  2024104815,
  2227730452,
  2361852424,
  2428436474,
  2756734187,
  3204031479,
  3329325298
]);
function rotate(value, bits) {
  return value >>> bits | value << 32 - bits;
}
async function checksum(data) {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  const whole = Math.floor(bytes.length / 64) * 64;
  const tail = new Uint8Array(Math.ceil((bytes.length - whole + 9) / 64) * 64);
  tail.set(bytes.subarray(whole));
  tail[bytes.length - whole] = 128;
  const end = new DataView(tail.buffer);
  end.setUint32(tail.length - 8, Math.floor(bytes.length / 536870912), false);
  end.setUint32(tail.length - 4, bytes.length * 8 >>> 0, false);
  const input = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const h = new Uint32Array([1779033703, 3144134277, 1013904242, 2773480762, 1359893119, 2600822924, 528734635, 1541459225]);
  const w = new Uint32Array(64);
  for (let offset = 0; offset < whole + tail.length; offset += 64) {
    const view = offset < whole ? input : end;
    const start = offset < whole ? offset : offset - whole;
    for (let j = 0; j < 16; j++) w[j] = view.getUint32(start + j * 4, false);
    for (let j = 16; j < 64; j++) {
      const x = w[j - 15], y = w[j - 2];
      w[j] = w[j - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ x >>> 3) + w[j - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ y >>> 10);
    }
    let a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (let j = 0; j < 64; j++) {
      const t1 = hh + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) + (e & f ^ ~e & g) + K[j] + w[j] | 0;
      const t2 = (rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) + (a & b ^ a & c ^ b & c) | 0;
      hh = g;
      g = f;
      f = e;
      e = d + t1 | 0;
      d = c;
      c = b;
      b = a;
      a = t1 + t2 | 0;
    }
    h[0] += a;
    h[1] += b;
    h[2] += c;
    h[3] += d;
    h[4] += e;
    h[5] += f;
    h[6] += g;
    h[7] += hh;
    if (offset > 0 && offset % 65536 === 0) await new Promise((resolve) => setTimeout(resolve, 0));
  }
  return Array.from(h, (value) => value.toString(16).padStart(8, "0")).join("");
}

// aiui/link-src/secure.js
function unhex(text) {
  if (typeof text !== "string" || !/^(?:[a-f0-9]{2})+$/i.test(text)) throw new Error("Invalid link key");
  return new Uint8Array(text.match(/../g).map((x) => parseInt(x, 16)));
}
function join(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a);
  out.set(b, a.length);
  return out;
}
async function sessionKey(secret, salt) {
  const key = unhex(secret);
  if (key.length !== 32 || !/^[a-f0-9]{64}$/.test(salt)) throw new Error("Invalid phone setup");
  const inner = new Uint8Array(64).fill(54), outer = new Uint8Array(64).fill(92);
  for (let i = 0; i < key.length; i++) {
    inner[i] ^= key[i];
    outer[i] ^= key[i];
  }
  const message = new TextEncoder().encode("voice-relay-link-v1:" + salt);
  return unhex(await checksum(join(outer, unhex(await checksum(join(inner, message))))));
}
var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function base64(bytes) {
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i], b = bytes[i + 1] || 0, c = bytes[i + 2] || 0;
    out += alphabet[a >>> 2] + alphabet[(a & 3) << 4 | b >>> 4] + (i + 1 < bytes.length ? alphabet[(b & 15) << 2 | c >>> 6] : "=") + (i + 2 < bytes.length ? alphabet[c & 63] : "=");
  }
  return out;
}
function unbase64(text) {
  if (typeof text !== "string" || text.length % 4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(text)) throw new Error("Invalid link response");
  const out = new Uint8Array(text.length / 4 * 3 - (text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0));
  let n = 0;
  for (let i = 0; i < text.length; i += 4) {
    const value = alphabet.indexOf(text[i]) << 18 | alphabet.indexOf(text[i + 1]) << 12 | Math.max(0, alphabet.indexOf(text[i + 2])) << 6 | Math.max(0, alphabet.indexOf(text[i + 3]));
    if (n < out.length) out[n++] = value >>> 16;
    if (n < out.length) out[n++] = value >>> 8;
    if (n < out.length) out[n++] = value;
  }
  return out;
}
function nonce(seq, response) {
  if (!Number.isSafeInteger(seq) || seq < 1 || seq > 4294967295) throw new Error("Reconnect to your phone");
  const value = new Uint8Array(12);
  value[0] = response ? 1 : 0;
  new DataView(value.buffer).setUint32(8, seq, false);
  return value;
}
function aad(sid, seq, response) {
  if (!/^[a-f0-9]{32}$/.test(sid)) throw new Error("Invalid session");
  return new TextEncoder().encode("voice-relay-link-v1:" + sid + ":" + seq + ":" + (response ? "response" : "request"));
}
function seal(key, sid, seq, value, response = false) {
  return { sid, seq, box: base64(gcmsiv(key, nonce(seq, response), aad(sid, seq, response)).encrypt(new TextEncoder().encode(JSON.stringify(value)))) };
}
function open(key, sid, seq, envelope, response = true) {
  if (!envelope || envelope.sid !== sid || envelope.seq !== seq) throw new Error("Phone response does not match this request");
  return JSON.parse(new TextDecoder().decode(gcmsiv(key, nonce(seq, response), aad(sid, seq, response)).decrypt(unbase64(envelope.box))));
}

// aiui/link-src/network.js
var MAX_AUDIO = 12 * 1024 * 1024;
var CHUNK = 24576;
function timeout(work, ms, text) {
  let timer;
  return Promise.race([work, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(text)), ms);
  })]).finally(() => clearTimeout(timer));
}
function allowedEndpoint(value) {
  if (typeof value !== "string") return false;
  const m = value.match(/^http:\/\/(\d+)\.(\d+)\.(\d+)\.(\d+):8766$/);
  if (!m) return false;
  const n = m.slice(1).map(Number);
  return n.every((x) => x >= 0 && x < 256) && (n[0] === 10 || n[0] === 172 && n[1] >= 16 && n[1] <= 31 || n[0] === 192 && n[1] === 168);
}
var NetworkBridge = class {
  constructor(wx2, profile) {
    this.wx = wx2;
    this.profile = profile;
    this.generation = 0;
    this.tasks = /* @__PURE__ */ new Set();
    this.tail = Promise.resolve();
    this.diagnostic = { version: "LINK 1.0.0", stage: "Ready", lastError: "" };
  }
  configured() {
    return !!this.profile && typeof this.profile === "object" && /^[a-f0-9]{64}$/.test(this.profile.key) && Array.isArray(this.profile.endpoints) && this.profile.endpoints.some(allowedEndpoint);
  }
  remember(patch) {
    Object.assign(this.diagnostic, patch);
    try {
      this.wx.setStorageSync("voice-link-details", this.diagnostic);
    } catch (_) {
    }
  }
  details() {
    return this.diagnostic;
  }
  connected() {
    return !!this.session;
  }
  check(generation) {
    if (generation !== this.generation) throw new Error("Connection cancelled");
  }
  http(url, body, generation, ms = 12e3) {
    return new Promise((resolve, reject) => {
      let nativeTask, timer, finished = false;
      const lease = { cancel: () => finish(new Error("Connection cancelled"), null, true) };
      const finish = (error, value, abort) => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        this.tasks.delete(lease);
        if (abort && nativeTask) {
          try {
            const result = nativeTask.abort();
            if (result && typeof result.catch === "function") result.catch(() => {
            });
          } catch (_) {
          }
        }
        if (error) reject(error);
        else {
          try {
            this.check(generation);
            resolve(value);
          } catch (e) {
            reject(e);
          }
        }
      };
      this.tasks.add(lease);
      timer = setTimeout(() => finish(new Error("Phone did not respond. Check its link status and Wi-Fi."), null, true), ms);
      try {
        nativeTask = this.wx.request({
          url,
          method: body ? "POST" : "GET",
          header: { "content-type": "application/json" },
          data: body ? JSON.stringify(body) : void 0,
          responseType: "text",
          dataType: "json",
          timeout: ms,
          success: (res) => {
            try {
              if (res.statusCode !== 200) throw new Error(res.statusCode === 401 ? "Phone setup no longer matches. Export a new glasses package from the phone." : "Phone link returned " + res.statusCode + ". Reconnect.");
              const data = typeof res.data === "string" ? JSON.parse(res.data) : res.data;
              finish(null, data);
            } catch (e) {
              finish(new Error(errorMessage(e)));
            }
          },
          fail: (err) => {
            let message = "Network request failed";
            try {
              message = err.errMsg || errorMessage(err);
            } catch (_) {
            }
            finish(new Error(message));
          }
        });
      } catch (e) {
        finish(new Error(errorMessage(e)));
      }
    });
  }
  connect(progress = () => {
  }) {
    if (this.connecting) return this.connecting;
    const work = this.start(progress);
    this.connecting = work;
    const clear = () => {
      if (this.connecting === work) this.connecting = null;
    };
    work.then(clear, clear);
    return work;
  }
  async start(progress) {
    await this.close();
    const generation = this.generation;
    if (!this.configured()) throw new Error("Phone setup needed. On the phone tap Export glasses setup ZIP, then import that ZIP into AIUI.");
    if (!this.wx || typeof this.wx.request !== "function") throw new Error("This AIUI runtime has no network request API.");
    let last;
    const endpoints = [...new Set(this.profile.endpoints.filter(allowedEndpoint))].slice(0, 6);
    for (const endpoint of endpoints) {
      this.check(generation);
      progress("Finding phone on Wi-Fi\u2026");
      this.remember({ stage: "Network connection", phone: endpoint, lastError: "" });
      try {
        const challenge = await this.http(endpoint + "/v1/challenge", null, generation, 5e3);
        this.check(generation);
        if (challenge.protocol !== 1 || !/^[a-f0-9]{32}$/.test(challenge.sid)) throw new Error("This address is not Voice Relay Link.");
        this.remember({ stage: "Checking private link" });
        progress("Checking your phone\u2026");
        const key = await sessionKey(this.profile.key, challenge.salt);
        this.check(generation);
        const session = { endpoint, sid: challenge.sid, key, seq: 1 };
        const packet = seal(key, session.sid, 1, { op: "hello", id: identifier(this.wx) });
        const answer = await this.http(endpoint + "/v1/open", packet, generation);
        const result = open(key, session.sid, 1, answer);
        this.check(generation);
        if (result.requestHash !== await checksum(unbase64(packet.box))) throw new Error("Phone handshake is stale. Reconnect.");
        this.check(generation);
        if (!result.ok || result.value.protocol !== 1) throw new Error("Update the phone companion to Voice Relay Link 1.0.");
        this.session = session;
        this.remember({ stage: "Connected by Wi-Fi", lastError: "" });
        return result.value;
      } catch (e) {
        this.check(generation);
        last = e;
        this.remember({ lastError: errorMessage(e) });
      }
    }
    throw new Error("Phone not reachable or setup changed. Use the same Wi-Fi / phone hotspot. Check Connection details; export a new setup ZIP if the phone address changed.");
  }
  exclusive(fn) {
    const generation = this.generation;
    const task = this.tail.then(() => {
      this.check(generation);
      return fn(generation);
    });
    this.tail = task.catch(() => {
    });
    return task;
  }
  rpc(query) {
    return this.exclusive((generation) => this.request(query, generation));
  }
  async request(query, generation) {
    this.check(generation);
    const s = this.session;
    if (!s) throw new Error("Connect to your phone first.");
    const seq = ++s.seq;
    try {
      const packet = seal(s.key, s.sid, seq, { ...query, id: identifier(this.wx) });
      const reply = await this.http(s.endpoint + "/v1/call", packet, generation, 95e3);
      const value = open(s.key, s.sid, seq, reply);
      this.check(generation);
      if (value.requestHash !== await checksum(unbase64(packet.box))) throw new Error("Phone response belongs to a different request");
      this.check(generation);
      if (!value.ok) {
        const e = new Error(value.error || "Phone could not complete this action");
        e.business = true;
        throw e;
      }
      return value.value;
    } catch (e) {
      this.check(generation);
      this.remember({ stage: "Request failed: " + query.op, lastError: errorMessage(e) });
      if (!e.business) this.session = null;
      throw e;
    }
  }
  upload(target, data, progress) {
    return this.exclusive(async (generation) => {
      const bytes = new Uint8Array(data);
      if (!bytes.length || bytes.length > MAX_AUDIO) throw new Error("Recording is empty or too large");
      const hash = await checksum(bytes);
      this.check(generation);
      const result = await this.request({ op: "begin", target, size: bytes.length }, generation);
      for (let offset = 0; offset < bytes.length; offset += CHUNK) {
        await this.request({ op: "upload_chunk", upload: result.upload, offset, data: base64(bytes.subarray(offset, offset + CHUNK)) }, generation);
        if (progress) progress(Math.min(100, Math.round((offset + CHUNK) * 100 / bytes.length)));
      }
      await this.request({ op: "seal", upload: result.upload, sha256: hash }, generation);
      return result.upload;
    });
  }
  downloadAudio(target, message, progress) {
    return this.exclusive(async (generation) => {
      const meta = await this.request({ op: "download", target, message }, generation);
      if (!Number.isInteger(meta.size) || meta.size < 1 || meta.size > MAX_AUDIO) throw new Error("Audio is empty or too large");
      const bytes = new Uint8Array(meta.size);
      let offset = 0;
      while (offset < meta.size) {
        const part = await this.request({ op: "download_chunk", download: meta.download, offset }, generation);
        const chunk = unbase64(part.data);
        if (!chunk.length || chunk.length > CHUNK || offset + chunk.length > meta.size) throw new Error("Audio transfer was incomplete");
        bytes.set(chunk, offset);
        offset += chunk.length;
        if (progress) progress(Math.round(offset * 100 / meta.size));
      }
      if (await checksum(bytes) !== meta.sha256) throw new Error("Audio integrity check failed");
      this.check(generation);
      return { data: bytes.buffer, mime: meta.mime };
    });
  }
  async close() {
    this.generation++;
    this.session = null;
    this.tail = Promise.resolve();
    for (const task of Array.from(this.tasks)) task.cancel();
  }
  forget() {
    return this.close();
  }
};

// aiui/voice-relay/lib/ui.js
function keyAction(code) {
  if (["Enter", "GlobalHook", "NumpadEnter"].includes(code)) return "select";
  if (["ArrowDown", "ArrowRight"].includes(code)) return "next";
  if (["ArrowUp", "ArrowLeft"].includes(code)) return "previous";
  if (["Backspace", "Escape", "BrowserBack"].includes(code)) return "back";
  return null;
}
function visibleRows(rows, selected) {
  const start = Math.max(0, Math.min(selected - 1, rows.length - 3));
  return rows.slice(start, start + 3).map((row2, i) => ({ ...row2, index: start + i, selected: start + i === selected }));
}
function excerpt(text, length) {
  return Array.from(String(text || "")).slice(0, length).join("") + (Array.from(String(text || "")).length > length ? "\u2026" : "");
}
function receiptTitle(state) {
  return { sent: "Sent", handed: "Passed to WhatsApp", phone: "Finish on phone", pending: "Check delivery", check: "Check delivery", unknown: "Check delivery", failed: "Not sent" }[state] || "Check delivery";
}
function recordingMime(recorder) {
  if (recorder && recorder.isTypeSupported("audio/ogg;codecs=opus")) return "audio/ogg;codecs=opus";
  if (recorder && recorder.isTypeSupported("audio/wav")) return "audio/wav";
  return null;
}

// aiui/link-src/page.js
var PHONE_PROFILE = "__VOICE_RELAY_PHONE_PROFILE__";
var SETTINGS = [
  ["telegram_enabled", "Telegram notifications"],
  ["whatsapp_enabled", "WhatsApp notifications"],
  ["hide_when_phone_unlocked", "Hide when phone is unlocked"],
  ["respect_dnd", "Respect Do Not Disturb"],
  ["respect_phone_silent", "Respect Silent mode"],
  ["nexus_notices", "Nexus alerts when AIUI is closed"]
];
function row(id, label) {
  return { id, label };
}
function timeLabel(seconds) {
  const date = new Date(seconds * 1e3);
  return date.toLocaleDateString() + " " + date.toLocaleTimeString().slice(0, 5);
}
var page_default = {
  data: { title: "Voice Relay", detail: "Telegram and WhatsApp", rows: [], counter: "", hint: "Swipe to choose \xB7 Tap to select", connected: false, busy: false, banner: "" },
  onLoad() {
    this.alive = true;
    this.visible = true;
    this.menu = [];
    this.selection = 0;
    this.inbox = [];
    this.bridge = new NetworkBridge(wx, PHONE_PROFILE);
    this.screen = "offline";
    this.offline();
  },
  onShow() {
    this.visible = true;
    if (!this.pollTimer) this.pollTimer = setInterval(() => this.poll(), 6e3);
    if (this.bridge && !this.bridge.connected()) this.offline();
  },
  onHide() {
    this.cleanup();
  },
  onUnload() {
    this.alive = false;
    this.cleanup();
  },
  cleanup() {
    this.visible = false;
    clearInterval(this.pollTimer);
    this.pollTimer = null;
    this.runToken = null;
    this.connectionUiToken = null;
    this.connectingPage = false;
    this.cancelCapture();
    this.stopPlayer();
    if (this.recognition) {
      try {
        this.recognition.abort();
      } catch (_) {
      }
      this.recognition = null;
    }
    if (this.bridge) this.bridge.close();
  },
  update(patch) {
    if (this.alive && this.visible) this.setData(patch);
  },
  show(screen, title, detail, menu, selected) {
    this.screen = screen;
    this.menu = menu;
    this.selection = Math.max(0, Math.min(selected || 0, menu.length - 1));
    this.update({ title: excerpt(title, 32), detail: excerpt(detail, 155), busy: false });
    this.paint();
  },
  paint() {
    this.update({ rows: visibleRows(this.menu, this.selection), counter: this.menu.length ? this.selection + 1 + " / " + this.menu.length : "" });
  },
  offline() {
    this.update({ connected: false, banner: "" });
    this.show("offline", "VOICE LINK 1.0.0", this.bridge.configured() ? "Wi-Fi edition. Start phone link, then Connect." : "On phone: Export glasses setup ZIP. Import that package into AIUI.", [row("connect", "Connect to phone"), row("help", "Setup help"), row("runtime", "Check runtime"), row("diagnostics", "Connection details")]);
  },
  async run(action, message) {
    if (this.data.busy) return;
    const token = {};
    this.runToken = token;
    this.update({ busy: true, hint: message || "Working\u2026" });
    try {
      await action();
    } catch (error) {
      if (this.visible && this.runToken === token) this.error(error);
    } finally {
      if (this.runToken === token) this.update({ busy: false, hint: "Swipe to choose \xB7 Tap to select" });
    }
  },
  error(error) {
    this.stopPlayer();
    const text = errorMessage(error);
    this.show("error", "Could not complete", text, [row(this.bridge.connected() ? "inbox" : "connect", this.bridge.connected() ? "Back to inbox" : "Reconnect"), row("diagnostics", "Connection details"), row("help", "Setup help")]);
    this.update({ banner: "", hint: "Back returns without sending" });
  },
  connect() {
    return this.run(async () => {
      const token = {};
      this.connectionUiToken = token;
      this.connectingPage = true;
      this.update({ title: "Connecting", hint: "Back cancels" });
      try {
        await this.bridge.connect((detail) => {
          if (this.connectionUiToken === token) this.update({ detail, hint: "Back cancels \xB7 Keep this page open" });
        });
        if (this.connectionUiToken !== token || !this.visible) return;
        this.update({ connected: true });
        await this.openInbox();
        if (this.connectionUiToken !== token || !this.visible) return;
        this.update({ busy: true });
        let operation;
        try {
          operation = wx.getStorageSync("voice-relay-pending-send");
        } catch (_) {
        }
        if (operation) {
          this.operation = operation;
          this.renderReceipt(await this.bridge.rpc({ op: "receipt", operation }));
        }
      } catch (e) {
        if (this.connectionUiToken === token) {
          await this.bridge.close();
          throw e;
        }
      } finally {
        if (this.connectionUiToken === token) {
          this.connectingPage = false;
          this.connectionUiToken = null;
        }
      }
    }, "Connecting\u2026");
  },
  async cancelConnection() {
    this.connectionUiToken = null;
    this.connectingPage = false;
    this.runToken = null;
    this.update({ busy: true, hint: "Closing phone link\u2026" });
    await this.bridge.close();
    if (this.visible) this.offline();
  },
  connectionDetails(next) {
    if (!next) {
      const d = this.bridge.details();
      this.connectionDetailPage = 0;
      const parts = [
        "App LINK 1.0.0. " + (d.version || ""),
        "Step: " + (d.stage || "Not started"),
        "Phone: " + (d.phone || "Not found"),
        "Transport: encrypted local network. No GATT pairing.",
        "Runtime: " + (d.runtime || "Use Check runtime to inspect"),
        "Last error: " + (d.lastError || "None recorded")
      ];
      this.connectionDetailPages = [];
      for (const part of parts) for (let offset = 0; offset < part.length; offset += 130) this.connectionDetailPages.push(part.slice(offset, offset + 130));
    } else this.connectionDetailPage = (this.connectionDetailPage + 1) % this.connectionDetailPages.length;
    this.show(
      "diagnostics",
      "Connection details",
      this.connectionDetailPages[this.connectionDetailPage],
      [row("nextDiagnostic", "Next detail " + (this.connectionDetailPage + 1) + "/" + this.connectionDetailPages.length), row("connect", "Connect to phone"), row("help", "Setup help")]
    );
  },
  async openInbox() {
    const list = await this.bridge.rpc({ op: "inbox" });
    if (!Array.isArray(list)) throw new Error("Invalid inbox from phone");
    this.inbox = list;
    const menu = list.map((m) => row("message:" + m.id, excerpt(m.sender, 27) + (m.voice ? " \xB7 Voice" : "")));
    menu.unshift(row("settings", "Settings"));
    menu.push(row("refresh", "Refresh inbox"));
    this.show("inbox", "Inbox", list.length ? "Saved conversations \xB7 Swipe to choose" : "No saved messages. Receive a Telegram or WhatsApp notification.", menu, list.length ? 1 : 0);
    this.update({ banner: "" });
  },
  detail() {
    if (!this.current) return;
    this.show(
      "message",
      this.current.sender,
      this.current.app + " \xB7 " + this.current.text,
      [row("listen", "Listen"), row("reply", "Reply"), row("read", "Read full message"), row("settings", "Settings"), row("dismiss", "Remove from inbox")]
    );
  },
  async settings() {
    this.options = await this.bridge.rpc({ op: "settings" });
    this.renderSettings();
  },
  renderSettings() {
    this.show("settings", "Settings", "Tap to change \xB7 Changes save on your phone", SETTINGS.map((s) => row("setting:" + s[0], s[1] + ": " + (this.options[s[0]] ? "ON" : "OFF"))), this.screen === "settings" ? this.selection : 0);
  },
  async poll() {
    if (!this.visible || this.data.busy || this.polling || !this.bridge.connected()) return;
    this.polling = true;
    try {
      const status = await this.bridge.rpc({ op: "status" });
      if (!status.listener) this.update({ banner: "Enable notification access on phone" });
      else if (this.revision && this.revision !== status.revision && status.alerts) this.update({ banner: "Inbox updated \xB7 Back to review" });
      else if (!status.alerts) this.update({ banner: "" });
      this.revision = status.revision;
    } catch (_) {
      this.update({ connected: false, banner: "Phone connection lost \xB7 Back to reconnect" });
    } finally {
      this.polling = false;
    }
  },
  onKeyDown(event) {
    const action = keyAction(event && (event.code || event.key));
    if (!action) return;
    if (event.preventDefault) event.preventDefault();
    if (event.stopPropagation) event.stopPropagation();
    if (event.repeat) return;
    const now = Date.now();
    if (this.lastDown && now - this.lastDown.time < 140 && this.lastDown.action === action) return;
    this.lastDown = { action, time: now };
    this.ignoreTapUntil = now + 250;
    this.handle(action);
  },
  onKeyUp(event) {
    const action = keyAction(event && (event.code || event.key));
    if (!action) return;
    if (event.preventDefault) event.preventDefault();
    if (this.lastDown && this.lastDown.action === action && Date.now() - this.lastDown.time < 1e3) return;
    this.ignoreTapUntil = Date.now() + 250;
    this.handle(action);
  },
  handle(action) {
    if (action === "back") {
      this.back();
      return;
    }
    if (this.data.busy) return;
    if (action === "select") {
      this.activate();
      return;
    }
    if (this.screen === "recording" || this.screen === "dictating") return;
    this.selection = Math.max(0, Math.min(this.menu.length - 1, this.selection + (action === "next" ? 1 : -1)));
    this.paint();
  },
  tapRow(event) {
    if (Date.now() < (this.ignoreTapUntil || 0) || this.data.busy) return;
    const target = event.currentTarget || event.target;
    const value = target && (target.dataset && target.dataset.index !== void 0 ? target.dataset.index : target.attributes && target.attributes["data-index"]);
    if (value !== void 0 && value !== null) this.selection = Number(value);
    this.activate();
  },
  tapBack() {
    if (Date.now() >= (this.ignoreTapUntil || 0)) this.back();
  },
  activate() {
    const choice = this.menu[this.selection];
    if (!choice) return;
    const id = choice.id;
    if (id === "connect") return this.connect();
    if (id === "help") return this.show("help", "VOICE LINK setup", "Same Wi-Fi or phone hotspot. Phone: Start link, Export setup ZIP. Import ZIP in AIUI; Package AIX and sync. Open Voice Relay Link.", [row("connect", "Connect"), row("diagnostics", "Connection details")]);
    if (id === "diagnostics") return this.connectionDetails(false);
    if (id === "runtime") return this.runtimeCheck();
    if (id === "nextDiagnostic") return this.connectionDetails(true);
    if (id === "forget") return this.run(async () => {
      await this.bridge.forget();
      this.offline();
    });
    if (id === "stopRecord") return this.finishCapture();
    if (id === "stopDictation") {
      if (this.recognition) this.recognition.stop();
      return;
    }
    if (id === "stopPlay") {
      this.stopPlayer();
      if (this.playReturn === "preview") this.preview();
      else this.detail();
      return;
    }
    if (id === "record" || id === "retake") return this.record();
    if (id === "dictate") return this.dictate();
    if (id === "reply") return this.show("reply", "Reply to " + this.current.sender, "Voice stays audio. Text dictation is optional.", [row("record", "Record a voice reply"), row("dictate", "Dictate a text note")]);
    if (id === "preview") return this.run(() => this.playAudio(this.draft.data, this.draft.mime, "preview"), "Preparing playback\u2026");
    if (id === "mode:voice" || id === "mode:file" || id === "mode:text") {
      this.sendMode = id.slice(5);
      const who = this.draft ? this.draft.target : this.current;
      return this.show("confirm", "Send to " + who.sender, who.app + " \xB7 " + { voice: "Voice note", file: "Audio file", text: "Text note" }[this.sendMode], [row("send", "Confirm send"), row("cancel", "Cancel")]);
    }
    if (id === "send") return this.run(() => this.send(), "Sending\u2026");
    if (id === "receipt") return this.run(async () => this.renderReceipt(await this.bridge.rpc({ op: "receipt", operation: this.operation })), "Checking delivery\u2026");
    if (id === "ackReceipt") return this.run(async () => {
      wx.removeStorageSync("voice-relay-pending-send");
      this.operation = null;
      await this.openInbox();
    });
    if (id === "cancel") {
      this.draft = null;
      this.operation = null;
      return this.detail();
    }
    if (id === "read") {
      this.textPage = 0;
      return this.readMessage();
    }
    if (id === "nextText") {
      this.textPage++;
      return this.readMessage();
    }
    if (id === "textFull") {
      this.draftTextPage = 0;
      return this.readDraft();
    }
    if (id === "nextDraftText") {
      this.draftTextPage++;
      return this.readDraft();
    }
    return this.run(async () => {
      if (id === "inbox" || id === "refresh") return this.openInbox();
      if (id === "settings") return this.settings();
      if (id.startsWith("setting:")) {
        const key = id.slice(8);
        this.options = await this.bridge.rpc({ op: "setting", key, enabled: !this.options[key] });
        this.renderSettings();
        return;
      }
      if (id.startsWith("message:")) {
        this.current = this.inbox.find((m) => m.id === id.slice(8));
        this.draft = null;
        this.detail();
        return;
      }
      if (id === "dismiss") {
        await this.bridge.rpc({ op: "dismiss", target: this.current.id });
        this.current = null;
        return this.openInbox();
      }
      if (id === "listen") {
        this.notes = await this.bridge.rpc({ op: "voices", target: this.current.id });
        if (!this.notes.length) throw new Error("No received voice notes in the recent Telegram history. Refresh after Telegram has loaded the chat.");
        this.show("voices", "Choose a voice message", this.current.sender, this.notes.map((v, i) => row("voice:" + i, timeLabel(v.date) + (v.seconds ? " \xB7 " + v.seconds + "s" : ""))));
        return;
      }
      if (id.startsWith("voice:")) {
        const note = this.notes[Number(id.slice(6))];
        const audio = await this.bridge.downloadAudio(this.current.id, note.message, (p) => this.update({ hint: "Loading audio " + p + "%" }));
        return this.playAudio(audio.data, audio.mime, "message");
      }
    });
  },
  readMessage() {
    const chars = Array.from(this.current.text || "");
    const pages = Math.max(1, Math.ceil(chars.length / 130));
    this.textPage %= pages;
    this.show("read", this.current.sender, chars.slice(this.textPage * 130, (this.textPage + 1) * 130).join(""), [row("nextText", "Next page " + (this.textPage + 1) + "/" + pages), row("reply", "Reply")]);
  },
  readDraft() {
    const chars = Array.from(this.draft.text || "");
    const pages = Math.max(1, Math.ceil(chars.length / 130));
    this.draftTextPage %= pages;
    this.show(
      "readDraft",
      "Review text",
      chars.slice(this.draftTextPage * 130, (this.draftTextPage + 1) * 130).join(""),
      [row("nextDraftText", "Next page " + (this.draftTextPage + 1) + "/" + pages), row("mode:text", "Send text note"), row("cancel", "Discard")]
    );
  },
  async record() {
    if (this.capturing || this.data.busy) return;
    this.stopPlayer();
    this.draft = null;
    this.operation = null;
    this.captureCancelled = false;
    const target = JSON.parse(JSON.stringify(this.current));
    const Recorder = typeof MediaRecorder === "undefined" ? null : MediaRecorder;
    const mime = recordingMime(Recorder);
    if (!mime || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return this.error(new Error("This AIUI runtime does not expose audio recording. Update the glasses runtime or use the existing Nexus voice reply."));
    this.capturing = true;
    this.show("recording", "Opening microphone", "Reply to " + target.sender, [row("stopRecord", "Stop recording")]);
    const captureToken = {};
    this.captureToken = captureToken;
    try {
      const media = navigator.mediaDevices.getUserMedia({ audio: { sampleRate: 16e3, channelCount: 1, echoCancellation: true } });
      media.then((stream2) => {
        if (this.captureToken !== captureToken || this.captureCancelled || !this.visible) stream2.getTracks().forEach((t) => t.stop());
      }, () => {
      });
      const stream = await timeout(media, 12e3, "Microphone permission was not granted");
      if (this.captureToken !== captureToken || this.captureCancelled || !this.visible) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      this.stream = stream;
      const chunks = [];
      const recorder = new Recorder(this.stream, { mimeType: mime, audioBitsPerSecond: 24e3 });
      this.recorder = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size) chunks.push(event.data);
      };
      recorder.onerror = (event) => {
        if (this.captureToken !== captureToken) return;
        this.cancelCapture();
        this.error(new Error(event.error && event.error.message || "Recording failed"));
      };
      recorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        if (this.captureToken !== captureToken) return;
        clearInterval(this.clock);
        this.clock = null;
        this.capturing = false;
        this.stream = null;
        this.recorder = null;
        if (this.captureCancelled || !this.visible) return;
        try {
          const data = await new Blob(chunks, { type: mime }).arrayBuffer();
          if (this.captureToken !== captureToken || !this.visible) return;
          if (!data.byteLength) throw new Error("The microphone returned no audio. Record again.");
          this.draft = { target, data, mime, upload: null };
          this.preview();
        } catch (e) {
          this.error(e);
        }
      };
      recorder.start();
      this.recordStart = Date.now();
      this.update({ title: "Recording", detail: "Reply to " + target.sender, hint: "Tap to stop \xB7 Back to discard" });
      this.clock = setInterval(() => {
        const seconds = Math.floor((Date.now() - this.recordStart) / 1e3);
        this.update({ title: "Recording " + seconds + "s" });
        if (seconds >= (mime === "audio/wav" ? 20 : 60)) this.finishCapture();
      }, 250);
    } catch (e) {
      if (this.captureToken === captureToken) {
        this.cancelCapture();
        this.error(e);
      }
    }
  },
  finishCapture() {
    if (!this.recorder || this.recorder.state === "inactive") return;
    clearInterval(this.clock);
    this.clock = null;
    try {
      this.recorder.stop();
      this.update({ title: "Preparing recording", hint: "Please wait\u2026" });
    } catch (e) {
      this.cancelCapture();
      this.error(e);
    }
  },
  cancelCapture() {
    this.captureCancelled = true;
    this.capturing = false;
    this.captureToken = null;
    clearInterval(this.clock);
    this.clock = null;
    if (this.recorder) {
      try {
        if (this.recorder.state !== "inactive") this.recorder.stop();
      } catch (_) {
      }
    }
    if (this.stream) this.stream.getTracks().forEach((t) => t.stop());
    this.recorder = null;
    this.stream = null;
  },
  preview() {
    this.show(
      "preview",
      "Review reply",
      this.draft.target.sender + " \xB7 " + this.draft.target.app,
      [row("preview", "Listen to recording"), row("mode:voice", "Send as voice note"), row("mode:file", "Send as audio file"), row("retake", "Record again"), row("cancel", "Discard")]
    );
  },
  dictate() {
    if (typeof SpeechRecognition === "undefined") return this.error(new Error("Text dictation is unavailable in this runtime. Use a voice reply."));
    this.stopPlayer();
    this.draft = { target: JSON.parse(JSON.stringify(this.current)), text: "" };
    this.operation = null;
    const recognition = new SpeechRecognition();
    this.recognition = recognition;
    recognition.continuous = false;
    recognition.interimResults = false;
    this.show("dictating", "Dictating text", "Speak your note. You will review it before sending.", [row("stopDictation", "Stop dictation")]);
    recognition.onresult = (event) => {
      if (this.recognition !== recognition) return;
      const parts = [];
      for (let i = event.resultIndex || 0; i < event.results.length; i++) {
        if (event.results[i][0]) parts.push(event.results[i][0].transcript);
      }
      const text = parts.join(" ").trim();
      if (!text) return;
      this.draft.text = text;
    };
    recognition.onend = () => {
      if (this.recognition !== recognition) return;
      this.recognition = null;
      if (!this.visible || this.screen !== "dictating") return;
      if (!this.draft || !this.draft.text) return this.error(new Error("No words were captured. Try again."));
      this.show("textPreview", "Review text", this.draft.text, [row("textFull", "Read full text"), row("mode:text", "Send text note"), row("dictate", "Dictate again"), row("cancel", "Discard")]);
    };
    recognition.onerror = (event) => {
      if (this.recognition !== recognition) return;
      this.recognition = null;
      this.error(new Error(event.message || "Dictation failed"));
    };
    try {
      recognition.start();
    } catch (e) {
      this.recognition = null;
      this.error(e);
    }
  },
  runtimeCheck() {
    let runtime = "Unknown";
    try {
      runtime = navigator.userAgent || navigator.versions && navigator.versions.ink || runtime;
    } catch (_) {
    }
    const d = "Network: " + (typeof wx.request === "function" ? "yes" : "no") + " \xB7 Recorder: " + (typeof MediaRecorder !== "undefined" ? "yes" : "no") + " \xB7 Playback: " + (typeof AudioPlayer !== "undefined" ? "yes" : "no");
    this.bridge.remember({ runtime });
    this.show("help", "LINK runtime check", d, [row("diagnostics", "Connection details"), row("connect", "Connect to phone")]);
  },
  async playAudio(data, mime, returnScreen) {
    this.stopPlayer();
    this.playReturn = returnScreen;
    if (mime.includes("wav")) {
      if (typeof AudioContext === "undefined") throw new Error("WAV playback is unavailable in this runtime.");
      const context = new AudioContext();
      let source;
      const player2 = { stop() {
        if (source) {
          try {
            source.stop();
          } catch (_) {
          }
        }
      }, destroy() {
        try {
          const closed = context.close();
          if (closed && closed.catch) closed.catch(() => {
          });
        } catch (_) {
        }
      } };
      this.player = player2;
      try {
        const buffer = await context.decodeAudioData(data);
        if (this.player !== player2 || !this.visible) return;
        source = context.createBufferSource();
        source.buffer = buffer;
        source.connect(context.destination);
        source.onended = () => {
          if (this.player !== player2) return;
          this.stopPlayer();
          if (returnScreen === "preview") this.preview();
          else this.detail();
        };
        await context.resume();
        if (this.player !== player2 || !this.visible) return;
        source.start();
        this.show("playing", "Playing", this.current.sender, [row("stopPlay", "Stop playback")]);
        return;
      } catch (e) {
        if (this.player === player2) this.stopPlayer();
        throw e;
      }
    }
    if (typeof AudioPlayer === "undefined") throw new Error("AudioPlayer is unavailable in this AIUI runtime. Update Hi Rokid and the glasses runtime.");
    const player = new AudioPlayer();
    this.player = player;
    if (typeof player.setBuffer !== "function") {
      player.destroy();
      this.player = null;
      throw new Error("This runtime cannot play received audio buffers");
    }
    player.onEnded(() => {
      if (this.player !== player) return;
      this.stopPlayer();
      if (returnScreen === "preview") this.preview();
      else this.detail();
    });
    player.onError(() => {
      if (this.player !== player) return;
      this.error(new Error("The glasses could not play this audio format"));
    });
    player.setBuffer(data, mime.includes("ogg") ? "ogg" : "wav");
    player.play();
    this.show("playing", "Playing", this.current.sender, [row("stopPlay", "Stop playback")]);
  },
  stopPlayer() {
    if (this.player) {
      try {
        this.player.stop();
        this.player.destroy();
      } catch (_) {
      }
    }
    this.player = null;
  },
  async send() {
    if (!this.draft) throw new Error("No reply is ready");
    if (this.operation) {
      this.renderReceipt(await this.bridge.rpc({ op: "receipt", operation: this.operation }));
      return;
    }
    const draft = this.draft;
    if (this.sendMode !== "text" && !draft.upload) draft.upload = await this.bridge.upload(draft.target.id, draft.data, (p) => this.update({ hint: "Sending recording to phone " + p + "%" }));
    this.operation = identifier(wx);
    wx.setStorageSync("voice-relay-pending-send", this.operation);
    const receipt = await this.bridge.rpc({ op: "send", operation: this.operation, target: draft.target.id, upload: draft.upload || "", mode: this.sendMode, text: draft.text || "" });
    this.renderReceipt(receipt);
  },
  renderReceipt(receipt) {
    if (["sent", "handed", "phone", "failed"].includes(receipt.state)) {
      try {
        wx.removeStorageSync("voice-relay-pending-send");
      } catch (_) {
      }
    }
    const menu = [row("inbox", "Back to inbox")];
    if (["pending", "check", "unknown"].includes(receipt.state) && this.operation) {
      menu.unshift(row("receipt", "Check delivery again"));
      menu.push(row("ackReceipt", "I checked the chat on my phone"));
    }
    this.show("receipt", receiptTitle(receipt.state), receipt.detail || "Check the conversation on your phone.", menu);
    this.draft = null;
  },
  back() {
    if (this.connectingPage) {
      this.cancelConnection();
      return;
    }
    if (this.screen === "recording") {
      this.cancelCapture();
      this.detail();
      return;
    }
    if (this.screen === "dictating") {
      if (this.recognition) this.recognition.abort();
      this.recognition = null;
      this.draft = null;
      this.detail();
      return;
    }
    if (this.screen === "playing") {
      this.stopPlayer();
      if (this.playReturn === "preview") this.preview();
      else this.detail();
      return;
    }
    if (this.data.busy) {
      this.update({ hint: "Finishing transfer. No automatic resend." });
      return;
    }
    if (this.screen === "confirm") {
      if (this.sendMode === "text") this.show("textPreview", "Review text", this.draft.text, [row("textFull", "Read full text"), row("mode:text", "Send text note"), row("cancel", "Discard")]);
      else this.preview();
      return;
    }
    if (this.screen === "readDraft") {
      this.show("textPreview", "Review text", this.draft.text, [row("textFull", "Read full text"), row("mode:text", "Send text note"), row("cancel", "Discard")]);
      return;
    }
    if (!this.bridge.connected()) {
      this.offline();
      return;
    }
    if (["message", "settings", "receipt", "error", "help", "diagnostics"].includes(this.screen)) return this.run(() => this.openInbox());
    if (this.screen === "inbox") {
      wx.exitMiniProgram();
      return;
    }
    this.draft = null;
    this.detail();
  }
};
export {
  page_default as default
};

</script>
<page>
  <view class="page">
    <view class="header"><text class="title">{{title}}</text><text class="count">{{counter}}</text></view>
    <text class="detail">{{detail}}</text>
    <view class="choices">
      <view wx:for="{{rows}}" wx:key="id" class="choice {{item.selected ? 'chosen' : ''}}" data-index="{{item.index}}" bindtap="tapRow">
        <text class="marker">{{item.selected ? '›' : ' '}}</text><text class="label">{{item.label}}</text>
      </view>
    </view>
    <text class="banner">{{banner}}</text>
    <view class="footer"><button class="back" bindtap="tapBack">Back</button><text class="hint">{{hint}}</text></view>
  </view>
</page>
<style>
.page { width: 100%; height: 100%; box-sizing: border-box; padding: 12px 16px; background-color: #000000; color: #b8ffcc; display: flex; flex-direction: column; gap: 6px; }
.header { display: flex; justify-content: space-between; align-items: center; height: 32px; }
.title { font-size: 23px; font-weight: 500; color: #00ff66; }
.count { font-size: 14px; color: #b8ffcc; }
.detail { height: 68px; font-size: 17px; line-height: 22px; }
.choices { display: flex; flex-direction: column; height: 138px; gap: 3px; }
.choice { height: 42px; padding: 6px 8px; box-sizing: border-box; display: flex; align-items: center; border: 1px solid #003d18; border-radius: 4px; }
.chosen { border: 2px solid #00ff66; background-color: #001e0c; }
.marker { width: 20px; font-size: 24px; color: #00ff66; }
.label { font-size: 19px; color: #b8ffcc; }
.banner { height: 20px; font-size: 14px; color: #b8ffcc; }
.footer { display: flex; align-items: center; gap: 8px; }
.back { height: 30px; padding: 2px 10px; font-size: 14px; color: #b8ffcc; background-color: #000000; border: 1px solid #007a30; border-radius: 4px; }
.hint { font-size: 13px; color: #b8ffcc; }
</style>
