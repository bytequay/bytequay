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
import type { PullRequestDto } from './types';
import KanbanPrCard from './kanban/KanbanPrCard';
import type { KanbanColumnKind } from './kanban/KanbanColumn';

import {
  CATEGORIES,
  CATEGORY_LABEL,
  type Category,
  categorize,
  groupByCategory,
  isResurfaced,
  type HandledGroups,
} from './prBuckets';

type InboxCardProps = {
  pr: PullRequestDto;
  selected: boolean;
  onSelect: () => void;
  onHandle: () => void;
  onSnooze?: (untilIso: string) => void;
};

export function InboxCard({ pr, selected, onSelect, onHandle, onSnooze }: InboxCardProps) {
  // The inbox / repo-detail / handled-timeline contexts now reuse the
  // rich kanban card. We pick a column flavour from the same signals
  // the old PrCard used for its left-edge dot — a resurfaced or
  // attention-flagged PR maps to needs_attention (urgent banner +
  // styling); an active reviewer verdict (changes-requested /
  // commented) maps to awaiting_author so the banner names the
  // reviewer; everything else is in_progress, the safe default.
  const column = pickColumnFor(pr);
  return (
    <KanbanPrCard
      pr={pr}
      column={column}
      mode="inbox"
      selected={selected}
      onSelect={onSelect}
      onHandle={onHandle}
      onSnooze={onSnooze}
    />
  );
}

function pickColumnFor(pr: PullRequestDto): KanbanColumnKind {
  if (isResurfaced(pr) || pr.attentionReason !== null) return 'needs_attention';
  if (pr.handledAction === 'CHANGES_REQUESTED' || pr.handledAction === 'COMMENTED') {
    return 'awaiting_author';
  }
  return 'in_progress';
}


type HandledCardProps = {
  pr: PullRequestDto;
  selected: boolean;
  onSelect: () => void;
  onReopen: () => void;
};

export function HandledCard({ pr, selected, onSelect, onReopen }: HandledCardProps) {
  // Handled cards now reuse the rich kanban card with column='handled':
  // KanbanPrCard automatically swaps the ✓ Mark-handled corner button
  // for a ↺ Reopen one in this column. The handled-badge / reviewedAt
  // line that the old HandledCard showed is now derivable from the
  // status pill + meta line on the kanban card.
  return (
    <KanbanPrCard
      pr={pr}
      column="handled"
      mode="inbox"
      selected={selected}
      onSelect={onSelect}
      onReopen={onReopen}
    />
  );
}

type CollapsibleGroupProps = {
  title: string;
  color?: 'blue' | 'orange' | 'grey' | 'plain';
  count?: number;
  children: React.ReactNode;
  defaultOpen?: boolean;
};

export function CollapsibleGroup({ title, color = 'plain', count, children, defaultOpen = true }: CollapsibleGroupProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="v2-group">
      <button
        type="button"
        className={`v2-group__header v2-group__header--${color} v2-group__header--clickable`}
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
      >
        <span
          className={`v2-group__chevron${open ? '' : ' v2-group__chevron--collapsed'}`}
          aria-hidden="true"
        >
          ▾
        </span>
        <span className="v2-group__title">{title}</span>
        {count !== undefined && <span className="v2-group__count">{count}</span>}
      </button>
      {open && <div className="v2-group__body">{children}</div>}
    </div>
  );
}

type InboxGroupProps = {
  title: string;
  color: 'blue' | 'orange' | 'grey';
  prs: PullRequestDto[];
  selectedId: number | null;
  onSelect: (pr: PullRequestDto) => void;
  onHandle: (prId: number) => void;
};

export function InboxGroup({ title, color, prs, selectedId, onSelect, onHandle }: InboxGroupProps) {
  if (prs.length === 0) return null;
  return (
    <CollapsibleGroup title={title} color={color} count={prs.length}>
      {prs.map(pr => (
        <InboxCard
          key={pr.id}
          pr={pr}
          selected={selectedId === pr.id}
          onSelect={() => onSelect(pr)}
          onHandle={() => onHandle(pr.id)}
        />
      ))}
    </CollapsibleGroup>
  );
}

type HandledTimelineProps = {
  groups: HandledGroups;
  selectedId: number | null;
  onSelect: (pr: PullRequestDto) => void;
  onReopen: (prId: number) => void;
};

