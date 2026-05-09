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
import type {
  IssueDto,
  LocalRepoStatusDto,
  PullRequestDto,
  RepoMetaDto,
  UserProfileDto,
} from '../types';
import LogoLoading from '../LogoLoading';
import { formatRelativeTime } from '../pr/utils';

type Props = {
  owner: string;
  repo: string;
  onBack: () => void;
  /** Tab clicks navigate the user out of this shell into the existing
   *  PRs / Issues / Branches surfaces. Inlining them inside the shell
   *  was tried in R3b-1 and rolled back — the embedded layouts didn't
   *  read well, and the standalone pages are the canonical detail
   *  surfaces. */
  onOpenPrs: (owner: string, repo: string) => void;
  onOpenIssues: (owner: string, repo: string) => void;
  onOpenBranches: (owner: string, repo: string) => void;
  /** Click target for an inline PR card on the Overview panel — same
   *  callback shape PullRequestList uses elsewhere. */
  onSelectPr: (owner: string, repo: string, prNumber: number) => void;
};

type Tab = 'overview' | 'pulls' | 'issues' | 'branches';
type PrFilter = 'needs-you' | 'yours' | 'all-open';

/**
 * Repository home page — unified entry per
 * docs/mockups/design/repository/SUMMARY.md. Hero + tab strip
 * (Overview / Pull Requests / Issues / Branches) with the Overview
 * tab as the default. Branches shows a hint when the repo isn't
 * mapped to a local clone — that flow needs git.
 */
function RepositoryPage(props: Props) {
  const { owner, repo, onBack, onOpenPrs, onOpenIssues, onOpenBranches, onSelectPr } = props;
  const [tab, setTab] = useState<Tab>('overview');
  const [meta, setMeta] = useState<RepoMetaDto | null>(null);
  const [metaError, setMetaError] = useState<string | null>(null);
  const [status, setStatus] = useState<LocalRepoStatusDto | null>(null);
  const [pulls, setPulls] = useState<PullRequestDto[] | null>(null);
  const [issues, setIssues] = useState<IssueDto[] | null>(null);
  const [me, setMe] = useState<UserProfileDto | null>(null);

  useEffect(() => {
    let cancelled = false;
    setMeta(null);
    setMetaError(null);
    setStatus(null);
    setPulls(null);
    setIssues(null);
    window.bridge.getRepoMeta(owner, repo)
      .then(m => { if (!cancelled) setMeta(m); })
      .catch(e => { if (!cancelled) setMetaError(e instanceof Error ? e.message : String(e)); });
    // Local-clone status drives the Overview's clone block. Reuses
    // listLocalRepos and filters — saves us a per-repo endpoint.
    window.bridge.listLocalRepos()
      .then(rs => {
        if (cancelled) return;
        const match = rs.find(r => r.owner === owner && r.repo === repo);
        if (match) setStatus(match);
      })
      .catch(() => { /* swallow — page renders without clone status */ });
    window.bridge.getRepoPulls(owner, repo)
      .then(p => { if (!cancelled) setPulls(p); })
      .catch(() => { /* swallow */ });
    window.bridge.getRepoIssues(owner, repo)
      .then(i => { if (!cancelled) setIssues(i); })
      .catch(() => { /* swallow */ });
    window.bridge.getUserProfile()
      .then(u => { if (!cancelled) setMe(u); })
      .catch(() => { /* swallow */ });
    return () => { cancelled = true; };
  }, [owner, repo]);

  const isMapped = status?.localClonePath != null;
  const openPulls = useMemo(() => (pulls ?? []).filter(p => p.state === 'open'), [pulls]);
  // getRepoIssues returns open issues only — no state field on the
  // DTO, so just pass the list through.
  const openIssues = issues ?? [];

  return (
    <div className="repository-page">
      <nav className="repository-page__breadcrumb">
        <button className="repository-page__back" onClick={onBack} type="button">← Repos</button>
        <span className="repository-page__crumb-sep" aria-hidden="true">/</span>
        <span className="repository-page__crumb-current">{owner}/{repo}</span>
      </nav>

      <RepositoryHero owner={owner} repo={repo} meta={meta} metaError={metaError} />

      <div className="repository-page__tabs" role="tablist">
        <RepoTab label="Overview" active={tab === 'overview'} onClick={() => setTab('overview')} />
        <RepoTab label="Pull Requests" count={openPulls.length} active={tab === 'pulls'} onClick={() => { setTab('pulls'); onOpenPrs(owner, repo); }} />
        <RepoTab label="Issues" count={openIssues.length} active={tab === 'issues'} onClick={() => { setTab('issues'); onOpenIssues(owner, repo); }} />
        <RepoTab label="Branches" active={tab === 'branches'} disabled={!isMapped} disabledHint="map a clone to enable" onClick={() => { setTab('branches'); onOpenBranches(owner, repo); }} />
      </div>

      {tab === 'overview' && (
        <RepositoryOverview
          owner={owner}
          repo={repo}
          status={status}
          meta={meta}
          pulls={openPulls}
          issues={openIssues}
          meLogin={me?.login ?? null}
          onOpenAllPrs={() => onOpenPrs(owner, repo)}
          onOpenBranches={() => onOpenBranches(owner, repo)}
          onSelectPr={onSelectPr}
        />
      )}
      {tab !== 'overview' && (
        <div className="repository-page__placeholder">
          Opening {tab}…
        </div>
      )}
    </div>
  );
}

