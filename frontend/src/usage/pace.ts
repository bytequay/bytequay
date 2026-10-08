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
import type { ProviderUsageWindowDto } from '../types';

export function usagePace(window: ProviderUsageWindowDto, now: number, stale = false) {
  const start = window.startsAt === null ? NaN : Date.parse(window.startsAt);
  const reset = window.resetsAt === null ? NaN : Date.parse(window.resetsAt);
  const expired = Number.isFinite(reset) && reset <= now;
  const used = Math.min(100, Math.max(0, window.usedPercent));
  const expected = !stale && !expired && Number.isFinite(start) && Number.isFinite(reset) && start <= now && reset > start
    ? Math.min(100, Math.max(0, (now - start) / (reset - start) * 100)) : null;
  return {
    expired,
    expected,
    gray: expired ? 0 : expected === null ? used : Math.min(used, expected),
    green: expected === null ? 0 : Math.max(0, expected - used),
    red: expected === null ? 0 : Math.max(0, used - expected),
    guidance: expired ? 'Waiting for reset data' : stale ? 'Update needed' : expected === null ? 'Pace unavailable'
      : window.usedPercent >= 100 ? 'Limit reached' : Math.abs(window.usedPercent - expected) < 1 ? 'On pace'
      : window.usedPercent > expected ? 'Slow down' : 'Room to use more',
  };
}

export function resetLabel(resetsAt: string | null, now: number): string {
  if (resetsAt === null) return 'Reset time unavailable';
  const minutes = Math.ceil((Date.parse(resetsAt) - now) / 60_000);
  if (!Number.isFinite(minutes)) return 'Reset time unavailable';
  if (minutes <= 0) return 'Reset · awaiting update';
  if (minutes >= 1440) return `Resets in ${Math.floor(minutes / 1440)}d ${Math.floor(minutes % 1440 / 60)}h`;
  if (minutes >= 60) return `Resets in ${Math.floor(minutes / 60)}h ${minutes % 60}m`;
  return `Resets in ${minutes}m`;
}
