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

/** Half-hour steps across the day, as minutes of day, for the quiet-hours pickers. */
export const MINUTE_OPTIONS = Array.from({ length: 48 }, (_, index) => index * 30);

export function formatMinuteOfDay(minute: number): string {
  const hour24 = Math.floor(minute / 60);
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  const minutes = String(minute % 60).padStart(2, '0');
  return `${hour12}:${minutes} ${hour24 < 12 ? 'AM' : 'PM'}`;
}

export type ProviderStatusTone = { label: string; tone: 'is-ok' | 'is-warn' | 'is-neutral' | 'is-danger' };

/** Maps backend provider health to the settings badge; checking wins over any health. */
export function providerStatusLabel(health: string, checking: boolean): ProviderStatusTone {
  if (checking) return { label: 'Checking…', tone: 'is-neutral' };
  switch (health) {
    case 'AVAILABLE':
      return { label: '✓ Available', tone: 'is-ok' };
    case 'DEGRADED':
      return { label: 'Limited', tone: 'is-warn' };
    default:
      return { label: 'Unavailable', tone: 'is-neutral' };
  }
}