function RepoTab({
  label,
  count,
  active,
  disabled,
  disabledHint,
  onClick,
}: {
  label: string;
  count?: number;
  active: boolean;
  disabled?: boolean;
  disabledHint?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      disabled={disabled}
      className={`repository-page__tab${active ? ' repository-page__tab--active' : ''}${disabled ? ' repository-page__tab--disabled' : ''}`}
      onClick={onClick}
      title={disabled ? disabledHint : undefined}
    >
      {label}
      {count != null && count > 0 && <span className="repository-page__tab-count">{count}</span>}
    </button>
  );
}

function RepositoryHero({
  owner,
  repo,
  meta,
  metaError,
}: {
  owner: string;
  repo: string;
  meta: RepoMetaDto | null;
  metaError: string | null;
}) {
  return (
    <header className="repository-hero">
      <RepoAvatar repo={repo} avatarUrl={meta?.ownerAvatarUrl ?? null} size={36} />
      <div className="repository-hero__main">
        <div className="repository-hero__title-row">
          <h1 className="repository-hero__title">
            <span className="repository-hero__owner">{owner}/</span>{repo}
          </h1>
          <div className="repository-hero__actions">
            <a
              className="repository-hero__action"
              href={meta?.htmlUrl || `https://github.com/${owner}/${repo}`}
              target="_blank"
              rel="noreferrer"
              title="Open this repo on github.com"
            >
              View on GitHub ↗
            </a>
            <button type="button" className="repository-hero__action" disabled title="Per-repo settings — coming soon">
              Settings
            </button>
            <button type="button" className="repository-hero__action repository-hero__action--star" disabled title="Star — coming soon">
              ★ Star
            </button>
          </div>
        </div>
        {meta?.description && (
          <p className="repository-hero__tagline">{meta.description}</p>
        )}
        {metaError && (
          <p className="repository-hero__tagline repository-hero__tagline--error">
            Couldn't load repo metadata: {metaError}
          </p>
        )}
        {meta && (
          <div className="repository-hero__stats">
            <span><strong>{formatCount(meta.stargazersCount)}</strong> stars</span>
            <span><strong>{formatCount(meta.forksCount)}</strong> forks</span>
            <span><strong>{formatCount(meta.watchersCount)}</strong> watching</span>
          </div>
        )}
        {meta && meta.topics.length > 0 && (
          <div className="repository-hero__topics">
            {meta.topics.map(t => (
              <span key={t} className="repository-hero__topic">{t}</span>
            ))}
          </div>
        )}
      </div>
    </header>
  );
}

