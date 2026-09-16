export function keyAction(code) {
  if (['Enter', 'GlobalHook', 'NumpadEnter'].includes(code)) return 'select';
  if (['ArrowDown', 'ArrowRight'].includes(code)) return 'next';
  if (['ArrowUp', 'ArrowLeft'].includes(code)) return 'previous';
  if (['Backspace', 'Escape', 'BrowserBack'].includes(code)) return 'back';
  return null;
}
export function visibleRows(rows, selected) {
  const start = Math.max(0, Math.min(selected - 1, rows.length - 3));
  return rows.slice(start, start + 3).map((row, i) => ({ ...row, index: start + i, selected: start + i === selected }));
}
export function excerpt(text, length) { return Array.from(String(text || '')).slice(0, length).join('') + (Array.from(String(text || '')).length > length ? '…' : ''); }
export function receiptTitle(state) {
  return ({ sent: 'Sent', handed: 'Passed to WhatsApp', phone: 'Finish on phone', pending: 'Check delivery', check: 'Check delivery', unknown: 'Check delivery', failed: 'Not sent' })[state] || 'Check delivery';
}
export function recordingMime(recorder) {
  if (recorder && recorder.isTypeSupported('audio/ogg;codecs=opus')) return 'audio/ogg;codecs=opus';
  if (recorder && recorder.isTypeSupported('audio/wav')) return 'audio/wav';
  return null;
}
