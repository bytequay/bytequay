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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PullRequestDto } from '../types';
import { HarnessHeader, HarnessIdle } from './WorkspaceHarnessChrome';
import { HarnessDashboard, type HarnessActions } from './WorkspaceHarnessDashboard';
import { isPollableHarnessStatus } from './WorkspaceHarnessPage';
import {
  workspaceApi,
  type CiHarnessCycleDetailDto,
  type CiHarnessFailureDto,
  type CiHarnessWatchSnapshotDto,
} from './workspaceApi';

afterEach(cleanup);

function snapshot(status: CiHarnessWatchSnapshotDto['status']): CiHarnessWatchSnapshotDto {
  return {
    watchId: 'watch-1', workspaceId: 'w1', status, owner: 'acme', repo: 'widget',
    prNumber: 1234, localPrId: 'local-pr-1', branch: 'bump-upstream-9.9', title: 'Bump Trino',
    headSha: 'abc123', bootstrapStatus: 'complete',
    bootstrapProfile: {
      forge: 'github-actions', ecosystems: ['maven'], workflowFiles: ['.github/workflows/ci.yml'],
      verifySteps: { style: ['./mvnw spotless:check'] }, aggregatorJobs: ['build-success'],
      infraJobs: ['cloud-tests'], modules: { core: 'core/trino-main' },
      runtimeMetadata: { java: '25' }, verificationEnvironment: { CI: 'true' },
      warnings: ['cloud-tests require secrets'],
    },
    budget: { limitMilliUsd: 5000, spentMilliUsd: 1200, cycleMilliUsd: 300, remainingMilliUsd: 3800 },
    activeCycle: null, cycles: [], milestones: [],
    failures: status === 'needs_attention' ? [escalation()] : [],
    stats: {
      failuresByState: status === 'needs_attention' ? { escalated: 1 } : {},
      cycleCostMilliUsd: 300, watchCostMilliUsd: 1200,
    },
    backupRef: status === 'handoff' ? 'refs/bytequay/backups/watch-1' : null,
    netNeutralProof: status === 'handoff' ? {
      beforeHead: 'before', afterHead: 'after', beforeTree: 'tree-a', afterTree: 'tree-a',
      emptyTreeDiff: true, rangeEquivalent: true, remoteUndiverged: true, detail: 'range-diff clean',
    } : null,
    handoff: status === 'handoff'
      ? { reason: 'cycle complete', failureId: null, command: 'git push origin bump-upstream-9.9', detail: null }
      : null,
    handoffCommand: status === 'handoff' ? 'git push origin bump-upstream-9.9' : null,
    runStatusTail: '',
  };
}

function escalation(): CiHarnessFailureDto {
  return {
    id: 'failure-1', cycleId: 'cycle-1', status: 'escalated', bucket: 'unknown',
    jobName: 'unit tests', module: 'core', testClass: null, testMethod: null,
    signature: 'cannot find symbol',
    logExcerpt: 'Compiler output from the failed job', targetSubject: 'Update SPI', ruleId: null,
    diagnosis: {
      rootCause: 'A fork-only consumer was missed', culpritCommit: null,
      targetSubject: 'Update SPI', edits: [{ path: 'core/Spi.java', find: 'old', replace: 'new' }],
      signaturePattern: 'cannot find symbol', bucket: 'build', binding: 'agent',
      verifyHint: ['build'], confidence: 0.41, needsHuman: true, rationale: 'ambiguous owner',
    },
    fix: null,
    verification: {
      passed: false, reproducible: true, reason: 'verify failed twice',
      commands: [{ command: './mvnw -pl core test', exitCode: 1, timedOut: false, outputTail: 'BUILD FAILURE' }],
    },
    updatedAtMs: 1,
  };
}

function actions(overrides: Partial<HarnessActions> = {}): HarnessActions {
  return {
    busy: false,
    onResolve: () => {},
    onRetry: () => {},
    ...overrides,
  };
}

function cycleDetail(): CiHarnessCycleDetailDto {
  return {
    cycle: {
      id: 'cycle-1', ordinal: 3, triggerKind: 'poll', status: 'handoff', phase: 'done',
      headSha: 'abc1234def', costMilliUsd: 620, backupRef: 'refs/bytequay/backup/bump-upstream-9.9',
      netNeutralProof: {
        beforeHead: 'before', afterHead: 'after', beforeTree: 'tree-a', afterTree: 'tree-a',
        emptyTreeDiff: true, rangeEquivalent: true, remoteUndiverged: true, detail: null,
      },
      runStatusTail: null, startedAtMs: 1, finishedAtMs: 2,
      phaseStates: [{ phase: 'probe', status: 'done' }, { phase: 'rebase', status: 'done' }],
    },
    milestones: [{
      id: 9, cycleId: 'cycle-1', phase: 'commit', kind: 'commit',
      message: 'Committed a path-scoped fixup', detailJson: null, createdAtMs: 1,
    }],
    failures: [escalation()],
  };
}

