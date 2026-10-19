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
import { useGoogleHost } from './host';
import { useState } from 'react';
import { CalendarDays, Mail, UserPlus } from 'lucide-react';
import { useGoogle } from './useGoogle';
import { Badge, SettingsCard, SettingsRow, Toggle } from '@bytequay/ui/settings';
import GeminiSetupCard from './GeminiSetupCard';

export default function GooglePage() {
  const { bridge } = useGoogleHost();
  const { status, error, busy, act, reload } = useGoogle(bridge);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const data = status?.snapshot;
  return <div className="setw-page">
    <h1>Google Apps</h1>
    <p className="setw-footnote">Google Apps appears in the sidebar while an account is connected.</p>
    {error && <div className="setw-error" role="alert">{error} <button type="button" className="setw-link" onClick={() => { void reload(); }}>Check again</button></div>}
    {!status ? <p role="status">Loading Google settings…</p> : <>
      <SettingsCard>
        <SettingsRow title="Workspace connector" badge={<Badge>{status.installed ? 'Installed' : 'Setup needed'}</Badge>}>
          <button type="button" className="setw-btn" disabled={busy} onClick={() => { void act(bridge.installGoogleConnector); }}>{status.installed ? 'Repair connector' : 'Install connector'}</button>
        </SettingsRow>
        <SettingsRow title={data?.email || data?.name || 'Google account'} subtitle={data ? undefined : 'Choose your account and approve access in your browser.'}>
          <button type="button" className="setw-btn-primary" disabled={busy || !status.installed} onClick={() => { void act(bridge.connectGoogle); }}>{data ? 'Reconnect' : 'Sign in with Google'}</button>
          {data && <button type="button" className="setw-btn" disabled={busy || !status.installed} onClick={() => { void act(bridge.addGoogleAccount); }}><UserPlus size={14} aria-hidden="true" />Add account</button>}
        </SettingsRow>
      </SettingsCard>
      {status.operation && <p role="status">{status.operation}… {status.operation.includes('sign-in') && 'Complete sign-in in your browser. It may take a few minutes.'} <button type="button" className="setw-link" onClick={() => { void act(bridge.cancelGoogleOperation); }}>Cancel</button></p>}
      {!status.installed && <p className="setw-footnote">Need Gemini CLI? <button type="button" className="setw-link" onClick={() => { void bridge.openExternal('https://geminicli.com/docs/get-started/installation/'); }}>Installation guide</button></p>}
      {data && <>
        <SettingsCard>
          <SettingsRow title="Gmail and Calendar">
            <a className="setw-btn" href="#/google"><Mail size={14} /><CalendarDays size={14} />Open Google Apps</a>
          </SettingsRow>
          <SettingsRow title="Let task agents read cached Google data" subtitle="Shares cached items with your chosen task model. Task conversations may retain shared content. Start a new task after enabling.">
            <Toggle label="Let task agents read cached Google data" on={data.agentAccess} disabled={busy} onChange={enabled => { void act(() => bridge.setGoogleAgentAccess(enabled)); }} />
          </SettingsRow>
          <SettingsRow title="Disconnect and clear cache">
            <button type="button" className="setw-btn is-danger" disabled={busy} onClick={() => setConfirmRemove(true)}>Disconnect…</button>
          </SettingsRow>
        </SettingsCard>
        {confirmRemove && <div className="setw-section" role="group" aria-label="Confirm Google disconnect"><p>Disconnect this account and clear its cached data?</p><button type="button" className="setw-btn is-danger" disabled={busy} onClick={() => { void act(bridge.disconnectGoogle); setConfirmRemove(false); }}>Disconnect and clear</button> <button type="button" className="setw-btn" onClick={() => setConfirmRemove(false)}>Keep connection</button></div>}
      </>}
    </>}
    <GeminiSetupCard />
  </div>;
}
