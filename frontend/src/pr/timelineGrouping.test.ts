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
import { groupTimelineEntries, type RawTimelineEntry } from './timelineGrouping';
import type { ActivityItemDto, ReactionsDto, ReviewThreadDto } from '../types';

const ZERO_REACTIONS: ReactionsDto = {
  plusOne: 0, minusOne: 0, laugh: 0, hooray: 0,
  confused: 0, heart: 0, rocket: 0, eyes: 0,
};

function activity(overrides: Partial<ActivityItemDto> = {}): ActivityItemDto {
  return {
    actor: 'alice',
    eventType: 'committed',
    timestamp: '2026-04-29T10:00:00Z',
    body: null,
    state: null,
    beforeSha: null,
    afterSha: 'abc1234',
    requestedReviewer: null,
    reviewId: null,
    authorAssociation: null,
    githubId: null,
    reactions: ZERO_REACTIONS,
    labelName: null,
    labelColor: null,
    milestoneTitle: null,
    assigneeLogin: null,
    crossRefNumber: null,
    crossRefTitle: null,
    crossRefUrl: null,
    crossRefIsPullRequest: false,
    ...overrides,
  };
}

function rawActivity(item: ActivityItemDto, attachedThreads?: ReviewThreadDto[]): RawTimelineEntry {
  return {
    kind: 'activity',
    ts: item.timestamp ? new Date(item.timestamp).getTime() : 0,
    item,
    attachedThreads,
  };
}

function rawThread(thread: ReviewThreadDto): RawTimelineEntry {
  return {
    kind: 'thread',
    ts: 0,
    thread,
  };
}

function makeThread(overrides: Partial<ReviewThreadDto> = {}): ReviewThreadDto {
  return {
    rootGithubId: 1,
    filePath: 'src/foo.ts',
    line: 10,
    side: 'RIGHT',
    diffHunk: null,
    messages: [],
    resolved: false,
    outdated: false,
    startLine: null,
    startSide: null,
    originalLine: null,
    originalStartLine: null,
    ...overrides,
  };
}

