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
import { useEffect, useRef, useState, type CSSProperties, type DragEvent, type ReactNode } from 'react';
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
  /** When provided, a small ⌛ button appears next to ✓. Click opens
   *  a time-preset menu; picking one calls onSnooze with the chosen
   *  ISO-8601 wake instant. Suppressed on already-done columns. */
  onSnooze?: (untilIso: string) => void;
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
  prId: number;
  fromColumn: KanbanColumnKind;
  repo: string;
  number: number;
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
function KanbanPrCard({ pr, column, mode = 'inbox', selected, onSelect, onHandle, onReopen, onSnooze, draggable = false, disabled = false }: Props) {
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
        </div>
      </div>
      <div className="kpr-card__title">{pr.title}</div>
      {pr.labels.length > 0 && (
        <div className="kpr-card__labels">
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
      )}
      <div className="kpr-card__status-row">
        <div className="kpr-card__status-left">
          {statusPill && (
            <span className={`kpr-card__pill kpr-card__pill--${statusPill.kind}`}>
              {statusPill.label}
            </span>
          )}
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
        </div>
        {visibleReviewers.length > 0 && (
          <span className="kpr-card__reviewers">
            {visibleReviewers.map(({ login, verdict }) => (
              <span
                key={login}
                className={`kpr-card__reviewer kpr-card__reviewer--${verdictClass(verdict)}`}
                title={`${login}${verdict ? ` · ${verdict.toLowerCase().replace(/_/g, ' ')}` : ' · pending'}`}
              >
                <Avatar login={login} size={20} className="kpr-card__reviewer-avatar" />
              </span>
            ))}
            {overflow > 0 && (
              <span className="kpr-card__reviewer-overflow" title={`${overflow} more`}>
                +{overflow}
              </span>
            )}
          </span>
        )}
      </div>
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
        <span className="kpr-card__time" title={openedLabel}>
          <span className="kpr-card__time-icon" aria-hidden="true">🕐</span>
          {compactRelative(pr.createdAt ?? pr.updatedAt)}
        </span>
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
function bannerFor(pr: PullRequestDto, column: KanbanColumnKind): Banner | null {
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
