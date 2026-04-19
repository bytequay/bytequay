import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import type { CiStatus, PullRequestDto } from './types';
import Avatar from './Avatar';

function labelChipStyle(color: string | null | undefined): CSSProperties | undefined {
  if (!color || !/^[0-9a-fA-F]{6}$/.test(color)) return undefined;
  const r = parseInt(color.slice(0, 2), 16);
  const g = parseInt(color.slice(2, 4), 16);
  const b = parseInt(color.slice(4, 6), 16);
  const luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  const text = luma > 0.6 ? '#1f2937' : '#ffffff';
  return { background: `#${color}`, color: text, borderColor: 'transparent' };
}

/**
 * Picks the colored banner that crowns a Kanban card when the PR has been
 * promoted to "Needs attention". Banner tone maps to the v2-card__banner CSS
 * modifier; the label is the short-form copy shown to the user.
 */
function attentionBanner(pr: PullRequestDto): { tone: 'red' | 'orange' | 'blue' | 'gray'; label: string } | null {
  switch (pr.attentionReason) {
    case 'CI_FAILING':     return { tone: 'red',    label: '⚠ CI failing' };
    case 'MERGE_CONFLICT': return { tone: 'red',    label: '⚠ Merge conflict' };
    case 'MENTIONED':      return { tone: 'blue',   label: '@ mentioned you' };
    case 'NEW_COMMENT':    return { tone: 'blue',   label: '✦ New activity' };
    case 'BLOCKING':       return { tone: 'red',    label: '⛔ Marked blocking' };
    case 'STALE':          return { tone: 'orange', label: 'Stale — no progress' };
    case 'MINE':           return { tone: 'gray',   label: 'Your PR' };
    default:               return null;
  }
}

/** Maps a PR label to a color-coded tag style. */
function tagClass(label: string): string {
  const l = label.toLowerCase();
  if (l.includes('bug')) return 'v2-pill--tag-bug';
  if (l.includes('feat')) return 'v2-pill--tag-feat';
  if (l.includes('perf')) return 'v2-pill--tag-perf';
  if (l.includes('docs')) return 'v2-pill--tag-docs';
  return 'v2-pill--tag-default';
}

/** Tiny status dot for the foot-row CI signal. */
function CiDot({ status }: { status: CiStatus }) {
  return <span className={`v2-card__ci-dot v2-card__ci-dot--${status.toLowerCase()}`} aria-hidden="true" />;
}

/**
 * Small repo badge used on PR cards: the owner's GitHub avatar (works for
 * both user and org accounts) + short repo name. Helpful now that the kanban
 * mixes PRs from multiple repos in the same column.
 */
function RepoChip({ repo }: { repo: string }) {
  if (!repo) return null;
  const [owner, name] = repo.includes('/') ? repo.split('/') : [repo, repo];
  return (
    <span className="repo-chip" title={repo}>
      <Avatar login={owner} size={14} className="avatar--repo-small" />
      <span className="repo-chip__name">{name}</span>
    </span>
  );
}
import {
  CATEGORIES,
  CATEGORY_LABEL,
  type Category,
  categorize,
  formatRelative,
  groupByCategory,
  handledBadge,
  isResurfaced,
  type HandledGroups,
} from './prBuckets';

type InboxCardProps = {
  pr: PullRequestDto;
  selected: boolean;
  onSelect: () => void;
  onHandle: () => void;
};

