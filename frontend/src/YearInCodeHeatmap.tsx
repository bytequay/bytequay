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
import { forwardRef, useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ContributionCalendarDto, ContributionDayDto, UserCommitDto } from './types';
import { getCached, setCached } from './dataCache';

const CELL = 12;
const GAP = 2;
const STRIDE = CELL + GAP;
const ROW_PAD = 18;
const COL_PAD = 28;

const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Warm-tinted five-step ramp ending in the app's system green — the
 *  graph and the Less→More legend both draw from it. */
const LEVEL_COLORS = ['#efece6', '#d3ead6', '#9fd8ae', '#57bd79', '#16a34a'];

/** GitHub's GraphQL palette hex → intensity level. GitHub buckets each
 *  day for us; we keep its bucketing but repaint with the warm ramp. */
const GITHUB_LEVEL: Record<string, number> = {
  '#ebedf0': 0,
  '#9be9a8': 1,
  '#40c463': 2,
  '#30a14e': 3,
  '#216e39': 4,
};

function levelFor(day: ContributionDayDto): number {
  const mapped = GITHUB_LEVEL[day.color.toLowerCase()];
  if (mapped !== undefined) return mapped;
  // Unknown palette (theme change on GitHub's side): bucket by count.
  const c = day.contributionCount;
  return c === 0 ? 0 : c < 3 ? 1 : c < 6 ? 2 : c < 10 ? 3 : 4;
}

function cacheKey(login: string) {
  return `home:contribution-graph:${login}`;
}

/** Pretty-prints an ISO yyyy-MM-dd as "Mon, Jan 1, 2026" for the
 *  hover tooltip — shorter than spelling out the weekday in full but
 *  still unambiguous when you're glancing at a specific cell. */
