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
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { TimelinePersonEvent } from './TimelinePersonEvent';

afterEach(cleanup);

describe('TimelinePersonEvent', () => {
  it('renders the avatar and the eye/check icon as separate, adjacent elements', () => {
    const { container } = render(
      <TimelinePersonEvent actor="findinpath" verdict={null} time={Date.now()} />,
    );
    const row = container.querySelector('.pr-person-event');
    expect(row?.querySelector('img, .avatar--fallback')).toBeTruthy();
    const eye = row?.querySelector('.eye');
    expect(eye).toBeTruthy();
    // Not nested inside the avatar — a sibling on the rail, per the fix.
    expect(row?.querySelector('img, .avatar--fallback')?.contains(eye ?? null)).toBe(false);
  });

  it('shows the eye icon for a plain review, a check for an approval', () => {
    const plain = render(<TimelinePersonEvent actor="findinpath" verdict={null} time={Date.now()} />);
    const plainEye = plain.container.querySelector('.eye');
    expect(plainEye?.querySelector('svg circle')).toBeTruthy();
    expect(plainEye?.className).not.toContain('approved');
    plain.unmount();

    const approved = render(<TimelinePersonEvent actor="findinpath" verdict="APPROVED" time={Date.now()} />);
    const eye = approved.container.querySelector('.eye');
    expect(eye?.querySelector('svg path')).toBeTruthy();
    expect(eye?.className).toContain('approved');
  });
});
