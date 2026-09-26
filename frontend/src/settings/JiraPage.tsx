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

import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { ExternalLink, LoaderCircle, Plus } from 'lucide-react';
import CopyLinkButton from '../CopyLinkButton';
import JiraIcon from '../jira/JiraIcon';
import { jiraAccountLabel, jiraSiteDomain, notifyJiraChanged, type JiraConnection, type JiraTokenRequest } from '../jira/contracts';
import { Badge, SettingsCard, SettingsRow, StatusDot } from './primitives';
import { settingsRoute } from './routes';

const message = (cause: unknown) => cause instanceof Error ? cause.message : String(cause);

export default function JiraPage() {
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [connections, setConnections] = useState<JiraConnection[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [tokenForm, setTokenForm] = useState<JiraConnection | 'new' | null>(null);
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
  const saveToken = async (request: JiraTokenRequest) => {
    listRequest.current += 1; setBusy('token'); setError(null);
    try {
      const connection = tokenForm === 'new' || tokenForm === null ? await window.bridge.connectJiraToken(request) : await window.bridge.replaceJiraToken(tokenForm.id, request);
      if (live.current) { changed(connection, true); setTokenForm(null); }
    }
    finally { if (live.current) setBusy(null); }
  };
  return <div className="setw-page jira-settings">
    <h1><JiraIcon size={26} />Jira</h1>
    <p className="jira-settings__intro">Connect your Atlassian accounts to see sprint tickets, update fields, and start development in ByteQuay.</p>
    {error && <div className="setw-error" role="alert">{error}<button type="button" className="setw-link" onClick={load}>Retry</button></div>}
    <div className="setw-section jira-settings__toolbar"><span className="setw-section-title">Accounts</span>{configured && <button type="button" className="setw-btn" disabled={busy !== null || tokenForm !== null} onClick={() => { void signIn(); }}>{busy === 'add' ? 'Opening sign-in…' : 'Sign in with browser'}</button>}<button type="button" className="setw-btn-primary" disabled={busy !== null || tokenForm !== null} onClick={() => { setError(null); setTokenForm('new'); }}><Plus size={14} />Add account</button></div>
    {tokenForm !== null && <JiraTokenForm key={tokenForm === 'new' ? 'new' : tokenForm.id} connection={tokenForm === 'new' ? null : tokenForm} busy={busy !== null} onSave={saveToken} onCancel={() => setTokenForm(null)} />}
    {connections === null ? <div className="setw-row-empty">Loading accounts…</div> : connections.length === 0 ? tokenForm === null && <SettingsCard><div className="setw-row-empty">No Jira accounts connected. Add your Jira site and personal API token to get started.</div></SettingsCard>
      : connections.map(connection => <JiraAccountCard key={connection.id} connection={connection} busy={busy !== null || tokenForm !== null} onChanged={changed} onReconnect={() => { if (connection.authType === 'api_token') { setError(null); setTokenForm(connection); } else void signIn(connection.id); }} onRemove={() => { void remove(connection.id); }} />)}
    <p className="setw-footnote">Removing an account keeps its development task history.</p>
    <p className="setw-footnote">Jira sprint and backlog lists refresh every 10 minutes. <a href={settingsRoute('general')}>Manage quiet hours in General</a>.</p>
  </div>;
}

function JiraTokenForm({ connection, busy, onSave, onCancel }: { connection: JiraConnection | null; busy: boolean; onSave: (request: JiraTokenRequest) => Promise<void>; onCancel: () => void }) {
  const id = useId();
  const [siteUrl, setSiteUrl] = useState(connection?.sites[0]?.url ?? '');
  const [email, setEmail] = useState(connection?.tokenEmail ?? '');
  const [token, setToken] = useState('');
  const [error, setError] = useState<string | null>(null);
  const submitting = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const submit = async () => {
    if (submitting.current) return;
    submitting.current = true; setError(null);
    const request = { siteUrl: siteUrl.trim(), email: email.trim(), token: token.trim() };
    setToken('');
    try { await onSave(request); }
    catch (cause) { if (mounted.current) setError(message(cause)); }
    finally { submitting.current = false; }
  };
  return <SettingsCard><form className="jira-token-form" aria-labelledby={`${id}-title`} onSubmit={event => { event.preventDefault(); void submit(); }}>
    <h2 id={`${id}-title`}>{connection ? 'Replace API token' : 'Connect with an API token'}</h2>
    <label htmlFor={`${id}-site`}>Jira site</label>
    <input id={`${id}-site`} className="setw-input" type="url" placeholder="https://your-team.atlassian.net" pattern="https://[a-zA-Z0-9\-]+\.atlassian\.net/?" required autoFocus={!connection} readOnly={connection !== null} disabled={busy} value={siteUrl} onChange={event => setSiteUrl(event.target.value)} />
    <label htmlFor={`${id}-email`}>Atlassian account email</label>
    <input id={`${id}-email`} className="setw-input" type="email" autoComplete="username" required disabled={busy} value={email} onChange={event => setEmail(event.target.value)} />
    <div className="jira-token-form__label"><label htmlFor={`${id}-token`}>API token</label><button type="button" className="setw-link" title="Create a personal API token in your Atlassian account" onClick={() => { void window.bridge.openExternal('https://id.atlassian.com/manage-profile/security/api-tokens').catch(cause => setError(message(cause))); }}>Create API token <ExternalLink size={12} aria-hidden="true" /></button></div>
    <input id={`${id}-token`} className="setw-input setw-mono" type="password" autoComplete="new-password" spellCheck={false} required autoFocus={connection !== null} disabled={busy} value={token} onChange={event => setToken(event.target.value)} aria-describedby={`${id}-note`} />
    <p id={`${id}-note`} className="setw-footnote">Your token is stored encrypted on this Mac. It does not refresh automatically; replace it here when it expires.</p>
    <details className="jira-token-form__permissions"><summary>Required token permissions</summary><p>Choose <strong>Create API token with scopes</strong> in Atlassian, select <strong>Jira</strong>, then add these permissions:</p><ul>{['read:jira-user', 'read:jira-work', 'write:jira-work', 'read:board-scope:jira-software', 'read:project:jira', 'read:sprint:jira-software', 'write:sprint:jira-software', 'read:issue-details:jira', 'read:jql:jira'].map(scope => <li key={scope}><code>{scope}</code></li>)}</ul></details>
    {error && <div className="setw-error" role="alert">{error}</div>}
    <div className="jira-token-form__actions"><button type="button" className="setw-btn" disabled={busy} onClick={() => { setToken(''); onCancel(); }}>Cancel</button><button type="submit" className="setw-btn-primary" disabled={busy || !siteUrl.trim() || !email.trim() || !token.trim()}>{busy ? 'Connecting…' : connection ? 'Save replacement' : 'Connect account'}</button></div>
  </form></SettingsCard>;
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
      <SettingsRow title={<><span className="jira-account__avatar">{account?.displayName.trim().split(/\s+/).map(part => part[0]).filter(Boolean).slice(0, 2).join('') || <JiraIcon size={20} />}</span>{label}</>} subtitle={connection.tokenEmail ?? account?.email ?? (account ? undefined : 'Identity will appear after sign-in')} badge={<><span className="jira-account__status" role="status"><StatusDot tone={pending ? 'idle' : connection.state === 'connected' ? 'ok' : 'warn'} />{status}</span>{connection.authType === 'api_token' && <Badge>API token</Badge>}</>}>
        <button type="button" className="setw-btn" disabled={busy || checking} onClick={onReconnect}>{connection.authType === 'api_token' ? 'Replace token' : 'Reconnect'}</button>
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
