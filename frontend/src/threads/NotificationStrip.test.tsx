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
import { describe, it, expect } from 'vitest';
import { hasJumpInAffordance } from './NotificationStrip';

describe('hasJumpInAffordance', () => {
  it('offers Jump in for a stuck/running NEEDS_ATTENTION task', () => {
    expect(hasJumpInAffordance({ kind: 'NEEDS_ATTENTION', status: 'UNREAD' })).toBe(true);
    expect(hasJumpInAffordance({ kind: 'NEEDS_ATTENTION', status: 'RESOLVING' })).toBe(true);
  });

  it('does NOT offer Jump in for a parked AWAITING_REVIEW — Review handles it', () => {
    // The reported bug: Jump in did nothing on a parked review; it's removed.
    expect(hasJumpInAffordance({ kind: 'AWAITING_REVIEW', status: 'UNREAD' })).toBe(false);
  });

  it('does not offer Jump in for an informational AUTO_FIX_DONE row', () => {
    expect(hasJumpInAffordance({ kind: 'AUTO_FIX_DONE', status: 'UNREAD' })).toBe(false);
  });
});
