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
import type { FootprintStopDto } from '../types';
import { FootprintIcon } from './FootprintIcon';
import { formatClock, surfaceMeta, START_META, type PinPos } from './trailLayout';

/** The grey origin marker that opens the trail. Non-interactive. */
export function StartPin({ pos }: { pos: PinPos }) {
  return (
    <div className="hp-fp-pin-wrap" style={{ left: `${pos.leftPct}%`, top: `${pos.topPct}%` }}>
      <span className="hp-fp-pin hp-fp-pin--start" style={{ color: START_META.color }} aria-hidden>
        <FootprintIcon kind={START_META.icon} />
      </span>
    </div>
  );
}

type StopPinProps = {
  stop: FootprintStopDto;
  pos: PinPos;
  /** The latest stop — gets the pulsing "you are here" ring. */
  current: boolean;
  onResume: (stop: FootprintStopDto) => void;
};

/**
 * One surface stop: a coloured ring + icon, a title and time label, an
 * optional "N×" revisit badge, and (for the latest stop) a pulsing ring.
 * Clicking resumes the surface.
 */
export function StopPin({ stop, pos, current, onResume }: StopPinProps) {
  const meta = surfaceMeta(stop.surfaceType);
  const label = stop.title ?? stop.surfaceId;
  return (
    <div className="hp-fp-pin-wrap" style={{ left: `${pos.leftPct}%`, top: `${pos.topPct}%` }}>
      <button
        type="button"
        className={`hp-fp-pin${current ? ' hp-fp-pin--current' : ''}`}
        style={{ color: meta.color }}
        onClick={() => onResume(stop)}
        title={current ? `${label} (you are here) — resume` : `${label} — resume`}
        aria-label={current ? `${label} (you are here) — resume` : `${label} — resume`}
      >
        {current && <span className="hp-fp-pin__pulse" aria-hidden />}
        <FootprintIcon kind={meta.icon} />
        {stop.visitCount > 1 && (
          <span className="hp-fp-pin__badge" aria-label={`visited ${stop.visitCount} times`}>
            {stop.visitCount}×
          </span>
        )}
      </button>
      <div className="hp-fp-label">
        <div className="hp-fp-label__title">{label}</div>
        <div className="hp-fp-label__meta">{formatClock(stop.latestVisitAt)}</div>
      </div>
    </div>
  );
}
