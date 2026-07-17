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
import { useMemo, useState, type ReactNode } from 'react';
import type { PullRequestDto } from '../types';

type View = 'board' | 'list';
type Filter = 'review' | 'mine' | 'all';
type Bucket = 'attention' | 'progress' | 'cleared';

/**
 * The exact board/list surface shared by workspace Pull requests and global
 * remote-only Reviews. Workspace callers hide repository chips; the global
 * caller shows them and deliberately exposes no local-code affordances.
 */
export default function PullRequestBoardList({
  title,
  rows,
  loading,
  error,
  showRepository,
  remoteOnly = false,
  initialView = 'board',
  initialFilter = 'review',
  initialIncludeClosed = false,
  countOverride,
  onOpen,
  onRefresh,
}: {
  title: string;
  rows: PullRequestDto[];
  loading: boolean;
  error: string | null;
  showRepository: boolean;
  remoteOnly?: boolean;
  /** Deterministic initial state for visual fixtures and deep-link restores. */
  initialView?: View;
  initialFilter?: Filter;
  initialIncludeClosed?: boolean;
  countOverride?: { review: number; mine: number; open: number };
  onOpen: (pr: PullRequestDto) => void;
  onRefresh: () => void;
}) {
  const [view, setView] = useState<View>(initialView);
  const [filter, setFilter] = useState<Filter>(initialFilter);
  const [includeClosed, setIncludeClosed] = useState(initialIncludeClosed);
  const filtered = rows.filter(pr => {
    if (!includeClosed && pr.state !== null && pr.state !== 'open') return false;
    if (filter === 'review') return pr.origin === 'REVIEW_REQUESTED';
    if (filter === 'mine') return pr.origin === 'AUTHORED';
    return true;
  });
  const columns = useMemo(() => {
    const result: Record<Bucket, PullRequestDto[]> = {
      attention: [],
      progress: [],
      cleared: [],
    };
    for (const pr of filtered) result[bucketFor(pr)].push(pr);
    return result;
  }, [filtered]);
  const reviewCount = countOverride?.review
    ?? rows.filter(pr => pr.origin === 'REVIEW_REQUESTED' && pr.state === 'open').length;
  const mineCount = countOverride?.mine
    ?? rows.filter(pr => pr.origin === 'AUTHORED' && pr.state === 'open').length;
  const openCount = countOverride?.open
    ?? rows.filter(pr => pr.state === 'open').length;

  return (
    <section className="wu-page wu-prs">
      <header className="wu-page-header">
        <div className="wu-page-heading">
          <span className="wu-pr-page-title">{title}</span>
          {view === 'board' && <span className="wu-pr-open-count">{openCount} open</span>}
          <div className="wu-pr-view-toggle">
            <Segmented<View>
              options={[['board', <><BoardIcon /> Board</>], ['list', <><ListIcon /> List</>]] as const}
              value={view}
              onChange={setView}
            />
          </div>
          {remoteOnly && <i className="wu-remote-only-chip">REMOTE ONLY</i>}
        </div>
        <div className="wu-header-actions">
          <Segmented<Filter>
            options={[
              ['review', view === 'board' ? `To review · ${reviewCount}` : 'To review'],
              ['mine', view === 'board' ? `Mine · ${mineCount}` : 'Mine'],
              ['all', view === 'board' ? `All · ${openCount}` : 'All open'],
            ] as const}
            value={filter}
            onChange={setFilter}
          />
          {view === 'list' && (
            <label className="wu-check-label">
              <input
                type="checkbox"
                checked={includeClosed}
                onChange={event => setIncludeClosed(event.target.checked)}
              />
              <span className="wu-check-box" aria-hidden><CheckIcon /></span>
              Include closed
            </label>
          )}
          {view === 'board' && (
            <button type="button" className="wu-icon-button" onClick={onRefresh}>
              <RefreshIcon /> Refresh
            </button>
          )}
        </div>
      </header>
      {remoteOnly && (
        <div className="wu-remote-only-note">
          GitHub data only. Local source, branches, tests, memory, and workspace Sessions are intentionally unavailable.
        </div>
      )}
      {error !== null ? <BodyMessage>{error}</BodyMessage>
        : loading ? <BodyMessage>Loading pull requests…</BodyMessage>
          : view === 'board' ? (
            <div className="wu-pr-board">
              <PrColumn title="Needs attention" tone="attention" rows={columns.attention}
                showRepository={showRepository} onOpen={onOpen} />
              <PrColumn title="In progress" tone="progress" rows={columns.progress}
                showRepository={showRepository} onOpen={onOpen} />
              <PrColumn title="Cleared today" tone="cleared" rows={columns.cleared}
                showRepository={showRepository} onOpen={onOpen} />
            </div>
          ) : (
            <div className="wu-table wu-pr-list">
              {filtered.map(pr => (
                <PrListRow key={`${pr.repo}:${pr.id}`} pr={pr}
                  showRepository={showRepository} onOpen={onOpen} />
              ))}
              {filtered.length === 0 && <BodyMessage>No pull requests match this view.</BodyMessage>}
            </div>
          )}
    </section>
  );
}

