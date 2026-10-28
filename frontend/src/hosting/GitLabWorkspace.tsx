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
import { settingsRoute } from '../settings/routes';
import type { WorkspaceDto } from '../types';
import GitLabAccounts from './GitLabAccounts';

export function WorkspaceHostingAccount({ workspace }: { workspace: WorkspaceDto }) {
  const [accountId, setAccountId] = useState<number>();
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    void window.bridge.getWorkspaceHosting(workspace.id).then(binding => { if (active) setAccountId(binding?.accountId); })
      .catch(failure => { if (active) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { active = false; };
  }, [workspace.id]);
  return <div className="setw-page">
    <h1>GitLab account</h1><p>{workspace.monitoredOwner}/{workspace.monitoredRepo}</p>
    <p className="setw-footnote">Choose the account used to read and clone this project.</p>
    <GitLabAccounts selected={accountId} server={workspace.hostingServer ?? undefined} onSelect={async account => {
      const binding = await window.bridge.setWorkspaceHosting(workspace.id, account.account.id);
      setAccountId(binding.accountId); setError(null);
    }} />
    {error !== null && <p className="setw-error" role="alert">{error}</p>}
  </div>;
}

export default function GitLabWorkspace({ workspace, onWorkspaceChanged }: {
  workspace: WorkspaceDto; onWorkspaceChanged: (workspace: WorkspaceDto) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (workspace.lastCodeSyncAt !== null) return;
    const timer = window.setTimeout(() => {
      void window.bridge.getWorkspace(workspace.id).then(onWorkspaceChanged)
        .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [workspace, onWorkspaceChanged]);
  const path = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const webUrl = `${workspace.hostingServer?.webBaseUrl}/${path.split('/').map(encodeURIComponent).join('/')}`;
  return <div className="ws-tab-page hosting-panel">
    <h1>{workspace.name}</h1><p>{workspace.hostingServer?.webBaseUrl} · {path}</p>
    <div className="hosting-form"><strong>{workspace.lastCodeSyncAt === null ? 'Local checkout not ready' : 'Local checkout ready'}</strong>
      <p>{workspace.localRepositoryPath}</p>
      <button type="button" className="setw-btn" disabled={busy} onClick={() => {
        setBusy(true); setError(null);
        void window.bridge.syncWorkspace(workspace.id).then(onWorkspaceChanged)
          .catch(failure => setError(failure instanceof Error ? failure.message : String(failure))).finally(() => setBusy(false));
      }}>{busy ? 'Syncing…' : 'Sync workspace'}</button>
      <a href={settingsRoute('ws-token', workspace.id)}>Choose GitLab account</a>
      <button type="button" className="setw-btn" onClick={() => { void window.bridge.openInAppBrowser(webUrl); }}>Open project in GitLab</button>
    </div>
    <p>GitLab workspace setup and code sync are available. Merge-request review, issues, agent tasks and delivery in ByteQuay are not available yet.</p>
    {error !== null && <p className="setw-error" role="alert">{error}</p>}
  </div>;
}