function dashboard(overrides: {
  snapshot?: CiHarnessWatchSnapshotDto;
  actions?: HarnessActions;
  cycleDetail?: CiHarnessCycleDetailDto | null;
  onCloseCycle?: () => void;
} = {}) {
  return (
    <HarnessDashboard
      snapshot={overrides.snapshot ?? snapshot('watching')}
      actions={overrides.actions ?? actions()}
      cycleDetail={overrides.cycleDetail ?? null}
      onCloseCycle={overrides.onCloseCycle ?? (() => {})} />
  );
}

describe('HarnessDashboard', () => {
  it('renders pending bootstrap before a profile is available', () => {
    render(dashboard({
      snapshot: { ...snapshot('bootstrap'), bootstrapStatus: 'pending', bootstrapProfile: null },
    }));

    expect(screen.getByText('Bootstrapping project knowledge')).toBeTruthy();
    expect(screen.queryByText('Bootstrap trust profile')).toBeNull();
  });

  it('renders completed bootstrap evidence before the first cycle', () => {
    render(dashboard());

    expect(screen.getByText('Bootstrap trust profile')).toBeTruthy();
    expect(screen.getByText(/\.\/mvnw spotless:check/)).toBeTruthy();
    expect(screen.getByText('No failures recorded')).toBeTruthy();
  });

  it('queues every escalation with a resolve and a resolve-and-run action', () => {
    const onResolve = vi.fn();
    const onRetry = vi.fn();
    render(dashboard({
      snapshot: snapshot('needs_attention'),
      actions: actions({ onResolve, onRetry }),
    }));

    expect(screen.getByRole('heading', { name: 'Needs you' })).toBeTruthy();
    expect(screen.getAllByText('cannot find symbol').length).toBeGreaterThan(0);
    expect(screen.getByText(/Verification failed: verify failed twice/)).toBeTruthy();
    expect(screen.getByText('1 escalated')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Your decision'), {
      target: { value: 'the SPI change is intentional' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }));
    expect(onResolve).toHaveBeenCalledWith('failure-1', 'the SPI change is intentional');

    fireEvent.click(screen.getByRole('button', { name: 'Resolve & run cycle' }));
    expect(onRetry).toHaveBeenCalledWith('failure-1', 'the SPI change is intentional');
  });

  it('shows a copy-only handoff proof', () => {
    render(dashboard({ snapshot: { ...snapshot('handoff'), runStatusTail: null } }));

    expect(screen.queryByText('Bootstrap trust profile')).toBeNull();
    expect(screen.getByText('History rewrite proof')).toBeTruthy();
    expect(screen.getByText('refs/bytequay/backups/watch-1')).toBeTruthy();
    expect(screen.getByText(/Tree diff empty/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^push$/i })).toBeNull();
    expect(screen.getAllByText('git push origin bump-upstream-9.9')).toHaveLength(2);
  });

  it('pins an escalated failure even while the containing cycle still runs', () => {
    render(dashboard({ snapshot: { ...snapshot('needs_attention'), status: 'running' as const } }));

    expect(screen.getByText('Needs you', { selector: 'strong' })).toBeTruthy();
    expect(screen.getAllByText('cannot find symbol').length).toBeGreaterThan(0);
  });

  it('renders the dedicated green completion state', () => {
    render(dashboard({ snapshot: snapshot('green') }));
    expect(screen.getByRole('heading', { name: 'CI is green' })).toBeTruthy();
    expect(screen.getByText(/Monitoring stays on/)).toBeTruthy();
  });

  it('replaces the watch view with one cycle and returns from it', () => {
    const onCloseCycle = vi.fn();
    render(dashboard({ cycleDetail: cycleDetail(), onCloseCycle }));

    expect(screen.getByText('Cycle 3')).toBeTruthy();
    expect(screen.getByText(/handoff · poll · abc1234 · \$0\.62/)).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Failures in this cycle' })).toBeTruthy();
    expect(screen.getByText('Committed a path-scoped fixup')).toBeTruthy();
    expect(screen.getByText('refs/bytequay/backup/bump-upstream-9.9')).toBeTruthy();
    expect(screen.queryByText('Bootstrap trust profile')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '← Back to watch' }));
    expect(onCloseCycle).toHaveBeenCalledOnce();
  });

  it('renders a question and its answer as conversation in the feed', () => {
    render(dashboard({
      snapshot: {
        ...snapshot('watching'),
        milestones: [
          { id: 1, cycleId: null, phase: 'classify', kind: 'question', message: 'why is core red?', detailJson: null, createdAtMs: 1 },
          { id: 2, cycleId: null, phase: 'classify', kind: 'answer', message: 'The SPI moved.', detailJson: null, createdAtMs: 2 },
        ],
      },
    }));

    expect(screen.getByText('why is core red?')).toBeTruthy();
    expect(screen.getByText('The SPI moved.')).toBeTruthy();
    expect(screen.getByText(/^You ·/)).toBeTruthy();
    expect(screen.getByText(/^Harness ·/)).toBeTruthy();
  });
});

