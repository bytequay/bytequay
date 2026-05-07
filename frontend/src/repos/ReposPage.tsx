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
import type { LocalRepoStatusDto } from '../types';
import LogoLoading from '../LogoLoading';
import AddRepoModal from './AddRepoModal';

type Props = {
  onSelectRepo: (owner: string, repo: string) => void;
};

/**
 * Top-level Repos page — one card per watched repo with its local-clone
 * state pill (CLEAN / MODIFIED / UNMAPPED / …). Read-only in this
 * slice; the clone / locate / fetch flows ship in follow-up commits.
 *
 * Data source: GET /api/repos/local — joins watched_repos with
 * `git status --porcelain` against each mapped working tree.
 */
function ReposPage({ onSelectRepo }: Props) {
  const [repos, setRepos] = useState<LocalRepoStatusDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // When set, open the Add-repo modal scoped to this repo. Cleared on
  // close or success. The modal is also responsible for kicking off
  // the clone / locate IPC calls — this page just decides when it's
  // visible and folds the result back into the list.
  const [mappingTarget, setMappingTarget] = useState<{ owner: string; repo: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    setRepos(null);
    setError(null);
    window.bridge.listLocalRepos()
      .then(rs => { if (!cancelled) setRepos(rs); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, []);

  const onMapped = (status: LocalRepoStatusDto) => {
    setRepos(prev => prev?.map(r =>
      r.owner === status.owner && r.repo === status.repo ? status : r,
    ) ?? null);
    setMappingTarget(null);
  };

  // GIT_UNAVAILABLE on any row implies git itself is missing — every
  // mapped repo will report it. Surface a single banner once instead
  // of N copies of the same error.
  const gitMissing = repos?.length ? repos.every(r => r.state === 'GIT_UNAVAILABLE') : false;

  return (
    <div className="repos-page">
      <header className="repos-page__header">
        <h1 className="repos-page__title">Repos</h1>
        <p className="repos-page__subtitle">
          Local working copies of the repos you watch. Map a clone to a
          repo to enable branch and commit views.
        </p>
      </header>

      {gitMissing && (
        <div className="repos-page__banner repos-page__banner--warn">
          <strong>git not found on PATH.</strong> ByteQuay shells out to
          your system git for local-repo features. Install Xcode
          Command Line Tools with{' '}
          <code>xcode-select --install</code> and reopen this page.
        </div>
      )}

      {error && (
        <div className="repos-page__banner repos-page__banner--error">
          Couldn't load repos: {error}
        </div>
      )}

      {repos === null && !error && (
        <div className="repos-page__loading">
          <LogoLoading size={48} label="Loading repos" />
        </div>
      )}

      {repos !== null && repos.length === 0 && (
        <div className="repos-page__empty">
          You're not watching any repos yet. Add one from the home
          page or settings to see it here.
        </div>
      )}

      {repos !== null && repos.length > 0 && !gitMissing && (
        <div className="repos-page__grid">
          {repos.map(r => (
            <RepoCard
              key={`${r.owner}/${r.repo}`}
              status={r}
              onOpen={() => onSelectRepo(r.owner, r.repo)}
              onMapClone={() => setMappingTarget({ owner: r.owner, repo: r.repo })}
            />
          ))}
        </div>
      )}

      {mappingTarget && (
        <AddRepoModal
          owner={mappingTarget.owner}
          repo={mappingTarget.repo}
          onClose={() => setMappingTarget(null)}
          onMapped={onMapped}
        />
      )}
    </div>
  );
}

function RepoCard({
  status,
  onOpen,
  onMapClone,
}: {
  status: LocalRepoStatusDto;
  onOpen: () => void;
  onMapClone: () => void;
}) {
  // Whole card is the click target so users don't have to find the
  // tiny name link. Mapped repos open the detail page; UNMAPPED
  // ones open the add-repo modal — same destination as the explicit
  // "Map clone…" button. The MISSING / ERROR / GIT_UNAVAILABLE
  // states still navigate so the user can see the error inline.
  const handleCardClick = () => {
    if (status.state === 'UNMAPPED') {
      onMapClone();
    } else {
      onOpen();
    }
  };

  return (
    <article
      className={`repo-card repo-card--${status.state.toLowerCase()}`}
      onClick={handleCardClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          handleCardClick();
        }
      }}
      role="button"
      tabIndex={0}
      title={status.state === 'UNMAPPED'
        ? `Map a local clone for ${status.owner}/${status.repo}`
        : `Open ${status.owner}/${status.repo}`}
    >
      <header className="repo-card__head">
        <span className="repo-card__name">
          <span className="repo-card__owner">{status.owner}/</span>
          <span className="repo-card__repo">{status.repo}</span>
        </span>
        <StatePill state={status.state} dirty={status.dirtyFileCount} />
      </header>

      {status.currentBranch && (
        <div className="repo-card__branch">
          <span className="repo-card__branch-icon" aria-hidden="true">⎇</span>
          <code>{status.currentBranch}</code>
        </div>
      )}

      {status.localClonePath ? (
        <div className="repo-card__path" title={status.localClonePath}>
          {status.localClonePath}
        </div>
      ) : (
        <div className="repo-card__path repo-card__path--empty">
          Not mapped to a local clone yet.
        </div>
      )}

      {status.errorMessage && status.state !== 'UNMAPPED' && (
        <div className="repo-card__error">{status.errorMessage}</div>
      )}

      {status.state === 'UNMAPPED' && (
        <div className="repo-card__cta">
          <button
            type="button"
            className="button button--primary button--sm"
            // stopPropagation so clicking the explicit button doesn't
            // double-fire alongside the card-level click handler.
            onClick={(e) => { e.stopPropagation(); onMapClone(); }}
          >
            Map clone…
          </button>
        </div>
      )}
    </article>
  );
}

function StatePill({ state, dirty }: { state: LocalRepoStatusDto['state']; dirty: number | null }) {
  switch (state) {
    case 'CLEAN':
      return <span className="repo-pill repo-pill--clean">Clean</span>;
    case 'MODIFIED':
      return (
        <span className="repo-pill repo-pill--modified">
          {dirty != null ? `${dirty} modified` : 'Modified'}
        </span>
      );
    case 'UNMAPPED':
      return <span className="repo-pill repo-pill--unmapped">Unmapped</span>;
    case 'MISSING':
      return <span className="repo-pill repo-pill--missing">Missing</span>;
    case 'ERROR':
      return <span className="repo-pill repo-pill--error">Error</span>;
    case 'GIT_UNAVAILABLE':
      return <span className="repo-pill repo-pill--error">No git</span>;
  }
}

export default ReposPage;
