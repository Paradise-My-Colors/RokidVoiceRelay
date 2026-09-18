import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { createHash, createHmac } from 'node:crypto';
import { gcmsiv } from '../tools/link-build/node_modules/@noble/ciphers/esm/aes.js';
import { NetworkBridge, allowedEndpoint } from '../aiui/link-src/network.js';
import { sessionKey, seal, open, base64, unbase64, unhex } from '../aiui/link-src/secure.js';
const MASTER='000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f';
const ENDPOINT='http://127.0.0.1:8766';
const profile={ key:MASTER, endpoints:[ENDPOINT] };
let passed=0;
async function test(name,fn) { await fn(); passed++; console.log('PASS '+name); }
await test('Session keys match standard HMAC-SHA256', async()=>{
  const salt='ab'.repeat(32);
  assert.deepEqual(Buffer.from(await sessionKey(MASTER,salt)),createHmac('sha256',Buffer.from(MASTER,'hex')).update('voice-relay-link-v1:'+salt).digest());
});
await test('AES-256-GCM-SIV matches the published RFC 8452 section C.2 vectors',()=>{
  const key=unhex('01'+'00'.repeat(31)),nonce=unhex('03'+'00'.repeat(11));
  for(const [plain,expected] of [['','07f5f4169bbf55a8400cd47ea6fd400f'],['0100000000000000','c2ef328e5c71c83b843122130f7364b761e0b97427e3df28']]) {
    const bytes=plain?unhex(plain):new Uint8Array();
    assert.equal(Buffer.from(gcmsiv(key,nonce).encrypt(bytes)).toString('hex'),expected);
    assert.deepEqual(gcmsiv(key,nonce).decrypt(unhex(expected)),bytes);
  }
});
await test('Encrypted envelopes reject changed payloads and the wrong direction',()=>{
  const key=unhex(MASTER),sid='bc'.repeat(16),seq=123,value={text:'مرحبا ✓',audio:'test'};
  for(const response of [false,true]) {
    const packet=seal(key,sid,seq,value,response);
    assert.deepEqual(open(key,sid,seq,packet,response),value);
    assert.throws(()=>open(key,sid,seq,packet,!response));
    packet.box=base64(Uint8Array.from(unbase64(packet.box),(n,i)=>i===0?n^1:n)); assert.throws(()=>open(key,sid,seq,packet,response));
  }
});
await test('Base64 handles every tail length and rejects malformed encodings',()=>{
  for(const n of [0,1,2,3,4,257,24576]) { const bytes=Uint8Array.from({length:n},(_,i)=>i%251); assert.equal(base64(bytes),Buffer.from(bytes).toString('base64')); assert.deepEqual(unbase64(base64(bytes)),bytes); }
  assert.throws(()=>unbase64('!!=='));
});
await test('Connection profiles accept phone loopback first and private IPv4 fallback endpoints only',()=>{
  assert.ok(allowedEndpoint(ENDPOINT)); assert.ok(allowedEndpoint('http://10.0.0.1:8766')); assert.ok(allowedEndpoint('http://192.168.43.1:8766'));
  for(const v of ['http://example.com:8766','http://8.8.8.8:8766','http://10.0.0.1:80','http://10.0.0.999:8766','http://10.0.0.1:8766/path']) assert.equal(allowedEndpoint(v),false);
});

