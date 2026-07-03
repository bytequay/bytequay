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
import { describe, expect, it } from 'vitest';
import { periodSince } from './PrActivityPage';

describe('periodSince', () => {
  const now = new Date(2026, 6, 3); // Jul 3, 2026 local

  it('today = the current local date', () => {
    expect(periodSince('today', now)).toBe('2026-07-03');
  });

  it('past week reaches back 7 days, crossing month boundaries', () => {
    expect(periodSince('week', now)).toBe('2026-06-26');
  });

  it('past month reaches back one calendar month', () => {
    expect(periodSince('month', now)).toBe('2026-06-03');
  });
});