function RepositoryOverview({
  owner,
  repo,
  status,
  meta,
  pulls,
  issues,
  meLogin,
  onOpenAllPrs,
  onOpenBranches,
  onSelectPr,
}: {
  owner: string;
  repo: string;
  status: LocalRepoStatusDto | null;
  meta: RepoMetaDto | null;
  pulls: PullRequestDto[];
  issues: IssueDto[];
  meLogin: string | null;
  onOpenAllPrs: () => void;
  onOpenBranches: () => void;
  onSelectPr: (owner: string, repo: string, prNumber: number) => void;
}) {
  return (
    <div className="repository-overview">
      <div className="repository-overview__main">
        <CloneBlock owner={owner} repo={repo} status={status} onOpenBranches={onOpenBranches} />
        <PullRequestsPanel
          pulls={pulls}
          meLogin={meLogin}
          onOpenAll={onOpenAllPrs}
          onSelectPr={(num) => onSelectPr(owner, repo, num)}
        />
      </div>
      <aside className="repository-overview__side">
        <AboutPanel meta={meta} />
        <AtAGlancePanel meta={meta} openPrCount={pulls.length} openIssueCount={issues.length} />
      </aside>
    </div>
  );
}

function CloneBlock({
  owner: _owner,
  repo: _repo,
  status,
  onOpenBranches,
}: {
  owner: string;
  repo: string;
  status: LocalRepoStatusDto | null;
  onOpenBranches: () => void;
}) {
  if (!status) {
    return (
      <div className="repo-overview-panel repo-overview-panel--clone">
        <span className="repo-overview-panel__loading">Reading clone status…</span>
      </div>
    );
  }
  if (status.localClonePath == null) {
    return (
      <div className="repo-overview-panel repo-overview-panel--clone repo-overview-panel--clone-unmapped">
        <div className="repo-overview-panel__clone-msg">
          No local clone yet — branches & commits unavailable.
        </div>
        <button type="button" className="repo-overview-panel__clone-cta" onClick={onOpenBranches}>
          Map a local clone…
        </button>
      </div>
    );
  }
  return (
    <div className="repo-overview-panel repo-overview-panel--clone">
      <div className="repo-overview-panel__clone-left">
        <span className="repo-overview-panel__clone-label">Local clone</span>
        <span className="repo-overview-panel__clone-path" title={status.localClonePath}>
          {compactPath(status.localClonePath)}
        </span>
      </div>
      {status.currentBranch && (
        <code className="repo-overview-panel__clone-branch">{status.currentBranch}</code>
      )}
      <span className={`repo-pill repo-pill--${status.state.toLowerCase()}`}>
        {status.state === 'CLEAN' ? 'Clean'
          : status.state === 'MODIFIED' ? `${status.dirtyFileCount ?? '?'} modified`
          : status.state === 'MISSING' ? 'Missing'
          : status.state === 'GIT_UNAVAILABLE' ? 'No git'
          : status.state === 'ERROR' ? 'Error'
          : 'Unmapped'}
      </span>
      <button type="button" className="repo-overview-panel__clone-action" onClick={onOpenBranches}>
        Open branches →
      </button>
    </div>
  );
}

