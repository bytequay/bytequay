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
import { useEffect, useMemo, useState } from 'react';
import type { LocalRepoStatusDto, PullRequestDto, RepoMetaDto, UserProfileDto, WatchedRepoDto } from '../types';
import LogoLoading from '../LogoLoading';
import AddRepoModal from './AddRepoModal';
import WatchRepoModal from '../AddRepoModal';
import { formatRelativeTime } from '../pr/utils';

type Props = {
  onSelectRepo: (owner: string, repo: string) => void;
};

/**
 * Top-level Repos page — one card per watched repo, three sections per
 * card: identity, ByteQuay-managed clone state, and GitHub activity.
 */
function ReposPage({ onSelectRepo }: Props) {
  const [repos, setRepos] = useState<LocalRepoStatusDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [me, setMe] = useState<UserProfileDto | null>(null);
  const [cloneTarget, setCloneTarget] = useState<{ owner: string; repo: string } | null>(null);
  const [showWatchModal, setShowWatchModal] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setRepos(null);
    setError(null);
    window.bridge.listLocalRepos()
      .then(rs => { if (!cancelled) setRepos(rs); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });
    // Best-effort: fetch the current user once so cards can highlight
    // PRs that need this user's review. Failure leaves me=null and
    // the "needs you" badge silently disabled.
    window.bridge.getUserProfile()
      .then(u => { if (!cancelled) setMe(u); })
      .catch(() => { /* swallow — needs-you is optional */ });
    return () => { cancelled = true; };
  }, []);

  const onStarted = () => {
    setCloneTarget(null);
  };

  // The add modal watches + creates the managed clone in one step, so
  // by the time it calls back the row already carries its clone.
  const handleAddWatched = async () => {
    const fresh = await window.bridge.listLocalRepos();
    setRepos(fresh);
  };

  // Modal expects WatchedRepoDto[] only to mark already-watched rows; the
  // local-repos list already contains every watched repo, so we synthesise
  // the minimum shape rather than firing a second backend call.
  const watchedSynthetic = useMemo<WatchedRepoDto[]>(() =>
    (repos ?? []).map((r, i): WatchedRepoDto => ({
      id: i, owner: r.owner, repo: r.repo, displayOrder: i, localClonePath: r.localClonePath,
    })),
  [repos]);

  // GIT_UNAVAILABLE on any row implies git itself is missing — every
  // mapped repo will report it. Surface a single banner once instead
  // of N copies of the same error.
  const gitMissing = repos?.length ? repos.every(r => r.state === 'GIT_UNAVAILABLE') : false;

  return (
    <div className="repos-page calm-page">
      <header className="repos-page__header calm-page-header">
        <div className="repos-page__header-row">
          <div>
            <h1 className="repos-page__title">Repos</h1>
            <p className="repos-page__subtitle">
              All repositories you're watching, with their GitHub state
              and ByteQuay-managed local clone state. Watching keeps PRs,
              issues, and merges tied to upstream; the managed clone gives
              branches, commits, code graph indexing, and future local
              optimizations.
            </p>
          </div>
          <button
            type="button"
            className="repos-page__watch-btn"
            title="Watch a new repo (browse GitHub)"
            onClick={() => setShowWatchModal(true)}
          >
            + Watch a repo
          </button>
        </div>
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
              meLogin={me?.login ?? null}
              onOpen={() => onSelectRepo(r.owner, r.repo)}
              onClone={() => setCloneTarget({ owner: r.owner, repo: r.repo })}
            />
          ))}
          <WatchPlaceholderCard onClick={() => setShowWatchModal(true)} />
        </div>
      )}

      {cloneTarget && (
        <AddRepoModal
          owner={cloneTarget.owner}
          repo={cloneTarget.repo}
          onClose={() => setCloneTarget(null)}
          onStarted={onStarted}
        />
      )}

      {showWatchModal && (
        <WatchRepoModal
          watchedRepos={watchedSynthetic}
          onAdded={() => { void handleAddWatched(); }}
          onClose={() => setShowWatchModal(false)}
        />
      )}
    </div>
  );
}

