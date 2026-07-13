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
import { useEffect, useRef, useState, type DragEvent, type ReactNode } from 'react';
import Avatar from '../Avatar';
import { formatRelative, type PrLikeWithId } from '../prBuckets';
import { Tag, type TagColor } from '../ui/primitives';
import type { KanbanColumnKind } from './KanbanColumn';

/** Always-on labels (present on nearly every PR) that carry no decision
 *  signal on a kanban card — suppressed from the tag row.
 *  TODO(config): make this per-repo configurable. */
const SUPPRESSED_LABELS = new Set(['cla-signed', 'docs']);

/** Max soft-tint label tags on a card before the neutral "+N" overflow. */
const MAX_VISIBLE_TAGS = 2;

/** Days without activity before an open PR earns the neutral STALE pill.
 *  Same threshold as the stale banner. TODO(config): make configurable. */
const STALE_DAYS = 6;

type Props<T extends PrLikeWithId> = {
  pr: T;
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
  /** When provided, a small ⌛ button appears next to ✓. Click opens
   *  a time-preset menu; picking one calls onSnooze with the chosen
   *  ISO-8601 wake instant. Suppressed on already-done columns. */
  onSnooze?: (untilIso: string) => void;
  /** P1 agent-review quick action. The caller owns the session mutation;
   * this card only provides the locked hover affordance. */
  onAgentReview?: () => void;
  reviewState?: 'none' | 'running' | 'done' | 'stale';
  /** When true, the card root is HTML5-draggable. The MIME payload
   *  encodes {prId, fromColumn, repo, number} so a drop target can
   *  validate the transition without a separate state lookup. */
  draggable?: boolean;
  /** When true, the card renders as a static surface — the root
   *  button is disabled so click-to-open is suppressed and the
   *  cursor stays default. Used by the Snoozed page where opening
   *  the detail breaks the row layout. */
  disabled?: boolean;
};

/** Custom MIME used for in-app PR drag/drop. Pinned name + JSON body
 *  so the drop site can detect "is this our drag?" without parsing. */
export const PR_DRAG_MIME = 'application/x-bytequay-pr';

export type PrDragPayload = {
  prId: number | string;
  fromColumn: KanbanColumnKind;
  repo: string;
  number: number;
};

/**
 * Rich PR card matching docs/mockups/v3/design/pr-kanban.html (unified
 * V3 card chrome, design.md #30). Reads exclusively from the
 * v26-enriched PR row (repo/team-scoped `PullRequestDto` or the personal
 * dashboard's `DashboardPR`) — no detail fetch required. Anatomy:
 * optional info banner → ref-row (#num + repo + exceptional-state pill)
 * → two-line title → soft-tint label tags (+N overflow) → dashed
 * meta-row (author · BUILD · reviewer stack · timestamp).
 *
 * Note: card-action buttons (Ping reviewers / Address feedback / Merge)
 * from the mockup are intentionally not rendered yet — they need new
 * backend endpoints + a confirmation flow we'll wire in a follow-up.
 */
