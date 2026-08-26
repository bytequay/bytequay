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
import { useEffect, useRef, useState } from 'react';
import Avatar from './Avatar';
import type {
  RepoRefDto,
  RepositorySearchResultDto,
  WatchRepositoryPreviewDto,
  WorkspaceDto,
} from './types';

type Props = {
  onAdded: (workspace: WorkspaceDto) => void;
  onOpenWorkspace: (workspaceId: string) => void;
  onClose: () => void;
};

function fullName(repo: RepoRefDto): string {
  return `${repo.owner}/${repo.repo}`;
}

type RoutingRow = { key: string; value: string };

function routingRows(
  preview: WatchRepositoryPreviewDto,
  linked: boolean,
): RoutingRow[] {
  const base = `${fullName(preview.pullRequestBase)} · ${preview.defaultBranch}`;
  if (linked) {
    return [
      { key: 'Cloned for your work', value: fullName(preview.selectedRepository) },
      { key: 'Followed for updates', value: fullName(preview.monitoredRepository) },
      { key: 'New branches start from', value: preview.developmentBase },
      { key: 'Pull requests open against', value: base },
    ];
  }
  return [
    { key: 'New branches start from', value: preview.developmentBase },
    { key: 'Work branches push to', value: preview.pushWritable ? preview.pushRemote : 'Read-only for you' },
    { key: 'Pull requests open against', value: base },
  ];
}

function summaryFor(preview: WatchRepositoryPreviewDto, linked: boolean): string {
  const name = fullName(preview.selectedRepository);
  if (linked) {
    return `ByteQuay clones the fork for local work and follows ${fullName(preview.monitoredRepository)}`
      + ' for commits, issues and pull requests.';
  }
  if (preview.topology === 'FORK') {
    return `GitHub reports this as a fork, and it starts unlinked — everything uses ${name} until you`
      + ' choose an upstream in workspace settings.';
  }
  return `Everything uses ${name} — code, issues, pull requests and pushes.`
    + ' It appears in Workspaces the moment you watch it.';
}

