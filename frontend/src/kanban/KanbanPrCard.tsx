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
import type { CSSProperties } from 'react';
import type { PullRequestDto } from '../types';
import Avatar from '../Avatar';
import { formatRelative } from '../prBuckets';
import type { KanbanColumnKind } from './KanbanColumn';

type Props = {
  pr: PullRequestDto;
  column: KanbanColumnKind;
  /** When "team", the card surfaces the repo avatar in the head and the
   *  author + their avatar in the meta line. Inbox cards omit both —
   *  the user is implicitly the author and the repo column / sidebar
   *  already provides repo context. */
  mode?: 'inbox' | 'team';
  selected: boolean;
  onSelect: () => void;
  /** When provided, a small "✓" button appears in the top-right corner.
   *  Click marks the PR handled (sets handledAction='MANUAL') without
   *  navigating into it. Click stops propagation so the card's onSelect
   *  doesn't fire. Suppressed on done columns. */
  onHandle?: () => void;
  /** When provided AND the card is in the "handled" column, a small
   *  "↺" reopen button appears in the top-right corner. Clears the
   *  PR's handledAction and bounces it back into its proper column. */
  onReopen?: () => void;
};

/**
 * Rich PR card matching docs/mockups/v2/kanban/bytequay-pr-kanban-redesign.html.
 * Reads exclusively from the v26-enriched PullRequestDto — no detail fetch
 * required. Each card carries: status pill, optional info banner, two-line
 * title, opened/age meta, reviewer-avatar row with per-verdict status dot,
 * label chips, CI dot, comment count.
 *
 * Note: card-action buttons (Ping reviewers / Address feedback / Merge)
 * from the mockup are intentionally not rendered yet — they need new
 * backend endpoints + a confirmation flow we'll wire in a follow-up.
 */