function PrColumn({
  title,
  tone,
  rows,
  showRepository,
  onOpen,
}: {
  title: string;
  tone: Bucket;
  rows: PullRequestDto[];
  showRepository: boolean;
  onOpen: (pr: PullRequestDto) => void;
}) {
  return (
    <section className={`wu-pr-column ${tone}`}>
      <div className="wu-pr-column__header">
        <span className="wu-pr-column__dot" />
        <span className="wu-pr-column__title">{title}</span>
        <span>{rows.length}</span>
      </div>
      {rows.map(pr => (
        <PrCard key={`${pr.repo}:${pr.id}`} pr={pr}
          showRepository={showRepository} onOpen={onOpen} />
      ))}
      {tone === 'cleared' ? (
        <div className="wu-cleared-empty">Cleared PRs collapse here<br />and reset at midnight</div>
      ) : rows.length === 0 && <div className="wu-pr-column__empty">Nothing here</div>}
    </section>
  );
}

function PrCard({ pr, showRepository, onOpen }: {
  pr: PullRequestDto;
  showRepository: boolean;
  onOpen: (pr: PullRequestDto) => void;
}) {
  const badge = statusBadge(pr);
  return (
    <div
      role="button"
      tabIndex={0}
      className={`wu-pr-card${pr.ciStatus === 'FAILING' ? ' failing' : ''}`}
      onClick={() => onOpen(pr)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen(pr);
        }
      }}
    >
      <div className="wu-pr-card__top">
        {pr.ciStatus === 'FAILING'
          ? <i className="wu-pr-badge danger"><FailureIcon />CI failing</i>
          : <span className="wu-pr-card__eyebrow">#{pr.number}</span>}
        {pr.ciStatus === 'FAILING'
          ? <span className="wu-pr-card__number wu-pr-card__number--literal">#{pr.number}</span>
          : badge.length > 0
            ? <i className={`wu-pr-badge wu-pr-card__status ${statusTone(pr)}`}>{badge}</i>
            : null}
      </div>
      {showRepository && <span className="wu-pr-repo-chip">{pr.repo}</span>}
      <span className="wu-pr-card__title">{pr.title}</span>
      <div className="wu-pr-card__labels">
        {pr.labels.slice(0, 2).map(label => (
          <i className={labelTone(label)} key={label}>{label}</i>
        ))}
        {pr.labels.length > 2 && <small>+{pr.labels.length - 2}</small>}
      </div>
      <div className="wu-pr-card__meta">
        <span>{isToday(pr.reviewedAt) ? 'reviewed by you' : `@${pr.author ?? 'unknown'}`}</span>
        {pr.ciStatus === 'PASSING' && !isToday(pr.reviewedAt)
          && <span className="wu-ci-ok"><CiCheckIcon />build</span>}
        <time>{relative(pr.updatedAt)}</time>
      </div>
    </div>
  );
}

function PrListRow({ pr, showRepository, onOpen }: {
  pr: PullRequestDto;
  showRepository: boolean;
  onOpen: (pr: PullRequestDto) => void;
}) {
  return (
    <div className="wu-table-row" role="button" tabIndex={0}
      onClick={() => onOpen(pr)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen(pr);
        }
      }}>
      <PullIcon tone={pr.state === 'merged'
        ? 'merged'
        : pr.state === 'closed'
          ? 'closed'
          : pr.draft ? 'neutral' : 'open'} />
      <div className="wu-table-row__main">
        <span className="wu-pr-list-title">
          {pr.title}
          {showRepository && <i className="wu-pr-repo-chip">{pr.repo}</i>}
        </span>
        <small><ListMeta pr={pr} /></small>
      </div>
      <span className={`wu-pr-badge ${listStatusTone(pr)}`}>
        {listStatus(pr)}
      </span>
      {pr.ciStatus === 'PASSING' && pr.state === 'open' && !pr.draft
        && <span className="wu-ci-ok"><CiCheckIcon /><span>CI</span></span>}
      <span className="wu-delta"><b>+{pr.additions}</b> <b className="wu-delta-deletions">−{pr.deletions}</b></span>
    </div>
  );
}

function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: readonly (readonly [T, ReactNode])[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div className="wu-segmented">
      {options.map(([key, label]) => (
        <div role="button" tabIndex={0} key={key} className={value === key ? 'active' : ''}
          onClick={() => onChange(key)}
          onKeyDown={event => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              onChange(key);
            }
          }}>
          {label}
        </div>
      ))}
    </div>
  );
}

function BodyMessage({ children }: { children: ReactNode }) {
  return <div className="wu-body-message">{children}</div>;
}