function KanbanPrCard<T extends PrLikeWithId>({ pr, column, mode = 'inbox', selected, onSelect, onHandle, onReopen, onSnooze, onAgentReview, reviewState = 'none', draggable = false, disabled = false }: Props<T>) {
  // Defensive — a missed prop from a non-strict caller used to crash the
  // whole UI when .replace(...) was invoked on an undefined column. Treat
  // any missing column as a generic in-progress so the card still renders.
  const safeColumn: KanbanColumnKind = column ?? 'in_progress';
  const repoShort = pr.repo.includes('/') ? pr.repo.split('/').slice(-1)[0] : pr.repo;
  const repoOwner = pr.repo.includes('/') ? pr.repo.split('/')[0] : '';
  const banner = bannerFor(pr, safeColumn);
  const statusPill = statusPillFor(pr, safeColumn);
  // Label tags after the suppression list, capped with a "+N" overflow.
  const visibleLabels = pr.labels.filter(l => !SUPPRESSED_LABELS.has(l.toLowerCase()));
  const shownLabels = visibleLabels.slice(0, MAX_VISIBLE_TAGS);
  const overflowLabels = visibleLabels.length - shownLabels.length;
  const isUrgent = safeColumn === 'needs_attention' && pr.attentionReason !== null;
  const showAuthor = mode === 'team' && !!pr.author;
  // Repo avatar (owner's GitHub avatar) sits next to the repo-short name
  // in every mode — replaces the bare "owner/repo" text from the older
  // PrBucketViews cards. Owner-less repo strings (legacy rows without a
  // slash) hide the avatar gracefully.
  const showRepoAvatar = !!repoOwner;
  // The card footer shows LAST ACTIVITY (updatedAt), because the columns
  // sort by updatedAt — labelling it with the open date made the top card
  // look "6 days old" when it was actually the most recently active one.
  // The tooltip keeps the open date for reference.
  const timeTooltip = [
    pr.createdAt ? `opened ${formatRelative(pr.createdAt)}` : null,
    pr.updatedAt ? `last active ${formatRelative(pr.updatedAt)}` : null,
  ].filter(Boolean).join(' · ');

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
    disabled ? 'kpr-card--static' : '',
    `kpr-card--col-${safeColumn.replace(/_/g, '-')}`,
  ].filter(Boolean).join(' ');

  // Drag handler — only attached when draggable is true so non-draggable
  // cards (team kanban, etc.) don't accidentally trigger native drag.
  const onDragStart = draggable
    ? (e: DragEvent<HTMLButtonElement>) => {
        const payload: PrDragPayload = {
          prId: pr.id,
          fromColumn: safeColumn,
          repo: pr.repo,
          number: pr.number,
        };
        e.dataTransfer.setData(PR_DRAG_MIME, JSON.stringify(payload));
        e.dataTransfer.effectAllowed = 'move';
      }
    : undefined;

  return (
    <button
      type="button"
      className={className}
      onClick={onSelect}
      title={`${pr.repo} #${pr.number}`}
      draggable={draggable}
      onDragStart={onDragStart}
      disabled={disabled}
    >
      {banner && (
        <div className={`kpr-card__banner kpr-card__banner--${banner.tone}`}>
          {banner.icon} {banner.text}
        </div>
      )}
      <div className="kpr-card__head">
        <div className="kpr-card__repo">
          <span className="kpr-card__num">#{pr.number}</span>
          {showRepoAvatar && (
            <Avatar login={repoOwner} size={12} className="kpr-card__repo-avatar" />
          )}
          <span className="kpr-card__repo-name">{repoShort}</span>
        </div>
        <div className="kpr-card__head-right">
          {reviewState !== 'none' && <span className={`kpr-card__review-state kpr-card__review-state--${reviewState}`} title={`Agent review: ${reviewState}`}>⚖</span>}
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
          {/* Snooze "⌛": opens a time-preset popup. Suppressed on done
              columns and on the Handled tab — there's nothing to park. */}
          {onSnooze
              && safeColumn !== 'cleared_today'
              && safeColumn !== 'recently_merged'
              && safeColumn !== 'handled' && (
            <SnoozeMenuButton onPick={onSnooze} />
          )}
          {onAgentReview !== undefined
              && safeColumn !== 'cleared_today'
              && safeColumn !== 'recently_merged'
              && safeColumn !== 'handled' && (
            <button
              type="button"
              className="kpr-card__handle kpr-card__agent-review"
              onClick={(event) => { event.stopPropagation(); onAgentReview(); }}
              title="Review with agent"
              aria-label="Review with agent"
            >
              ⚖
            </button>
          )}
        </div>
      </div>
      <div className="kpr-card__title">{pr.title}</div>
      {(shownLabels.length > 0 || overflowLabels > 0) && (
        <div className="kpr-card__labels">
          {shownLabels.map(label => (
            <Tag key={label} color={tagColor(label)}>{label}</Tag>
          ))}
          {overflowLabels > 0 && <Tag color="plain">+{overflowLabels}</Tag>}
        </div>
      )}
      <div className="kpr-card__foot">
        <div className="kpr-card__author">
          {showAuthor && pr.author ? (
            <>
              <Avatar login={pr.author} size={16} className="kpr-card__author-avatar" />
              <span className="kpr-card__author-login">{pr.author}</span>
            </>
          ) : (
            <span className="kpr-card__author-login kpr-card__author-login--muted">
              {reviewerEntries.length === 0 ? 'no reviewers yet' : `${reviewerEntries.length} reviewer${reviewerEntries.length === 1 ? '' : 's'}`}
            </span>
          )}
        </div>
        {pr.ciStatus && pr.ciStatus !== 'NONE' && (
          <span
            className={`kpr-card__build kpr-card__build--${pr.ciStatus.toLowerCase()}`}
            title={`CI: ${pr.ciStatus.toLowerCase()}`}
          >
            <span
              className={`kpr-card__build-badge kpr-card__build-badge--${pr.ciStatus.toLowerCase()}`}
              aria-hidden="true"
            >
              {pr.ciStatus === 'PASSING' ? '✓' : pr.ciStatus === 'FAILING' ? '✕' : '·'}
            </span>
            BUILD
          </span>
        )}
        <div className="kpr-card__foot-right">
          {visibleReviewers.length > 0 && (
            <span className="kpr-card__reviewers">
              {visibleReviewers.map(({ login, verdict }) => (
                <span
                  key={login}
                  className={`kpr-card__reviewer kpr-card__reviewer--${verdictClass(verdict)}`}
                  title={`${login}${verdict ? ` · ${verdict.toLowerCase().replace(/_/g, ' ')}` : ' · pending'}`}
                >
                  <Avatar login={login} size={16} className="kpr-card__reviewer-avatar" />
                </span>
              ))}
              {overflow > 0 && (
                <span className="kpr-card__reviewer-overflow" title={`${overflow} more`}>
                  +{overflow}
                </span>
              )}
            </span>
          )}
          <span
            className={`kpr-card__time${compactRelative(pr.updatedAt ?? pr.createdAt) === 'just now' ? ' kpr-card__time--hot' : ''}`}
            title={timeTooltip}
          >
            {compactRelative(pr.updatedAt ?? pr.createdAt)}
          </span>
        </div>
      </div>
    </button>
  );
}

