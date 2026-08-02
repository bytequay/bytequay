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
import { buildChecks, buildTimeline, isBotActor, isCiErrorLine, statePill } from './detailModel';

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

  it('exposes a check-run id only for remote checks whose run id is a GitHub id', () => {
    const model = buildChecks([
      check({ id: '1', status: 'failed', name: 'remote-numeric', runId: '90465481459' }),
      check({ id: '2', status: 'failed', name: 'remote-no-run-id', runId: null }),
      check({ id: '3', status: 'failed', name: 'remote-opaque', runId: 'run-abc' }),
      check({ id: '4', status: 'failed', name: 'local-run', kind: 'local', runId: '90465481459' }),
    ]);
    const ids = new Map(model?.groups[0].rows.map(r => [r.name, r.checkRunId]));
    expect(ids.get('remote-numeric')).toBe(90465481459);
    expect(ids.get('remote-no-run-id')).toBeNull();
    expect(ids.get('remote-opaque')).toBeNull();
    expect(ids.get('local-run')).toBeNull();
  });
});

describe('isCiErrorLine', () => {
  it('picks out the error and root-cause lines, leaving build chatter alone', () => {
    // Verbatim lines from a failing trinodb/trino delta-lake job.
    expect(isCiErrorLine('[ERROR] Error executing Maven.')).toBe(true);
    expect(isCiErrorLine('[ERROR] Caused by: Plugin foo:0.2.0 could not be resolved:')).toBe(true);
    expect(isCiErrorLine('##[error]Process completed with exit code 1.')).toBe(true);
    expect(isCiErrorLine('Caused by: java.lang.IllegalStateException')).toBe(true);
    expect(isCiErrorLine('Apache Maven 3.9.16')).toBe(false);
    expect(isCiErrorLine('\tFailed to read artifact descriptor for foo')).toBe(false);
    expect(isCiErrorLine('[INFO] Building Trino')).toBe(false);
    expect(isCiErrorLine('')).toBe(false);
  });
});

