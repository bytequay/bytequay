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
import { useState } from 'react';
import type { HostingConnectionDto, HostingRepositoryDto, WorkspaceDto } from '../types';
import GitLabAccounts from './GitLabAccounts';

export default function GitLabWorkspaceSetup({ initialWorkspace, onAdded }: { initialWorkspace?: WorkspaceDto; onAdded: (workspace: WorkspaceDto) => void }) {
  const [account, setAccount] = useState<HostingConnectionDto | null>(null);
  const [path, setPath] = useState(initialWorkspace ? `${initialWorkspace.selectedOwner}/${initialWorkspace.selectedRepo}` : '');
  const [repository, setRepository] = useState<HostingRepositoryDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return <div className="hosting-panel">
    <GitLabAccounts selected={account?.account.id} server={initialWorkspace?.hostingServer ?? undefined} onSelect={value => { setAccount(value); setRepository(null); setError(null); }} />
    {account !== null && <form className="hosting-form" onSubmit={event => {
      event.preventDefault(); if (busy) return;
      setBusy(true); setError(null);
      void window.bridge.resolveHostingRepository(account.account.id, path.trim()).then(setRepository)
        .catch(failure => setError(failure instanceof Error ? failure.message : String(failure))).finally(() => setBusy(false));
    }}>
      <label>Project path<input aria-label="GitLab project path" required placeholder="group/subgroup/project" value={path} disabled={busy}
        onChange={event => { setPath(event.target.value); setRepository(null); }} /></label>
      <button className="setw-btn" type="submit" disabled={busy || path.trim() === ''}>{busy ? 'Checking…' : 'Find project'}</button>
    </form>}
    {repository !== null && account !== null && <div className="hosting-form">
      <strong>{repository.fullPath}</strong><p>{account.instance.webBaseUrl} · {repository.defaultBranch ?? 'No default branch'}</p>
      <p>ByteQuay will clone this project directly using {account.account.login}. Merge-request review and delivery are not available yet.</p>
      <button type="button" className="setw-btn-primary" disabled={busy || repository.defaultBranch === null} onClick={() => {
        setBusy(true); setError(null);
        void window.bridge.createHostingWorkspace(account.account.id, repository.id).then(onAdded)
          .catch(failure => setError(failure instanceof Error ? failure.message : String(failure))).finally(() => setBusy(false));
      }}>{busy ? 'Creating workspace…' : 'Create workspace'}</button>
    </div>}
    {error !== null && <p role="alert" className="setw-error">{error}</p>}
  </div>;
}
