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
  WatchRepositoryCommand,
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

function routingRows(preview: WatchRepositoryPreviewDto): RoutingRow[] {
  const base = `${fullName(preview.pullRequestBase)} · ${preview.defaultBranch}`;
  return [
    { key: 'Followed for updates', value: fullName(preview.monitoredRepository) },
    { key: 'New branches start from', value: preview.developmentBase },
    { key: 'Work branches push to', value: fullName(preview.pushRepository) },
    { key: 'Pull requests open against', value: base },
  ];
}

function summaryFor(preview: WatchRepositoryPreviewDto): string {
  const upstream = fullName(preview.monitoredRepository);
  const push = fullName(preview.pushRepository);
  if (preview.forkRequired) {
    return `Allow ByteQuay to create ${push} as your personal fork of ${upstream}?`
      + ` ByteQuay will follow ${upstream} for commits, issues and pull requests,`
      + ` and publish your work branches to ${push}.`;
  }
  if (preview.topology === 'FORK') {
    return `ByteQuay will reuse your existing personal fork ${push} for work branches`
      + ` and follow ${upstream} for commits, issues and pull requests.`;
  }
  return `Code, issues, pull requests and work branches use your repository ${upstream}.`;
}

export function WatchRepositoryConfirmation({ preview, adding, onBack, onWatch, onOpenWorkspace }: {
  preview: WatchRepositoryPreviewDto;
  adding: boolean;
  onBack: () => void;
  onWatch: (command: WatchRepositoryCommand) => void;
  onOpenWorkspace: (workspaceId: string) => void;
}) {
  return (
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
              {preview.topology === 'FORK' ? '⑂ personal fork workflow' : 'your repository'}
            </span>
          </div>
          <div className="modal-preview__sub">
            {preview.visibility} · default branch {preview.defaultBranch}
          </div>
        </div>
      </div>

      {!preview.pushWritable && !preview.forkRequired && (
        <div className="modal-preview__warning" role="status">
          You can’t push to {fullName(preview.pushRepository)}. Check your GitHub permissions
          before publishing work branches.
        </div>
      )}

      <div className="modal-routing">
        <div className="modal-routing__head">HOW THIS REPOSITORY WILL WORK</div>
        <div className="modal-routing__grid">
          {routingRows(preview).map(row => (
            <div className="modal-routing__row" key={row.key}>
              <span>{row.key}</span>
              <b>{row.value}</b>
            </div>
          ))}
        </div>
        <p className="modal-routing__summary">{summaryFor(preview)}</p>
      </div>

      <div className="modal-preview__actions">
        <button type="button" disabled={adding} onClick={onBack}>Back</button>
        <button
          className="modal-preview__confirm"
          type="button"
          disabled={adding}
          onClick={() => {
            if (preview.existingWorkspaceId !== null) {
              onOpenWorkspace(preview.existingWorkspaceId);
              return;
            }
            onWatch({
              selectedRepositoryId: preview.selectedRepositoryId,
              previewFingerprint: preview.fingerprint,
              allowForkCreation: preview.forkRequired,
            });
          }}
        >
          {adding ? 'Watching…'
            : preview.existingWorkspaceId !== null ? 'Open workspace'
              : preview.forkRequired ? 'Create fork and watch' : 'Watch repository'}
        </button>
      </div>
    </div>
  );
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
    if (!selected) return;
    let cancelled = false;
    setPreviewLoading(true);
    window.bridge.previewWatch(selected.id)
      .then(result => { if (!cancelled) setPreview(result); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setPreviewLoading(false); });
    return () => { cancelled = true; };
  }, [selected]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || adding) return;
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
  }, [adding, onClose, selected]);

  const pick = (repo: RepositorySearchResultDto) => {
    setSelected(repo);
    setPreview(null);
    setError(null);
  };

  const add = async (command: WatchRepositoryCommand) => {
    if (adding) return;
    setAdding(true);
    setError(null);
    try {
      const workspace = await window.bridge.watchRepository(command);
      onAdded(workspace);
      onClose();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      try {
        setPreview(await window.bridge.previewWatch(command.selectedRepositoryId));
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
    <div className="modal-overlay" onClick={() => { if (!adding) onClose(); }}>
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
                      {repo.fork && repo.upstream ? (
                        <span className="modal-chip is-fork">⑂ fork of {fullName(repo.upstream)}</span>
                      ) : null}
                    </div>
                    <div className="modal-repo-row__meta">
                      {repo.visibility} · branch {repo.defaultBranch}
                      {repo.description ? ` · ${repo.description}` : ''}
                    </div>
                  </div>
                  <span className="modal-repo-row__pill">Watch</span>
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
              <button className="modal__back" disabled={adding} onClick={back} type="button">‹ Search</button>
              <button className="modal__close" disabled={adding} onClick={onClose} type="button">✕</button>
            </div>
            {previewLoading && <div className="modal__loading">Checking repository routing…</div>}
            {error && <div className="modal__empty">{error}</div>}
            {preview && (
              <WatchRepositoryConfirmation
                preview={preview}
                adding={adding}
                onBack={back}
                onWatch={command => { void add(command); }}
                onOpenWorkspace={workspaceId => {
                  onClose();
                  onOpenWorkspace(workspaceId);
                }}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
}
