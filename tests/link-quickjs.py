"""Execute the shipped page in a real QuickJS engine with no Web Crypto/Bluetooth.

Only the documented host surfaces needed by these checks are supplied as stubs.
This proves script/runtime compatibility, not Rokid hardware integration.
"""
import json
import re
from pathlib import Path
import quickjs

ink = Path('aiui/voice-relay-link/pages/link/home.ink').read_text()
script = re.search(r'<script setup>([\s\S]*?)</script>', ink).group(1)
script = re.sub(r'^import wx from "wx";$', '', script, flags=re.M)
script = re.sub(r'export \{\s*(\S+) as default\s*\};?', r'globalThis.definition = \1;', script)
engine = quickjs.Context()
engine.set_memory_limit(128 * 1024 * 1024)
engine.add_callable('__utf8_encode', lambda s: json.dumps(list(s.encode('utf-8'))))
engine.add_callable('__utf8_decode', lambda s: bytes(json.loads(s)).decode('utf-8'))
engine.eval('''
var wx = {getStorageSync:function(){},setStorageSync:function(){},removeStorageSync:function(){},exitMiniProgram:function(){}};
var console = {log:function(){},error:function(){}};
var navigator = {userAgent:'QuickJS compatibility test'};
function setTimeout(){throw new Error('Unexpected timer in startup/self-check');}
function clearTimeout(){}
class TextEncoder { encode(text){return new Uint8Array(JSON.parse(__utf8_encode(text)));} }
class TextDecoder { decode(value){return __utf8_decode(JSON.stringify(Array.from(value)));} }
''')
engine.eval(script)
engine.eval('''
var page = Object.assign({}, definition, {data:JSON.parse(JSON.stringify(definition.data)),setData:function(patch){Object.assign(this.data,patch);}});
page.onLoad();
if (typeof crypto !== 'undefined') throw new Error('Crypto must be absent');
if (page.data.title !== 'VOICE LINK 1.1.0') throw new Error('Page did not start');
page.runtimeCheck();
if (page.data.detail.indexOf('Network: no') < 0) throw new Error('Capability handling failed');
var result = null;
sessionKey('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f', 'ab'.repeat(32)).then(function(key){
  var sid='bc'.repeat(16), value={text:'مرحبا ✓'};
  var envelope=seal(key,sid,1,value,false);
  var clear=open(key,sid,1,envelope,false);
  if(clear.text !== value.text) throw new Error('Encryption round trip failed');
  result={key:Array.from(key),box:envelope.box,text:clear.text};
}, function(error){result={error:String(error)};});
''')
for _ in range(100):
    if not engine.execute_pending_job():
        break
result = json.loads(engine.eval('JSON.stringify(result)'))
assert result and 'error' not in result, result
import hmac
import hashlib
expected = hmac.new(bytes(range(32)), ('voice-relay-link-v1:' + 'ab' * 32).encode(), hashlib.sha256).digest()
assert bytes(result['key']) == expected
print('PASS real QuickJS: shipped page starts, missing capabilities are handled, session key and AES-GCM-SIV work without Web Crypto or Bluetooth')
