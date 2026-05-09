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
import { useEffect, useState } from 'react';
import type { ContributionCalendarDto } from './types';
import { getCached, setCached } from './dataCache';

const CELL = 12;
const GAP = 2;
const STRIDE = CELL + GAP;
const ROW_PAD = 18;
const COL_PAD = 28;

const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** GitHub light-theme palette, used for the Less→More legend swatches. */
const LEGEND_COLORS = ['#ebedf0', '#9be9a8', '#40c463', '#30a14e', '#216e39'];

function cacheKey(login: string) {
  return `home:contribution-graph:${login}`;
}

/**
 * Native, GitHub-GraphQL-backed contribution heatmap rendered as an inline
 * SVG. The viewBox + percentage width keep it crisp at any zoom — no
 * raster proxy, no fixed-bitmap intermediate. Hovering a cell shows a
 * native browser tooltip with the date and contribution count.
 *
 * <p>The colour for each cell is taken straight from GitHub's GraphQL
 * response, which means we inherit GitHub's palette without server-side
 * bucketing.
 */
export default function YearInCodeHeatmap({ login }: { login: string }) {
  const [data, setData] = useState<ContributionCalendarDto | null>(
    () => getCached<ContributionCalendarDto>(cacheKey(login)) ?? null,
  );

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
                fill={day.color || '#ebedf0'}
                className="home-heatmap__cell"
              >
                <title>{`${day.contributionCount} contribution${day.contributionCount === 1 ? '' : 's'} on ${day.date}`}</title>
              </rect>
            );
          }),
        )}
      </svg>

      <div className="home-heatmap__footer">
        <span className="home-heatmap__total">{totalLabel}</span>
        <span className="home-heatmap__legend" aria-hidden="true">
          <span className="home-heatmap__legend-label">Less</span>
          {LEGEND_COLORS.map((c) => (
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
