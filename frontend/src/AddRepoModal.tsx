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

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={event => event.stopPropagation()}>
        <div className="modal__header">
          <h2 className="modal__title">Watch a repository</h2>
          <button className="modal__close" onClick={onClose} type="button">✕</button>
        </div>
        {!selected && (
          <div className="modal__search-row">
            <input
              ref={inputRef}
              className="modal__search"
              value={query}
              placeholder="Search your GitHub repositories…"
              onChange={event => setQuery(event.target.value)}
            />
          </div>
        )}
        {error && <div className="modal__empty">{error}</div>}
        {!selected && (
          <div className="modal__list">
            {loading ? <div className="modal__loading">Searching…</div> : repos.map(repo => (
              <div className="modal-repo-row" key={repo.id}>
                <div className="modal-repo-row__info">
                  <Avatar login={repo.repository.owner} size={20} />
                  <div className="modal-repo-row__text">
                    <div className="modal-repo-row__name">{repo.fullName}</div>
                    <div className="modal-repo-row__meta">
                      <span>{repo.visibility}</span>
                      <span>branch {repo.defaultBranch}</span>
                      {repo.fork && (
                        <span>⑂ fork{repo.upstream ? ` of ${fullName(repo.upstream)}` : ''}</span>
                      )}
                      {repo.existingWorkspaceId && <span>already watched</span>}
                    </div>
                    {repo.description && (
                      <div className="modal-repo-row__desc">{repo.description}</div>
                    )}
                  </div>
                </div>
                <button
                  className="modal-repo-row__add-btn"
                  type="button"
                  onClick={() => { void choose(repo); }}
                >
                  {repo.existingWorkspaceId ? 'Open' : 'Review'}
                </button>
              </div>
            ))}
            {!loading && repos.length === 0 && (
              <div className="modal__empty">No matching repositories.</div>
            )}
          </div>
        )}
        {selected && previewLoading && <div className="modal__loading">Checking repository routing…</div>}
        {selected && preview && (
          <div className="modal-preview">
            <div className="modal-preview__title-row">
              <Avatar login={preview.selectedRepository.owner} size={24} />
              <strong>{fullName(preview.selectedRepository)}</strong>
              <span className="modal-preview__chip">
                {preview.topology === 'FORK' ? '⑂ fork' : 'direct'}
              </span>
              <span className="modal-preview__chip">{preview.visibility}</span>
            </div>

            {preview.existingWorkspaceId ? (
              <div className="modal-preview__notice">
                You’re already watching this repository. A repository can be watched once.
              </div>
            ) : (
              <>
                {!preview.pushWritable && (
                  <div className="modal-preview__warning" role="status">
                    You can’t push to {fullName(preview.selectedRepository)}. You can still browse
                    commits, cherry-pick, and work locally; ByteQuay won’t push somewhere else.
                  </div>
                )}
                <div className="modal-preview__routing">
                  <div><span>Selected repository</span><b>{fullName(preview.selectedRepository)} · origin</b></div>
                  <div><span>Followed for updates</span><b>{fullName(preview.monitoredRepository)}</b></div>
                  <div><span>New branches start from</span><b>{preview.developmentBase}</b></div>
                  <div><span>Push destination</span><b>{preview.pushWritable ? preview.pushRemote : 'Read-only for you'}</b></div>
                  <div><span>Pull requests open against</span><b>{fullName(preview.pullRequestBase)}</b></div>
                </div>
                <p className="modal-preview__summary">
                  {preview.topology === 'FORK'
                    ? `ByteQuay clones the selected fork and follows ${fullName(preview.monitoredRepository)} for commits, issues, and pull requests.`
                    : `Everything uses ${fullName(preview.selectedRepository)} — code, issues, pull requests, and pushes.`}
                </p>
              </>
            )}

            <div className="modal-preview__actions">
              <button type="button" onClick={back}>Back</button>
              {preview.existingWorkspaceId ? (
                <button
                  className="modal-repo-row__add-btn"
                  type="button"
                  onClick={() => {
                    onClose();
                    onOpenWorkspace(preview.existingWorkspaceId as string);
                  }}
                >
                  Open workspace →
                </button>
              ) : (
                <button
                  className="modal-repo-row__add-btn"
                  type="button"
                  disabled={adding}
                  onClick={() => { void add(); }}
                >
                  {adding
                    ? 'Watching…'
                    : !preview.pushWritable
                      ? 'Watch anyway'
                      : preview.topology === 'FORK' ? 'Watch fork' : 'Watch repository'}
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
