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
import RepoAvatar from '../RepoAvatar';
import ErrorDetails from '../ErrorDetails';
import { Clickable } from '../cards/Card';
import { remoteErrorMessage } from '../remoteError';
import type { HostingConnectionDto, HostingRepositoryDto, WorkspaceDto } from '../types';
import GitLabAccounts from './GitLabAccounts';

export default function GitLabWorkspaceSetup({ initialWorkspace, onAdded }: { initialWorkspace?: WorkspaceDto; onAdded: (workspace: WorkspaceDto) => void }) {
  const [account, setAccount] = useState<HostingConnectionDto | null>(null);
  const [query, setQuery] = useState(initialWorkspace ? `${initialWorkspace.selectedOwner}/${initialWorkspace.selectedRepo}` : '');
  const [repositories, setRepositories] = useState<HostingRepositoryDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const selectAccount = useCallback((value: HostingConnectionDto) => {
    setAccount(value); setRepositories([]); setLoading(true); setError(null);
  }, []);

  useEffect(() => {
    if (account === null) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setLoading(true); setError(null);
      void window.bridge.searchHostingRepositories(account.account.id, query.trim(), 30)
        .then(rows => { if (!cancelled) setRepositories(rows); })
        .catch(failure => { if (!cancelled) { setRepositories([]); setError(remoteErrorMessage(failure)); } })
        .finally(() => { if (!cancelled) setLoading(false); });
    }, 250);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [account, query]);

  const watch = async (repository: HostingRepositoryDto) => {
    if (account === null || adding !== null) return;
    if (repository.defaultBranch === null) { setError('This project has no default branch yet.'); return; }
    setAdding(repository.id); setError(null);
    try { onAdded(await window.bridge.createHostingWorkspace(account.account.id, repository.id)); }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setAdding(null); }
  };

  return <>
    <div className="hosting-watch-accounts">
      <GitLabAccounts selected={account?.account.id} server={initialWorkspace?.hostingServer ?? undefined} onSelect={selectAccount} autoSelectFirst disabled={adding !== null} />
    </div>
    {account !== null && <>
      <div className="modal__search-row">
        <input className="modal__search" aria-label="Search GitLab projects" placeholder="Search GitLab projects…" autoFocus
          value={query} disabled={adding !== null} onChange={event => setQuery(event.target.value)} />
      </div>
      {error !== null && <ErrorDetails message={error} className="modal__empty" />}
      <div className="modal__list">
        {loading ? <div className="modal__loading" role="status">Searching…</div> : repositories.map(repository => {
          const slash = repository.fullPath.lastIndexOf('/');
          return <Clickable className="modal-repo-row" key={repository.id} label={`Watch ${repository.fullPath}`} onOpen={() => { void watch(repository); }}>
            <RepoAvatar owner={repository.fullPath.slice(0, slash)} repo={repository.fullPath.slice(slash + 1)} size={28} radius={7} github={false} />
            <div className="modal-repo-row__text">
              <div className="modal-repo-row__head"><span className="modal-repo-row__name">{repository.fullPath}</span></div>
              <div className="modal-repo-row__meta">{repository.defaultBranch === null ? 'No default branch' : `branch ${repository.defaultBranch}`}</div>
            </div>
            <span className="modal-repo-row__pill">{adding === repository.id ? 'Watching…' : 'Watch'}</span>
          </Clickable>;
        })}
        {!loading && repositories.length === 0 && error === null && <div className="modal__empty">
          {query.trim() === '' ? 'No matching repositories.' : `No repository matches “${query.trim()}”.`}
        </div>}
      </div>
    </>}
  </>;
}