/** Compact "Xh ago" / "Xd ago" form for the card footer. The full
 *  "opened …" sentence lives in the title attribute. */
function compactRelative(iso: string | null): string {
  if (!iso) return '';
  const ms = Date.now() - new Date(iso).getTime();
  const hours = Math.floor(ms / (60 * 60 * 1000));
  if (hours < 1) return 'just now';
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  const months = Math.floor(days / 30);
  return `${months}mo ago`;
}

// ── Helpers ────────────────────────────────────────────────────────────────

type Banner = { tone: 'danger' | 'warn' | 'info' | 'success'; icon: string; text: string };

/** Wake-reason → human-readable banner copy. Mirrors the strings the
 *  Snoozed-list card uses so users see the same vocabulary across views. */
const WAKE_BANNER_COPY: Record<string, string> = {
  CI_FAILING: 'Just woke up — CI failed',
  CHANGES_REQUESTED: 'Just woke up — reviewer requested changes',
  MERGE_CONFLICT: 'Just woke up — merge conflict appeared',
};

/** Computes the optional info banner above the card title. Priority order:
 *  wake-up alert (always wins so the user notices) > blocking failures >
 *  stale > approval > mention. */
function bannerFor(pr: PrLikeWithId, column: KanbanColumnKind): Banner | null {
  // Wake-up wins everything — the user explicitly parked this PR and
  // the auto-wake brought it back for a reason. Show that reason
  // before any other signal.
  if (pr.snoozeWakeReason) {
    return {
      tone: 'success',
      icon: '⏰',
      text: WAKE_BANNER_COPY[pr.snoozeWakeReason] ?? `Just woke up — ${pr.snoozeWakeReason}`,
    };
  }

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

type StatusPill = { kind: 'draft' | 'changes' | 'approved' | 'stale'; label: string };

/** Only exceptional states earn a pill — the board position already
 *  carries "opened", and done columns carry "merged"/"cleared". */
const DONE_COLUMNS = new Set<KanbanColumnKind>(['recently_merged', 'cleared_today', 'handled']);

function statusPillFor(pr: PrLikeWithId, column: KanbanColumnKind): StatusPill | null {
  if (DONE_COLUMNS.has(column)) return null;
  if (pr.state === 'merged' || pr.mergedAt || pr.state === 'closed') return null;
  if (pr.draft) return { kind: 'draft', label: 'Draft' };
  const verdicts = Object.values(pr.reviewerVerdicts ?? {});
  if (verdicts.includes('CHANGES_REQUESTED')) return { kind: 'changes', label: 'Changes' };
  if (verdicts.includes('APPROVED')) return { kind: 'approved', label: 'Approved' };
  if (daysSince(pr.updatedAt) >= STALE_DAYS) return { kind: 'stale', label: 'Stale' };
  return null;
}

/** Stable label → tint mapping so a label keeps its colour across
 *  renders and cards. GitHub's own label hexes are ignored — V3 tags
 *  are always soft-tint (K4). */
const TAG_COLORS: TagColor[] = ['accent', 'teal', 'orange', 'green'];

function tagColor(label: string): TagColor {
  let hash = 0;
  for (let i = 0; i < label.length; i++) hash = (hash * 31 + label.charCodeAt(i)) | 0;
  return TAG_COLORS[Math.abs(hash) % TAG_COLORS.length];
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

// ── Snooze menu ────────────────────────────────────────────────────────────

type SnoozePreset = { label: string; compute: () => Date };

/**
 * Time presets in display order. Computed lazily so `Date.now()` reflects
 * the moment the user opens the menu, not the card's mount time.
 */
const SNOOZE_PRESETS: SnoozePreset[] = [
  { label: '1 hour', compute: () => new Date(Date.now() + 60 * 60 * 1000) },
  { label: '6 hours', compute: () => new Date(Date.now() + 6 * 60 * 60 * 1000) },
  {
    label: 'Tomorrow morning (10am)',
    compute: () => {
      const d = new Date();
      d.setDate(d.getDate() + 1);
      d.setHours(10, 0, 0, 0);
      return d;
    },
  },
  { label: '1 week', compute: () => new Date(Date.now() + 7 * 24 * 60 * 60 * 1000) },
  { label: '1 month', compute: () => new Date(Date.now() + 30 * 24 * 60 * 60 * 1000) },
];

type SnoozeMenuButtonProps = {
  onPick: (untilIso: string) => void;
  /** Trigger element customization. Defaults to the corner ⌛ control
   *  used inside KanbanPrCard. The Snoozed-list "Edit snooze" button
   *  passes its own classes / label so it reads as a regular action
   *  button instead of a card overlay. */
  triggerClassName?: string;
  triggerContent?: ReactNode;
  triggerTitle?: string;
  triggerAriaLabel?: string;
  /** Wrap-element class. Defaults to the kanban card's hover-aware
   *  wrap so the trigger fades in/out with the card. Override to
   *  always-visible when the trigger sits outside a card. */
  wrapClassName?: string;
};

export function SnoozeMenuButton({
  onPick,
  triggerClassName = 'kpr-card__handle kpr-card__handle--snooze',
  triggerContent = '⌛',
  triggerTitle = 'Snooze — park this PR until later.',
  triggerAriaLabel = 'Snooze this PR',
  wrapClassName,
}: SnoozeMenuButtonProps) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (!wrapRef.current) return;
      if (wrapRef.current.contains(e.target as Node)) return;
      setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const baseWrap = wrapClassName ?? 'kpr-card__snooze-wrap';
  return (
    <span ref={wrapRef} className={`${baseWrap}${open ? ` ${baseWrap}--open` : ''}`}>
      <button
        type="button"
        className={triggerClassName}
        onClick={(e) => { e.stopPropagation(); setOpen(o => !o); }}
        title={triggerTitle}
        aria-label={triggerAriaLabel}
        aria-expanded={open}
        aria-haspopup="menu"
      >
        {triggerContent}
      </button>
      {open && (
        <div className="kpr-card__snooze-menu" role="menu" onClick={(e) => e.stopPropagation()}>
          {SNOOZE_PRESETS.map(preset => (
            <button
              key={preset.label}
              type="button"
              role="menuitem"
              className="kpr-card__snooze-item"
              onClick={(e) => {
                e.stopPropagation();
                setOpen(false);
                onPick(preset.compute().toISOString());
              }}
            >
              {preset.label}
            </button>
          ))}
        </div>
      )}
    </span>
  );
}

export default KanbanPrCard;
