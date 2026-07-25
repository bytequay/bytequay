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
import type { LocalPR, LocalPRBundle, LocalPRCheck, LocalPRComment, LocalPRTimelineEvent } from '../types/localPr';
import { buildChecks, buildTimeline, isBotActor, statePill } from './detailModel';

function check(overrides: Partial<LocalPRCheck>): LocalPRCheck {
  return {
    id: 'c1', localPrId: 'pr1', kind: 'remote', name: 'test (core)', status: 'passed',
    durationMs: null, startedAt: 0, finishedAt: null, runId: null,
    ...overrides,
  };
}

function event(overrides: Partial<LocalPRTimelineEvent>): LocalPRTimelineEvent {
  return {
    id: 'e1', localPrId: 'pr1', eventType: 'commit', actor: '@octocat',
    isLocalOnly: false, strippedOnPushAt: null, createdAt: 1000, payload: null,
    ...overrides,
  };
}

function comment(overrides: Partial<LocalPRComment>): LocalPRComment {
  return {
    id: 'm1', localPrId: 'pr1', origin: 'remote', scope: 'pr', filePath: null,
    lineNumber: null, side: 'RIGHT', startLine: null, startSide: null,
    author: '@octocat', body: 'hi', createdAt: 2000, resolvedAt: null,
    dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
    ...overrides,
  };
}

function bundle(overrides: Partial<Omit<LocalPRBundle, 'pr'>> & { pr?: Partial<LocalPR> }): LocalPRBundle {
  const pr: LocalPR = {
    id: 'pr1', taskId: null, branchName: 'fix-it', baseBranch: 'master',
    title: 'A change', description: '', status: 'remote-open', createdAt: 100,
    pushedAt: null, remotePrNumber: 7, remotePrUrl: null, mergedAt: null,
    closedAt: null, origin: 'external', repo: 'trinodb/trino', author: '@octocat',
    syncedAt: null, syncedAdditions: null, syncedDeletions: null,
    syncedMergeable: null, syncedMergeableState: null, syncedMergeQueueEnabled: false,
    syncedMergeQueueState: null, branchDeletedAt: null,
    ...(overrides.pr ?? {}),
  };
  return { commits: [], timeline: [], checks: [], comments: [], ...overrides, pr };
}

describe('statePill', () => {
  it('paints local phases blue, remote-open green, merged purple', () => {
    expect(statePill('local-drafted')).toEqual({ label: 'Local Draft', bg: '#0969da', icon: 'open' });
    expect(statePill('local-open')).toEqual({ label: 'Local Open', bg: '#0969da', icon: 'open' });
    expect(statePill('remote-drafted').label).toBe('Draft');
    expect(statePill('remote-open')).toEqual({ label: 'Open', bg: '#1f883d', icon: 'open' });
    expect(statePill('merged').icon).toBe('merged');
    expect(statePill('closed').label).toBe('Closed');
    expect(statePill(null).label).toBe('Open');
  });
});

describe('buildChecks', () => {
  it('returns null for no checks', () => {
    expect(buildChecks([])).toBeNull();
  });

  it('groups mixed statuses with the failing title and counts sentence', () => {
    const model = buildChecks([
      check({ id: '1', status: 'failed', name: 'a' }),
      check({ id: '2', status: 'running', name: 'b' }),
      check({ id: '3', status: 'passed', name: 'c' }),
      check({ id: '4', status: 'neutral', name: 'd' }),
    ]);
    expect(model?.state).toBe('fail');
    expect(model?.title).toBe('Some checks were not successful');
    expect(model?.sub).toBe('1 failing, 1 in progress, 2 completed');
    expect(model?.groups.map(g => g.label)).toEqual(['Failing (1)', 'In progress (1)', 'Successful (1)', 'Neutral (1)']);
    expect(model?.groups.map(g => g.defaultOpen)).toEqual([true, true, false, false]);
    expect(model?.groups[3].rows[0].note).toBe('skipped');
  });

  it('reports all-green and in-progress states', () => {
    const green = buildChecks([check({ id: '1' }), check({ id: '2', name: 'x' })]);
    expect(green?.title).toBe('All checks have passed');
    expect(green?.sub).toBe('2 successful checks');
    const prog = buildChecks([check({ id: '1', status: 'pending' }), check({ id: '2', name: 'x' })]);
    expect(prog?.title).toBe("Some checks haven't completed yet");
    expect(prog?.sub).toBe('1 in progress, 1 successful');
  });
});

