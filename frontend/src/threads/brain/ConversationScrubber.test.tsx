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
import { cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConversationScrubber } from './ConversationScrubber';
import type { ScrubberDash } from '../../types/brainView';

afterEach(cleanup);

const DASHES: ScrubberDash[] = [
  { id: 'feed-1', label: '14m · Dev stage opened', active: false },
  { id: 'feed-8', label: 'now · CI fix #3 running', active: true },
];

describe('ConversationScrubber', () => {
  it('carries each dash label as a hover tooltip (data-label + accessible name)', () => {
    const { container } = render(
      <ConversationScrubber position="left" dashes={DASHES} onJumpTo={() => {}} />,
    );
    const dashes = container.querySelectorAll('.dash');
    expect(dashes).toHaveLength(2);
    expect(dashes[0].getAttribute('data-label')).toBe('14m · Dev stage opened');
    expect(dashes[0].getAttribute('aria-label')).toBe('14m · Dev stage opened');
  });

  it('marks the active dash and applies the side variant', () => {
    const { container } = render(
      <ConversationScrubber position="left" dashes={DASHES} onJumpTo={() => {}} />,
    );
    expect(container.querySelector('.conv-scrub.stages')).not.toBeNull();
    expect(container.querySelectorAll('.dash.active')).toHaveLength(1);
  });

  it('uses the you-msgs variant on the right side', () => {
    const { container } = render(
      <ConversationScrubber position="right" dashes={DASHES} onJumpTo={() => {}} />,
    );
    expect(container.querySelector('.conv-scrub-wrap.right')).not.toBeNull();
    expect(container.querySelector('.conv-scrub.you-msgs')).not.toBeNull();
  });

  it('calls onJumpTo with the dash id on click', () => {
    const onJumpTo = vi.fn();
    const { container } = render(
      <ConversationScrubber position="left" dashes={DASHES} onJumpTo={onJumpTo} />,
    );
    fireEvent.click(container.querySelectorAll('.dash')[1]);
    expect(onJumpTo).toHaveBeenCalledWith('feed-8');
  });
});