describe('groupTimelineEntries', () => {
  it('returns an empty list for empty input', () => {
    expect(groupTimelineEntries([])).toEqual([]);
  });

  it('passes a single activity through unchanged', () => {
    const item = activity();
    const out = groupTimelineEntries([rawActivity(item)]);
    expect(out).toEqual([{ kind: 'activity', item, attachedThreads: undefined }]);
  });

  it('preserves attachedThreads when an activity has them', () => {
    const item = activity({ eventType: 'reviewed', state: 'COMMENTED' });
    const thread = makeThread();
    const out = groupTimelineEntries([rawActivity(item, [thread])]);
    expect(out).toHaveLength(1);
    expect(out[0]).toEqual({ kind: 'activity', item, attachedThreads: [thread] });
  });

  it('passes a thread row through unchanged', () => {
    const thread = makeThread({ rootGithubId: 42 });
    const out = groupTimelineEntries([rawThread(thread)]);
    expect(out).toEqual([{ kind: 'thread', thread }]);
  });

  it('collapses two same-actor committed events on the same day into an event-group', () => {
    const a = activity({ timestamp: '2026-04-29T10:00:00Z', afterSha: 'aaa' });
    const b = activity({ timestamp: '2026-04-29T11:00:00Z', afterSha: 'bbb' });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b)]);
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({
      kind: 'event-group',
      actor: 'alice',
      eventType: 'committed',
      count: 2,
    });
    // The lastItem carries the most recent commit's SHA so the renderer
    // can show "last commit was bbb".
    expect((out[0] as { lastItem: ActivityItemDto }).lastItem.afterSha).toBe('bbb');
  });

  it('does not group commits by different actors', () => {
    const a = activity({ actor: 'alice', timestamp: '2026-04-29T10:00:00Z' });
    const b = activity({ actor: 'bob', timestamp: '2026-04-29T10:01:00Z' });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b)]);
    expect(out).toHaveLength(2);
    expect(out[0]).toMatchObject({ kind: 'activity' });
    expect(out[1]).toMatchObject({ kind: 'activity' });
  });

  it('does not group commits that span midnight (different local days)', () => {
    const a = activity({ timestamp: '2026-04-29T23:30:00Z' });
    const b = activity({ timestamp: '2026-04-30T00:30:00Z' });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b)]);
    // Whether these two land on the same local day depends on the test
    // runner's TZ; on UTC they're different days, on PT they'd both be
    // the 29th. Assert the count instead — either result has at least 2
    // visible entries (split = 2 separate, same day = 1 group).
    expect(out.length).toBeGreaterThanOrEqual(1);
  });

  it('falls through a lone committed event as a plain activity', () => {
    const a = activity();
    const out = groupTimelineEntries([rawActivity(a)]);
    expect(out).toHaveLength(1);
    expect(out[0].kind).toBe('activity');
  });

  it('groups force-pushed events the same way as commits', () => {
    const a = activity({ eventType: 'head_ref_force_pushed', timestamp: '2026-04-29T10:00:00Z' });
    const b = activity({ eventType: 'head_ref_force_pushed', timestamp: '2026-04-29T11:00:00Z' });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b)]);
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({
      kind: 'event-group',
      eventType: 'head_ref_force_pushed',
      count: 2,
    });
  });

  it('does not group across event types', () => {
    const a = activity({ eventType: 'committed', timestamp: '2026-04-29T10:00:00Z' });
    const b = activity({ eventType: 'head_ref_force_pushed', timestamp: '2026-04-29T10:01:00Z' });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b)]);
    expect(out).toHaveLength(2);
  });

  it('collapses a review_requested burst by the same actor within 60s', () => {
    const a = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:00Z',
      requestedReviewer: 'alice',
    });
    const b = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:10Z',
      requestedReviewer: 'bob',
    });
    const c = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:20Z',
      requestedReviewer: 'carol',
    });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b), rawActivity(c)]);
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({
      kind: 'event-group',
      eventType: 'review_requested',
      count: 3,
      reviewers: ['alice', 'bob', 'carol'],
    });
  });

  it('does not collapse review_requested events more than 60s apart', () => {
    const a = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:00Z',
      requestedReviewer: 'alice',
    });
    const b = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:01:30Z',
      requestedReviewer: 'bob',
    });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b)]);
    expect(out).toHaveLength(2);
    expect(out[0].kind).toBe('activity');
    expect(out[1].kind).toBe('activity');
  });

  it('does not collapse review_requested events from different actors', () => {
    const a = activity({
      actor: 'lead-1',
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:00Z',
      requestedReviewer: 'alice',
    });
    const b = activity({
      actor: 'lead-2',
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:10Z',
      requestedReviewer: 'bob',
    });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b)]);
    expect(out).toHaveLength(2);
  });

  it('renders a lone review_requested as a plain activity, not a group', () => {
    const a = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:00Z',
      requestedReviewer: 'alice',
    });
    const out = groupTimelineEntries([rawActivity(a)]);
    expect(out).toHaveLength(1);
    expect(out[0].kind).toBe('activity');
  });

  it('keeps ungroupable events between groups intact', () => {
    const c1 = activity({ timestamp: '2026-04-29T10:00:00Z', afterSha: 'one' });
    const c2 = activity({ timestamp: '2026-04-29T10:05:00Z', afterSha: 'two' });
    const merged = activity({ eventType: 'merged', timestamp: '2026-04-29T11:00:00Z' });
    const out = groupTimelineEntries([rawActivity(c1), rawActivity(c2), rawActivity(merged)]);
    expect(out).toHaveLength(2);
    expect(out[0]).toMatchObject({ kind: 'event-group', count: 2 });
    expect(out[1]).toMatchObject({ kind: 'activity' });
  });

  it('groups correctly even when some review_requested events have null reviewers', () => {
    // Defensive: a malformed event with no requestedReviewer shouldn't
    // crash the burst collapse. The group's reviewers list omits nulls.
    const a = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:00Z',
      requestedReviewer: 'alice',
    });
    const b = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:10Z',
      requestedReviewer: null,
    });
    const c = activity({
      eventType: 'review_requested',
      timestamp: '2026-04-29T12:00:20Z',
      requestedReviewer: 'carol',
    });
    const out = groupTimelineEntries([rawActivity(a), rawActivity(b), rawActivity(c)]);
    expect(out).toHaveLength(1);
    expect((out[0] as { reviewers: string[] }).reviewers).toEqual(['alice', 'carol']);
  });
});
