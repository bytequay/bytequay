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

const formatters = {
  narrow: new Intl.RelativeTimeFormat('en', { numeric: 'auto', style: 'narrow' }),
  long: new Intl.RelativeTimeFormat('en', { numeric: 'auto', style: 'long' }),
};
// Months and years always read as a count: "1mo ago", never "last mo.".
const counted = {
  narrow: new Intl.RelativeTimeFormat('en', { numeric: 'always', style: 'narrow' }),
  long: new Intl.RelativeTimeFormat('en', { numeric: 'always', style: 'long' }),
};

export function relativeTime(
  value: string | number | null | undefined,
  {
    suffix = true,
    now = Date.now(),
    style = 'narrow',
  }: { suffix?: boolean; now?: number; style?: keyof typeof formatters } = {},
): string {
  if (value === null || value === undefined) return '';
  const elapsed = Math.max(0, now - (typeof value === 'number' ? value : Date.parse(value)));
  if (!Number.isFinite(elapsed)) return '';
  const [amount, unit, short] = elapsed < 60_000
    ? [0, 'second', 'now'] as const
    : elapsed < 3_600_000
      ? [Math.floor(elapsed / 60_000), 'minute', 'm'] as const
      : elapsed < 86_400_000
        ? [Math.floor(elapsed / 3_600_000), 'hour', 'h'] as const
        : elapsed < 30 * 86_400_000
          ? [Math.floor(elapsed / 86_400_000), 'day', 'd'] as const
          : elapsed < 365 * 86_400_000
            ? [Math.floor(elapsed / (30 * 86_400_000)), 'month', 'mo'] as const
            : [Math.floor(elapsed / (365 * 86_400_000)), 'year', 'y'] as const;
  const formatter = (unit === 'month' || unit === 'year' ? counted : formatters)[style];
  return suffix ? formatter.format(-amount, unit) : amount === 0 ? short : `${amount}${short}`;
}

/** The same elapsed time as a phrase: "3d ago", "just now", or "" when there is no timestamp. */
export function ago(value: string | number | null | undefined, now = Date.now()): string {
  const elapsed = relativeTime(value, { suffix: false, now });
  if (elapsed === '') return '';
  return elapsed === 'now' ? 'just now' : `${elapsed} ago`;
}