export function HandledTimeline({ groups, selectedId, onSelect, onReopen }: HandledTimelineProps) {
  const { today, thisWeek, older } = groups;
  return (
    <>
      {today.length > 0 && (
        <CollapsibleGroup title="Today" count={today.length}>
          {today.map(pr => (
            <HandledCard key={pr.id} pr={pr} selected={selectedId === pr.id} onSelect={() => onSelect(pr)} onReopen={() => onReopen(pr.id)} />
          ))}
        </CollapsibleGroup>
      )}
      {thisWeek.length > 0 && (
        <CollapsibleGroup title="This week" count={thisWeek.length}>
          {thisWeek.map(pr => (
            <HandledCard key={pr.id} pr={pr} selected={selectedId === pr.id} onSelect={() => onSelect(pr)} onReopen={() => onReopen(pr.id)} />
          ))}
        </CollapsibleGroup>
      )}
      {older.length > 0 && (
        <CollapsibleGroup title="Older" count={older.length} defaultOpen={false}>
          {older.map(pr => (
            <HandledCard key={pr.id} pr={pr} selected={selectedId === pr.id} onSelect={() => onSelect(pr)} onReopen={() => onReopen(pr.id)} />
          ))}
        </CollapsibleGroup>
      )}
    </>
  );
}

// ── Kanban board + categorised sidebar ──────────────────────────────────────

type CategorizedListProps = {
  prs: PullRequestDto[];
  selectedId: number | null;
  onSelect: (pr: PullRequestDto) => void;
  onHandle: (prId: number) => void;
  onReopen: (prId: number) => void;
  onSnooze?: (prId: number, untilIso: string) => void;
};

/**
 * The left sidebar shown when a PR is selected. Each of the four categories
 * is collapsible; the category containing the currently-selected PR is
 * automatically expanded (others stay collapsed so the list stays focused on
 * the group you're currently reviewing). The user can still toggle each
 * section manually.
 */
export function CategorizedList({ prs, selectedId, onSelect, onHandle, onReopen, onSnooze }: CategorizedListProps) {
  const groups = useMemo(() => groupByCategory(prs), [prs]);
  const selected = selectedId !== null ? prs.find(p => p.id === selectedId) : undefined;
  const activeCategory: Category | null = selected ? categorize(selected) : null;

  const [open, setOpen] = useState<Record<Category, boolean>>(() => ({
    needs_attention: activeCategory === null || activeCategory === 'needs_attention',
    in_progress: activeCategory === 'in_progress',
    awaiting_author: activeCategory === 'awaiting_author',
    cleared: activeCategory === 'cleared',
  }));

  // When the selection changes category, auto-expand its group and collapse
  // the others — the intent the user wants.
  useEffect(() => {
    if (!activeCategory) return;
    setOpen({
      needs_attention: activeCategory === 'needs_attention',
      in_progress: activeCategory === 'in_progress',
      awaiting_author: activeCategory === 'awaiting_author',
      cleared: activeCategory === 'cleared',
    });
  }, [activeCategory]);

  const toggle = (cat: Category) => setOpen(prev => ({ ...prev, [cat]: !prev[cat] }));

  return (
    <div className="categorized-list">
      {CATEGORIES.map(cat => {
        const bucket = groups[cat];
        const isOpen = open[cat];
        return (
          <section key={cat} className={`cat-group cat-group--${cat.replace('_', '-')}`}>
            <button
              type="button"
              className="cat-group__header"
              onClick={() => toggle(cat)}
              aria-expanded={isOpen}
            >
              <span className={`cat-group__chevron${isOpen ? '' : ' cat-group__chevron--collapsed'}`} aria-hidden="true">▾</span>
              <span className="cat-group__dot" aria-hidden="true" />
              <span className="cat-group__title">{CATEGORY_LABEL[cat]}</span>
              <span className="cat-group__count">{bucket.length}</span>
            </button>
            {isOpen && (
              <div className="cat-group__body">
                {bucket.length === 0 ? (
                  <div className="cat-group__empty">No PRs here.</div>
                ) : (
                  bucket.map(pr => (
                    cat === 'cleared'
                      ? (
                        <HandledCard
                          key={pr.id}
                          pr={pr}
                          selected={selectedId === pr.id}
                          onSelect={() => onSelect(pr)}
                          onReopen={() => onReopen(pr.id)}
                        />
                      )
                      : (
                        <InboxCard
                          key={pr.id}
                          pr={pr}
                          selected={selectedId === pr.id}
                          onSelect={() => onSelect(pr)}
                          onHandle={() => onHandle(pr.id)}
                          onSnooze={onSnooze ? (untilIso) => onSnooze(pr.id, untilIso) : undefined}
                        />
                      )
                  ))
                )}
              </div>
            )}
          </section>
        );
      })}
    </div>
  );
}