function KanbanPrCard({ pr, column, mode = 'inbox', selected, onSelect, onHandle, onReopen }: Props) {
  // Defensive — a missed prop from a non-strict caller used to crash the
  // whole UI when .replace(...) was invoked on an undefined column. Treat
  // any missing column as a generic in-progress so the card still renders.
  const safeColumn: KanbanColumnKind = column ?? 'in_progress';
  const repoShort = pr.repo.includes('/') ? pr.repo.split('/').slice(-1)[0] : pr.repo;
  const repoOwner = pr.repo.includes('/') ? pr.repo.split('/')[0] : '';
  const banner = bannerFor(pr, safeColumn);
  const statusPill = statusPillFor(pr);
  const isUrgent = safeColumn === 'needs_attention' && pr.attentionReason !== null;
  const showAuthor = mode === 'team' && !!pr.author;
  // Repo avatar (owner's GitHub avatar) sits next to the repo-short name
  // in every mode — replaces the bare "owner/repo" text from the older
  // PrBucketViews cards. Owner-less repo strings (legacy rows without a
  // slash) hide the avatar gracefully.
  const showRepoAvatar = !!repoOwner;
  const openedLabel = pr.createdAt
    ? `opened ${formatRelative(pr.createdAt)}`
    : `updated ${formatRelative(pr.updatedAt)}`;

  const verdicts = pr.reviewerVerdicts ?? {};
  const requested = pr.requestedReviewers ?? [];
  // Reviewer-avatar list = anyone who's already weighed in + still-pending
  // requested reviewers, in that order, deduped. Caps at 4 to keep the row
  // stable; an "+N" chip stands in for the rest.
  const reviewerEntries = mergedReviewers(verdicts, requested);
  const reviewerCap = 4;
  const visibleReviewers = reviewerEntries.slice(0, reviewerCap);
  const overflow = reviewerEntries.length - visibleReviewers.length;

  const className = [
    'kpr-card',
    selected ? 'kpr-card--selected' : '',
    isUrgent ? 'kpr-card--urgent' : '',
    `kpr-card--col-${safeColumn.replace(/_/g, '-')}`,
  ].filter(Boolean).join(' ');

  return (
    <button
      type="button"
      className={className}
      onClick={onSelect}
      title={`${pr.repo} #${pr.number}`}
    >
      {banner && (
        <div className={`kpr-card__banner kpr-card__banner--${banner.tone}`}>
          {banner.icon} {banner.text}
        </div>
      )}
      <div className="kpr-card__head">
        <div className="kpr-card__repo">
          {showRepoAvatar && (
            <Avatar login={repoOwner} size={14} className="kpr-card__repo-avatar" />
          )}
          <span className="kpr-card__repo-name">{repoShort}</span>
          <span className="kpr-card__num">#{pr.number}</span>
        </div>
        <div className="kpr-card__head-right">
          {statusPill && (
            <span className={`kpr-card__pill kpr-card__pill--${statusPill.kind}`}>
              {statusPill.label}
            </span>
          )}
          {/* Quick-dismiss "✓": drops the PR into the Handled column
              without opening it. Suppressed on already-done columns. */}
          {onHandle
              && safeColumn !== 'cleared_today'
              && safeColumn !== 'recently_merged'
              && safeColumn !== 'handled' && (
            <button
              type="button"
              className="kpr-card__handle"
              onClick={(e) => { e.stopPropagation(); onHandle(); }}
              title="Mark handled — drops this PR into Handled without opening it."
              aria-label="Mark this PR handled"
            >
              ✓
            </button>
          )}
          {/* Reopen "↺": only shown on the Handled column. Pulls the PR
              back into its proper active column. */}
          {onReopen && safeColumn === 'handled' && (
            <button
              type="button"
              className="kpr-card__handle kpr-card__handle--reopen"
              onClick={(e) => { e.stopPropagation(); onReopen(); }}
              title="Reopen — moves this PR back into its active column."
              aria-label="Reopen this PR"
            >
              ↺
            </button>
          )}
        </div>
      </div>
      <div className="kpr-card__title">{pr.title}</div>
      <div className="kpr-card__meta">
        {showAuthor && pr.author && (
          <>
            <span className="kpr-card__author" title={pr.author}>
              <Avatar login={pr.author} size={16} className="kpr-card__author-avatar" />
              <span className="kpr-card__author-login">{pr.author}</span>
            </span>
            <span className="kpr-card__meta-sep">·</span>
          </>
        )}
        <span>{openedLabel}</span>
        {visibleReviewers.length > 0 && (
          <>
            <span className="kpr-card__meta-sep">·</span>
            <span className="kpr-card__reviewers">
              {visibleReviewers.map(({ login, verdict }) => (
                <span
                  key={login}
                  className={`kpr-card__reviewer kpr-card__reviewer--${verdictClass(verdict)}`}
                  title={`${login}${verdict ? ` · ${verdict.toLowerCase().replace(/_/g, ' ')}` : ' · pending'}`}
                >
                  <Avatar login={login} size={18} className="kpr-card__reviewer-avatar" />
                </span>
              ))}
              {overflow > 0 && (
                <span className="kpr-card__reviewer-overflow" title={`${overflow} more`}>
                  +{overflow}
                </span>
              )}
            </span>
          </>
        )}
        {reviewerEntries.length > 0 && (
          <span className="kpr-card__reviewer-count">
            {reviewerEntries.length} {reviewerEntries.length === 1 ? 'reviewer' : 'reviewers'}
          </span>
        )}
        {reviewerEntries.length === 0 && requested.length === 0 && (
          <>
            <span className="kpr-card__meta-sep">·</span>
            <span className="kpr-card__reviewer-count kpr-card__reviewer-count--empty">no reviewers yet</span>
          </>
        )}
      </div>
      <div className="kpr-card__foot">
        <div className="kpr-card__tags">
          {pr.labels.slice(0, 3).map(label => (
            <span
              key={label}
              className="kpr-card__tag"
              style={tagStyle(pr.labelColors?.[label])}
              title={label}
            >
              {label}
            </span>
          ))}
          {pr.labels.length > 3 && (
            <span className="kpr-card__tag kpr-card__tag--more">+{pr.labels.length - 3}</span>
          )}
        </div>
        <div className="kpr-card__signals">
          {/* Card foot keeps only the CI dot now — the comment count and
              +/− diff signals were noise more than info on a card-sized
              surface; they live on the PR detail page where there's
              room. */}
          {pr.ciStatus && pr.ciStatus !== 'NONE' && (
            <span className="kpr-card__signal" title={`CI: ${pr.ciStatus.toLowerCase()}`}>
              <span className={`kpr-card__ci-dot kpr-card__ci-dot--${pr.ciStatus.toLowerCase()}`} />
              CI
            </span>
          )}
        </div>
      </div>
    </button>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────────

type Banner = { tone: 'danger' | 'warn' | 'info' | 'success'; icon: string; text: string };

/** Computes the optional info banner above the card title. Priority order
 *  matches the mockup: blocking failures > stale > approval > mention. */
function bannerFor(pr: PullRequestDto, column: KanbanColumnKind): Banner | null {
  const verdicts = pr.reviewerVerdicts ?? {};
  const changesRequesters = Object.entries(verdicts)
    .filter(([, state]) => state === 'CHANGES_REQUESTED')
    .map(([login]) => login);

  if (changesRequesters.length > 0 && (column === 'needs_changes' || column === 'needs_attention')) {
    const who = changesRequesters[0];
    const more = changesRequesters.length > 1 ? ` +${changesRequesters.length - 1}` : '';
    // We can't say "N unaddressed comments" without GraphQL — that's the
    // Phase 2 follow-up. For now we just name the reviewer.
    return { tone: 'danger', icon: '⊘', text: `${who}${more} requested changes` };
  }

  if (pr.attentionReason === 'CI_FAILING') {
    return { tone: 'danger', icon: '✗', text: 'CI failing' };
  }
  if (pr.attentionReason === 'MERGE_CONFLICT') {
    return { tone: 'danger', icon: '⊘', text: 'Merge conflict — needs rebase' };
  }
  if (pr.attentionReason === 'MENTIONED') {
    return { tone: 'info', icon: '@', text: 'mentioned you' };
  }

  if (column === 'waiting_on_review') {
    const days = daysSince(pr.updatedAt);
    if (days >= 6) return { tone: 'warn', icon: '⏱', text: `Stale · no review in ${days} days` };
  }

  if (column === 'ready_to_merge') {
    const approver = Object.entries(verdicts).find(([, s]) => s === 'APPROVED')?.[0];
    return {
      tone: 'success',
      icon: '✓',
      text: approver ? `Approved by ${approver} · all checks passing` : 'Approved · all checks passing',
    };
  }

  return null;
}

type StatusPill = { kind: 'draft' | 'opened' | 'changes' | 'approved' | 'merged' | 'closed'; label: string };

function statusPillFor(pr: PullRequestDto): StatusPill | null {
  if (pr.draft) return { kind: 'draft', label: 'Draft' };
  if (pr.state === 'merged' || pr.mergedAt) return { kind: 'merged', label: 'Merged' };
  if (pr.state === 'closed') return { kind: 'closed', label: 'Closed' };
  const verdicts = Object.values(pr.reviewerVerdicts ?? {});
  if (verdicts.includes('CHANGES_REQUESTED')) return { kind: 'changes', label: 'Changes' };
  if (verdicts.includes('APPROVED')) return { kind: 'approved', label: 'Approved' };
  return { kind: 'opened', label: 'Opened' };
}

function mergedReviewers(
  verdicts: Record<string, string>,
  requested: string[],
): Array<{ login: string; verdict: string | null }> {
  const seen = new Set<string>();
  const out: Array<{ login: string; verdict: string | null }> = [];
  // Reviewers who already submitted a verdict come first so their dot
  // colour reads as the dominant signal.
  for (const [login, verdict] of Object.entries(verdicts)) {
    if (seen.has(login)) continue;
    seen.add(login);
    out.push({ login, verdict });
  }
  for (const login of requested) {
    if (seen.has(login)) continue;
    seen.add(login);
    out.push({ login, verdict: null });
  }
  return out;
}

function verdictClass(verdict: string | null): string {
  switch (verdict) {
    case 'APPROVED': return 'approved';
    case 'CHANGES_REQUESTED': return 'changes';
    case 'COMMENTED': return 'commented';
    case 'DISMISSED': return 'dismissed';
    default: return 'requested';
  }
}

function daysSince(iso: string | null): number {
  if (!iso) return 0;
  const ms = Date.now() - new Date(iso).getTime();
  return Math.floor(ms / (24 * 60 * 60 * 1000));
}

/** Inline style for a label chip, mirroring the same hex-→ readable-text
 *  treatment we use elsewhere on PR labels. Returns undefined to fall
 *  back to the neutral chip styling when GitHub didn't give us a colour. */
function tagStyle(color: string | null | undefined): CSSProperties | undefined {
  if (!color || !/^[0-9a-fA-F]{6}$/.test(color)) return undefined;
  const r = parseInt(color.slice(0, 2), 16);
  const g = parseInt(color.slice(2, 4), 16);
  const b = parseInt(color.slice(4, 6), 16);
  const luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  const text = luma > 0.6 ? '#1f2937' : '#ffffff';
  return { background: `#${color}`, color: text, borderColor: 'transparent' };
}

export default KanbanPrCard;