export function InboxCard({ pr, selected, onSelect, onHandle }: InboxCardProps) {
  const resurfaced = isResurfaced(pr);
  const unread = pr.viewedAt === null;
  const action = pr.handledAction;
  // Marker-dot vocabulary from review-flow.png:
  //   - Blue glow  (`--blue`)     — New, untouched
  //   - Orange glow (`--orange`)  — Resurfaced (activity since last look)
  //   - Hollow ring (`--seen`)    — Viewed, no activity yet
  //   - Blue dot   (`--reviewed`) — Reviewed (commented / changes-requested),
  //                                  waiting for the author
  // The `dotKind` determines both the dot class and any pill + tooltip.
  let dotKind: 'new' | 'resurfaced' | 'seen' | 'reviewed' | null = null;
  if (resurfaced) dotKind = 'resurfaced';
  else if (action === 'CHANGES_REQUESTED' || action === 'COMMENTED') dotKind = 'reviewed';
  else if (unread) dotKind = 'new';
  else if (pr.reviewedAt === null) dotKind = 'seen';

  const dotClass = dotKind
    ? `v2-card__dot v2-card__dot--${dotKind === 'new' ? 'blue' : dotKind === 'resurfaced' ? 'orange' : dotKind}`
    : '';
  const dotTooltip =
    dotKind === 'new' ? 'New — you haven\'t opened this yet'
      : dotKind === 'resurfaced' ? 'Updated since your review'
        : dotKind === 'seen' ? 'Opened but not yet handled'
          : dotKind === 'reviewed' ? 'You reviewed — waiting on the author'
            : undefined;

  const banner = attentionBanner(pr);
  const hasDiff = pr.additions > 0 || pr.deletions > 0;
  const hasFoot = pr.labels.length > 0 || pr.ciStatus || pr.commentCount > 0;
  // Awaiting-author cards are dimmed (per design) so they're scannable but
  // quieter than the active columns. Hover restores full opacity.
  const dimmed = dotKind === 'reviewed';

  return (
    <div
      className={
        'v2-card' +
        (selected ? ' v2-card--selected' : '') +
        (dotKind === 'seen' ? ' v2-card--seen' : '') +
        (dimmed ? ' v2-card--dim' : '')
      }
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') onSelect(); }}
      title={dotTooltip}
    >
      {banner && (
        <div className={`v2-card__banner v2-card__banner--${banner.tone}`}>
          {banner.label}
        </div>
      )}
      <div className="v2-card__row-wrap">
        {dotClass && <span className={dotClass} aria-hidden="true" />}
        <div className="v2-card__body">
        <div className="v2-card__row">
          <RepoChip repo={pr.repo} />
          <span className="v2-card__number">#{pr.number}</span>
          {pr.draft && <span className="v2-pill v2-pill--draft">DRAFT</span>}
          {resurfaced && <span className="v2-pill v2-pill--resurfaced">Updated since your review</span>}
          {dotKind === 'seen' && <span className="v2-pill v2-pill--seen">Opened</span>}
          {dotKind === 'reviewed' && (
            <span className="v2-pill v2-pill--reviewed">Awaiting author</span>
          )}
        </div>
        <div className="v2-card__title">{pr.title}</div>
        <div className="v2-card__meta">
          {pr.author && (
            <>
              <Avatar login={pr.author} size={14} className="avatar--repo-small" />
              <span>{pr.author}</span>
            </>
          )}
          <span className="v2-card__ts" title={`Updated ${formatRelative(pr.updatedAt)}`}>· {formatRelative(pr.updatedAt)}</span>
          {hasDiff && (
            <span className="v2-card__diff">
              <span className="v2-card__diff-add">+{pr.additions}</span>
              <span className="v2-card__diff-del">−{pr.deletions}</span>
            </span>
          )}
        </div>
        {hasFoot && (
          <div className="v2-card__foot">
            <div className="v2-card__tags">
              {pr.labels.map(l => {
                // GitHub colors are hex without the #. Use the color as the
                // background and pick a readable text color from luminance —
                // same approach GitHub uses on its own labels. Falls back
                // to the existing tagClass colour mapping when no color is
                // recorded yet (legacy rows / pre-V19 sync).
                const style = labelChipStyle(pr.labelColors?.[l]);
                return (
                  <span key={l} className={`v2-pill ${tagClass(l)}`} style={style}>{l}</span>
                );
              })}
            </div>
            <div className="v2-card__signals">
              {pr.ciStatus && pr.ciStatus !== 'NONE' && (
                <span className="v2-card__signal" title={`CI: ${pr.ciStatus.toLowerCase()}`}>
                  <CiDot status={pr.ciStatus} />
                  <span>CI</span>
                </span>
              )}
              {pr.commentCount > 0 && (
                <span className="v2-card__signal" title={`${pr.commentCount} comment${pr.commentCount === 1 ? '' : 's'}`}>
                  💬 {pr.commentCount}
                </span>
              )}
            </div>
          </div>
        )}
      </div>
      </div>
      <button
        className="v2-hover-action"
        onClick={e => { e.stopPropagation(); onHandle(); }}
        title="Mark as handled"
        type="button"
      >
        ✓ Handled
      </button>
    </div>
  );
}

type HandledCardProps = {
  pr: PullRequestDto;
  selected: boolean;
  onSelect: () => void;
  onReopen: () => void;
};

export function HandledCard({ pr, selected, onSelect, onReopen }: HandledCardProps) {
  const badge = handledBadge(pr.handledAction);
  return (
    <div
      className={`v2-card v2-card--handled${selected ? ' v2-card--selected' : ''}`}
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') onSelect(); }}
    >
      <div className="v2-card__row-wrap">
        <div className="v2-card__body">
          <div className="v2-card__row">
            <RepoChip repo={pr.repo} />
            <span className="v2-card__number">#{pr.number}</span>
            <span className={`handled-badge ${badge.cls}`}>
              <span className="handled-badge__icon">{badge.icon}</span>
              {badge.label}
            </span>
            <span className="v2-card__ts">· {formatRelative(pr.reviewedAt)}</span>
          </div>
          <div className="v2-card__title">{pr.title}</div>
          <div className="v2-card__meta">
            {pr.author && (
              <>
                <Avatar login={pr.author} size={14} className="avatar--repo-small" />
                <span>{pr.author}</span>
              </>
            )}
            <span className="v2-card__ts" title={`Updated ${formatRelative(pr.updatedAt)}`}>· {formatRelative(pr.updatedAt)}</span>
          </div>
        </div>
      </div>
      <button
        className="v2-hover-action"
        onClick={e => { e.stopPropagation(); onReopen(); }}
        title="Reopen to Inbox"
        type="button"
      >
        ↗ Reopen
      </button>
    </div>
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
};

/**
 * The left sidebar shown when a PR is selected. Each of the four categories
 * is collapsible; the category containing the currently-selected PR is
 * automatically expanded (others stay collapsed so the list stays focused on
 * the group you're currently reviewing). The user can still toggle each
 * section manually.
 */
export function CategorizedList({ prs, selectedId, onSelect, onHandle, onReopen }: CategorizedListProps) {
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
