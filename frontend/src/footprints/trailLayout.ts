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

// ── Trail geometry. The SVG stretches to the card (preserveAspect none),
//    so pins are placed as percentages of the viewBox. A short trail uses a
//    single horizontal line; only once there are more than PER_ROW pins
//    does it wrap into the serpentine (top row left→right, curve down the
//    right edge, bottom row right→left) so a couple of visits don't sprawl
//    across two rows. ──
const VB_W = 680;
const X_START = 48;
const X_END = 632;

/** Pins beyond this wrap to a second row. Five keeps labels (~124px) from
 *  colliding across a full-width card. */
const PER_ROW = 5;

// Single-row layout.
const ONE_ROW_VB_H = 88;
const ONE_ROW_Y = 34;
const ONE_ROW_HEIGHT_PX = 104;

// Two-row (serpentine) layout.
const TWO_ROW_VB_H = 190;
const TOP_Y = 52;
const BOT_Y = 132;
const X_TOP_END = 600;
const X_BOT_END = 92;
const TWO_ROW_HEIGHT_PX = 196;

export type PinPos = { leftPct: number; topPct: number };

export type TrailConfig = {
  viewBox: string;
  /** Dashed route the pins sit on. */
  path: string;
  /** Pixel height of the trail area. */
  heightPx: number;
  /** One position per pin, in chronological index order. */
  positions: PinPos[];
};

/** n evenly-spaced x-coordinates across [a, b]; a single point is centred. */
function spreadX(n: number, a: number, b: number): number[] {
  if (n <= 0) return [];
  if (n === 1) return [(a + b) / 2];
  return Array.from({ length: n }, (_, i) => a + (i / (n - 1)) * (b - a));
}

/**
 * Geometry for a trail of {@code count} pins. Up to {@link PER_ROW} pins lay
 * out on one horizontal line (short, compact card); more than that wrap into
 * the two-row serpentine. Pin index order always reads chronologically.
 */
export function computeTrail(count: number): TrailConfig {
  if (count <= 0) {
    return { viewBox: `0 0 ${VB_W} ${ONE_ROW_VB_H}`, path: '', heightPx: ONE_ROW_HEIGHT_PX, positions: [] };
  }

  if (count <= PER_ROW) {
    const positions = spreadX(count, X_START, X_END).map(x => ({
      leftPct: (x / VB_W) * 100,
      topPct: (ONE_ROW_Y / ONE_ROW_VB_H) * 100,
    }));
    return {
      viewBox: `0 0 ${VB_W} ${ONE_ROW_VB_H}`,
      path: `M${X_START} ${ONE_ROW_Y} H${X_END}`,
      heightPx: ONE_ROW_HEIGHT_PX,
      positions,
    };
  }

  const topCount = Math.ceil(count / 2);
  const bottomCount = count - topCount;
  const topX = spreadX(topCount, X_START, X_TOP_END);
  // Bottom row runs right→left so the path reads as one continuous route.
  const bottomX = spreadX(bottomCount, X_BOT_END, X_TOP_END).reverse();
  const positions: PinPos[] = [];
  for (let i = 0; i < topCount; i++) {
    positions.push({ leftPct: (topX[i] / VB_W) * 100, topPct: (TOP_Y / TWO_ROW_VB_H) * 100 });
  }
  for (let i = 0; i < bottomCount; i++) {
    positions.push({ leftPct: (bottomX[i] / VB_W) * 100, topPct: (BOT_Y / TWO_ROW_VB_H) * 100 });
  }
  return {
    viewBox: `0 0 ${VB_W} ${TWO_ROW_VB_H}`,
    path: `M${X_START} ${TOP_Y} H${X_TOP_END} C636 ${TOP_Y} 636 ${BOT_Y} ${X_TOP_END} ${BOT_Y} H${X_BOT_END}`,
    heightPx: TWO_ROW_HEIGHT_PX,
    positions,
  };
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