function RepoCard({
  status,
  meLogin,
  onOpen,
  onClone,
}: {
  status: LocalRepoStatusDto;
  meLogin: string | null;
  onOpen: () => void;
  onClone: () => void;
}) {
  // Lazy-fetch the GitHub-side metadata + PR list per card. Both are
  // best-effort — when they fail we keep rendering the local-clone
  // bits and just suppress the missing chrome.
  const [meta, setMeta] = useState<RepoMetaDto | null>(null);
  const [prs, setPrs] = useState<PullRequestDto[] | null>(null);
  useEffect(() => {
    let cancelled = false;
    window.bridge.getRepoMeta(status.owner, status.repo)
      .then(m => { if (!cancelled) setMeta(m); })
      .catch(() => { /* swallow */ });
    window.bridge.getRepoPulls(status.owner, status.repo)
      .then(p => { if (!cancelled) setPrs(p); })
      .catch(() => { /* swallow */ });
    return () => { cancelled = true; };
  }, [status.owner, status.repo]);

  const isUnmapped = status.localClonePath == null;
  const handleCardClick = () => {
    if (isUnmapped) onClone();
    else onOpen();
  };

  // PR/issue stats derived from the cached PR list. GitHub's
  // openIssuesCount conflates issues + PRs, so issues = total - PRs
  // is the best client-side approximation without a second fetch.
  const openPrs = (prs ?? []).filter(p => p.state === 'open');
  const openPrCount = openPrs.length;
  const needsYouCount = meLogin
    ? openPrs.filter(p => (p.requestedReviewers ?? []).includes(meLogin)).length
    : 0;
  const openIssueCount = meta != null ? Math.max(0, meta.openIssuesCount - openPrCount) : null;

  return (
    <article
      className={`repo-card repo-card--${status.state.toLowerCase()}${isUnmapped ? ' repo-card--unmapped' : ''}`}
      onClick={handleCardClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          handleCardClick();
        }
      }}
      role="button"
      tabIndex={0}
      title={isUnmapped
        ? `Clone ${status.owner}/${status.repo} into ByteQuay`
        : `Open ${status.owner}/${status.repo}`}
    >
      <RepoCardIdentity status={status} meta={meta} />
      <RepoCardLocalClone status={status} onClone={onClone} />
      <RepoCardActivity
        prCount={openPrCount}
        needsYouCount={needsYouCount}
        issueCount={openIssueCount}
        lastActivityAt={meta?.pushedAt ?? null}
      />
    </article>
  );
}

function RepoCardIdentity({
  status,
  meta,
}: {
  status: LocalRepoStatusDto;
  meta: RepoMetaDto | null;
}) {
  return (
    <div className="repo-card__identity">
      <RepoAvatar repo={status.repo} avatarUrl={meta?.ownerAvatarUrl ?? null} />
      <div className="repo-card__identity-main">
        <div className="repo-card__name">
          <span className="repo-card__owner">{status.owner}/</span>
          <span className="repo-card__repo">{status.repo}</span>
        </div>
        {meta?.description && (
          <p className="repo-card__tagline" title={meta.description}>
            {meta.description}
          </p>
        )}
        {meta && (
          <div className="repo-card__stats">
            <span className="repo-card__stat" title="Stars">
              <span aria-hidden="true">★</span> {formatCount(meta.stargazersCount)}
            </span>
            <span className="repo-card__stat" title="Forks">
              <span aria-hidden="true">⑂</span> {formatCount(meta.forksCount)}
            </span>
            <span className="repo-card__stat" title="Watching">
              <span aria-hidden="true">👁</span> {formatCount(meta.watchersCount)}
            </span>
          </div>
        )}
      </div>
    </div>
  );
}

function RepoCardLocalClone({
  status,
  onClone,
}: {
  status: LocalRepoStatusDto;
  onClone: () => void;
}) {
  if (status.localClonePath == null) {
    return (
      <div className="repo-card__clone repo-card__clone--unmapped">
        <span className="repo-card__clone-label">Managed clone</span>
        <span className="repo-card__clone-empty">
          Not cloned into ByteQuay yet
        </span>
        <button
          type="button"
          className="repo-card__clone-cta"
          onClick={(e) => { e.stopPropagation(); onClone(); }}
        >
          Clone…
        </button>
      </div>
    );
  }
  return (
    <div className="repo-card__clone repo-card__clone--mapped">
      <span className="repo-card__clone-label">Managed clone</span>
      <div className="repo-card__clone-body">
        {status.currentBranch && (
          <code className="repo-card__clone-branch">{status.currentBranch}</code>
        )}
        <span className="repo-card__clone-path" title={status.localClonePath}>
          {compactPath(status.localClonePath)}
        </span>
      </div>
      <ClonePill state={status.state} dirty={status.dirtyFileCount} />
    </div>
  );
}

