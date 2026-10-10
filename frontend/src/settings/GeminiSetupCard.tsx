/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useState } from 'react';
import type { GeminiSetupStatus } from '../google/contracts';
import { Badge, SettingsCard, SettingsRow } from './primitives';

export default function GeminiSetupCard() {
  const [status, setStatus] = useState<GeminiSetupStatus | null>(null);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    const refresh = () => { void window.bridge.getGeminiSetupStatus().then(next => {
      if (active) setStatus(next);
    }).catch(() => { if (active) setError('Unable to read Gemini setup. Check macOS Keychain access and retry.'); }); };
    refresh();
    window.addEventListener('focus', refresh);
    return () => { active = false; window.removeEventListener('focus', refresh); };
  }, []);
  async function act(action: () => Promise<void>) {
    setBusy(true); setError(null); setNotice(null);
    try { await action(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Gemini setup failed. Try again.'); }
    finally { setBusy(false); }
  }
  const openLink = (url: string) => { void act(() => window.bridge.openExternal(url)); };
  return <>
    <div className="setw-section"><span className="setw-section-title">Gemini assistant</span></div>
    {error && <div className="setw-error" role="alert">{error} <button type="button" className="setw-link" disabled={busy} onClick={() => { void act(async () => setStatus(await window.bridge.getGeminiSetupStatus())); }}>Retry</button></div>}
    {notice && <p className="setw-footnote" role="status">{notice}</p>}
    <SettingsCard>
      <SettingsRow title="Gemini API key" badge={<Badge>{status?.configured ? 'Saved in Keychain' : 'Setup needed'}</Badge>}
        subtitle="Create a key in Google AI Studio, then paste it below. Google may ask you to sign in, accept its terms, and choose a project.">
        <button type="button" className="setw-btn" disabled={busy} onClick={() => openLink('https://aistudio.google.com/apikey')}>Create API key…</button>
      </SettingsRow>
      {(!status?.configured || editing) && <form className="setw-row" onSubmit={event => {
        event.preventDefault();
        if (busy || !key.trim()) return;
        void act(async () => {
          const next = await window.bridge.saveGeminiSetupKey(key.trim());
          setKey(''); setEditing(false); setStatus(next);
          setNotice('Key saved. Start a conversation on the Google Apps page.');
        });
      }}>
        <input type="password" aria-label="Gemini API key" autoComplete="off" spellCheck={false} maxLength={4096}
          className="setw-input setw-mono" style={{ flex: 1, minWidth: 0 }} placeholder="Paste your Gemini API key"
          value={key} disabled={busy} onChange={event => setKey(event.target.value)} />
        <button type="submit" className="setw-btn-primary" disabled={busy || !key.trim()}>Save key</button>
        {editing && <button type="button" className="setw-btn" disabled={busy} onClick={() => { setKey(''); setEditing(false); }}>Cancel</button>}
      </form>}
      {status?.configured && <SettingsRow title="Gemini assistant">
        {!editing && <button type="button" className="setw-btn" disabled={busy} onClick={() => setEditing(true)}>Replace key…</button>}
        <button type="button" className="setw-btn is-danger" disabled={busy} onClick={() => { void act(async () => {
          setStatus(await window.bridge.removeGeminiSetupKey()); setKey(''); setEditing(false);
          setNotice('Saved key removed. The key still exists in Google AI Studio.');
        }); }}>Remove key</button>
        <a className="setw-btn-primary" href="#/google">Open Google Apps</a>
      </SettingsRow>}
    </SettingsCard>
    {status && !status.installed && <p className="setw-footnote">Install Gemini CLI first. <button type="button" className="setw-link" disabled={busy} onClick={() => openLink('https://geminicli.com/docs/get-started/installation/')}>Installation guide</button></p>}
    <p className="setw-footnote">Free usage depends on your Google project, model, and remaining quota. A billing-enabled project can incur charges. ByteQuay does not enable billing or create Google projects. <button type="button" className="setw-link" disabled={busy} onClick={() => openLink('https://aistudio.google.com/usage')}>Check usage in AI Studio</button></p>
  </>;
}