function formatTipDate(iso: string): string {
  const d = new Date(iso + 'T00:00:00');
  return d.toLocaleDateString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

/**
 * Native, GitHub-GraphQL-backed contribution heatmap rendered as an inline
 * SVG. The viewBox + percentage width keep it crisp at any zoom — no
 * raster proxy, no fixed-bitmap intermediate. Hovering a cell shows a
 * native browser tooltip with the date and contribution count.
 *
 * <p>Each cell's intensity level comes from GitHub's GraphQL response
 * (its palette hex encodes the bucket), repainted with the app's warm
 * ramp so the graph matches the rest of the visual system.
 */
export default function YearInCodeHeatmap({ login }: { login: string }) {
  const [data, setData] = useState<ContributionCalendarDto | null>(
    () => getCached<ContributionCalendarDto>(cacheKey(login)) ?? null,
  );
  // Hovered cell + cursor position for the custom tooltip. clientX/Y
  // pair with position:fixed CSS so we don't have to map SVG coords
  // back into DOM space when the heatmap is laid out fluidly.
  const [hover, setHover] = useState<{ day: ContributionDayDto; clientX: number; clientY: number } | null>(null);

  // Click → unfold a day into the commits that produced it. The
  // popover is anchored to the cell with viewport coords (its own
  // position:fixed), and the commit list is cached per-date for the
  // session so re-opening the same day doesn't re-hit GitHub's
  // search-commits rate limit (30/min authenticated).
  const [selected, setSelected] = useState<{ day: ContributionDayDto; clientX: number; clientY: number } | null>(null);
  const [commitsByDate, setCommitsByDate] = useState<Record<string, UserCommitDto[] | 'loading' | 'error'>>({});
  const popoverRef = useRef<HTMLDivElement | null>(null);

  const closePopover = useCallback(() => setSelected(null), []);

  useEffect(() => {
    if (selected === null) return;
    const date = selected.day.date;
    if (commitsByDate[date] !== undefined) return;
    setCommitsByDate(prev => ({ ...prev, [date]: 'loading' }));
    let cancelled = false;
    void window.bridge.getUserCommitsOnDate(login, date)
      .then((list) => {
        if (cancelled) return;
        setCommitsByDate(prev => ({ ...prev, [date]: list }));
      })
      .catch(() => {
        if (cancelled) return;
        setCommitsByDate(prev => ({ ...prev, [date]: 'error' }));
      });
    return () => { cancelled = true; };
  }, [selected, login, commitsByDate]);

  // Dismiss on outside click + Escape. The popover stops propagation
  // on its own click so a click *inside* (e.g. on a commit row) doesn't
  // immediately close it before openExternal fires.
  useEffect(() => {
    if (selected === null) return;
    const onDown = (e: MouseEvent) => {
      if (popoverRef.current && e.target instanceof Node && popoverRef.current.contains(e.target)) return;
      closePopover();
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closePopover(); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [selected, closePopover]);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.getContributionCalendar(login)
      .then((c) => {
        if (cancelled) return;
        setData(c);
        setCached(cacheKey(login), c);
      })
      .catch(() => { /* non-fatal — leave any cached data visible */ });
    return () => { cancelled = true; };
  }, [login]);

  if (!data) {
    return <div className="home-heatmap home-heatmap--loading" aria-hidden="true" />;
  }

  const weeks = data.weeks;
  const weekCount = weeks.length;

  // GitHub's GraphQL hop can come back empty (transient 4xx/5xx, an
  // account with no public contributions, or an offline first paint).
  // The SVG's viewBox is computed from {@code weekCount}, so a zero
  // here would collapse it to a near-square aspect ratio. With
  // {@code width: 100%; height: auto}, the card would then balloon
  // vertically and the 9px day-of-week labels would render at card
  // height. Bail to a placeholder instead.
  if (weekCount === 0) {
    return (
      <div className="home-heatmap home-heatmap--empty">
        No contributions in the last year.
      </div>
    );
  }
  const gridWidth = weekCount * STRIDE - GAP;
  const gridHeight = 7 * STRIDE - GAP;
  const totalWidth = COL_PAD + gridWidth;
  const totalHeight = ROW_PAD + gridHeight;

  // Month labels: place each label above the first column whose first
  // (top-most non-null) day is in a new month. Skips the very first column
  // when its month would crowd the second.
  const monthMarkers: { weekIndex: number; label: string }[] = [];
  let lastMonth = -1;
  weeks.forEach((week, weekIndex) => {
    const firstDay = week.days[0];
    if (!firstDay) return;
    const month = new Date(firstDay.date + 'T00:00:00').getMonth();
    if (month !== lastMonth) {
      // Push when there's enough horizontal room from the previous label.
      const prev = monthMarkers[monthMarkers.length - 1];
      if (!prev || weekIndex - prev.weekIndex >= 3) {
        monthMarkers.push({ weekIndex, label: MONTH_LABELS[month] });
        lastMonth = month;
      }
    }
  });

  const totalLabel = `${data.totalContributions.toLocaleString()} contribution${data.totalContributions === 1 ? '' : 's'} in the last year`;

  return (
    <div className="home-heatmap">
      <svg
        className="home-heatmap__svg"
        role="img"
        aria-label={totalLabel}
        viewBox={`0 0 ${totalWidth} ${totalHeight}`}
        preserveAspectRatio="xMinYMin meet"
      >
        {monthMarkers.map((m) => (
          <text
            key={`month-${m.weekIndex}`}
            x={COL_PAD + m.weekIndex * STRIDE}
            y={ROW_PAD - 6}
            className="home-heatmap__month"
          >
            {m.label}
          </text>
        ))}

        {(['Mon', 'Wed', 'Fri'] as const).map((label, i) => {
          // 0=Sun..6=Sat — Mon=1, Wed=3, Fri=5
          const dayRow = i * 2 + 1;
          return (
            <text
              key={`dow-${label}`}
              x={COL_PAD - 6}
              y={ROW_PAD + dayRow * STRIDE + CELL - 3}
              textAnchor="end"
              className="home-heatmap__dow"
            >
              {label}
            </text>
          );
        })}

        {weeks.map((week, weekIndex) =>
          week.days.map((day) => {
            const dow = new Date(day.date + 'T00:00:00').getDay();
            return (
              <rect
                key={`${weekIndex}-${day.date}`}
                x={COL_PAD + weekIndex * STRIDE}
                y={ROW_PAD + dow * STRIDE}
                width={CELL}
                height={CELL}
                rx={2}
                ry={2}
                fill={LEVEL_COLORS[levelFor(day)]}
                className={day.contributionCount > 0
                  ? 'home-heatmap__cell home-heatmap__cell--has-commits'
                  : 'home-heatmap__cell'}
                onMouseEnter={(e) => setHover({ day, clientX: e.clientX, clientY: e.clientY })}
                onMouseMove={(e) => setHover({ day, clientX: e.clientX, clientY: e.clientY })}
                onMouseLeave={() => setHover(null)}
                onClick={(e) => {
                  // Zero-commit cubes have nothing to unfold — skip
                  // the network roundtrip and the empty popover.
                  if (day.contributionCount === 0) return;
                  // The hover tooltip would float on top of the
                  // popover; close it so we don't double-render.
                  setHover(null);
                  setSelected({ day, clientX: e.clientX, clientY: e.clientY });
                }}
              >
                {/* Kept for screen-readers + a graceful fallback if
                    the React state-driven tooltip misses an event
                    (rapid scroll-out, etc.). The custom div below
                    is the primary affordance for sighted users. */}
                <title>{`${day.contributionCount} contribution${day.contributionCount === 1 ? '' : 's'} on ${day.date}`}</title>
              </rect>
            );
          }),
        )}
      </svg>

      {/* Portal both the hover tooltip and the click popover to
          {@code document.body} so they escape the home-card's
          stacking context. {@code .home-card:hover} promotes the
          card with {@code transform: translateY(-1px)}, which makes
          the card the containing block for every fixed-position
          descendant — without the portal the tip + popover would be
          trapped behind the next card ("Your recent activity"). */}
      {hover && selected === null && createPortal(
        <div
          className="home-heatmap__tip"
          // Offset above and slightly right of the cursor so the cell
          // is still visible while the tip floats. position:fixed
          // matches clientX/Y so the tip tracks the viewport on scroll.
          style={{ left: hover.clientX + 10, top: hover.clientY - 40 }}
          role="tooltip"
        >
          <strong className="home-heatmap__tip-count">
            {hover.day.contributionCount}
          </strong>{' '}
          contribution{hover.day.contributionCount === 1 ? '' : 's'}
          <span className="home-heatmap__tip-date">
            {formatTipDate(hover.day.date)}
          </span>
        </div>,
        document.body,
      )}

      {selected && createPortal(
        <CommitPopover
          ref={popoverRef}
          day={selected.day}
          clientX={selected.clientX}
          clientY={selected.clientY}
          state={commitsByDate[selected.day.date] ?? 'loading'}
          onClose={closePopover}
        />,
        document.body,
      )}

      <div className="home-heatmap__footer">
        <span className="home-heatmap__total">{totalLabel}</span>
        <span className="home-heatmap__legend" aria-hidden="true">
          <span className="home-heatmap__legend-label">Less</span>
          {LEVEL_COLORS.map((c) => (
            <span
              key={c}
              className="home-heatmap__legend-swatch"
              style={{ background: c }}
            />
          ))}
          <span className="home-heatmap__legend-label">More</span>
        </span>
      </div>
    </div>
  );
}

type PopoverState = UserCommitDto[] | 'loading' | 'error';

/** Floating list of commits for one cube. Anchored by the click's
 *  viewport coords (position:fixed) and clamped to stay inside the
 *  window. Each row opens the commit on github.com via the bridge's
 *  shell.openExternal — keeps the embedded WebContentsView free for
 *  the PR review surfaces. */
const CommitPopover = forwardRef<HTMLDivElement, {
  day: ContributionDayDto;
  clientX: number;
  clientY: number;
  state: PopoverState;
  onClose: () => void;
}>(function CommitPopover({ day, clientX, clientY, state, onClose }, ref) {
  // Anchor below-and-right of the click, then clamp to the viewport
  // so a click near the right edge of the card doesn't render the
  // popover off-screen.
  const WIDTH = 360;
  const left = Math.max(8, Math.min(window.innerWidth - WIDTH - 8, clientX + 8));
  const top = Math.min(window.innerHeight - 80, clientY + 12);
  return (
    <div
      ref={ref}
      className="home-heatmap__popover"
      style={{ left, top, width: WIDTH }}
      role="dialog"
      aria-label={`Commits on ${formatTipDate(day.date)}`}
    >
      <div className="home-heatmap__popover-head">
        <span className="home-heatmap__popover-title">
          {day.contributionCount} contribution{day.contributionCount === 1 ? '' : 's'}
        </span>
        <span className="home-heatmap__popover-date">{formatTipDate(day.date)}</span>
        <button
          type="button"
          className="home-heatmap__popover-close"
          onClick={onClose}
          aria-label="Close"
        >
          ×
        </button>
      </div>
      {state === 'loading' && (
        <div className="home-heatmap__popover-empty">Loading commits…</div>
      )}
      {state === 'error' && (
        <div className="home-heatmap__popover-empty">
          Couldn't load commits — search-commits may be rate-limited.
        </div>
      )}
      {Array.isArray(state) && state.length === 0 && (
        <div className="home-heatmap__popover-empty">
          No commits visible to your PAT on this day.
          {/* The contribution graph counts more than commits — PRs,
              issues, code reviews — so a cube can be green even when
              /search/commits returns nothing for the same day. */}
        </div>
      )}
      {Array.isArray(state) && state.length > 0 && (
        <ul className="home-heatmap__popover-list">
          {state.map((c) => (
            <li key={c.sha}>
              <button
                type="button"
                className="home-heatmap__popover-row"
                onClick={() => { void window.bridge.openExternal(c.htmlUrl); }}
                title={`${c.repoFullName} · ${c.sha}`}
              >
                <span className="home-heatmap__popover-repo">{c.repoFullName}</span>
                <span className="home-heatmap__popover-msg">{c.shortMessage}</span>
                <span className="home-heatmap__popover-sha">{c.sha.slice(0, 7)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
});
