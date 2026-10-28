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
import { useCallback, useEffect, useState } from 'react';
import type { GitLabCliStatusDto, HostingConnectionDto, HostingServerDto } from '../types';
import '../css/settings-window.css';
import './hosting.css';

export function GitLabConnectionForm({ server, onConnected }: {
  server?: HostingServerDto;
  onConnected: (account: HostingConnectionDto) => void;
}) {
  const [url, setUrl] = useState(server?.webBaseUrl ?? 'https://gitlab.com');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState<'install' | 'cli' | 'token' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cliStatus, setCliStatus] = useState<GitLabCliStatusDto | null>(null);
  const [checking, setChecking] = useState(true);
  const checkCli = useCallback(async () => {
    setChecking(true); setError(null);
    try { setCliStatus(await window.bridge.getGitLabCliStatus()); }
    catch (failure) { setCliStatus(null); setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setChecking(false); }
  }, []);
  useEffect(() => { void checkCli(); }, [checkCli]);
  const connect = async (cli: boolean) => {
    if (busy !== null) return;
    setBusy(cli ? 'cli' : 'token'); setError(null);
    const webBaseUrl = url.trim().replace(/\/+$/, '');
    const target: HostingServerDto = { provider: 'GITLAB', webBaseUrl, apiBaseUrl: server?.apiBaseUrl ?? `${webBaseUrl}/api/v4` };
    try {
      if (cli && !cliStatus?.ready) {
        setBusy('install');
        const installed = await window.bridge.installGitLabCli();
        setCliStatus(installed);
        if (!installed.ready) throw new Error('GitLab CLI is not ready. Check again or use a personal access token.');
        setBusy('cli');
      }
      const account = await (cli ? window.bridge.connectHostingCliAccount(target) : window.bridge.connectHostingAccount({ ...target, token: token.trim() }));
      setToken(''); onConnected(account);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(null); }
  };
  const openTokenPage = async () => {
    setError(null);
    try {
      const target = new URL(url.trim());
      if (!['https:', 'http:'].includes(target.protocol) || target.username || target.password || target.search || target.hash) {
        throw new Error('Enter a GitLab HTTP or HTTPS server URL without credentials, query parameters, or a fragment.');
      }
      target.pathname = `${target.pathname.replace(/\/+$/, '')}/-/user_settings/personal_access_tokens`;
      target.search = new URLSearchParams({ name: 'ByteQuay', scopes: 'api' }).toString();
      await window.bridge.openExternal(target.href);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
  };
  return <form className="hosting-form" onSubmit={event => {
    event.preventDefault();
    void connect(false);
  }}>
    <label>GitLab server<input aria-label="GitLab server" type="url" required value={url} disabled={busy !== null || server !== undefined}
      onChange={event => setUrl(event.target.value)} placeholder="https://gitlab.example.com" /></label>
    <section className="hosting-cli-setup" aria-label="GitLab sign-in setup">
      <h2>Browser sign-in</h2>
      {checking && <p role="status">Checking…</p>}
      {cliStatus !== null && (cliStatus.ready || cliStatus.homebrewAvailable) &&
        <button type="button" className="setw-btn-primary" disabled={busy !== null || checking} onClick={() => { void connect(true); }}>
          {busy === 'install' ? 'Installing GitLab CLI…' : busy === 'cli' ? 'Waiting for GitLab…' : cliStatus.ready ? 'Sign in with GitLab' : 'Install and sign in'}
        </button>
      }
      {cliStatus !== null && !cliStatus.ready && !cliStatus.homebrewAvailable && <>
        <p>Install Homebrew first, then check again. You can also connect with a personal access token below.</p>
        <button type="button" className="setw-btn" disabled={busy !== null || checking} onClick={() => {
          void window.bridge.openExternal('https://brew.sh').catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
        }}>Open Homebrew setup</button>
      </>}
      <button type="button" className="setw-link" disabled={busy !== null || checking} onClick={() => { void checkCli(); }}>Check again</button>
    </section>
    <label>Or use a personal access token<input aria-label="GitLab personal access token" type="password" required value={token} disabled={busy !== null}
      autoComplete="off" spellCheck={false} onChange={event => setToken(event.target.value)} /></label>
    <button type="button" className="setw-link" disabled={busy !== null} onClick={() => { void openTokenPage(); }}>Create access token</button>
    <p>The shortcut preselects the required <code>api</code> permission. If your server does not preselect it, select it before generating the token.
      The token is stored encrypted on this Mac.</p>
    {error !== null && <p role="alert" className="setw-error">{error}</p>}
    <button type="submit" className="setw-btn" disabled={busy !== null || token.trim() === ''}>{busy === 'token' ? 'Connecting…' : 'Connect GitLab'}</button>
  </form>;
}

export default function GitLabAccounts({ selected, server, onSelect, autoSelectFirst = false, disabled = false }: {
  selected?: number;
  server?: HostingServerDto;
  autoSelectFirst?: boolean;
  disabled?: boolean;
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
  useEffect(() => {
    if (!autoSelectFirst || selected !== undefined) return;
    const first = accounts.find(account => account.connected && account.instance.provider === 'GITLAB' && (server === undefined
      || account.instance.webBaseUrl === server.webBaseUrl && account.instance.apiBaseUrl === server.apiBaseUrl));
    if (first) void Promise.resolve(onSelect(first)).catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
  }, [accounts, autoSelectFirst, selected, server, onSelect]);
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
      {choices.length > 0 && <label>GitLab account<select aria-label="GitLab account" value={selected ?? ''} disabled={busy || disabled}
        onChange={event => { const account = choices.find(row => row.account.id === Number(event.target.value)); if (account) void choose(account); }}>
        <option value="" disabled>Choose an account</option>
        {choices.map(account => <option key={account.account.id} value={account.account.id} disabled={!account.connected}>
          {account.account.login} · {account.instance.webBaseUrl}{account.connected ? '' : ' · disconnected'}
        </option>)}
      </select></label>}
      {(adding || choices.length === 0) ? <GitLabConnectionForm server={server} onConnected={account => {
        setAccounts(rows => [...rows.filter(row => row.account.id !== account.account.id), account]); setAdding(false); void choose(account);
      }} /> : <button type="button" className="setw-link" disabled={disabled} onClick={() => setAdding(true)}>Add or reconnect a GitLab account</button>}
    </>}
    {error !== null && <p className="setw-error" role="alert">{error}</p>}
  </div>;
}
