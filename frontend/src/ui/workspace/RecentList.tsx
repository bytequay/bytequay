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
import type { FootprintStopDto, SurfaceType } from '../../types';
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

/**
 * The sidebar's "Recent" section, shown on the Home surface in place
 * of the workspace list: the most recently visited surfaces (PRs,
 * tasks, threads), newest first, backed by the footprints visit
 * capture. Clicking a row resumes that surface.
 */
export function RecentList({ onResume }: {
  onResume?: (stop: FootprintStopDto) => void;
}) {
  const [stops, setStops] = useState<FootprintStopDto[]>([]);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.getFootprints()
      .then(trail => {
        // The trail arrives oldest-first; the sidebar wants newest on top.
        if (!cancelled) setStops(trail.stops.slice().reverse().slice(0, MAX_ROWS));
      })
      .catch(() => { /* non-fatal — section renders empty */ });
    return () => { cancelled = true; };
  }, []);

  return (
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
  );
}