describe('harness watch refresh contract', () => {
  it('keeps every live watch pollable until explicitly stopped', () => {
    expect(isPollableHarnessStatus('handoff')).toBe(true);
    expect(isPollableHarnessStatus('green')).toBe(true);
    expect(isPollableHarnessStatus('needs_attention')).toBe(true);
    expect(isPollableHarnessStatus('stopped')).toBe(false);
  });

  it('sends bounded user guidance in the run request body', async () => {
    const request = vi.fn(async () => snapshot('running'));
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    await workspaceApi.runHarnessWatch('w1', 'watch-1', '  Prefer the module-local fixture.  ');

    expect(request).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/ci-harness/watches/watch-1/run',
      method: 'POST',
      body: { steeringText: 'Prefer the module-local fixture.' },
    });
  });

  it('posts a trimmed question and an escalation note to their own routes', async () => {
    const request = vi.fn(async () => snapshot('watching'));
    (window as unknown as { bridge: unknown }).bridge = { workspaceApi: request };

    await workspaceApi.askHarnessWatch('w1', 'watch-1', '  what did you change?  ');
    expect(request).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/ci-harness/watches/watch-1/ask',
      method: 'POST',
      body: { question: 'what did you change?' },
    });

    await workspaceApi.retryHarnessFailure('w1', 'watch-1', 'failure-1', '  intentional  ');
    expect(request).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/ci-harness/watches/watch-1/failures/failure-1/retry',
      method: 'POST',
      body: { note: 'intentional' },
    });

    await workspaceApi.resolveHarnessFailure('w1', 'watch-1', 'failure-1', '   ');
    expect(request).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/ci-harness/watches/watch-1/failures/failure-1/resolve',
      method: 'POST',
      body: {},
    });
  });
});

describe('HarnessIdle', () => {
  it('offers only open pull requests with failing CI and validates the backend budget range', () => {
    const failing = pull({ number: 1, title: 'Red build' });
    const passing = pull({ number: 2, title: 'Green build', ciStatus: 'PASSING' });
    const closed = pull({ number: 3, title: 'Closed red build', state: 'closed' });
    const onCreate = vi.fn();
    const { rerender } = render(<HarnessIdle pulls={[failing, passing, closed]} selectedPr={1} budget=""
      onSelectPr={() => {}} onBudget={() => {}} busy={false} onCreate={onCreate} />);

    expect(screen.getByRole('option', { name: /Red build/ })).toBeTruthy();
    expect(screen.queryByRole('option', { name: /Green build/ })).toBeNull();
    expect(screen.queryByRole('option', { name: /Closed red build/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Watch selected pull request' }));
    expect(onCreate).toHaveBeenCalledOnce();

    rerender(<HarnessIdle pulls={[failing]} selectedPr={1} budget="0.05"
      onSelectPr={() => {}} onBudget={() => {}} busy={false} onCreate={onCreate} />);
    expect(screen.getByText('Enter $0.10–$100.00.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Watch selected pull request' }).hasAttribute('disabled')).toBe(true);

    rerender(<HarnessIdle pulls={[passing]} selectedPr={null} budget=""
      onSelectPr={() => {}} onBudget={() => {}} busy={false} onCreate={onCreate} />);
    expect(screen.getByText('No open pull requests with failing CI.')).toBeTruthy();
  });
});

describe('HarnessHeader', () => {
  it('offers unwatch for green watches and no invalid run action for stopped watches', () => {
    const onRun = vi.fn();
    const onStop = vi.fn();
    const { rerender } = render(<HarnessHeader snapshot={snapshot('green')} showPr={false} busy={false}
      onRun={onRun} onStop={onStop} />);

    expect(screen.queryByRole('button', { name: 'Run cycle' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Unwatch' }));
    expect(onStop).toHaveBeenCalledOnce();

    rerender(<HarnessHeader snapshot={snapshot('stopped')} showPr={false} busy={false}
      onRun={onRun} onStop={onStop} />);
    expect(screen.queryByRole('button', { name: 'Run cycle' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Unwatch' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Stop safely' })).toBeNull();
  });
});

function pull(overrides: Partial<PullRequestDto>): PullRequestDto {
  return {
    id: 1, repo: 'acme/widget', number: 1, title: 'Failing pull request', author: 'octocat',
    htmlUrl: 'https://github.com/acme/widget/pull/1', createdAt: null,
    updatedAt: '2026-07-24T00:00:00Z', origin: 'AUTHORED', labels: [], labelColors: null,
    draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
    ciStatus: 'FAILING', additions: 1, deletions: 0, commentCount: 0, attentionReason: 'CI_FAILING',
    state: 'open', closedAt: null, mergedAt: null, mergeable: true, mergeableState: 'dirty',
    headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
    ...overrides,
  };
}
