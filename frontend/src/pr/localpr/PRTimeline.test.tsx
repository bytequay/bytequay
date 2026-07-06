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
import type { LocalPR, LocalPRTimelineEvent } from '../../types/localPr';

afterEach(cleanup);

function pr(over: Partial<LocalPR> = {}): LocalPR {
  return {
    id: 'pr1', taskId: 't1', branchName: 'feat/x', baseBranch: 'main', title: 'T',
    description: '', status: 'local-open', createdAt: 1, pushedAt: null, remotePrNumber: null,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin: 'task', repo: null, author: null, syncedAt: null,
    syncedAdditions: null, syncedDeletions: null, ...over,
  };
}

function reviewEvent(over: Partial<LocalPRTimelineEvent> = {}): LocalPRTimelineEvent {
  return {
    id: 'ev1', localPrId: 'pr1', eventType: 'review', actor: '@reviewer1',
    isLocalOnly: false, strippedOnPushAt: null, createdAt: Date.parse('2026-06-20T10:00:00Z'),
    payload: { verdict: 'APPROVED' }, ...over,
  };
}

describe('PRTimeline review rendering', () => {
  it('renders an approval as a person-event with the review body attached', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      payload: { verdict: 'APPROVED', body: 'Nice cleanup, LGTM.' },
    })]} />);

    expect(screen.getAllByText('reviewer1', { exact: false }).length).toBeGreaterThan(0);
    expect(screen.getByText(/approved these changes/)).toBeTruthy();
    expect(screen.getByText('Nice cleanup, LGTM.')).toBeTruthy();
  });

  it('renders CHANGES_REQUESTED as "reviewed", not "approved"', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      payload: { verdict: 'CHANGES_REQUESTED' },
    })]} />);

    expect(screen.getByText(/reviewed/)).toBeTruthy();
    expect(screen.queryByText(/approved these changes/)).toBeNull();
  });

  it('renders the brain adversarial-review branch as a person-event too', () => {
    render(<PRTimeline pr={pr()} comments={[]} events={[reviewEvent({
      actor: 'brain', isLocalOnly: true,
      payload: { scope: 'plan', verdict: 'approved', iteration: 1 },
    })]} />);

    expect(screen.getByText(/approved these changes/)).toBeTruthy();
  });
});

describe('PRTimeline composition', () => {
  it('always renders the description as the first bubble', () => {
    render(<PRTimeline pr={pr({ description: 'Adds a cache layer.' })} comments={[]} events={[]} />);

    expect(screen.getByText('Adds a cache layer.')).toBeTruthy();
    expect(screen.getByText(/drafted the description/)).toBeTruthy();
  });

  it('groups a file-line comment thread into one review-thread card', () => {
    render(<PRTimeline pr={pr()} events={[]} comments={[
      {
        id: 'c1', localPrId: 'pr1', origin: 'local', scope: 'file-line',
        filePath: 'src/Foo.java', lineNumber: 42, author: 'you', body: 'Fix this.',
        createdAt: 2, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: null, publishedAt: null,
      },
      {
        id: 'c2', localPrId: 'pr1', origin: 'local', scope: 'file-line',
        filePath: 'src/Foo.java', lineNumber: 42, author: 'claude-code', body: 'Fixed.',
        createdAt: 3, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
        parentCommentId: 'c1', publishedAt: null,
      },
    ]} />);

    expect(screen.getByText('src/Foo.java:42', { exact: false })).toBeTruthy();
    expect(screen.getByText('Fix this.')).toBeTruthy();
    expect(screen.getByText('Fixed.')).toBeTruthy();
  });
});
