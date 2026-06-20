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

/**
 * Pure formatting helpers for the aggregate strip and brain feed. Kept
 * separate from the components so they're unit-testable without a DOM.
 */

/** A whole-second duration as a short human string: `42s`, `12m`,
 *  `23m 14s`, `4h 12m`. Zero reads as `0s`. */
export function formatDuration(totalSec: number): string {
  const s = Math.max(0, Math.round(totalSec));
  if (s < 60) return `${s}s`;
  const minutes = Math.floor(s / 60);
  const secs = s % 60;
  if (minutes < 60) {
    return secs === 0 ? `${minutes}m` : `${minutes}m ${secs}s`;
  }
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`;
}

/** Cents as a dollar string with two decimals: `147` → `$1.47`. */
export function formatCost(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

/** Tokens as a compact `k` string: `86000` → `86.0k`, `200000` → `200.0k`. */
export function formatTokensK(tokens: number): string {
  return `${(tokens / 1000).toFixed(1)}k`;
}

const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

/** Short relative time for inline `.ts` chips: `now`, `14m ago`,
 *  `21h ago`, `3d ago`. `nowMs` is injectable so callers (and tests)
 *  control the reference clock. */
export function relativeShort(iso: string, nowMs: number): string {
  const diff = Math.max(0, nowMs - new Date(iso).getTime());
  if (diff < 45_000) return 'now';
  if (diff < HOUR) return `${Math.max(1, Math.round(diff / MIN))}m ago`;
  if (diff < DAY) return `${Math.round(diff / HOUR)}h ago`;
  return `${Math.round(diff / DAY)}d ago`;
}

/** Long relative time for the time dividers: `now`, `14 minutes ago`,
 *  `1 hour ago`, `3 days ago`. */
export function relativeLong(iso: string, nowMs: number): string {
  const diff = Math.max(0, nowMs - new Date(iso).getTime());
  if (diff < 45_000) return 'now';
  if (diff < HOUR) {
    const m = Math.max(1, Math.round(diff / MIN));
    return `${m} minute${m === 1 ? '' : 's'} ago`;
  }
  if (diff < DAY) {
    const h = Math.round(diff / HOUR);
    return `${h} hour${h === 1 ? '' : 's'} ago`;
  }
  const d = Math.round(diff / DAY);
  return `${d} day${d === 1 ? '' : 's'} ago`;
}
