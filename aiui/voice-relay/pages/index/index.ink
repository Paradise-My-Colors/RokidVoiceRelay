<script def>
{"navigationBarTitleText":"Voice Relay","description":"Choose a conversation, listen to a voice message, record or dictate a reply, and manage notification settings."}
</script>
<script setup>
import wx from 'wx';
import { AudioPlayer } from 'audio';
import { Bridge, identifier, timeout } from '../../lib/bridge.js';
import { keyAction, visibleRows, excerpt, receiptTitle, recordingMime } from '../../lib/ui.js';
const SETTINGS = [
  ['telegram_enabled', 'Telegram notifications'],
  ['whatsapp_enabled', 'WhatsApp notifications'],
  ['hide_when_phone_unlocked', 'Hide when phone is unlocked'],
  ['respect_dnd', 'Respect Do Not Disturb'],
  ['respect_phone_silent', 'Respect Silent mode'],
  ['nexus_notices', 'Nexus alerts when AIUI is closed']
];
function row(id, label) { return { id, label }; }
function timeLabel(seconds) {
  const date = new Date(seconds * 1000);
  return date.toLocaleDateString() + ' ' + date.toLocaleTimeString().slice(0, 5);
}
export default {
  data: { title: 'Voice Relay', detail: 'Telegram and WhatsApp', rows: [], counter: '', hint: 'Swipe to choose · Tap to select', connected: false, busy: false, banner: '' },
  onLoad() {
    this.alive = true; this.visible = true; this.menu = []; this.selection = 0; this.inbox = [];
    this.bridge = new Bridge(typeof navigator === 'undefined' ? null : navigator.bluetooth, wx);
    this.screen = 'offline'; this.offline();
  },
  onShow() {
    this.visible = true;
    if (!this.pollTimer) this.pollTimer = setInterval(() => this.poll(), 6000);
    if (this.bridge && !this.bridge.connected()) this.offline();
  },
  onHide() { this.cleanup(); },
  onUnload() { this.alive = false; this.cleanup(); },
  cleanup() {
    this.visible = false; clearInterval(this.pollTimer); this.pollTimer = null;
    this.cancelCapture(); this.stopPlayer();
    if (this.recognition) { try { this.recognition.abort(); } catch (_) {} this.recognition = null; }
    if (this.bridge) this.bridge.close();
  },
  update(patch) { if (this.alive && this.visible) this.setData(patch); },
  show(screen, title, detail, menu, selected) {
    this.screen = screen; this.menu = menu; this.selection = Math.max(0, Math.min(selected || 0, menu.length - 1));
    this.update({ title: excerpt(title, 32), detail: excerpt(detail, 155), busy: false }); this.paint();
  },
  paint() { this.update({ rows: visibleRows(this.menu, this.selection), counter: this.menu.length ? (this.selection + 1) + ' / ' + this.menu.length : '' }); },
  offline() {
    this.update({ connected: false, banner: '' });
    this.show('offline', 'Voice Relay', 'Start the bridge on your phone, then connect.', [row('connect', 'Connect to phone'), row('help', 'Setup help'), row('forget', 'Choose a different phone')]);
  },
  async run(action, message) {
    if (this.data.busy) return;
    this.update({ busy: true, hint: message || 'Working…' });
    try { await action(); }
    catch (error) { if (this.visible) this.error(error); }
    finally { this.update({ busy: false, hint: 'Swipe to choose · Tap to select' }); }
  },
  error(error) {
    this.stopPlayer();
    const text = error && error.message ? error.message : String(error);
    this.show('error', 'Could not complete', text, [row(this.bridge.connected() ? 'inbox' : 'connect', this.bridge.connected() ? 'Back to inbox' : 'Reconnect'), row('help', 'Setup help')]);
    this.update({ banner: '', hint: 'Back returns without sending' });
  },
  connect() { return this.run(async () => {
    try {
      await this.bridge.connect(); this.update({ connected: true });
      await this.openInbox();
      // A lost send is recovered by receipt lookup, never by automatic resend.
      let operation;
      try { operation = wx.getStorageSync('voice-relay-pending-send'); } catch (_) {}
      if (operation) { this.operation = operation; this.renderReceipt(await this.bridge.rpc({ op: 'receipt', operation })); }
    } catch (e) { await this.bridge.close(); throw e; }
  }, 'Connecting…'); },
  async openInbox() {
    const list = await this.bridge.rpc({ op: 'inbox' });
    if (!Array.isArray(list)) throw new Error('Invalid inbox from phone');
    this.inbox = list;
    const menu = list.map(m => row('message:' + m.id, excerpt(m.sender, 27) + (m.voice ? ' · Voice' : '')));
    menu.unshift(row('settings', 'Settings'));
    menu.push(row('refresh', 'Refresh inbox'));
    this.show('inbox', 'Inbox', list.length ? 'Saved conversations · Swipe to choose' : 'No saved messages. Receive a Telegram or WhatsApp notification.', menu, list.length ? 1 : 0);
    this.update({ banner: '' });
  },
  detail() {
    if (!this.current) return;
    this.show('message', this.current.sender, this.current.app + ' · ' + this.current.text,
      [row('listen', 'Listen'), row('reply', 'Reply'), row('read', 'Read full message'), row('settings', 'Settings'), row('dismiss', 'Remove from inbox')]);
  },
  async settings() {
    this.options = await this.bridge.rpc({ op: 'settings' }); this.renderSettings();
  },
  renderSettings() {
    this.show('settings', 'Settings', 'Tap to change · Changes save on your phone', SETTINGS.map(s => row('setting:' + s[0], s[1] + ': ' + (this.options[s[0]] ? 'ON' : 'OFF'))), this.screen === 'settings' ? this.selection : 0);
  },
  async poll() {
    if (!this.visible || this.data.busy || this.polling || !this.bridge.connected()) return;
    this.polling = true;
    try {
      const status = await this.bridge.rpc({ op: 'status' });
      if (!status.listener) this.update({ banner: 'Enable notification access on phone' });
      else if (this.revision && this.revision !== status.revision && status.alerts) this.update({ banner: 'Inbox updated · Back to review' });
      else if (!status.alerts) this.update({ banner: '' });
      this.revision = status.revision;
    } catch (_) { this.update({ connected: false, banner: 'Phone connection lost · Back to reconnect' }); }
    finally { this.polling = false; }
  },
  onKeyDown(event) {
    const action = keyAction(event && (event.code || event.key)); if (!action) return;
    if (event.preventDefault) event.preventDefault();
    if (event.stopPropagation) event.stopPropagation();
    if (event.repeat) return;
    const now = Date.now();
    if (this.lastDown && now - this.lastDown.time < 140 && this.lastDown.action === action) return;
    this.lastDown = { action, time: now }; this.ignoreTapUntil = now + 250; this.handle(action);
  },
  onKeyUp(event) {
    const action = keyAction(event && (event.code || event.key)); if (!action) return;
    if (event.preventDefault) event.preventDefault();
    if (this.lastDown && this.lastDown.action === action && Date.now() - this.lastDown.time < 1000) return;
    this.ignoreTapUntil = Date.now() + 250; this.handle(action);
  },
  handle(action) {
    if (action === 'back') { this.back(); return; }
    if (this.data.busy) return;
    if (action === 'select') { this.activate(); return; }
    if (this.screen === 'recording' || this.screen === 'dictating') return;
    this.selection = Math.max(0, Math.min(this.menu.length - 1, this.selection + (action === 'next' ? 1 : -1))); this.paint();
  },
  tapRow(event) {
    if (Date.now() < (this.ignoreTapUntil || 0) || this.data.busy) return;
    const target = event.currentTarget || event.target;
    const value = target && ((target.dataset && target.dataset.index) || (target.attributes && target.attributes['data-index']));
    if (value !== undefined && value !== null) this.selection = Number(value);
    this.activate();
  },
  tapBack() { if (Date.now() >= (this.ignoreTapUntil || 0)) this.back(); },
  activate() {
    const choice = this.menu[this.selection]; if (!choice) return;
    const id = choice.id;
    if (id === 'connect') return this.connect();
    if (id === 'help') return this.show('help', 'Setup', 'Phone: enable notification access, Start bridge, Pair glasses. Here: Connect. Phone: confirm pairing and Approve glasses. Then Connect again.', [row('connect', 'Connect')]);
    if (id === 'forget') return this.run(async () => { await this.bridge.forget(); this.offline(); });
    if (id === 'stopRecord') return this.finishCapture();
    if (id === 'stopDictation') { if (this.recognition) this.recognition.stop(); return; }
    if (id === 'stopPlay') { this.stopPlayer(); if (this.playReturn === 'preview') this.preview(); else this.detail(); return; }
    if (id === 'record' || id === 'retake') return this.record();
    if (id === 'dictate') return this.dictate();
    if (id === 'reply') return this.show('reply', 'Reply to ' + this.current.sender, 'Voice stays audio. Text dictation is optional.', [row('record', 'Record a voice reply'), row('dictate', 'Dictate a text note')]);
    if (id === 'preview') return this.run(() => this.playAudio(this.draft.data, this.draft.mime, 'preview'), 'Preparing playback…');
    if (id === 'mode:voice' || id === 'mode:file' || id === 'mode:text') {
      this.sendMode = id.slice(5);
      const who = this.draft ? this.draft.target : this.current;
      return this.show('confirm', 'Send to ' + who.sender, who.app + ' · ' + ({ voice: 'Voice note', file: 'Audio file', text: 'Text note' })[this.sendMode], [row('send', 'Confirm send'), row('cancel', 'Cancel')]);
    }
    if (id === 'send') return this.run(() => this.send(), 'Sending…');
    if (id === 'receipt') return this.run(async () => this.renderReceipt(await this.bridge.rpc({ op: 'receipt', operation: this.operation })), 'Checking delivery…');
    if (id === 'cancel') { this.draft = null; this.operation = null; return this.detail(); }
    if (id === 'read') { this.textPage = 0; return this.readMessage(); }
    if (id === 'nextText') { this.textPage++; return this.readMessage(); }
    if (id === 'textFull') { this.draftTextPage = 0; return this.readDraft(); }
    if (id === 'nextDraftText') { this.draftTextPage++; return this.readDraft(); }
    return this.run(async () => {
      if (id === 'inbox' || id === 'refresh') return this.openInbox();
      if (id === 'settings') return this.settings();
      if (id.startsWith('setting:')) {
        const key = id.slice(8); this.options = await this.bridge.rpc({ op: 'setting', key, enabled: !this.options[key] }); this.renderSettings(); return;
      }
      if (id.startsWith('message:')) {
        this.current = this.inbox.find(m => m.id === id.slice(8)); this.draft = null; this.detail(); return;
      }
      if (id === 'dismiss') { await this.bridge.rpc({ op: 'dismiss', target: this.current.id }); this.current = null; return this.openInbox(); }
      if (id === 'listen') {
        this.notes = await this.bridge.rpc({ op: 'voices', target: this.current.id });
        if (!this.notes.length) throw new Error('No received voice notes in the recent Telegram history. Refresh after Telegram has loaded the chat.');
        this.show('voices', 'Choose a voice message', this.current.sender, this.notes.map((v, i) => row('voice:' + i, timeLabel(v.date) + (v.seconds ? ' · ' + v.seconds + 's' : '')))); return;
      }
      if (id.startsWith('voice:')) {
        const note = this.notes[Number(id.slice(6))];
        const audio = await this.bridge.downloadAudio(this.current.id, note.message, p => this.update({ hint: 'Loading audio ' + p + '%' }));
        return this.playAudio(audio.data, audio.mime, 'message');
      }
    });
  },
  readMessage() {
    const chars = Array.from(this.current.text || ''); const pages = Math.max(1, Math.ceil(chars.length / 130));
    this.textPage %= pages;
    this.show('read', this.current.sender, chars.slice(this.textPage * 130, (this.textPage + 1) * 130).join(''), [row('nextText', 'Next page ' + (this.textPage + 1) + '/' + pages), row('reply', 'Reply')]);
  },
  readDraft() {
    const chars = Array.from(this.draft.text || ''); const pages = Math.max(1, Math.ceil(chars.length / 130));
    this.draftTextPage %= pages;
    this.show('readDraft', 'Review text', chars.slice(this.draftTextPage * 130, (this.draftTextPage + 1) * 130).join(''),
      [row('nextDraftText', 'Next page ' + (this.draftTextPage + 1) + '/' + pages), row('mode:text', 'Send text note'), row('cancel', 'Discard')]);
  },
  async record() {
    if (this.capturing || this.data.busy) return;
    this.stopPlayer(); this.draft = null; this.operation = null; this.captureCancelled = false;
    const target = JSON.parse(JSON.stringify(this.current));
    const Recorder = typeof MediaRecorder === 'undefined' ? null : MediaRecorder;
    const mime = recordingMime(Recorder);
    if (!mime || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return this.error(new Error('This AIUI runtime does not expose audio recording. Update the glasses runtime or use the existing Nexus voice reply.'));
    this.capturing = true;
    this.show('recording', 'Opening microphone', 'Reply to ' + target.sender, [row('stopRecord', 'Stop recording')]);
    try {
      const captureToken = {}; this.captureToken = captureToken;
      const media = navigator.mediaDevices.getUserMedia({ audio: { sampleRate: 16000, channelCount: 1, echoCancellation: true } });
      media.then(stream => { if (this.captureToken !== captureToken || this.captureCancelled || !this.visible) stream.getTracks().forEach(t => t.stop()); }, () => {});
      this.stream = await timeout(media, 12000, 'Microphone permission was not granted');
      if (this.captureCancelled || !this.visible) { this.cancelCapture(); return; }
      const chunks = []; const recorder = new Recorder(this.stream, { mimeType: mime, audioBitsPerSecond: 24000 });
      this.recorder = recorder;
      recorder.ondataavailable = event => { if (event.data && event.data.size) chunks.push(event.data); };
      recorder.onerror = event => { this.cancelCapture(); this.error(new Error((event.error && event.error.message) || 'Recording failed')); };
      recorder.onstop = async () => {
        clearInterval(this.clock); this.clock = null; this.capturing = false;
        if (this.stream) this.stream.getTracks().forEach(t => t.stop()); this.stream = null; this.recorder = null;
        if (this.captureCancelled || !this.visible) return;
        try {
          const data = await new Blob(chunks, { type: mime }).arrayBuffer();
          if (!data.byteLength) throw new Error('The microphone returned no audio. Record again.');
          this.draft = { target, data, mime, upload: null }; this.preview();
        } catch (e) { this.error(e); }
      };
      recorder.start(); this.recordStart = Date.now();
      this.update({ title: 'Recording', detail: 'Reply to ' + target.sender, hint: 'Tap to stop · Back to discard' });
      this.clock = setInterval(() => {
        const seconds = Math.floor((Date.now() - this.recordStart) / 1000);
        this.update({ title: 'Recording ' + seconds + 's' });
        if (seconds >= (mime === 'audio/wav' ? 20 : 60)) this.finishCapture();
      }, 250);
    } catch (e) { this.cancelCapture(); this.error(e); }
  },
  finishCapture() {
    if (!this.recorder || this.recorder.state === 'inactive') return;
    clearInterval(this.clock); this.clock = null;
    try { this.recorder.stop(); this.update({ title: 'Preparing recording', hint: 'Please wait…' }); }
    catch (e) { this.cancelCapture(); this.error(e); }
  },
  cancelCapture() {
    this.captureCancelled = true; this.capturing = false;
    this.captureToken = null;
    clearInterval(this.clock); this.clock = null;
    if (this.recorder) { try { if (this.recorder.state !== 'inactive') this.recorder.stop(); } catch (_) {} }
    if (this.stream) this.stream.getTracks().forEach(t => t.stop());
    this.recorder = null; this.stream = null;
  },
  preview() {
    this.show('preview', 'Review reply', this.draft.target.sender + ' · ' + this.draft.target.app,
      [row('preview', 'Listen to recording'), row('mode:voice', 'Send as voice note'), row('mode:file', 'Send as audio file'), row('retake', 'Record again'), row('cancel', 'Discard')]);
  },
  dictate() {
    if (typeof SpeechRecognition === 'undefined') return this.error(new Error('Text dictation is unavailable in this runtime. Use a voice reply.'));
    this.stopPlayer(); this.draft = { target: JSON.parse(JSON.stringify(this.current)), text: '' }; this.operation = null;
    const recognition = new SpeechRecognition(); this.recognition = recognition;
    recognition.continuous = false; recognition.interimResults = false;
    this.show('dictating', 'Dictating text', 'Speak your note. You will review it before sending.', [row('stopDictation', 'Stop dictation')]);
    recognition.onresult = event => {
      const parts = [];
      for (let i = event.resultIndex || 0; i < event.results.length; i++) { if (event.results[i][0]) parts.push(event.results[i][0].transcript); }
      const text = parts.join(' ').trim(); if (!text) return;
      this.draft.text = text;
    };
    recognition.onend = () => {
      this.recognition = null;
      if (!this.visible || this.screen !== 'dictating') return;
      if (!this.draft || !this.draft.text) return this.error(new Error('No words were captured. Try again.'));
      this.show('textPreview', 'Review text', this.draft.text, [row('textFull', 'Read full text'), row('mode:text', 'Send text note'), row('dictate', 'Dictate again'), row('cancel', 'Discard')]);
    };
    recognition.onerror = event => { this.recognition = null; this.error(new Error(event.message || 'Dictation failed')); };
    try { recognition.start(); } catch (e) { this.recognition = null; this.error(e); }
  },
  async playAudio(data, mime, returnScreen) {
    this.stopPlayer(); this.playReturn = returnScreen;
    const player = new AudioPlayer(); this.player = player;
    if (typeof player.setBuffer !== 'function') { player.destroy(); this.player = null; throw new Error('This runtime cannot play received audio buffers'); }
    player.onEnded(() => { if (this.player !== player) return; this.stopPlayer(); if (returnScreen === 'preview') this.preview(); else this.detail(); });
    player.onError(() => { if (this.player !== player) return; this.error(new Error('The glasses could not play this audio format')); });
    player.setBuffer(data, mime); player.play();
    this.show('playing', 'Playing', this.current.sender, [row('stopPlay', 'Stop playback')]);
  },
  stopPlayer() { if (this.player) { try { this.player.stop(); this.player.destroy(); } catch (_) {} } this.player = null; },
  async send() {
    if (!this.draft) throw new Error('No reply is ready');
    if (this.operation) { this.renderReceipt(await this.bridge.rpc({ op: 'receipt', operation: this.operation })); return; }
    const draft = this.draft;
    if (this.sendMode !== 'text' && !draft.upload) draft.upload = await this.bridge.upload(draft.target.id, draft.data, p => this.update({ hint: 'Sending recording to phone ' + p + '%' }));
    this.operation = identifier();
    wx.setStorageSync('voice-relay-pending-send', this.operation);
    const receipt = await this.bridge.rpc({ op: 'send', operation: this.operation, target: draft.target.id, upload: draft.upload || '', mode: this.sendMode, text: draft.text || '' });
    this.renderReceipt(receipt);
  },
  renderReceipt(receipt) {
    if (['sent', 'handed', 'phone', 'failed'].includes(receipt.state)) {
      try { wx.removeStorageSync('voice-relay-pending-send'); } catch (_) {}
    }
    const menu = [row('inbox', 'Back to inbox')];
    if (['pending', 'check', 'unknown'].includes(receipt.state) && this.operation) menu.unshift(row('receipt', 'Check delivery again'));
    this.show('receipt', receiptTitle(receipt.state), receipt.detail || 'Check the conversation on your phone.', menu);
    this.draft = null;
  },
  back() {
    if (this.screen === 'recording') { this.cancelCapture(); this.detail(); return; }
    if (this.screen === 'dictating') { if (this.recognition) this.recognition.abort(); this.recognition = null; this.draft = null; this.detail(); return; }
    if (this.screen === 'playing') { this.stopPlayer(); if (this.playReturn === 'preview') this.preview(); else this.detail(); return; }
    if (this.data.busy) { this.update({ hint: 'Finishing transfer. No automatic resend.' }); return; }
    if (this.screen === 'confirm') { if (this.sendMode === 'text') this.show('textPreview', 'Review text', this.draft.text, [row('mode:text', 'Send text note'), row('cancel', 'Discard')]); else this.preview(); return; }
    if (this.screen === 'readDraft') { this.show('textPreview', 'Review text', this.draft.text, [row('textFull', 'Read full text'), row('mode:text', 'Send text note'), row('cancel', 'Discard')]); return; }
    if (!this.bridge.connected()) { this.offline(); return; }
    if (['message', 'settings', 'receipt', 'error', 'help'].includes(this.screen)) return this.run(() => this.openInbox());
    if (this.screen === 'inbox') { wx.exitMiniProgram(); return; }
    this.draft = null; this.detail();
  }
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
