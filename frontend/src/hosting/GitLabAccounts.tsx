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
import type { HostingConnectionDto, HostingServerDto } from '../types';
import './hosting.css';

export function GitLabConnectionForm({ server, onConnected }: {
  server?: HostingServerDto;
  onConnected: (account: HostingConnectionDto) => void;
}) {
  const [url, setUrl] = useState(server?.webBaseUrl ?? 'https://gitlab.com');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return <form className="hosting-form" onSubmit={event => {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError(null);
    const webBaseUrl = url.trim().replace(/\/+$/, '');
    void window.bridge.connectHostingAccount({ provider: 'GITLAB', webBaseUrl, apiBaseUrl: server?.apiBaseUrl ?? `${webBaseUrl}/api/v4`, token: token.trim() })
      .then(account => { setToken(''); onConnected(account); })
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)))
      .finally(() => setBusy(false));
  }}>
    <label>GitLab server<input aria-label="GitLab server" type="url" required value={url} disabled={busy || server !== undefined}
      onChange={event => setUrl(event.target.value)} placeholder="https://gitlab.example.com" /></label>
    <label>Personal access token<input aria-label="GitLab personal access token" type="password" required value={token} disabled={busy}
      autoComplete="off" spellCheck={false} onChange={event => setToken(event.target.value)} /></label>
    <p>Use a token with <code>read_api</code> and <code>read_repository</code> access to connect and clone your projects. The token is stored encrypted on this Mac.</p>
    {error !== null && <p role="alert" className="setw-error">{error}</p>}
    <button type="submit" className="setw-btn-primary" disabled={busy || token.trim() === ''}>{busy ? 'Connecting…' : 'Connect GitLab'}</button>
  </form>;
}

export default function GitLabAccounts({ selected, server, onSelect }: {
  selected?: number;
  server?: HostingServerDto;
  onSelect: (account: HostingConnectionDto) => void | Promise<void>;
}) {
  const [accounts, setAccounts] = useState<HostingConnectionDto[]>([]);
  const [adding, setAdding] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    void window.bridge.listHostingAccounts().then(rows => { if (active) setAccounts(rows); })
      .catch(failure => { if (active) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  const choices = accounts.filter(account => account.instance.provider === 'GITLAB' && (server === undefined
    || account.instance.webBaseUrl === server.webBaseUrl && account.instance.apiBaseUrl === server.apiBaseUrl));
  const choose = async (account: HostingConnectionDto) => {
    setBusy(true); setError(null);
    try { await onSelect(account); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(false); }
  };
  return <div className="hosting-accounts">
    {loading ? <p role="status">Loading GitLab accounts…</p> : <>
      {choices.length > 0 && <label>GitLab account<select aria-label="GitLab account" value={selected ?? ''} disabled={busy}
        onChange={event => { const account = choices.find(row => row.account.id === Number(event.target.value)); if (account) void choose(account); }}>
        <option value="" disabled>Choose an account</option>
        {choices.map(account => <option key={account.account.id} value={account.account.id} disabled={!account.connected}>
          {account.account.login} · {account.instance.webBaseUrl}{account.connected ? '' : ' · disconnected'}
        </option>)}
      </select></label>}
      {(adding || choices.length === 0) ? <GitLabConnectionForm server={server} onConnected={account => {
        setAccounts(rows => [...rows.filter(row => row.account.id !== account.account.id), account]); setAdding(false); void choose(account);
      }} /> : <button type="button" className="setw-link" onClick={() => setAdding(true)}>Add or reconnect a GitLab account</button>}
    </>}
    {error !== null && <p className="setw-error" role="alert">{error}</p>}
  </div>;
}
