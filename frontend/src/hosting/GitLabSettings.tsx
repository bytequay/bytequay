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
import Avatar from '../Avatar';
import ErrorDetails from '../ErrorDetails';
import { remoteErrorMessage } from '../remoteError';
import { SettingsCard, SettingsRow, StatusDot } from '../settings/primitives';
import type { HostingConnectionDto } from '../types';
import { clearGitLabPermissions, GitLabConnectionForm, GitLabPermissions } from './GitLabAccounts';

export default function GitLabSettings() {
  const [accounts, setAccounts] = useState<HostingConnectionDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [disconnecting, setDisconnecting] = useState<number | null>(null);
  const [checking, setChecking] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void window.bridge.listHostingAccounts()
      .then(rows => { if (active) setAccounts(rows.filter(row => row.instance.provider === 'GITLAB')); })
      .catch(failure => { if (active) setError(remoteErrorMessage(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const connected = (account: HostingConnectionDto) => {
    setAccounts(rows => rows.some(row => row.account.id === account.account.id)
      ? rows.map(row => row.account.id === account.account.id ? account : row)
      : [...rows, account]);
    setEditing(null); setError(null);
  };
  const disconnect = async (id: number) => {
    setDisconnecting(id); setError(null);
    try {
      await window.bridge.disconnectHostingAccount(id);
      setAccounts(rows => rows.map(row => row.account.id === id ? { ...row, connected: false, permissions: null } : row));
    } catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setDisconnecting(null); }
  };
  const checkPermissions = async (id: number) => {
    setChecking(id); setError(null);
    setAccounts(rows => rows.map(row => row.account.id === id ? { ...row, permissions: null } : row));
    try {
      const permissions = await window.bridge.checkHostingPermissions(id);
      setAccounts(rows => rows.map(row => row.account.id === id ? { ...row, permissions } : row));
    } catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setChecking(null); }
  };

  return <div className="setw-page"><h1>GitLab</h1>
    <div className="setw-section"><span className="setw-section-title">Accounts</span></div>
    {error !== null && <ErrorDetails message={error} className="setw-error" />}
    {loading ? <p role="status">Loading GitLab accounts…</p> : <>
      {accounts.map(account => <SettingsCard key={account.account.id}>
        <SettingsRow title={<>
          <Avatar login={account.account.login} size={30} allowGitHubFallback={false} />
          <span>{account.account.login}</span><StatusDot tone={account.connected ? 'ok' : 'idle'} />
        </>} subtitle={`${account.instance.webBaseUrl} · ${account.connected ? 'Connected' : 'Disconnected'}`}>
          {editing !== account.account.id && <>
            <button type="button" className="setw-btn" disabled={disconnecting !== null || checking !== null}
              onClick={() => setEditing(account.account.id)}>{account.connected ? 'Update…' : 'Reconnect'}</button>
            {account.connected && <button type="button" className="setw-btn is-danger" disabled={disconnecting !== null || checking !== null}
              onClick={() => { void disconnect(account.account.id); }}>{disconnecting === account.account.id ? 'Disconnecting…' : 'Disconnect'}</button>}
          </>}
        </SettingsRow>
        {account.connected && <SettingsRow title="API permissions" subtitle={checking === account.account.id
          ? <p role="status">Checking permissions…</p> : <GitLabPermissions permissions={account.permissions} />}>
          <button type="button" className="setw-btn" disabled={disconnecting !== null || checking !== null || editing !== null}
            onClick={() => { void checkPermissions(account.account.id); }}>Check permissions</button>
        </SettingsRow>}
        {editing === account.account.id && <div className="setw-row hosting-account-form">
          <GitLabConnectionForm server={account.instance} onConnecting={target => setAccounts(rows => clearGitLabPermissions(rows, target))} onConnected={connected} />
          <button type="button" className="setw-btn" onClick={() => setEditing(null)}>Cancel</button>
        </div>}
      </SettingsCard>)}
      {editing === 'new' || accounts.length === 0 && error === null ? <SettingsCard>
        <div className="setw-row hosting-account-form">
          <GitLabConnectionForm onConnecting={target => setAccounts(rows => clearGitLabPermissions(rows, target))} onConnected={connected} />
          {accounts.length > 0 && <button type="button" className="setw-btn" onClick={() => setEditing(null)}>Cancel</button>}
        </div>
      </SettingsCard> : <button type="button" className="setw-btn" disabled={disconnecting !== null || checking !== null} onClick={() => setEditing('new')}>Add GitLab account</button>}
    </>}
  </div>;
}
