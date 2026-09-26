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

import { useCallback, useEffect, useRef, useState } from 'react';
import { ExternalLink, LoaderCircle, Plus } from 'lucide-react';
import CopyLinkButton from '../CopyLinkButton';
import JiraIcon from '../jira/JiraIcon';
import { jiraAccountLabel, jiraSiteDomain, notifyJiraChanged, type JiraConnection } from '../jira/contracts';
import { Badge, SettingsCard, SettingsRow, StatusDot } from './primitives';

const message = (cause: unknown) => cause instanceof Error ? cause.message : String(cause);

export default function JiraPage() {
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [connections, setConnections] = useState<JiraConnection[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const live = useRef(true);
  const listRequest = useRef(0);
  const reloadConnections = useCallback(() => {
    const request = ++listRequest.current;
    void window.bridge.listJiraConnections().then(value => { if (live.current && request === listRequest.current) setConnections(value); }).catch(cause => { if (live.current && request === listRequest.current) setError(message(cause)); });
  }, []);
  const load = useCallback(() => {
    setError(null);
    void window.bridge.getJiraConfig().then(value => { if (live.current) setConfigured(value.configured); }).catch(cause => { if (live.current) setError(message(cause)); });
    reloadConnections();
  }, [reloadConnections]);
  useEffect(() => { live.current = true; load(); return () => { live.current = false; }; }, [load]);
  const changed = useCallback((connection: JiraConnection, add = false) => {
    if (!live.current) return;
    setConnections(values => values?.some(value => value.id === connection.id) ? values.map(value => value.id === connection.id ? connection : value) : add ? [...(values ?? []), connection] : values);
    notifyJiraChanged();
    if (connection.state !== 'pending') reloadConnections();
  }, [reloadConnections]);
  const signIn = async (id?: string) => {
    listRequest.current += 1; setBusy(id ?? 'add'); setError(null);
    try {
      const connection = id === undefined ? await window.bridge.connectJira() : await window.bridge.reconnectJira(id);
      if (!live.current) return;
      changed(connection, true);
      if (connection.authorizationUrl) await window.bridge.openExternal(connection.authorizationUrl);
    }
    catch (cause) { if (live.current) setError(message(cause)); }
    finally { if (live.current) setBusy(null); }
  };
  const remove = async (id: string) => {
    listRequest.current += 1; setBusy(id); setError(null);
    try { await window.bridge.disconnectJira(id); if (live.current) { setConnections(values => values?.filter(value => value.id !== id) ?? []); reloadConnections(); notifyJiraChanged(); } }
    catch (cause) { if (live.current) setError(message(cause)); }
    finally { if (live.current) setBusy(null); }
  };
  return <div className="setw-page jira-settings">
    <h1><JiraIcon size={26} />Jira</h1>
    <p className="jira-settings__intro">Connect your Atlassian accounts to see sprint tickets, update fields, and start development in ByteQuay.</p>
    {error && <div className="setw-error" role="alert">{error}<button type="button" className="setw-link" onClick={load}>Retry</button></div>}
    {configured === false && <div className="jira-settings__notice" role="status">Jira sign-in is not available in this installation yet. Your ByteQuay administrator needs to enable it before you can add an account.</div>}
    <div className="setw-section"><span className="setw-section-title">Accounts</span><button type="button" className="setw-btn-primary" disabled={configured !== true || busy !== null} onClick={() => { void signIn(); }}><Plus size={14} />{busy === 'add' ? 'Opening sign-in…' : 'Add account'}</button></div>
    {connections === null ? <div className="setw-row-empty">Loading accounts…</div> : connections.length === 0 ? <SettingsCard><div className="setw-row-empty">No Jira accounts connected. Sign in using your browser to get started.</div></SettingsCard>
      : connections.map(connection => <JiraAccountCard key={connection.id} connection={connection} busy={busy !== null} onChanged={changed} onReconnect={() => { void signIn(connection.id); }} onRemove={() => { void remove(connection.id); }} />)}
    <p className="setw-footnote">Sign-in opens in your browser. ByteQuay checks automatically while this page is open. Removing an account keeps its development task history.</p>
  </div>;
}

function JiraAccountCard({ connection, busy, onChanged, onReconnect, onRemove }: { connection: JiraConnection; busy: boolean; onChanged: (value: JiraConnection) => void; onReconnect: () => void; onRemove: () => void }) {
  const [pollError, setPollError] = useState<string | null>(null);
  const [paused, setPaused] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [checking, setChecking] = useState(false);
  const checkActive = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const changed = useRef(onChanged);
  changed.current = onChanged;
  const pending = connection.state === 'pending';
  useEffect(() => {
    setPollError(null); setPaused(false);
    if (!pending) return;
    let active = true;
    let timer: number | undefined;
    let delay = 2_000;
    const deadline = Date.now() + 10 * 60_000;
    const poll = async () => {
      if (!active) return;
      if (Date.now() >= deadline) { setPaused(true); return; }
      try {
        const value = await window.bridge.refreshJiraConnection(connection.id);
        if (!active) return;
        setPollError(null); changed.current(value);
        if (value.state !== 'pending') return;
      }
      catch (cause) { if (active) setPollError(message(cause)); }
      if (active) { delay = Math.min(delay * 2, 15_000); timer = window.setTimeout(() => { void poll(); }, Math.min(delay, Math.max(0, deadline - Date.now()))); }
    };
    timer = window.setTimeout(() => { void poll(); }, delay);
    return () => { active = false; window.clearTimeout(timer); };
  }, [connection.id, connection.authorizationUrl, pending]);
  const check = async () => {
    if (checkActive.current) return;
    checkActive.current = true; setChecking(true); setPollError(null);
    try { const value = await window.bridge.refreshJiraConnection(connection.id); if (mounted.current) onChanged(value); }
    catch (cause) { if (mounted.current) setPollError(message(cause)); }
    finally { checkActive.current = false; if (mounted.current) setChecking(false); }
  };
  const account = connection.account;
  const label = account?.displayName ?? (pending ? 'Signing in to Atlassian' : 'Jira account');
  const status = pending ? 'Waiting for sign-in' : connection.state === 'connected' ? 'Connected' : 'Needs attention';
  return <section className="jira-account" aria-label={`${label} Jira connection`} data-connection-id={connection.id}>
    <SettingsCard>
      <SettingsRow title={<><span className="jira-account__avatar">{account?.displayName.trim().split(/\s+/).map(part => part[0]).filter(Boolean).slice(0, 2).join('') || <JiraIcon size={20} />}</span>{label}</>} subtitle={account?.email ?? (account ? undefined : 'Identity will appear after sign-in')} badge={<span className="jira-account__status" role="status"><StatusDot tone={pending ? 'idle' : connection.state === 'connected' ? 'ok' : 'warn'} />{status}</span>}>
        <button type="button" className="setw-btn" disabled={busy || checking} onClick={onReconnect}>Reconnect</button>
        <button type="button" className="setw-btn is-quiet" disabled={busy || checking} onClick={() => setConfirmRemove(value => !value)}>Remove</button>
      </SettingsRow>
      {connection.sites.length > 0 && <div className="jira-account__sites" aria-label={`Sites for ${jiraAccountLabel(connection)}`}>{connection.sites.map(site => <div key={site.id}><strong>{site.name}</strong><span>{jiraSiteDomain(site)}</span></div>)}</div>}
      {pending && <div className="jira-account__pending">
        <p><LoaderCircle size={14} aria-hidden="true" />{paused ? 'Automatic checking paused. Finish sign-in in your browser, then check its status.' : 'Finish sign-in in your browser. This account will update automatically.'}</p>
        {connection.pairingCode && <div className="jira-account__pairing"><span>Enter this code on the sign-in page</span><strong>{connection.pairingCode}</strong><CopyLinkButton value={connection.pairingCode} label="pairing code" /></div>}
        <div className="jira-account__actions">{connection.authorizationUrl && <button type="button" className="setw-btn" onClick={() => { void window.bridge.openExternal(connection.authorizationUrl ?? '').catch(cause => setPollError(message(cause))); }}>Reopen sign-in <ExternalLink size={13} /></button>}{paused && <button type="button" className="setw-btn" disabled={checking || busy} onClick={() => { void check(); }}>Check status</button>}</div>
      </div>}
      {connection.duplicateOf && <div className="jira-account__notice"><Badge tone="warn">Already connected</Badge> This account and these sites are also connected in another row. Keep the connection you already use, or remove this additional one.</div>}
      {(connection.error || pollError) && <div className="jira-account__error" role="alert">{connection.error ?? pollError}{pending && pollError && !paused && ' ByteQuay will check again automatically.'}</div>}
      {confirmRemove && <div className="jira-account__remove"><p>Remove this Jira account from ByteQuay? Existing tasks and their saved ticket context will remain.</p><div><button type="button" className="setw-btn is-danger" disabled={busy || checking} onClick={onRemove}>Remove connection</button><button type="button" className="setw-btn" disabled={busy || checking} onClick={() => setConfirmRemove(false)}>Keep connection</button></div></div>}
    </SettingsCard>
  </section>;
}
