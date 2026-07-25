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
import { HarnessDashboard } from './WorkspaceHarnessDashboard';
import { isPollableHarnessStatus } from './WorkspaceHarnessPage';
import { workspaceApi, type CiHarnessRuleDto, type CiHarnessWatchSnapshotDto } from './workspaceApi';

afterEach(cleanup);

function snapshot(status: CiHarnessWatchSnapshotDto['status']): CiHarnessWatchSnapshotDto {
  return {
    watchId: 'watch-1', workspaceId: 'w1', status, owner: 'acme', repo: 'cork',
    prNumber: 4004, localPrId: 'local-pr-1', branch: 'trino-482', title: 'Bump Trino',
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
    failures: status === 'needs_attention' ? [{
      id: 'failure-1', cycleId: 'cycle-1', status: 'ESCALATED', bucket: 'unknown',
      jobName: 'unit tests', module: 'core', signature: 'cannot find symbol',
      logExcerpt: 'Compiler output from the failed job', targetSubject: 'Update SPI', ruleId: null,
    }] : [],
    stats: {
      failuresByState: status === 'needs_attention' ? { ESCALATED: 1 } : {},
      activeRules: 4, candidateRules: 1, cycleCostMilliUsd: 300, watchCostMilliUsd: 1200,
    },
    backupRef: status === 'handoff' ? 'refs/bytequay/backups/watch-1' : null,
    netNeutralProof: status === 'handoff' ? {
      beforeHead: 'before', afterHead: 'after', beforeTree: 'tree-a', afterTree: 'tree-a',
      emptyTreeDiff: true, rangeEquivalent: true, remoteUndiverged: true, detail: 'range-diff clean',
    } : null,
    handoff: status === 'handoff'
      ? { reason: 'cycle complete', failureId: null, command: 'git push origin trino-482', detail: null }
      : null,
    handoffCommand: status === 'handoff' ? 'git push origin trino-482' : null,
    runStatusTail: '',
  };
}

const candidate: CiHarnessRuleDto = {
  id: 'rule-1', matcherPattern: 'cannot find symbol', scope: 'core', bucket: 'build',
  binding: 'agent', status: 'candidate', origin: 'agent', priority: 50, hits: 2,
  approvedAtMs: null,
};

describe('HarnessDashboard', () => {
  it('renders pending bootstrap before a profile is available', () => {
    render(<HarnessDashboard snapshot={{ ...snapshot('bootstrap'), bootstrapStatus: 'pending', bootstrapProfile: null }}
      rules={[]} busy={false} onApproveRule={() => {}} />);

    expect(screen.getByText('Bootstrapping project knowledge')).toBeTruthy();
    expect(screen.queryByText('Bootstrap trust profile')).toBeNull();
  });

  it('renders completed bootstrap evidence before the first cycle and approves a candidate rule', () => {
    const onApproveRule = vi.fn();
    render(<HarnessDashboard snapshot={snapshot('watching')} rules={[candidate]} busy={false}
      onApproveRule={onApproveRule} />);

    expect(screen.getByText('Bootstrap trust profile')).toBeTruthy();
    expect(screen.getByText(/\.\/mvnw spotless:check/)).toBeTruthy();
    expect(screen.getByText('No failures recorded')).toBeTruthy();
    expect(screen.getByText('2 evidence hits')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Approve rule' }));
    expect(onApproveRule).toHaveBeenCalledWith('rule-1');
  });

  it('shows escalation evidence and a copy-only handoff proof', () => {
    const { rerender } = render(<HarnessDashboard snapshot={snapshot('needs_attention')} rules={[]} busy={false}
      onApproveRule={() => {}} />);
    expect(screen.getByText('Compiler output from the failed job')).toBeTruthy();
    expect(screen.getAllByText('Update SPI')).toHaveLength(2);
    expect(screen.getByText('1 escalated')).toBeTruthy();

    rerender(<HarnessDashboard snapshot={{ ...snapshot('handoff'), runStatusTail: null }} rules={[]} busy={false}
      onApproveRule={() => {}} />);
    expect(screen.queryByText('Bootstrap trust profile')).toBeNull();
    expect(screen.getByText('History rewrite proof')).toBeTruthy();
    expect(screen.getByText('refs/bytequay/backups/watch-1')).toBeTruthy();
    expect(screen.getByText(/Tree diff empty/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^push$/i })).toBeNull();
    expect(screen.getAllByText('git push origin trino-482')).toHaveLength(2);
  });

  it('pins an escalated failure even while the containing cycle still runs', () => {
    const running = {
      ...snapshot('needs_attention'),
      status: 'running' as const,
    };
    render(<HarnessDashboard snapshot={running} rules={[]} busy={false} onApproveRule={() => {}} />);

    expect(screen.getByText('Needs you')).toBeTruthy();
    expect(screen.getByText('Compiler output from the failed job')).toBeTruthy();
  });

  it('renders the dedicated green completion state', () => {
    render(<HarnessDashboard snapshot={snapshot('green')} rules={[]} busy={false} onApproveRule={() => {}} />);
    expect(screen.getByRole('heading', { name: 'CI is green' })).toBeTruthy();
    expect(screen.getByText(/Monitoring stays on/)).toBeTruthy();
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
    id: 1, repo: 'acme/cork', number: 1, title: 'Failing pull request', author: 'octocat',
    htmlUrl: 'https://github.com/acme/cork/pull/1', createdAt: null,
    updatedAt: '2026-07-24T00:00:00Z', origin: 'AUTHORED', labels: [], labelColors: null,
    draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
    ciStatus: 'FAILING', additions: 1, deletions: 0, commentCount: 0, attentionReason: 'CI_FAILING',
    state: 'open', closedAt: null, mergedAt: null, mergeable: true, mergeableState: 'dirty',
    headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
    ...overrides,
  };
}
