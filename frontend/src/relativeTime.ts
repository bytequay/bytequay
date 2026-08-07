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
 * The one relative-time formatter. Seventeen near-identical copies of this
 * used to live next to the components that needed them, disagreeing about
 * when to switch units and whether sub-minute reads "now" or "just now".
 *
 * Surfaces that genuinely format something else keep their own helper —
 * elapsed-timer displays, and the ones that fall back to a calendar date
 * past a cutoff. Those aren't relative timestamps.
 */

const MINUTE = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

type Options = {
  /** `false` drops the trailing " ago" for dense chips: `5m`, `3h`, `2d`. */
  suffix?: boolean;
  /** Reference clock, injectable so callers and tests control it. */
  now?: number;
};

/**
 * A timestamp as `just now` / `5m ago` / `3h ago` / `2d ago`, counting down
 * in whole units so nothing ever rounds up into a unit that hasn't elapsed.
 *
 * Returns `''` for null, undefined, and unparseable input — callers that
 * want a placeholder write `relativeTime(x) || '—'`.
 */
export function relativeTime(
  value: string | number | null | undefined,
  { suffix = true, now = Date.now() }: Options = {},
): string {
  if (value === null || value === undefined) return '';
  const then = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(then)) return '';

  const elapsed = Math.max(0, now - then);
  const ago = suffix ? ' ago' : '';
  if (elapsed < MINUTE) return suffix ? 'just now' : 'now';
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)}m${ago}`;
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)}h${ago}`;
  return `${Math.floor(elapsed / DAY)}d${ago}`;
}
