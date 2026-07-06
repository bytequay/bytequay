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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PRTimeline } from './PRTimeline';
import type { LocalPRTimelineEvent } from '../../types/localPr';

afterEach(cleanup);

function reviewEvent(over: Partial<LocalPRTimelineEvent> = {}): LocalPRTimelineEvent {
  return {
    id: 'ev1', localPrId: 'pr1', eventType: 'review', actor: '@reviewer1',
    isLocalOnly: false, strippedOnPushAt: null, createdAt: Date.parse('2026-06-20T10:00:00Z'),
    payload: { verdict: 'APPROVED' }, ...over,
  };
}

describe('PRTimeline remote review rendering', () => {
  it('shows the verdict and written summary for a remote GitHub review', () => {
    render(<PRTimeline mode="remote" events={[reviewEvent({
      payload: { verdict: 'APPROVED', body: 'Nice cleanup, LGTM.' },
    })]} />);

    expect(screen.getByText('@reviewer1', { exact: false })).toBeTruthy();
    expect(screen.getByText('APPROVED')).toBeTruthy();
    expect(screen.getByText('Nice cleanup, LGTM.')).toBeTruthy();
  });

  it('shows CHANGES REQUESTED without a body when the reviewer left no summary', () => {
    render(<PRTimeline mode="remote" events={[reviewEvent({
      payload: { verdict: 'CHANGES_REQUESTED' },
    })]} />);

    expect(screen.getByText('CHANGES REQUESTED')).toBeTruthy();
  });

  it('still renders the brain adversarial-review branch untouched', () => {
    render(<PRTimeline mode="local" events={[reviewEvent({
      actor: 'brain', isLocalOnly: true,
      payload: { scope: 'plan', verdict: 'approved', iteration: 1 },
    })]} />);

    expect(screen.getByText('BRAIN')).toBeTruthy();
    expect(screen.getByText('APPROVED')).toBeTruthy();
  });
});
