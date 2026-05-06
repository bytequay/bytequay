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
import KanbanPrCard, { SnoozeMenuButton } from './kanban/KanbanPrCard';
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

// ── Snoozed list ────────────────────────────────────────────────────────────

type SnoozedListProps = {
  prs: PullRequestDto[];
  selectedId: number | null;
  onSelect: (pr: PullRequestDto) => void;
  onUnsnooze: (prId: number) => void;
  /** Replaces an existing snooze with a new wake time (same backend
   *  endpoint as fresh snooze — `snooze()` overwrites). */
  onEditSnooze: (prId: number, untilIso: string) => void;
  // onClearWakeReason kept for callsite parity; PRs in the snoozed
  // bucket can't carry a wake reason (auto-wake clears snoozedUntil
  // first), so the prop is unused here.
  onClearWakeReason?: (prId: number) => void;
};

/** "Tomorrow at 8:00 AM" / "Mon Sep 14 at 9:00 AM" — the absolute time
 *  shown as the primary wake info. Day prefix is contextual: today /
 *  tomorrow / weekday name / full date. */
function formatWakeAbsolute(iso: string | null, now: number = Date.now()): string {
  if (!iso) return '';
  const target = new Date(iso);
  const targetDay = new Date(target);
  targetDay.setHours(0, 0, 0, 0);
  const todayDay = new Date(now);
  todayDay.setHours(0, 0, 0, 0);
  const dayDiff = Math.round((targetDay.getTime() - todayDay.getTime()) / (24 * 60 * 60 * 1000));
  const time = target.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  if (dayDiff <= 0) return `today at ${time}`;
  if (dayDiff === 1) return `tomorrow at ${time}`;
  if (dayDiff < 7) {
    return `${target.toLocaleDateString(undefined, { weekday: 'long' })} at ${time}`;
  }
  return `${target.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} at ${time}`;
}

/** "in 17h" / "in 2d" — the secondary relative-time hint. */
function formatWakeRelative(iso: string | null, now: number = Date.now()): string {
  if (!iso) return '';
  const diffMs = new Date(iso).getTime() - now;
  if (diffMs <= 0) return 'waking now…';
  const mins = Math.round(diffMs / 60_000);
  if (mins < 60) return `in ${mins}m`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `in ${hrs}h`;
  const days = Math.round(hrs / 24);
  return `in ${days}d`;
}

export function SnoozedList({ prs, selectedId, onSelect, onUnsnooze, onEditSnooze, onClearWakeReason: _onClearWakeReason }: SnoozedListProps) {
  return (
    <>
      <p className="snoozed-explainer">
        Snoozed PRs stay out of your Inbox until they wake. They wake when the
        timer runs out — or, always, the moment they become urgent (CI fails,
        reviewer requests changes, merge conflict appears).
      </p>

      <header className="snoozed-section-head">
        <h2 className="snoozed-section-title">Snoozed PRs</h2>
        <span className="snoozed-section-count">{prs.length}</span>
        {prs.length > 0 && (
          <span className="snoozed-section-sort">sort: wake soonest first</span>
        )}
      </header>

      {prs.length === 0 ? (
        <div className="v2-empty snoozed-empty">
          Nothing snoozed. Use the ⌛ button on a PR card to park it for later.
        </div>
      ) : (
        <div className="snoozed-list">
          {prs.map(pr => (
            <article key={pr.id} className="snoozed-row">
              <div className="snoozed-row__card">
                {/* Same rich card surface the kanban uses — labels,
                    reviewers, CI dot, status pill. Column='handled'
                    keeps it reading as "parked / done for now".
                    `disabled` suppresses the card click — opening the
                    detail collapses the list into a sidebar that the
                    snoozed row layout (card + 220px rail) can't fit
                    into. The Wake-now / Edit-snooze buttons in the
                    rail stay clickable. */}
                <KanbanPrCard
                  pr={pr}
                  column="handled"
                  mode="inbox"
                  selected={false}
                  onSelect={() => { /* disabled — see comment above */ }}
                  disabled
                />
              </div>
              <aside className="snoozed-row__rail">
                <div className="snoozed-row__info">
                  <span className="snoozed-row__kind">TIME-BASED</span>
                  <span className="snoozed-row__when">
                    Wakes {formatWakeAbsolute(pr.snoozedUntil)}
                  </span>
                  <span className="snoozed-row__hint">
                    {formatWakeRelative(pr.snoozedUntil)}
                  </span>
                </div>
                <div className="snoozed-row__actions">
                  <button
                    type="button"
                    className="snoozed-row__action snoozed-row__action--primary"
                    onClick={(e) => { e.stopPropagation(); onUnsnooze(pr.id); }}
                    title="Wake this PR now and return it to the Inbox."
                  >
                    Wake now
                  </button>
                  {/* Edit snooze reuses the same preset popup the
                      kanban ⌛ button uses; picking a preset replaces
                      the existing snoozedUntil via the same backend
                      endpoint. */}
                  <SnoozeMenuButton
                    onPick={(untilIso) => onEditSnooze(pr.id, untilIso)}
                    triggerClassName="snoozed-row__action"
                    triggerContent="Edit snooze"
                    triggerTitle="Pick a different wake time."
                    triggerAriaLabel="Edit snooze for this PR"
                    wrapClassName="snoozed-row__edit-wrap"
                  />
                </div>
              </aside>
            </article>
          ))}
        </div>
      )}
    </>
  );
}
