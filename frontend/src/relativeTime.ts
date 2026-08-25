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
        : [Math.floor(elapsed / 86_400_000), 'day', 'd'] as const;
  return suffix ? formatters[style].format(-amount, unit) : amount === 0 ? short : `${amount}${short}`;
}