const ink=fs.readFileSync('aiui/voice-relay-link/pages/link/home.ink','utf8');
const script=ink.match(/<script setup>([\s\S]*?)<\/script>/)[1];
function makePage(config=profile) {
  const memory=new Map();
  const wx={getStorageSync:k=>memory.get(k),setStorageSync:(k,v)=>memory.set(k,v),removeStorageSync:k=>memory.delete(k),exitMiniProgram(){}};
  const context=vm.createContext({wx,console,setTimeout,clearTimeout,setInterval,clearInterval,TextEncoder,TextDecoder,Blob,Uint8Array,ArrayBuffer,DataView,navigator:{userAgent:'Test QuickJS host',mediaDevices:{}}});
  const code=script.replace(/^import wx from "wx";$/m,'').replace('"__VOICE_RELAY_PHONE_PROFILE__"',JSON.stringify(config)).replace(/export \{\s*([^\s]+) as default\s*\};?/, 'globalThis.definition=$1;');
  vm.runInContext(code,context);
  const p={...context.definition,data:JSON.parse(JSON.stringify(context.definition.data)),setData(patch){Object.assign(this.data,patch)}};p.onLoad(); return {p,wx,memory,context};
}
await test('Complete glasses bundle opens without crypto, Bluetooth, audio imports or network access',()=>{
  assert.ok(!/\bcrypto\s*[.(]|navigator\.bluetooth|from ["']audio/.test(script));
  const {p,context}=makePage('__VOICE_RELAY_PHONE_PROFILE__');
  assert.equal(vm.runInContext('typeof crypto',context),'undefined'); assert.equal(p.data.title,'VOICE LINK 1.1.0');
  assert.equal(p.bridge.configured(),false); p.runtimeCheck(); assert.match(p.data.detail,/Network: no/);p.cleanup();
});
await test('All new bundle controls and declared page files resolve',()=>{
  const {p}=makePage(); const manifest=JSON.parse(fs.readFileSync('aiui/voice-relay-link/app.json','utf8'));
  for(const route of manifest.pages) assert.ok(fs.existsSync('aiui/voice-relay-link/'+route+'.ink'));
  for(const m of ink.matchAll(/bind\w+="(\w+)"/g)) assert.equal(typeof p[m[1]],'function');p.cleanup();
});
await test('Missing phone setup fails before making a request',async()=>{
  const b=new NetworkBridge({request(){throw new Error('must not call')}},null);
  await assert.rejects(b.connect(),/Phone setup needed/);
});
await test('A replayed challenge and encrypted handshake cannot impersonate a current phone session',async()=>{
  const challenge={protocol:1,sid:'ab'.repeat(16),salt:'cd'.repeat(32)};
  const key=await sessionKey(MASTER,challenge.salt),memory=new Map();let recorded;
  const host={getStorageSync:k=>memory.get(k),setStorageSync:(k,v)=>memory.set(k,v),request(options){
    if(options.url.endsWith('/v1/challenge')) options.success({statusCode:200,data:challenge});
    else {
      const request=JSON.parse(options.data);
      if(!recorded) recorded=seal(key,challenge.sid,1,{ok:true,value:{protocol:1},requestHash:createHash('sha256').update(unbase64(request.box)).digest('hex')},true);
      options.success({statusCode:200,data:recorded});
    }
    return {abort(){}};
  }};
  const bridge=new NetworkBridge(host,profile);await bridge.connect();assert.ok(bridge.connected());
  await bridge.close();await assert.rejects(bridge.connect(),/(?:setup changed|Phone relay not reachable)/);assert.equal(bridge.connected(),false);assert.match(bridge.details().lastError,/handshake is stale/);
});
await test('Ogg playback uses the documented format hint and releases its player',async()=>{
  const {p,context}=makePage();let hint,stopped=false;
  context.AudioPlayer=class {setBuffer(data,value){hint=value;}play(){}onEnded(){}onError(){}stop(){stopped=true;}destroy(){}};
  p.current={sender:'Alice'};await p.playAudio(new Uint8Array([79,103,103,83]).buffer,'audio/ogg','message');
  assert.equal(hint,'ogg');assert.equal(p.screen,'playing');p.cleanup();assert.ok(stopped);
});
await test('A cancelled WAV decode cannot start playback after leaving the page',async()=>{
  const {p,context}=makePage();let finish,started=false,closed=false;
  context.AudioContext=class {decodeAudioData(){return new Promise(r=>{finish=r;});}close(){closed=true;return Promise.resolve();}createBufferSource(){started=true;throw new Error('must not play');}};
  p.current={sender:'Alice'};const work=p.playAudio(new ArrayBuffer(44),'audio/wav','message');p.cleanup();finish({});await work;
  assert.ok(closed);assert.equal(started,false);
});

if(!process.env.VOICE_LINK_JAVA_CP) throw new Error('Run with VOICE_LINK_JAVA_CP for the real Java HTTP integration checks');
const child=spawn('java',['-cp',process.env.VOICE_LINK_JAVA_CP,'LinkHarness'],{stdio:['pipe','pipe','inherit']});
const port=await new Promise((resolve,reject)=>{
  let text='';const timer=setTimeout(()=>reject(new Error('Java server did not start')),10000);
  child.once('exit',code=>{clearTimeout(timer);reject(new Error('Java server exited '+code));});
  child.stdout.on('data',chunk=>{text+=chunk; const match=text.match(/PORT=(\d+)/);if(match){clearTimeout(timer);resolve(Number(match[1]));}});
});
let dropNextReply=false, calls=0;
function raw(path,body) {
  return new Promise((resolve,reject)=>{
    const data=body?JSON.stringify(body):null;
    const req=http.request({host:'127.0.0.1',port,path,method:data?'POST':'GET',headers:data?{'content-type':'application/json','content-length':Buffer.byteLength(data)}:{}},res=>{
      let s='';res.on('data',chunk=>s+=chunk);res.on('end',()=>resolve({status:res.statusCode,data:JSON.parse(s)}));
    });req.on('error',reject);req.end(data);
  });
}
const storage=new Map();
const wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),request(options){
  calls++; const data=options.data || null, path=new URL(options.url).pathname;
  const req=http.request({host:'127.0.0.1',port,path,method:options.method,headers:data?{'content-type':'application/json','content-length':Buffer.byteLength(data)}:{}},res=>{
    let text='';res.on('data',chunk=>text+=chunk);res.on('end',()=>{
      if(dropNextReply && path==='/v1/call'){dropNextReply=false;options.fail({errMsg:'Simulated lost response after phone executed request'});return;}
      options.success({statusCode:res.statusCode,data:text});
    });
  });req.on('error',err=>options.fail({errMsg:err.message}));req.end(data);return {abort(){req.destroy(new Error('aborted'));}};
}};
let b;
try {
  await test('Actual Java server and AIUI client authenticate through phone loopback without custom GATT or Web Crypto',async()=>{
    b=new NetworkBridge(wx,profile);await b.connect();assert.ok(b.connected());
    const list=await b.rpc({op:'inbox'});assert.equal(list[0].sender,'Alice العربية');
  });
  await test('All six notification preferences round-trip through the real encrypted transport',async()=>{
    for(const key of ['telegram_enabled','whatsapp_enabled','hide_when_phone_unlocked','respect_dnd','respect_phone_silent','nexus_notices']) {
      const options=await b.rpc({op:'setting',key,enabled:false});assert.equal(options[key],false);
    }
  });
  await test('Audio larger than a network chunk downloads and verifies its checksum',async()=>{
    const data=await b.downloadAudio('alice','voice-one');assert.equal(data.data.byteLength,60013);
    assert.deepEqual(new Uint8Array(data.data),Uint8Array.from({length:60013},(_,i)=>i%251));
  });
  await test('Chunked voice upload preserves the exact recipient and bytes',async()=>{
    const data=Uint8Array.from({length:60017},(_,i)=>(i*3)%251);await b.upload('alice',data.buffer);
    const info=await b.rpc({op:'upload_info'});assert.equal(info.target,'alice');assert.equal(info.bytes,data.length);assert.equal(info.sha256,createHash('sha256').update(data).digest('hex'));
  });
  await test('Lost send response never resends; reconnect retrieves the original receipt',async()=>{
    const operation='0123456789abcdef0123456789abcdef';dropNextReply=true;
    await assert.rejects(b.rpc({op:'send',target:'alice',operation,mode:'text',text:'Test'}),/lost response/);assert.ok(!b.connected());
    await b.connect();assert.equal((await b.rpc({op:'receipt',operation})).state,'sent');assert.equal((await b.rpc({op:'status'})).sends,1);
  });
  await test('Modified ciphertext and replayed requests do not reach phone operations',async()=>{
    const s=b.session,seq=s.seq+1;const packet=seal(s.key,s.sid,seq,{op:'status'});
    const modified={...packet,box:packet.box.substring(0,4)+'AAAA'+packet.box.substring(8)};
    assert.equal((await raw('/v1/call',modified)).status,401);
    assert.equal((await raw('/v1/call',packet)).status,200);s.seq=seq;
    assert.equal((await raw('/v1/call',packet)).status,401);
  });
  await test('Wrong phone key cannot authenticate or execute a command',async()=>{
    const wrong=new NetworkBridge(wx,{...profile,key:'ff'.repeat(32)});await assert.rejects(wrong.connect(),/(?:setup changed|Phone relay not reachable)/);assert.ok(!wrong.connected());
    assert.equal((await b.rpc({op:'status'})).sends,1);
  });
  await test('Cancellation rejects late network results and clears pending requests',async()=>{
    const work=b.rpc({op:'slow'});const rejected=assert.rejects(work,/cancelled/);
    await new Promise(r=>setTimeout(r,25));await b.close();await rejected;assert.equal(b.tasks.size,0);assert.ok(!b.connected());
  });
  await test('The bundled glasses page reaches Inbox using the real Java link',async()=>{
    const {p,wx:pageWx}=makePage();pageWx.request=wx.request;
    await p.connect();assert.equal(p.screen,'inbox');assert.equal(p.inbox[0].id,'alice');p.cleanup();
  });
  await test('Oversized HTTP bodies are rejected before allocation or command execution',async()=>{
    const answer=await new Promise((resolve,reject)=>{
      const req=http.request({host:'127.0.0.1',port,path:'/v1/call',method:'POST',headers:{'content-length':2000000000}},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));});req.on('error',reject);req.end();
    });assert.equal(answer,413);
  });
} finally { if(b) await b.close();child.stdin.end('\n'); }
console.log(passed+' Voice Relay Link checks passed, including actual Java HTTP/encryption integration. Physical glasses and messaging accounts remain device tests.');