describe('buildTimeline', () => {
  it('maps aggregate CI and de-duplicates short/full forms of one commit sha', () => {
    const items = buildTimeline(bundle({
      commits: [{
        id: 'commit', localPrId: 'pr1', sha: 'abcdef0123456', message: 'Fix it',
        additions: 1, deletions: 0, authoredAt: 1000, pushedAt: null,
      }],
      timeline: [
        event({ id: 'c-short', payload: { sha: 'abcdef0', message: 'Fix it\n\nLong commit body' } }),
        event({ id: 'c-full', createdAt: 1050, payload: { sha: 'abcdef0123456', message: 'Fix it' } }),
        event({ id: 'ci', eventType: 'ci', createdAt: 1100,
          payload: { status: 'passed', previousStatus: 'failed', headSha: 'abcdef0123456', checkCount: 12 } }),
        event({ id: 'st', eventType: 'status', createdAt: 1200, payload: { from: 'a', to: 'b' } }),
      ],
    }));
    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ kind: 'commit', sha: 'abcdef0123456', message: 'Fix it' });
    expect(items[1]).toMatchObject({
      kind: 'ci', status: 'passed', previousStatus: 'failed', headSha: 'abcdef0123456', checkCount: 12,
      trigger: null,
    });
  });

  it('distinguishes local harness milestones from aggregate CI transitions', () => {
    const items = buildTimeline(bundle({
      timeline: [
        event({ id: 'remote-ci', eventType: 'ci', actor: '@github', payload: { status: 'passed' } }),
        event({
          id: 'harness', eventType: 'ci', actor: 'ci-harness', isLocalOnly: true,
          payload: { message: 'Committed a verified fixup', phase: 'commit', status: 'verified', sha: 'abcdef0123' },
        }),
      ],
    }));

    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ id: 'remote-ci', kind: 'ci', status: 'passed' });
    expect(items[1]).toEqual(
      expect.objectContaining({
        id: 'harness', kind: 'ci-harness', message: 'Committed a verified fixup',
        phase: 'commit', status: 'verified', sha: 'abcdef0',
      }),
    );
  });

  it('keeps review lifecycle rows and maps concluded verdicts', () => {
    const items = buildTimeline(bundle({
      timeline: [
        event({ id: 'r1', eventType: 'review', actor: 'brain', payload: { reviewEvent: 'started', scope: 'dev', iteration: 2 } }),
        event({ id: 'r1-fix', eventType: 'review', actor: 'agent', createdAt: 1250,
          payload: { reviewEvent: 'addressing-started', scope: 'dev', iteration: 2 } }),
        event({ id: 'r2', eventType: 'review', actor: '@rev', createdAt: 1500, payload: { verdict: 'APPROVED' } }),
        event({ id: 'r3', eventType: 'review', actor: '@rev', createdAt: 1600, payload: { verdict: 'request-changes', body: 'nope' } }),
        event({ id: 'r4', eventType: 'review', actor: '@rev', createdAt: 1700, payload: { verdict: 'COMMENTED' } }),
        event({ id: 'r5', eventType: 'review', actor: '@rev', createdAt: 1800, payload: { verdict: 'DISMISSED' } }),
      ],
    }));
    expect(items.map(i => i.id)).toEqual(['r1', 'r1-fix', 'r2', 'r3', 'r4', 'r5']);
    expect(items[0]).toMatchObject({ kind: 'review-activity', activity: 'started', author: 'brain', iteration: 2 });
    expect(items[1]).toMatchObject({ kind: 'review-activity', activity: 'addressing-started', author: 'dev', iteration: 2 });
    expect(items[2]).toMatchObject({ kind: 'review', verdict: 'approved', author: 'rev' });
    expect(items[3]).toMatchObject({ kind: 'review', verdict: 'changes', body: 'nope' });
    expect(items[4]).toMatchObject({ kind: 'review', verdict: 'commented' });
    expect(items[5]).toMatchObject({ kind: 'review', verdict: 'dismissed' });
  });

  it('retains Brain review scope, round identity, finality, and failure reason', () => {
    const items = buildTimeline(bundle({
      timeline: [
        event({ id: 'plan', eventType: 'review', actor: 'brain',
          payload: { scope: 'plan', verdict: 'approved', iteration: 1 } }),
        event({ id: 'start', eventType: 'review', actor: 'brain', createdAt: 1100,
          payload: { reviewEvent: 'started', scope: 'dev', iteration: 2, roundId: 'round-abcdef' } }),
        event({ id: 'failed', eventType: 'review', actor: 'brain', createdAt: 1200,
          payload: { reviewEvent: 'failed', scope: 'dev', iteration: 2,
            roundId: 'round-abcdef', reason: 'brain_review_turn_failed' } }),
        event({ id: 'finished', eventType: 'review', actor: 'brain', isLocalOnly: true, createdAt: 1300,
          payload: { reviewEvent: 'finished', scope: 'dev', iteration: 3,
            roundId: 'round-next', verdict: 'changes_requested', body: 'Remove dead CSS.' } }),
      ],
    }));

    expect(items[0]).toMatchObject({ kind: 'review', scope: 'plan', verdict: 'approved', iteration: 1 });
    expect(items[1]).toMatchObject({ kind: 'review-activity', activity: 'started', scope: 'dev',
      iteration: 2, roundId: 'round-abcdef' });
    expect(items[2]).toMatchObject({ kind: 'review-activity', activity: 'failed', scope: 'dev',
      reason: 'brain_review_turn_failed' });
    expect(items[3]).toMatchObject({ kind: 'review', scope: 'dev', verdict: 'changes',
      iteration: 3, roundId: 'round-next', body: null });
  });

  it('renders Brain concerns as separate comment cards without its raw turn narration', () => {
    const items = buildTimeline(bundle({
      pr: { origin: 'task', taskId: 'task-1', status: 'local-open' },
      timeline: [
        event({ id: 'started', eventType: 'review', actor: 'brain', isLocalOnly: true,
          createdAt: 1_000, payload: { reviewEvent: 'started', scope: 'dev', iteration: 1,
            roundId: 'round-1' } }),
        event({ id: 'finished', eventType: 'review', actor: 'brain', isLocalOnly: true,
          createdAt: 3_000, payload: { reviewEvent: 'finished', scope: 'dev', iteration: 1,
            roundId: 'round-1', verdict: 'changes_requested', findingCount: 1,
            commentIds: ['brain-finding'],
            body: "I'll review the diff.\n\nLet me inspect the tests.\n\nThe null case needs a guard." } }),
      ],
      comments: [comment({
        id: 'brain-finding', origin: 'local', author: 'brain', scope: 'file-line',
        filePath: 'src/Foo.ts', lineNumber: 41, createdAt: 2_000,
        body: 'The value can be null here, so dereferencing it can throw.',
      })],
    }));

    expect(items).toHaveLength(3);
    expect(items[0]).toMatchObject({ kind: 'review-activity', id: 'started' });
    expect(items[1]).toMatchObject({ kind: 'local-thread', id: 'local-thread-brain-finding' });
    expect(items[2]).toMatchObject({ kind: 'review', id: 'finished', verdict: 'changes', body: null });
    const concern = items[1];
    if (concern.kind !== 'local-thread') throw new Error('expected local thread');
    expect(concern.comments[0]).toMatchObject({
      filePath: 'src/Foo.ts', lineNumber: 41,
      body: 'The value can be null here, so dereferencing it can throw.',
    });
  });

  it('renders an approved Brain pass without its progress transcript', () => {
    const items = buildTimeline(bundle({
      timeline: [event({
        id: 'approved', eventType: 'review', actor: 'brain', isLocalOnly: true,
        payload: { reviewEvent: 'finished', scope: 'dev', verdict: 'approved', findingCount: 0,
          commentIds: [], body: "I'll review the diff.\n\nLet me check the tests.\n\nEverything looks good." },
      })],
    }));

    expect(items).toEqual([
      expect.objectContaining({
        kind: 'review', id: 'approved', verdict: 'approved', body: null,
      }),
    ]);
  });

  it('omits a legacy Brain review card containing only progress narration', () => {
    const items = buildTimeline(bundle({
      timeline: [event({
        id: 'narration-only', eventType: 'review', actor: 'brain', isLocalOnly: true,
        payload: { reviewEvent: 'finished', scope: 'dev',
          body: "I'll inspect the diff now. Let me gather the evidence." },
      })],
    }));

    expect(items).toEqual([]);
  });

  it('shows a locally published GitHub review once using the canonical remote event', () => {
    const items = buildTimeline(bundle({
      timeline: [
        event({
          id: 'local', eventType: 'review', actor: 'you', isLocalOnly: true,
          createdAt: 1_000, payload: { reviewEvent: 'submitted', verdict: 'COMMENT' },
        }),
        event({
          id: 'remote', eventType: 'review', actor: '@me', isLocalOnly: false,
          createdAt: 1_001, remoteEventId: 42, payload: { verdict: 'COMMENTED', body: null },
        }),
      ],
    }));

    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ id: 'remote', kind: 'review', author: 'me', verdict: 'commented' });
  });

  it('presents the persisted development actor as a role, not a provider or bot', () => {
    const items = buildTimeline(bundle({
      timeline: [event({
        id: 'dev-review', eventType: 'review', actor: 'claude-code',
        payload: { verdict: 'REQUEST_CHANGES', body: 'Please address this.' },
      })],
    }));

    expect(items).toEqual([
      expect.objectContaining({ id: 'dev-review', kind: 'review', author: 'dev', bot: false }),
    ]);
  });

  it('groups remote PR comments and preserves local file-line threads with submission state', () => {
    const items = buildTimeline(bundle({
      pr: { origin: 'task', taskId: 'task-1', status: 'local-open' },
      timeline: [event({
        id: 'root-event', eventType: 'comment', remoteEventId: 4357983764,
        payload: { commentId: 'root' },
      }), event({
        id: 'submitted', eventType: 'review', actor: 'you', createdAt: 4000,
        payload: { reviewEvent: 'submitted', verdict: 'COMMENT', commentIds: ['line'] },
      })],
      comments: [
        comment({ id: 'root' }),
        comment({ id: 'reply', parentCommentId: 'root', createdAt: 3000, author: 'github-actions[bot]' }),
        comment({ id: 'line', origin: 'local', author: 'you', scope: 'file-line', filePath: 'a.ts', lineNumber: 3, createdAt: 2500 }),
        comment({ id: 'line-reply', origin: 'local', author: 'claude-code', scope: 'file-line',
          filePath: 'a.ts', lineNumber: 3, parentCommentId: 'line', createdAt: 3500 }),
      ],
    }));
    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({ kind: 'comment', id: 'root', remoteId: 4357983764 });
    expect(items[1]).toMatchObject({ kind: 'local-thread', submitted: true });
    const root = items[0];
    if (root.kind !== 'comment') throw new Error('expected comment');
    expect(root.replies.map(r => r.id)).toEqual(['reply']);
    expect(root.replies[0].bot).toBe(true);
    const local = items[1];
    if (local.kind !== 'local-thread') throw new Error('expected local thread');
    expect(local.comments.map(row => row.id)).toEqual(['line', 'line-reply']);
  });

  it('does not duplicate published external drafts or mislabel standalone review activity', () => {
    const items = buildTimeline(bundle({
      timeline: [event({
        id: 'standalone-start', eventType: 'review', actor: 'you',
        payload: { reviewEvent: 'started' },
      })],
      comments: [comment({
        id: 'published', origin: 'local', author: 'you', scope: 'file-line',
        filePath: 'src/A.java', lineNumber: 4, publishedAt: 5000,
      }), comment({
        id: 'orphaned-local-reply', origin: 'local', author: 'you', scope: 'file-line',
        filePath: 'src/A.java', lineNumber: 4, parentCommentId: 'published',
      })],
    }));

    expect(items).toEqual([]);
  });

  it('requires a fresh submission after a local review thread is reopened or updated', () => {
    const root = comment({
      id: 'local-root', origin: 'local', author: 'you', scope: 'file-line',
      filePath: 'src/A.java', lineNumber: 4,
    });
    const reviewEvent = (id: string, createdAt: number, payload: Record<string, unknown>) => event({
      id, eventType: 'review', actor: 'you', isLocalOnly: true, createdAt, payload,
    });
    const reopened = buildTimeline(bundle({
      pr: { origin: 'task', taskId: 'task-1', status: 'local-open' },
      comments: [root],
      timeline: [
        reviewEvent('submitted-1', 1000, { reviewEvent: 'submitted', commentIds: ['local-root'] }),
        reviewEvent('reopened', 2000, { reviewEvent: 'reopened', commentId: 'local-root' }),
      ],
    }));
    expect(reopened).toEqual([
      expect.objectContaining({ kind: 'local-thread', submitted: false }),
    ]);

    const resubmitted = buildTimeline(bundle({
      pr: { origin: 'task', taskId: 'task-1', status: 'local-open' },
      comments: [root],
      timeline: [
        reviewEvent('submitted-1', 1000, { reviewEvent: 'submitted', commentIds: ['local-root'] }),
        reviewEvent('updated', 2000, { reviewEvent: 'updated', commentId: 'local-root' }),
        reviewEvent('submitted-2', 3000, { reviewEvent: 'submitted', commentIds: ['local-root'] }),
      ],
    }));
    expect(resubmitted).toEqual([
      expect.objectContaining({ kind: 'local-thread', submitted: true }),
    ]);
  });

  it('appends a merged row from the PR when status is merged', () => {
    const items = buildTimeline(bundle({
      pr: { status: 'merged', mergedAt: 9000 },
      commits: [{ id: 'k', localPrId: 'pr1', sha: '1234567890abc', message: 'm', additions: 1, deletions: 0, authoredAt: 1, pushedAt: null }],
    }));
    expect(items[items.length - 1]).toMatchObject({ kind: 'merged', sha: '1234567', base: 'master', author: 'octocat', at: 9000 });
  });
});

describe('isBotActor', () => {
  it('flags GitHub bot logins without treating the dev role as a bot', () => {
    expect(isBotActor('github-actions[bot]')).toBe(true);
    expect(isBotActor('claude-code')).toBe(false);
    expect(isBotActor('@octocat')).toBe(false);
  });
});
