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
import { LoaderCircle } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import RepoAvatar from './RepoAvatar';
import { Clickable } from './cards/Card';
import type {
  RepoRefDto,
  RepositorySearchResultDto,
  WatchRepositoryCommand,
  WatchRepositoryPreviewDto,
  WatchRepositoryTopology,
  WorkspaceDto,
} from './types';
import { useModalDialog } from './useModalDialog';

type Props = {
  initialRepositoryId?: number;
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
  return `ByteQuay will clone ${upstream} directly, follow its commits, issues and pull requests, and publish work branches to this repository.`;
}

export function WatchRepositoryConfirmation({ preview, topology, ready, adding, onTopologyChange, onBack, onWatch, onOpenWorkspace }: {
  preview: WatchRepositoryPreviewDto;
  topology: WatchRepositoryTopology;
  ready: boolean;
  onTopologyChange: (topology: WatchRepositoryTopology) => void;
  adding: boolean;
  onBack: () => void;
  onWatch: (command: WatchRepositoryCommand) => void;
  onOpenWorkspace: (workspaceId: string) => void;
}) {
  return (
    <div className="modal-preview">
      <div className="modal-preview__title-row">
        <RepoAvatar owner={preview.monitoredRepository.owner} repo={preview.monitoredRepository.repo} size={32} radius={8} />
        <div>
          <div className="modal-preview__name-row">
            <span className="modal-preview__name">{fullName(preview.monitoredRepository)}</span>
            <span className={`modal-chip${preview.topology === 'FORK' ? ' is-fork' : ' is-direct'}`}>
              {preview.topology === 'FORK' ? '⑂ personal fork workflow' : 'direct clone'}
            </span>
          </div>
          <div className="modal-preview__sub">
            {preview.visibility} · default branch {preview.defaultBranch}
          </div>
        </div>
      </div>

      <fieldset className="modal-strategy" disabled={adding}>
        <legend>Repository workflow</legend>
        {(['DIRECT', 'FORK'] as const).filter(mode => mode === 'DIRECT' || preview.forkModeAvailable).map(mode => (
          <label className="modal-strategy__option" key={mode}>
            <input type="radio" name="repository-workflow" value={mode} checked={topology === mode}
              onChange={() => onTopologyChange(mode)} />
            <span>
              <strong>{mode === 'DIRECT' ? 'Direct clone' : 'Personal fork'}</strong>
              {preview.recommendedTopology === mode && <span className="modal-chip">Recommended</span>}
              <small>{mode === 'DIRECT' ? 'Follow and publish to the selected repository.' : 'Publish work branches to your personal fork.'}</small>
            </span>
          </label>
        ))}
      </fieldset>

      {ready && preview.topology === 'DIRECT' && (
        <p className={preview.pushWritable ? 'modal-preview__permission' : 'modal-preview__warning'}>
          Direct clone requires write permission to {fullName(preview.pushRepository)} to push development branches and publish changes.
          {!preview.pushWritable && ' Your current GitHub credentials do not have write access. You can still clone and follow this repository.'}
        </p>
      )}
      {ready && preview.topology === 'FORK' && !preview.pushWritable && !preview.forkRequired && (
        <div className="modal-preview__warning" role="status">
          You can’t push to {fullName(preview.pushRepository)}. Check your GitHub permissions
          before publishing work branches.
        </div>
      )}

      {ready && <div className="modal-routing">
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
      </div>}

      <div className="modal-preview__actions">
        {adding && (
          <div className="modal__progress" role="status">
            <span className="bq-spinner is-spinning"><LoaderCircle size={16} className="modal__spinner" aria-hidden="true" /></span>
            <span>{preview.forkRequired ? `Creating ${fullName(preview.pushRepository)} and saving your workspace…` : 'Saving your workspace…'}</span>
          </div>
        )}
        <button type="button" disabled={adding} onClick={onBack}>Back</button>
        <button
          className="modal-preview__confirm"
          type="button"
          disabled={adding || !ready}
          onClick={() => {
            if (preview.existingWorkspaceId !== null) {
              onOpenWorkspace(preview.existingWorkspaceId);
              return;
            }
            onWatch({
              selectedRepositoryId: preview.selectedRepositoryId,
              topology: preview.topology,
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

export default function AddRepoModal({ initialRepositoryId, onAdded, onOpenWorkspace, onClose }: Props) {
  const [repos, setRepos] = useState<RepositorySearchResultDto[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<number | null>(initialRepositoryId ?? null);
  const [topology, setTopology] = useState<WatchRepositoryTopology>();
  const [preview, setPreview] = useState<WatchRepositoryPreviewDto | null>(null);
  const [previewLoading, setPreviewLoading] = useState(initialRepositoryId !== undefined);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const previewCache = useRef(new Map<string, Promise<WatchRepositoryPreviewDto>>());
  const selectedRepositoryName = repos.find(repo => repo.id === selectedRepositoryId)?.fullName;

  const loadPreview = useCallback((repositoryId: number, mode?: WatchRepositoryTopology) => {
    const cache = previewCache.current;
    const key = `${repositoryId}:${mode ?? 'recommended'}`;
    const cached = cache.get(key);
    if (cached) return cached;
    const request = window.bridge.previewWatch(repositoryId, mode)
      .then(result => {
        if (cache.get(key) === request) cache.set(`${repositoryId}:${result.topology}`, request);
        return result;
      })
      .catch(error => {
        if (cache.get(key) === request) cache.delete(key);
        throw error;
      });
    cache.set(key, request);
    return request;
  }, []);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    if (selectedRepositoryId !== null) return;
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
  }, [query, selectedRepositoryId]);

  useEffect(() => {
    if (selectedRepositoryId === null) return;
    let cancelled = false;
    setPreviewLoading(true);
    loadPreview(selectedRepositoryId, topology)
      .then(result => { if (!cancelled) setPreview(result); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setPreviewLoading(false); });
    return () => { cancelled = true; };
  }, [selectedRepositoryId, topology, loadPreview]);

  const dialog = useModalDialog(inputRef);

  const pick = (repo: RepositorySearchResultDto) => {
    setSelectedRepositoryId(repo.id);
    setTopology(undefined);
    setPreviewLoading(true);
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
      previewCache.current.clear();
      try {
        setPreview(await loadPreview(command.selectedRepositoryId, command.topology));
      }
      catch { /* Keep the original confirmation error visible. */ }
    }
    finally {
      setAdding(false);
    }
  };

  const back = () => {
    setSelectedRepositoryId(null);
    setTopology(undefined);
    setPreview(null);
    setError(null);
    window.setTimeout(() => inputRef.current?.focus());
  };

  return (
    <dialog ref={dialog} className="modal" onCancel={event => { event.preventDefault(); if (!adding) { if (selectedRepositoryId !== null) back(); else onClose(); } }} role="dialog" aria-modal="true" aria-label="Watch a repository">
        {selectedRepositoryId === null && (
          <>
            <div className="modal__header">
              <h2 className="modal__title">Watch a repository</h2>
              <button className="modal__close" aria-label="Close" onClick={onClose} type="button">✕</button>
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
              {loading ? <div className="modal__loading" role="status">Searching…</div> : repos.map(repo => (
                <Clickable className="modal-repo-row" key={repo.id} onOpen={() => pick(repo)}>
                  <RepoAvatar owner={repo.repository.owner} repo={repo.repository.repo} size={28} radius={7} />
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
                </Clickable>
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

        {selectedRepositoryId !== null && (
          <>
            <div className="modal__header">
              <button className="modal__back" disabled={adding} onClick={back} type="button">‹ Search</button>
              <button className="modal__close" aria-label="Close" disabled={adding} onClick={onClose} type="button">✕</button>
            </div>
            {previewLoading && (
              <div className="modal__loading" role="status">
                {selectedRepositoryName && <div className="modal__loading-repo">{selectedRepositoryName}</div>}
                <div className="modal__progress">
                  <span className="bq-spinner is-spinning"><LoaderCircle size={16} className="modal__spinner" aria-hidden="true" /></span>
                  <span>Checking repository routing…</span>
                </div>
              </div>
            )}
            {error && <div className="modal__empty" role="alert">{error}</div>}
            {preview && (
              <WatchRepositoryConfirmation
                preview={preview}
                topology={topology ?? preview.topology}
                ready={!previewLoading && (topology === undefined || topology === preview.topology)}
                onTopologyChange={mode => {
                  setTopology(mode);
                  setPreviewLoading(true);
                  setError(null);
                }}
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
    </dialog>
  );
}
