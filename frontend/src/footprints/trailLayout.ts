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
import type { SurfaceType } from '../types';

/** Inline-SVG icon keys (we don't depend on an icon font). */
export type IconKind = 'kanban' | 'pull-request' | 'robot' | 'message' | 'flag' | 'map-pin';

export type SurfaceMeta = { color: string; icon: IconKind };

/** Per-surface pin colour + icon, matching the design's concept coding. */
export function surfaceMeta(surfaceType: SurfaceType): SurfaceMeta {
  switch (surfaceType) {
    case 'PR_KANBAN': return { color: '#3E74D9', icon: 'kanban' };
    case 'PR': return { color: '#3E74D9', icon: 'pull-request' };
    case 'TASK': return { color: '#6E5DE7', icon: 'robot' };
    case 'THREAD': return { color: '#1E9E78', icon: 'message' };
  }
}

/** Grey origin marker that opens every trail. */
export const START_META: SurfaceMeta = { color: '#8A8D93', icon: 'flag' };

// ── Serpentine geometry. The SVG stretches to the card (preserveAspect
//    none), so pins are placed as percentages of the viewBox. Top row runs
//    left→right, curves down the right edge, bottom row runs right→left. ──
const VB_W = 680;
const VB_H = 190;
const TOP_Y = 52;
const BOT_Y = 132;
const X_START = 48;
const X_TOP_END = 600;
const X_BOT_END = 92;

export const TRAIL_VIEWBOX = `0 0 ${VB_W} ${VB_H}`;
export const TRAIL_PATH = `M${X_START} ${TOP_Y} H${X_TOP_END} C636 ${TOP_Y} 636 ${BOT_Y} ${X_TOP_END} ${BOT_Y} H${X_BOT_END}`;
/** Fixed pixel height of the trail area; pin top is a % of this. */
export const TRAIL_HEIGHT_PX = 196;

export type PinPos = { leftPct: number; topPct: number };

/**
 * Positions for {@code count} evenly-spaced pins along the serpentine —
 * the first half on the top row (left→right), the rest on the bottom row
 * (right→left), so index order reads chronologically along the path.
 */
export function pinPositions(count: number): PinPos[] {
  const positions: PinPos[] = [];
  if (count <= 0) return positions;
  const topCount = Math.ceil(count / 2);
  const bottomCount = count - topCount;
  for (let i = 0; i < count; i++) {
    let x: number;
    let y: number;
    if (i < topCount) {
      const t = topCount <= 1 ? 0 : i / (topCount - 1);
      x = X_START + t * (X_TOP_END - X_START);
      y = TOP_Y;
    }
    else {
      const j = i - topCount;
      const t = bottomCount <= 1 ? 0 : j / (bottomCount - 1);
      x = X_TOP_END - t * (X_TOP_END - X_BOT_END);
      y = BOT_Y;
    }
    positions.push({ leftPct: (x / VB_W) * 100, topPct: (y / VB_H) * 100 });
  }
  return positions;
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Fri Jun 19" — the date-stepper label. */
export function formatDayLabel(date: Date): string {
  return `${WEEKDAYS[date.getDay()]} ${MONTHS[date.getMonth()]} ${date.getDate()}`;
}

/** "YYYY-MM-DD" in local time — the API's date param. */
export function toYmd(date: Date): string {
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${m}-${d}`;
}

/** "15:30" local time from an ISO instant. */
export function formatClock(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** Same calendar day in local time. */
export function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}

/** A new Date offset by whole days, at the same wall-clock time. */
export function addDays(date: Date, days: number): Date {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}