function PullRequestsPanel({
  pulls,
  meLogin,
  onOpenAll,
  onSelectPr,
}: {
  pulls: PullRequestDto[];
  meLogin: string | null;
  onOpenAll: () => void;
  onSelectPr: (number: number) => void;
}) {
  const [filter, setFilter] = useState<PrFilter>('needs-you');
  const filtered = useMemo(() => {
    if (filter === 'needs-you') return pulls.filter(p => meLogin && (p.requestedReviewers ?? []).includes(meLogin));
    if (filter === 'yours') return pulls.filter(p => p.author === meLogin);
    return pulls;
  }, [pulls, filter, meLogin]);
  const counts = {
    needsYou: meLogin ? pulls.filter(p => (p.requestedReviewers ?? []).includes(meLogin)).length : 0,
    yours: meLogin ? pulls.filter(p => p.author === meLogin).length : 0,
    all: pulls.length,
  };
  const visible = filtered.slice(0, 6);
  return (
    <div className="repo-overview-panel repo-overview-panel--prs">
      <div className="repo-overview-panel__head">
        <span className="repo-overview-panel__head-label">Pull requests · {pulls.length} open</span>
        <button type="button" className="repo-overview-panel__head-link" onClick={onOpenAll}>
          View all on the Pull Requests tab →
        </button>
      </div>
      <div className="repo-overview-panel__pr-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={filter === 'needs-you'}
          className={`repo-overview-panel__pr-tab${filter === 'needs-you' ? ' repo-overview-panel__pr-tab--active' : ''}`}
          onClick={() => setFilter('needs-you')}
        >
          Needs your review {counts.needsYou > 0 && <span>{counts.needsYou}</span>}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filter === 'yours'}
          className={`repo-overview-panel__pr-tab${filter === 'yours' ? ' repo-overview-panel__pr-tab--active' : ''}`}
          onClick={() => setFilter('yours')}
        >
          Yours {counts.yours > 0 && <span>{counts.yours}</span>}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filter === 'all-open'}
          className={`repo-overview-panel__pr-tab${filter === 'all-open' ? ' repo-overview-panel__pr-tab--active' : ''}`}
          onClick={() => setFilter('all-open')}
        >
          All open {counts.all > 0 && <span>{counts.all}</span>}
        </button>
      </div>
      <ul className="repo-overview-panel__pr-list">
        {visible.length === 0 && (
          <li className="repo-overview-panel__pr-empty">
            {filter === 'needs-you' ? 'No PRs awaiting your review.' : filter === 'yours' ? "You don't have any open PRs in this repo." : 'No open PRs.'}
          </li>
        )}
        {visible.map(p => (
          <PrMiniRow key={p.id} pr={p} onClick={() => onSelectPr(p.number)} />
        ))}
      </ul>
    </div>
  );
}

function PrMiniRow({ pr, onClick }: { pr: PullRequestDto; onClick: () => void }) {
  // Urgency color reflects the most-load-bearing signal: red when
  // changes were requested, amber when stale, green when approved,
  // blue otherwise. Keeps the at-a-glance scan informative.
  const verdicts = Object.values(pr.reviewerVerdicts ?? {});
  const changesRequested = verdicts.includes('CHANGES_REQUESTED');
  const approved = !changesRequested && verdicts.includes('APPROVED');
  // 14d since last activity is the same threshold the daily-cards
  // surfacing uses for "stale" — keeps the signal consistent.
  const stale = !changesRequested && !approved
    && pr.updatedAt
    && (Date.now() - new Date(pr.updatedAt).getTime()) > 14 * 24 * 3600 * 1000;
  const urgency = changesRequested ? 'red'
    : stale ? 'amber'
    : approved ? 'green'
    : 'blue';
  const stateLabel = pr.draft ? 'DRAFT'
    : changesRequested ? 'CHANGES'
    : stale ? 'STALE'
    : approved ? 'APPROVED'
    : 'OPEN';
  return (
    <li
      className={`pr-mini pr-mini--${urgency}`}
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
    >
      <span className="pr-mini__number">#{pr.number}</span>
      <div className="pr-mini__main">
        <div className="pr-mini__title">{pr.title}</div>
        <div className="pr-mini__meta">
          @{pr.author ?? '?'} ·{' '}
          {pr.requestedReviewers && pr.requestedReviewers.length > 0
            ? `${pr.requestedReviewers.length} reviewer${pr.requestedReviewers.length === 1 ? '' : 's'} requested`
            : 'no reviewers'} ·{' '}
          {pr.updatedAt ? formatRelativeTime(pr.updatedAt) : 'no activity'}
        </div>
      </div>
      <span className={`pr-mini__pill pr-mini__pill--${urgency}`}>{stateLabel}</span>
    </li>
  );
}