function bucketFor(pr: PullRequestDto): Bucket {
  if (isToday(pr.mergedAt) || isToday(pr.reviewedAt)) return 'cleared';
  if (pr.attentionReason !== null
      || (pr.origin === 'REVIEW_REQUESTED' && pr.handledAction === null)) {
    return 'attention';
  }
  return 'progress';
}

function statusBadge(pr: PullRequestDto): string {
  if (isToday(pr.reviewedAt)) return 'Reviewed';
  if (pr.handledAction === 'APPROVED') return 'Approved';
  if (pr.attentionReason === 'STALE') return 'Stale';
  if (pr.draft) return 'Draft';
  if (pr.state === 'merged') return 'Merged';
  if (pr.state === 'closed') return 'Closed';
  return '';
}

function statusTone(pr: PullRequestDto): string {
  if (pr.attentionReason === 'STALE') return 'stale';
  if (isToday(pr.reviewedAt)) return 'reviewed';
  if (pr.handledAction === 'APPROVED') return 'approved';
  return pr.draft ? 'quiet' : pr.state ?? '';
}

function listStatus(pr: PullRequestDto): string {
  if (pr.reviewRound !== null && pr.reviewRound !== undefined && pr.state === 'open') {
    return `review round ${pr.reviewRound} open`;
  }
  if (pr.draft) return 'draft · local';
  if (pr.state === 'merged') return 'merged';
  if (pr.state === 'closed') return 'closed';
  return statusBadge(pr);
}

function listStatusTone(pr: PullRequestDto): string {
  if (pr.reviewRound !== null && pr.reviewRound !== undefined && pr.state === 'open') {
    return 'review-round';
  }
  return statusTone(pr);
}

function ListMeta({ pr }: { pr: PullRequestDto }) {
  if (pr.state === 'merged') {
    return <>#{pr.number} · merged {relative(pr.mergedAt ?? pr.updatedAt)} ago
      {pr.linkedTaskKey ? ` · task ${pr.linkedTaskKey.replace(/^TASK-/i, '#')}` : ''}</>;
  }
  if (pr.state === 'closed') {
    return <>#{pr.number} · closed {relative(pr.closedAt ?? pr.updatedAt)} ago
      {pr.supersededBy ? ` · superseded by #${pr.supersededBy}` : ''}</>;
  }
  return <>#{pr.number} · <span className="wu-pr-head-ref">{pr.headRef ?? 'branch'}</span>
    {' · '}updated {relative(pr.updatedAt)} ago</>;
}

function labelTone(label: string): string {
  if (label === 'notable') return 'green';
  if (label === 'exasol') return 'pink';
  return '';
}

function isToday(iso: string | null): boolean {
  if (iso === null) return false;
  const date = new Date(iso);
  const now = new Date();
  return date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
}

function relative(iso: string): string {
  const delta = Date.now() - Date.parse(iso);
  if (!Number.isFinite(delta) || delta < 60_000) return 'now';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h`;
  return `${Math.floor(delta / 86_400_000)}d`;
}

function PullIcon({ tone }: { tone: 'open' | 'merged' | 'closed' | 'neutral' }) {
  const merged = tone === 'merged';
  return (
    <span className={`wu-pull-icon ${tone}`} aria-hidden>
      <svg viewBox="0 0 24 24" width="15" height="15" fill="none"
        stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="18" cy="18" r="2.6" />
        <circle cx="6" cy="6" r="2.6" />
        {merged
          ? <path d="M6 21V9a9 9 0 0 0 9 9" />
          : (
            <>
              <path d="M13 6h3a2 2 0 0 1 2 2v7" />
              <path d="M6 9v12" />
            </>
          )}
        {tone === 'closed' && (
          <>
            <path d="m14.5 12.5 5-5" />
            <path d="m19.5 12.5-5-5" />
          </>
        )}
      </svg>
    </span>
  );
}

function RefreshIcon() {
  return (
    <svg viewBox="0 0 24 24" width="12" height="12" fill="none"
      stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12a9 9 0 1 1-2.6-6.3" />
      <path d="M21 3v6h-6" />
    </svg>
  );
}

function FailureIcon() {
  return (
    <svg viewBox="0 0 24 24" width="10" height="10" fill="none"
      stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}

function BoardIcon() {
  return (
    <svg viewBox="0 0 24 24" width="11" height="11" fill="none"
      stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <rect x="3" y="4" width="5.5" height="16" rx="1.5" />
      <rect x="9.5" y="4" width="5.5" height="12" rx="1.5" />
      <rect x="16" y="4" width="5.5" height="8" rx="1.5" />
    </svg>
  );
}

function ListIcon() {
  return (
    <svg viewBox="0 0 24 24" width="11" height="11" fill="none"
      stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <path d="M4 6h16" />
      <path d="M4 12h16" />
      <path d="M4 18h16" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg viewBox="0 0 24 24" width="9" height="9" fill="none"
      stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

function CiCheckIcon() {
  return (
    <svg viewBox="0 0 24 24" width="11" height="11" fill="none"
      stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}
