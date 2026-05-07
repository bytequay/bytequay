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

  useEffect(() => {
    let cancelled = false;
    setRepos(null);
    setError(null);
    window.bridge.listLocalRepos()
      .then(rs => { if (!cancelled) setRepos(rs); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, []);

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
            />
          ))}
        </div>
      )}
    </div>
  );
}

function RepoCard({ status, onOpen }: { status: LocalRepoStatusDto; onOpen: () => void }) {
  return (
    <article className={`repo-card repo-card--${status.state.toLowerCase()}`}>
      <header className="repo-card__head">
        <button
          type="button"
          className="repo-card__name"
          onClick={onOpen}
          title={`Open ${status.owner}/${status.repo}`}
        >
          <span className="repo-card__owner">{status.owner}/</span>
          <span className="repo-card__repo">{status.repo}</span>
        </button>
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
