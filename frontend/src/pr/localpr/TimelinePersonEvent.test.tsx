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
  it('nests the eye/check badge inside the avatar wrapper, not as a floating sibling', () => {
    const { container } = render(
      <TimelinePersonEvent actor="findinpath" verdict={null} time={Date.now()} />,
    );
    const wrapper = container.querySelector('.pr-person-avatar');
    expect(wrapper).toBeTruthy();
    expect(wrapper?.querySelector('img, .avatar--fallback')).toBeTruthy();
    expect(wrapper?.querySelector('.eye')).toBeTruthy();
  });

  it('shows the eye glyph for a plain review, a check for an approval', () => {
    const plain = render(<TimelinePersonEvent actor="findinpath" verdict={null} time={Date.now()} />);
    expect(plain.container.querySelector('.eye')?.textContent).toBe('👁');
    plain.unmount();

    const approved = render(<TimelinePersonEvent actor="findinpath" verdict="APPROVED" time={Date.now()} />);
    const eye = approved.container.querySelector('.eye');
    expect(eye?.textContent).toBe('✓');
    expect(eye?.className).toContain('approved');
  });
});
