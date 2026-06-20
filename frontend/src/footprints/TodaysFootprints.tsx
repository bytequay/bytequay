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
import type { FootprintStopDto, FootprintsTrailDto } from '../types';
import { getCached, setCached } from '../dataCache';
import { StartPin, StopPin } from './FootprintPin';
import {
  addDays, formatDayLabel, isSameDay, pinPositions, toYmd,
  TRAIL_HEIGHT_PX, TRAIL_PATH, TRAIL_VIEWBOX,
} from './trailLayout';

type Props = {
  /** Re-open a stop's surface. */
  onResume: (stop: FootprintStopDto) => void;
  /** Open the full-day view. // decision pending: full-day map is a later slice. */
  onSeeFullDay?: () => void;
};

/**
 * "Today's footprints" — a winding map-style trail of the surfaces the
 * user visited on a day, each pin clickable to resume. Manages its own
 * date (steppable to prior days) and fetches the trail through the
 * bridge; the right-rail "recent activity" list stays the detailed log.
 */
export default function TodaysFootprints({ onResume, onSeeFullDay }: Props) {
  const [date, setDate] = useState<Date>(() => new Date());
  const ymd = toYmd(date);
  const cacheKey = `home:footprints:${ymd}`;
  const [trail, setTrail] = useState<FootprintsTrailDto | null>(
    () => getCached<FootprintsTrailDto>(cacheKey) ?? null,
  );

  useEffect(() => {
    let cancelled = false;
    setTrail(getCached<FootprintsTrailDto>(cacheKey) ?? null);
    void window.bridge.getFootprints(ymd)
      .then((t) => {
        if (cancelled) return;
        setTrail(t);
        setCached(cacheKey, t);
      })
      .catch(() => { /* non-fatal — leave any cached trail visible */ });
    return () => { cancelled = true; };
  }, [ymd, cacheKey]);

  const today = isSameDay(date, new Date());
  const stops = trail?.stops ?? [];
  // start marker + one pin per stop, in chronological order.
  const positions = pinPositions(stops.length + 1);

  return (
    <div className="home-card hp-footprints">
      <div className="hp-footprints__header">
        <span className="hp-footprints__label">TODAY’S FOOTPRINTS</span>
        <div className="hp-footprints__controls">
          <div className="hp-footprints__stepper">
            <button
              type="button"
              className="hp-footprints__step"
              onClick={() => setDate(d => addDays(d, -1))}
              aria-label="Previous day"
            >‹</button>
            <span className="hp-footprints__day">{formatDayLabel(date)}</span>
            <button
              type="button"
              className="hp-footprints__step"
              onClick={() => setDate(d => addDays(d, 1))}
              disabled={today}
              aria-label="Next day"
            >›</button>
          </div>
          <button type="button" className="hp-footprints__seeall" onClick={onSeeFullDay}>
            See full day →
          </button>
        </div>
      </div>

      {stops.length === 0 ? (
        <div className="hp-footprints__empty">
          {today ? 'No footprints yet today.' : 'No footprints on this day.'}
        </div>
      ) : (
        <>
          <div className="hp-footprints__trail" style={{ height: TRAIL_HEIGHT_PX }}>
            <svg
              className="hp-footprints__path"
              viewBox={TRAIL_VIEWBOX}
              preserveAspectRatio="none"
              aria-hidden
            >
              <path d={TRAIL_PATH} fill="none" stroke="#D5D5DA" strokeWidth={2} strokeDasharray="1.5 7" />
            </svg>
            <StartPin pos={positions[0]} />
            {stops.map((stop, i) => (
              <StopPin
                key={`${stop.surfaceType}:${stop.surfaceId}`}
                stop={stop}
                pos={positions[i + 1]}
                current={i === stops.length - 1}
                onResume={onResume}
              />
            ))}
          </div>
          {trail !== null && trail.totalStops > stops.length && (
            <div className="hp-footprints__overflow">
              Showing {stops.length} of {trail.totalStops} ·{' '}
              <button type="button" className="hp-footprints__seeall-inline" onClick={onSeeFullDay}>
                See full day →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