// ── Just-woke banner ────────────────────────────────────────────────────────

type JustWokeBannerProps = {
  prs: PullRequestDto[];
  onSelect: (pr: PullRequestDto) => void;
  onDismiss: (prId: number) => void;
};

/** Renders nothing when no PR carries a snoozeWakeReason. Otherwise shows a
 *  green strip listing the woken PRs with one-click dismiss + jump-in. */
export function JustWokeBanner({ prs, onSelect, onDismiss }: JustWokeBannerProps) {
  const woken = prs.filter(pr => pr.snoozeWakeReason);
  if (woken.length === 0) return null;
  return (
    <div className="just-woke-banner" role="status">
      <span className="just-woke-banner__icon" aria-hidden="true">⏰</span>
      <span className="just-woke-banner__lead">
        {woken.length === 1 ? '1 PR woke up:' : `${woken.length} PRs woke up:`}
      </span>
      <div className="just-woke-banner__items">
        {woken.map(pr => (
          <span key={pr.id} className="just-woke-banner__item">
            <button
              type="button"
              className="just-woke-banner__open"
              onClick={() => onSelect(pr)}
              title={`Open ${pr.repo} #${pr.number}`}
            >
              {pr.repo} #{pr.number} · {WAKE_REASON_COPY[pr.snoozeWakeReason!] ?? pr.snoozeWakeReason}
            </button>
            <button
              type="button"
              className="just-woke-banner__dismiss"
              onClick={() => onDismiss(pr.id)}
              title="Dismiss this alert"
              aria-label="Dismiss"
            >
              ×
            </button>
          </span>
        ))}
      </div>
    </div>
  );
}

// ── Snoozed list ────────────────────────────────────────────────────────────

type SnoozedListProps = {
  prs: PullRequestDto[];
  selectedId: number | null;
  onSelect: (pr: PullRequestDto) => void;
  onUnsnooze: (prId: number) => void;
  onClearWakeReason: (prId: number) => void;
};

const WAKE_REASON_COPY: Record<string, string> = {
  CI_FAILING: 'CI failed',
  CHANGES_REQUESTED: 'Reviewer requested changes',
  MERGE_CONFLICT: 'Merge conflict appeared',
};

function formatWakeRelative(iso: string | null, now: number = Date.now()): string {
  if (!iso) return '';
  const diffMs = new Date(iso).getTime() - now;
  if (diffMs <= 0) return 'waking now…';
  const mins = Math.round(diffMs / 60_000);
  if (mins < 60) return `wakes in ${mins}m`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `wakes in ${hrs}h`;
  const days = Math.round(hrs / 24);
  if (days < 7) {
    return `wakes ${new Date(iso).toLocaleDateString(undefined, { weekday: 'long' })}`;
  }
  return `wakes ${new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`;
}

export function SnoozedList({ prs, selectedId, onSelect, onUnsnooze, onClearWakeReason }: SnoozedListProps) {
  if (prs.length === 0) {
    return <div className="v2-empty">Nothing snoozed. Use the ⌛ button on a PR card to park it for later.</div>;
  }
  return (
    <div className="snoozed-list">
      {prs.map(pr => (
        <article
          key={pr.id}
          className={`snoozed-card${selectedId === pr.id ? ' snoozed-card--selected' : ''}`}
          onClick={() => onSelect(pr)}
          role="button"
          tabIndex={0}
        >
          <header className="snoozed-card__head">
            <span className="snoozed-card__repo">{pr.repo} · #{pr.number}</span>
            <span className="snoozed-card__wake">{formatWakeRelative(pr.snoozedUntil)}</span>
          </header>
          <div className="snoozed-card__title">{pr.title}</div>
          {pr.snoozeWakeReason && (
            <div className="snoozed-card__woke">
              <span aria-hidden="true">⏰</span>
              <span>Auto-woke: {WAKE_REASON_COPY[pr.snoozeWakeReason] ?? pr.snoozeWakeReason}</span>
              <button
                type="button"
                className="snoozed-card__woke-dismiss"
                onClick={(e) => { e.stopPropagation(); onClearWakeReason(pr.id); }}
                title="Dismiss this just-woke alert."
              >
                Dismiss
              </button>
            </div>
          )}
          <footer className="snoozed-card__actions">
            <button
              type="button"
              className="snoozed-card__wake-now"
              onClick={(e) => { e.stopPropagation(); onUnsnooze(pr.id); }}
              title="Wake this PR now and return it to the Inbox."
            >
              Wake now
            </button>
          </footer>
        </article>
      ))}
    </div>
  );
}