function AboutPanel({ meta }: { meta: RepoMetaDto | null }) {
  if (!meta) {
    return (
      <div className="repo-overview-panel">
        <div className="repo-overview-panel__h">About</div>
        <div className="repo-overview-panel__loading">Loading…</div>
      </div>
    );
  }
  const totalBytes = Object.values(meta.languages).reduce((a, b) => a + b, 0);
  const langs = Object.entries(meta.languages)
    .map(([name, bytes]) => ({ name, pct: totalBytes > 0 ? (bytes / totalBytes) * 100 : 0 }))
    .sort((a, b) => b.pct - a.pct)
    .slice(0, 6);
  return (
    <div className="repo-overview-panel">
      <div className="repo-overview-panel__h">About</div>
      {meta.description && <p className="repo-overview-panel__about-desc">{meta.description}</p>}
      <div className="repo-overview-panel__about-chips">
        {meta.htmlUrl && (
          <a className="repo-overview-panel__chip" href={meta.htmlUrl} target="_blank" rel="noreferrer">
            {meta.htmlUrl.replace(/^https?:\/\//, '')}
          </a>
        )}
        {meta.license && <span className="repo-overview-panel__chip">{meta.license}</span>}
      </div>
      {langs.length > 0 && (
        <>
          <div className="repo-overview-panel__sub-h">Languages</div>
          <div className="repo-overview-panel__lang-bar">
            {langs.map(l => (
              <span key={l.name} style={{ width: `${l.pct}%`, background: langColor(l.name) }} />
            ))}
          </div>
          <ul className="repo-overview-panel__lang-list">
            {langs.map(l => (
              <li key={l.name}>
                <span className="repo-overview-panel__lang-dot" style={{ background: langColor(l.name) }} />
                {l.name} {l.pct.toFixed(1)}%
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function AtAGlancePanel({
  meta,
  openPrCount,
  openIssueCount,
}: {
  meta: RepoMetaDto | null;
  openPrCount: number;
  openIssueCount: number;
}) {
  if (!meta) {
    return (
      <div className="repo-overview-panel">
        <div className="repo-overview-panel__h">At a glance</div>
        <div className="repo-overview-panel__loading">Loading…</div>
      </div>
    );
  }
  return (
    <div className="repo-overview-panel">
      <div className="repo-overview-panel__h">At a glance</div>
      <dl className="repo-overview-panel__meta">
        <dt>Default branch</dt>
        <dd>{meta.defaultBranch ?? '—'}</dd>
        <dt>Last push</dt>
        <dd>{meta.pushedAt ? formatRelativeTime(meta.pushedAt) : '—'}</dd>
        <dt>Open PRs</dt>
        <dd>{openPrCount}</dd>
        <dt>Open issues</dt>
        <dd>{openIssueCount}</dd>
        <dt>License</dt>
        <dd>{meta.license ?? '—'}</dd>
        <dt>Created</dt>
        <dd>{meta.createdAt ? new Date(meta.createdAt).toLocaleDateString(undefined, { month: 'short', year: 'numeric' }) : '—'}</dd>
      </dl>
    </div>
  );
}

function RepoAvatar({
  repo,
  avatarUrl = null,
  size = 28,
}: {
  repo: string;
  avatarUrl?: string | null;
  size?: number;
}) {
  if (avatarUrl) {
    return (
      <img
        className="repo-card__avatar repo-card__avatar--image"
        src={avatarUrl}
        alt=""
        width={size}
        height={size}
        style={{ width: size, height: size }}
        aria-hidden="true"
      />
    );
  }
  // Fallback: deterministic colour-and-letter placeholder. Used until
  // the meta row lands (very first ever visit) or for legacy rows
  // persisted before V44 added the avatar column.
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
      style={{ background: color, width: size, height: size, fontSize: Math.round(size * 0.5) }}
      aria-hidden="true"
    >
      {initial}
    </span>
  );
}

const LANG_COLORS: Record<string, string> = {
  Java: '#b07219',
  TypeScript: '#3178c6',
  JavaScript: '#f1e05a',
  Python: '#3572A5',
  Go: '#00ADD8',
  Rust: '#dea584',
  CSS: '#563d7c',
  HTML: '#e34c26',
  Shell: '#89e051',
  Kotlin: '#A97BFF',
  C: '#555555',
  'C++': '#f34b7d',
  'C#': '#178600',
};
function langColor(name: string): string {
  return LANG_COLORS[name] ?? '#888';
}

function compactPath(path: string): string {
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

export default RepositoryPage;

// Re-export for the route to detect "still loading" with a real component.
export function RepositoryLoading() {
  return (
    <div className="repository-page">
      <div className="repository-page__loading">
        <LogoLoading size={48} label="Loading repository" />
      </div>
    </div>
  );
}
