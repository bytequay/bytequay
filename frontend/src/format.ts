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

/** Shared display formatters. Only helpers that were byte-identical across
 *  several call sites live here — the duration/byte/relative-time variants
 *  scattered around the app deliberately format differently and stay local. */

/** True when {@code iso} falls on the viewer's current calendar day. */
export function isToday(iso: string | null): boolean {
  if (iso === null) return false;
  const date = new Date(iso);
  const now = new Date();
  return date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
}

/** Clips {@code s} to {@code max} characters, spending the last one on an ellipsis. */
export function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

/** Agent spend, stored as thousandths of a cent. Sub-$0.10 amounts keep four
 *  decimals so a cheap turn doesn't render as a flat "$0.00". */
export function formatCost(milli: number | null): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}