describe('buildTimeline', () => {
  it('keeps only final pull request publish results', () => {
    const items = buildTimeline(bundle({
      timeline: [
        event({ id: 'starting', eventType: 'pull-request-progress', payload: {
          phase: 'starting', branch: 'feature/publish', baseBranch: 'main',
        } }),
        event({ id: 'drafting', eventType: 'pull-request-progress', createdAt: 1100, payload: {
          phase: 'creating-draft', branch: 'feature/publish', baseBranch: 'main',
        } }),
        event({ id: 'failed', eventType: 'pull-request-progress', createdAt: 1200, payload: {
          phase: 'failed', branch: 'feature/publish', baseBranch: 'main',
          failedStep: 'ensure_pull_request', reason: 'GitHub returned 403 Forbidden',
        } }),
        event({ id: 'created', eventType: 'pull-request-created', createdAt: 1300, payload: {
          branch: 'feature/publish', baseBranch: 'main', number: 42,
          url: 'https://github.com/acme/widget/pull/42', additions: 5, deletions: 2,
        } }),
      ],
    }));

    expect(items).toHaveLength(2);
    expect(items[0]).toMatchObject({
      kind: 'pull-request', id: 'failed', pullRequest: {
        phase: 'failed', failedStep: 'ensure_pull_request', reason: 'GitHub returned 403 Forbidden',
      },
    });
    expect(items[1]).toMatchObject({
      kind: 'pull-request', id: 'created', pullRequest: {
        phase: 'created', number: 42, additions: 5, deletions: 2,
      },
    });
  });

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

  it('collapses duplicate aggregate CI events without hiding later transitions', () => {
    const items = buildTimeline(bundle({
      timeline: [
        event({ id: 'pass-1', eventType: 'ci', payload: { status: 'passed', headSha: 'abcdef0', checkCount: 23 } }),
        event({ id: 'pass-2', eventType: 'ci', createdAt: 1100, payload: { status: 'passed', headSha: 'abcdef0', checkCount: 23 } }),
        event({ id: 'fail', eventType: 'ci', createdAt: 1200, payload: { status: 'failed', headSha: 'abcdef0', checkCount: 23 } }),
        event({ id: 'pass-3', eventType: 'ci', createdAt: 1300, payload: { status: 'passed', headSha: 'abcdef0', checkCount: 23 } }),
      ],
    }));

    expect(items.map(item => item.id)).toEqual(['pass-1', 'fail', 'pass-3']);
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

  it('maps the durable V2 development lifecycle without exposing a raw Brain transcript', () => {
    const items = buildTimeline(bundle({
      pr: { origin: 'task', taskId: 'task-1', status: 'remote-open' },
      timeline: [
        event({ id: 'dev-commit', actor: 'v2-local-runtime', payload: {
          sha: 'dev-head', message: 'Implement the task',
        } }),
        event({ id: 'local-open', eventType: 'status', createdAt: 1_100,
          payload: { from: 'local-drafted', to: 'local-open' } }),
        event({ id: 'brain-start', eventType: 'review', actor: 'brain', isLocalOnly: true,
          createdAt: 1_200, payload: { reviewEvent: 'started', scope: 'dev', iteration: 1 } }),
        event({ id: 'brain-finish', eventType: 'review', actor: 'brain', isLocalOnly: true,
          createdAt: 1_300, payload: { reviewEvent: 'finished', scope: 'dev', iteration: 1,
            verdict: 'CHANGES_REQUESTED', structuredSummary: true,
            body: 'One issue remains.\n\nFindings:\n- Keep the fallback null-safe.' } }),
        event({ id: 'first-push', eventType: 'pull-request-created', createdAt: 1_400,
          payload: { phase: 'created', branch: 'feature/x', baseBranch: 'main', number: 17 } }),
        event({ id: 'remote-drafted', eventType: 'status', createdAt: 1_400,
          payload: { from: 'local-open', to: 'remote-drafted' } }),
        event({ id: 'repair-start', eventType: 'ci', actor: 'ci-fix', createdAt: 1_500,
          payload: { status: 'repair_started', classification: 'TASK_DETERMINISTIC', headSha: 'dev-head' } }),
        event({ id: 'repair-addressed', eventType: 'ci', actor: 'ci-fix', createdAt: 1_600,
          payload: { status: 'repair_addressed', headSha: 'repair-head' } }),
        event({ id: 'repair-commit', actor: 'ci-fix', createdAt: 1_600,
          payload: { sha: 'repair-head', message: 'Repair CI failure' } }),
        event({ id: 'repair-terminal', eventType: 'ci', actor: 'ci-fix', createdAt: 1_650,
          payload: { status: 'repair_succeeded', headSha: 'repair-head' } }),
        event({ id: 'ready', eventType: 'status', createdAt: 1_700,
          payload: { from: 'remote-drafted', to: 'remote-open', sha: 'repair-head' } }),
        event({ id: 'merge', eventType: 'status', createdAt: 1_800,
          payload: { from: 'remote-open', to: 'merged', sha: 'repair-head' } }),
        event({ id: 'cleanup-start', eventType: 'status', createdAt: 1_900,
          payload: { to: 'cleanup-started' } }),
        event({ id: 'cleanup-complete', eventType: 'status', createdAt: 2_000,
          payload: { to: 'cleanup-completed' } }),
      ],
    }));

    expect(items.map(item => item.id)).toEqual([
      'dev-commit', 'local-open', 'brain-start', 'brain-finish', 'first-push',
      'remote-drafted', 'repair-start', 'repair-addressed', 'repair-commit',
      'repair-terminal', 'ready', 'merge', 'cleanup-start', 'cleanup-complete',
    ]);
    expect(items[1]).toMatchObject({ kind: 'milestone', label: 'Development completed · local review opened' });
    expect(items[3]).toMatchObject({
      kind: 'review', verdict: 'changes',
      body: 'One issue remains.\n\nFindings:\n- Keep the fallback null-safe.',
    });
    expect(items[4]).toMatchObject({ kind: 'pull-request', pullRequest: { phase: 'created', number: 17 } });
    expect(items[5]).toMatchObject({ kind: 'milestone', label: 'First push completed · draft pull request opened' });
    expect(items[6]).toMatchObject({ kind: 'milestone', label: 'CI repair started · task deterministic' });
    expect(items[7]).toMatchObject({ kind: 'milestone', label: 'CI repair addressed the failing head' });
    expect(items[8]).toMatchObject({ kind: 'commit', message: 'Repair CI failure', sha: 'repair-head' });
    expect(items[9]).toMatchObject({ kind: 'milestone', label: 'CI repair completed', tone: 'success' });
    expect(items[10]).toMatchObject({ kind: 'milestone', label: 'Draft pull request marked ready for review' });
    expect(items[11]).toMatchObject({ kind: 'merged', sha: 'repair-head' });
    expect(items[12]).toMatchObject({ kind: 'milestone', label: 'Cleanup started' });
    expect(items[13]).toMatchObject({ kind: 'milestone', label: 'Cleanup completed' });
  });

  it('shows exhausted, stopped, and remotely closed terminal outcomes', () => {
    const items = buildTimeline(bundle({
      pr: { origin: 'task', taskId: 'task-1', status: 'closed' },
      timeline: [
        event({ id: 'exhausted', eventType: 'ci', actor: 'ci-fix', createdAt: 1_000,
          payload: { status: 'repair_exhausted', reason: 'budget exhausted' } }),
        event({ id: 'stopped', eventType: 'ci', actor: 'ci-fix', createdAt: 1_100,
          payload: { status: 'repair_stopped', reason: 'user canceled' } }),
        event({ id: 'closed', eventType: 'status', createdAt: 1_200,
          payload: { from: 'remote-open', to: 'closed', sha: 'final-head' } }),
      ],
    }));

    expect(items).toEqual([
      expect.objectContaining({
        id: 'exhausted', kind: 'milestone',
        label: 'CI repair budget exhausted', tone: 'attention',
      }),
      expect.objectContaining({
        id: 'stopped', kind: 'milestone',
        label: 'CI repair stopped · user canceled', tone: 'attention',
      }),
      expect.objectContaining({
        id: 'closed', kind: 'milestone',
        label: 'Pull request closed without merge', tone: 'attention',
        sha: 'final-head',
      }),
    ]);
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
