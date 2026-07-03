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
import type { FootprintStopDto, PullRequestDto, SurfaceType } from '../../types';
import { FootprintIcon, type IconKind } from '../../footprints/FootprintIcon';
import { relativeTime } from '../../notificationDisplay';

const MAX_ROWS = 8;

function iconFor(surfaceType: SurfaceType): IconKind {
  switch (surfaceType) {
    case 'PR_KANBAN': return 'kanban';
    case 'PR':        return 'pull-request';
    case 'TASK':      return 'robot';
    case 'THREAD':    return 'message';
  }
}

function isToday(iso: string): boolean {
  const d = new Date(iso);
  const now = new Date();
  return d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
}

/**
 * The sidebar's "Recent" section, shown on the Home surface in place
 * of the workspace list: the most recently visited surfaces (PRs,
 * tasks, threads), newest first, backed by the footprints visit
 * capture, plus a compact "Today" summary (what's being worked on +
 * the latest PR reviewed today). Clicking a row resumes that surface.
 */
export function RecentList({ onResume, onOpenPr }: {
  onResume?: (stop: FootprintStopDto) => void;
  /** Open a PR from the Today summary's "Reviewed" line. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
}) {
  const [stops, setStops] = useState<FootprintStopDto[]>([]);
  const [reviewedToday, setReviewedToday] = useState<PullRequestDto | null>(null);

  // The list is mounted for as long as the rail shows (i.e. across every
  // non-workspace surface), so refresh on a slow poll — visits recorded
  // while the user moves around should show up without a remount.
  useEffect(() => {
    let cancelled = false;
    const refresh = () => {
      void window.bridge.getFootprints()
        .then(trail => {
          // The trail arrives oldest-first; the sidebar wants newest on top.
          if (!cancelled) setStops(trail.stops.slice().reverse().slice(0, MAX_ROWS));
        })
        .catch(() => { /* non-fatal — section renders empty */ });
      void window.bridge.fetchPrs()
        .then(prs => {
          if (cancelled) return;
          const reviewed = prs
            .filter(p => p.reviewedAt !== null && isToday(p.reviewedAt))
            .sort((a, b) => Date.parse(b.reviewedAt as string) - Date.parse(a.reviewedAt as string));
          setReviewedToday(reviewed[0] ?? null);
        })
        .catch(() => { /* summary line just stays hidden */ });
    };
    refresh();
    const id = window.setInterval(refresh, 20_000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  const workingOn = stops[0] ?? null;

  return (
    <>
      <div className="sb-section">
        <div className="sb-section-h">
          <span className="nm">Recent</span>
        </div>
        {stops.length === 0 ? (
          <p className="sb-recent__empty">Nothing visited yet today.</p>
        ) : (
          <div className="sb-recent">
            {stops.map(stop => (
              <button
                key={`${stop.surfaceType}:${stop.surfaceId}`}
                type="button"
                className="sb-recent__row"
                onClick={() => onResume?.(stop)}
                title={stop.context ?? stop.surfaceId}
              >
                <span className="sb-recent__icon" aria-hidden="true">
                  <FootprintIcon kind={iconFor(stop.surfaceType)} size={12} />
                </span>
                <span className="sb-recent__meta">
                  <span className="sb-recent__title">{stop.title ?? stop.surfaceId}</span>
                  <span className="sb-recent__sub">
                    {stop.context ?? stop.surfaceId} · {relativeTime(stop.latestVisitAt)}
                  </span>
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {(workingOn !== null || reviewedToday !== null) && (
        <div className="sb-section">
          <div className="sb-section-h">
            <span className="nm">Today</span>
          </div>
          <div className="sb-recent">
            {workingOn !== null && (
              <button
                type="button"
                className="sb-recent__row"
                onClick={() => onResume?.(workingOn)}
                title={workingOn.context ?? workingOn.surfaceId}
              >
                <span className="sb-recent__meta">
                  <span className="sb-recent__label">Working on</span>
                  <span className="sb-recent__title">{workingOn.title ?? workingOn.surfaceId}</span>
                </span>
              </button>
            )}
            {reviewedToday !== null && (
              <button
                type="button"
                className="sb-recent__row"
                onClick={() => {
                  const slash = reviewedToday.repo.indexOf('/');
                  if (slash > 0) {
                    onOpenPr?.(
                        reviewedToday.repo.slice(0, slash),
                        reviewedToday.repo.slice(slash + 1),
                        reviewedToday.number);
                  }
                }}
                title={`${reviewedToday.repo} #${reviewedToday.number}`}
              >
                <span className="sb-recent__meta">
                  <span className="sb-recent__label">Reviewed</span>
                  <span className="sb-recent__title">{reviewedToday.title} #{reviewedToday.number}</span>
                </span>
              </button>
            )}
          </div>
        </div>
      )}
    </>
  );
}