export default function AddRepoModal({ onAdded, onOpenWorkspace, onClose }: Props) {
  const [repos, setRepos] = useState<RepositorySearchResultDto[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<RepositorySearchResultDto | null>(null);
  const [preview, setPreview] = useState<WatchRepositoryPreviewDto | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    const trimmed = query.trim();
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError(null);
      window.bridge.searchRepositories(trimmed, 30)
        .then(results => { if (!cancelled) setRepos(results); })
        .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
        .finally(() => { if (!cancelled) setLoading(false); });
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [query]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      if (selected) {
        setSelected(null);
        setPreview(null);
        setError(null);
      } else {
        onClose();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose, selected]);

  /** An already-watched repository goes straight to its workspace — there is
   *  nothing to confirm and nothing to clone a second time. */
  const pick = (repo: RepositorySearchResultDto) => {
    if (repo.existingWorkspaceId !== null) {
      onClose();
      onOpenWorkspace(repo.existingWorkspaceId);
      return;
    }
    void choose(repo);
  };

  const choose = async (repo: RepositorySearchResultDto) => {
    setSelected(repo);
    setPreview(null);
    setPreviewLoading(true);
    setError(null);
    try {
      setPreview(await window.bridge.previewWatch(repo.id));
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setPreviewLoading(false);
    }
  };

  const add = async () => {
    if (!selected || !preview) return;
    setAdding(true);
    setError(null);
    try {
      const workspace = await window.bridge.watchRepository({
        selectedRepositoryId: selected.id,
        previewFingerprint: preview.fingerprint,
      });
      onAdded(workspace);
      onClose();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      try {
        setPreview(await window.bridge.previewWatch(selected.id));
      }
      catch { /* Keep the original confirmation error visible. */ }
    }
    finally {
      setAdding(false);
    }
  };

  const back = () => {
    setSelected(null);
    setPreview(null);
    setError(null);
    window.setTimeout(() => inputRef.current?.focus());
  };

  const linked = preview !== null
    && preview.topology === 'FORK'
    && preview.upstreamRepository !== null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={event => event.stopPropagation()}>
        {!selected && (
          <>
            <div className="modal__header">
              <h2 className="modal__title">Watch a repository</h2>
              <button className="modal__close" onClick={onClose} type="button">✕</button>
            </div>
            <div className="modal__search-row">
              <input
                ref={inputRef}
                className="modal__search"
                value={query}
                placeholder="Search your GitHub repositories…"
                onChange={event => setQuery(event.target.value)}
              />
            </div>
            {error && <div className="modal__empty">{error}</div>}
            <div className="modal__list">
              {loading ? <div className="modal__loading">Searching…</div> : repos.map(repo => (
                <div
                  className="modal-repo-row"
                  key={repo.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => pick(repo)}
                  onKeyDown={event => {
                    if (event.target !== event.currentTarget) return;
                    if (event.key !== 'Enter' && event.key !== ' ') return;
                    event.preventDefault();
                    pick(repo);
                  }}
                >
                  <Avatar
                    login={repo.repository.owner}
                    size={28}
                    className="modal-repo-row__avatar"
                  />
                  <div className="modal-repo-row__text">
                    <div className="modal-repo-row__head">
                      <span className="modal-repo-row__name">{repo.fullName}</span>
                      {repo.existingWorkspaceId !== null ? (
                        <span className="modal-chip is-watched">already watched</span>
                      ) : repo.fork && repo.upstream ? (
                        <span className="modal-chip is-fork">⑂ fork of {fullName(repo.upstream)}</span>
                      ) : null}
                    </div>
                    <div className="modal-repo-row__meta">
                      {repo.visibility} · branch {repo.defaultBranch}
                      {repo.description ? ` · ${repo.description}` : ''}
                    </div>
                  </div>
                  <span
                    className={`modal-repo-row__pill${repo.existingWorkspaceId !== null ? ' is-open' : ''}`}
                  >
                    {repo.existingWorkspaceId !== null ? 'Open' : 'Watch'}
                  </span>
                </div>
              ))}
              {!loading && repos.length === 0 && (
                <div className="modal__empty">
                  {query.trim() === ''
                    ? 'No matching repositories.'
                    : `No repository matches “${query.trim()}”.`}
                </div>
              )}
            </div>
          </>
        )}

        {selected && (
          <>
            <div className="modal__header">
              <button className="modal__back" onClick={back} type="button">‹ Search</button>
              <button className="modal__close" onClick={onClose} type="button">✕</button>
            </div>
            {previewLoading && <div className="modal__loading">Checking repository routing…</div>}
            {error && <div className="modal__empty">{error}</div>}
            {preview && (
              <div className="modal-preview">
                <div className="modal-preview__title-row">
                  <Avatar
                    login={preview.selectedRepository.owner}
                    size={32}
                    className="modal-preview__avatar"
                  />
                  <div>
                    <div className="modal-preview__name-row">
                      <span className="modal-preview__name">{fullName(preview.selectedRepository)}</span>
                      <span className={`modal-chip${preview.topology === 'FORK' ? ' is-fork' : ' is-direct'}`}>
                        {preview.topology === 'FORK' && selected.upstream
                          ? `⑂ fork of ${fullName(selected.upstream)}`
                          : preview.topology === 'FORK' ? '⑂ fork' : 'direct repository'}
                      </span>
                    </div>
                    <div className="modal-preview__sub">
                      {preview.visibility} · default branch {preview.defaultBranch}
                    </div>
                  </div>
                </div>

                {!preview.pushWritable && (
                  <div className="modal-preview__warning" role="status">
                    You can’t push to {fullName(preview.selectedRepository)}. You can still browse
                    commits, cherry-pick, and work locally; ByteQuay won’t push somewhere else.
                  </div>
                )}

                <div className="modal-routing">
                  <div className="modal-routing__head">
                    {linked ? 'HOW THIS FORK WILL WORK' : 'HOW THIS REPOSITORY WILL WORK'}
                  </div>
                  <div className="modal-routing__grid">
                    {routingRows(preview, linked).map(row => (
                      <div className="modal-routing__row" key={row.key}>
                        <span>{row.key}</span>
                        <b>{row.value}</b>
                      </div>
                    ))}
                  </div>
                  <p className="modal-routing__summary">{summaryFor(preview, linked)}</p>
                </div>

                <div className="modal-preview__actions">
                  <button type="button" onClick={back}>Back</button>
                  <button
                    className="modal-preview__confirm"
                    type="button"
                    disabled={adding}
                    onClick={() => { void add(); }}
                  >
                    {adding
                      ? 'Watching…'
                      : preview.topology === 'FORK' ? 'Watch fork' : 'Watch repository'}
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