function RepoCardActivity({
  prCount,
  needsYouCount,
  issueCount,
  lastActivityAt,
}: {
  prCount: number;
  needsYouCount: number;
  issueCount: number | null;
  lastActivityAt: string | null;
}) {
  return (
    <div className="repo-card__activity">
      {needsYouCount > 0 ? (
        <span className="repo-card__activity-need">
          <strong>{needsYouCount}</strong> need you
        </span>
      ) : (
        <span className="repo-card__activity-stat">
          <strong>{prCount}</strong> open PR{prCount === 1 ? '' : 's'}
        </span>
      )}
      {needsYouCount > 0 && (
        <span className="repo-card__activity-stat">
          <strong>{prCount}</strong> open PR{prCount === 1 ? '' : 's'}
        </span>
      )}
      {issueCount != null && (
        <span className="repo-card__activity-stat">
          <strong>{issueCount}</strong> issue{issueCount === 1 ? '' : 's'}
        </span>
      )}
      {lastActivityAt && (
        <span className="repo-card__activity-time">
          last activity {formatRelativeTime(lastActivityAt)}
        </span>
      )}
    </div>
  );
}

function WatchPlaceholderCard({ onClick }: { onClick: () => void }) {
  return (
    <article
      className="repo-card repo-card--placeholder"
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onClick();
        }
      }}
      aria-label="Watch a repo"
    >
      <span className="repo-card__placeholder-glyph" aria-hidden="true">+</span>
      <span className="repo-card__placeholder-title">Watch a repo</span>
      <p className="repo-card__placeholder-body">
        Watch upstream for PRs and issues; ByteQuay will create the
        managed clone for local work.
      </p>
    </article>
  );
}

function RepoAvatar({ repo, avatarUrl }: { repo: string; avatarUrl: string | null }) {
  if (avatarUrl) {
    return (
      <img
        className="repo-card__avatar repo-card__avatar--image"
        src={avatarUrl}
        alt=""
        aria-hidden="true"
      />
    );
  }
  // Fallback: deterministic colour-and-letter placeholder until the
  // meta row lands (very first ever visit) or for legacy rows
  // persisted before the avatar column existed.
  const PALETTE = ['#1f6a57', '#cf6900', '#1f6feb', '#8a5cf5', '#cf222e', '#1a7f37', '#996600', '#0e8c8c'];
  let h = 0;
  for (let i = 0; i < repo.length; i++) {
    h = ((h << 5) - h + repo.charCodeAt(i)) | 0;
  }
  const color = PALETTE[Math.abs(h) % PALETTE.length];
  const initial = (repo[0] ?? '?').toUpperCase();
  return (
    <span
      className="repo-card__avatar"
      style={{ background: color }}
      aria-hidden="true"
    >
      {initial}
    </span>
  );
}

function ClonePill({ state, dirty }: { state: LocalRepoStatusDto['state']; dirty: number | null }) {
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

/** Compact "/Users/jack/IdeaProjects/trino" → "~/IdeaProjects/trino"
 *  for the local-clone path display. Falls back to the original
 *  string when no home prefix matches. */
function compactPath(path: string): string {
  // We don't have $HOME in the renderer, so match on the common macOS
  // /Users/<name>/ prefix and collapse to "~/…" — covers every path
  // the user types into the locate / clone modal in practice.
  const m = /^\/Users\/[^/]+\//.exec(path);
  if (m) return '~/' + path.slice(m[0].length);
  return path;
}

function formatCount(n: number): string {
  if (n < 1000) return String(n);
  if (n < 10_000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
  if (n < 1_000_000) return Math.round(n / 1000) + 'k';
  return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'm';
}

export default ReposPage;
