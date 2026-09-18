import { build } from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const result = await build({ entryPoints: [path.join(root, 'aiui/link-src/page.js')], bundle: true, write: false,
  format: 'esm', platform: 'neutral', target: 'es2020', external: ['wx'], treeShaking: true, legalComments: 'inline' });
const js = result.outputFiles[0].text;
if (/\bcrypto\s*[.(]|navigator\.bluetooth|from ['"](?:audio|crypto|node:)/.test(js)) throw new Error('Unexpected native dependency in Link bundle');
if (js.split('"__VOICE_RELAY_PHONE_PROFILE__"').length !== 2) throw new Error('Missing unique phone profile marker');
const folder = path.join(root, 'aiui/voice-relay-link');
const header = '<script def>\n{"navigationBarTitleText":"Voice Relay Link","description":"Private voice-message companion over the phone network link."}\n</script>\n<script setup>\n';
fs.writeFileSync(path.join(folder, 'pages/link/home.ink'), header + js + '\n</script>\n' + fs.readFileSync(path.join(root, 'aiui/link-src/template.ink'), 'utf8'));
fs.copyFileSync(path.join(here, 'node_modules/@noble/ciphers/LICENSE'), path.join(folder, 'THIRD_PARTY_LICENSES.txt'));
const assets = path.join(root, 'app/src/main/assets/link-template');
fs.mkdirSync(assets, { recursive: true }); fs.cpSync(folder, assets, { recursive: true });
console.log('Built Voice Relay Link page and phone export template: ' + js.length + ' JavaScript characters');
