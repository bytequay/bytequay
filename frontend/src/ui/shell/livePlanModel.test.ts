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
import { buildLivePlan } from './livePlanModel';
import type {
  AgentRunDto, AgentRunKind, DevPhaseDto, StageDto, StageState, StageType, TaskPhase,
} from '../../types/brainView';

function stage(type: StageType, state: StageState, over: Partial<StageDto> = {}): StageDto {
  return {
    id: `${type}-id`, taskId: 't', type, state,
    openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
    summary: '', loopIteration: 0, ...over,
  };
}

function run(kind: AgentRunKind, over: Partial<AgentRunDto> = {}): AgentRunDto {
  return {
    id: `${kind}-run`, taskId: 't', kind, source: 'remote', parentStageId: null,
    reviewRoundId: null, stageId: `${kind}-stage`, status: 'running', iterations: 1, budget: null,
    headline: null, startedAt: '2026-01-01T00:00:00Z', finishedAt: null, ...over,
  };
}

function devPhase(key: DevPhaseDto['key'], over: Partial<DevPhaseDto> = {}): DevPhaseDto {
  return { key, status: 'future', meta: null, badgeRunId: null, ...over };
}

const node = (nodes: ReturnType<typeof buildLivePlan>, key: string) =>
  nodes.find(n => n.key === key)!;

const phase = (nodes: ReturnType<typeof buildLivePlan>, nodeKey: string, phaseKey: string) =>
  node(nodes, nodeKey).phases!.find(p => p.key === phaseKey)!;

describe('buildLivePlan', () => {
  it('groups lifecycle checkpoints under four stable sidebar stages', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false, paused: false },
    });

    expect(nodes.map(n => n.key)).toEqual([
      'plan', 'local-development', 'remote-development', 'cleanup',
    ]);
    expect(nodes.filter(n => n.placement === 'sub')).toEqual([]);
    expect(nodes.map(n => n.nodeType)).toEqual(['stage', 'stage', 'stage', 'auto']);
    expect(node(nodes, 'local-development').phases?.map(p => p.key))
      .toEqual(['implementing', 'validation', 'brainReview', 'local-review', 'push-pr']);
    expect(node(nodes, 'remote-development').phases?.map(p => p.key))
      .toEqual(['remote-pr', 'ci-validation', 'comments', 'merge-close']);
  });

  it('moves remote work into Remote Development without reopening Local Development', () => {
    const nodes = buildLivePlan({
      stages: [
        stage('PLAN_STAGE', 'CLOSED'),
        stage('DEVELOPMENT_STAGE', 'OPEN', { loopIteration: 3 }),
        stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN'),
      ],
      subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_READY' as TaskPhase, terminal: false, paused: false },
    });

    expect(node(nodes, 'plan').status).toBe('done');
    expect(node(nodes, 'local-development').status).toBe('done');
    expect(node(nodes, 'remote-development').status).toBe('monitoring');
    expect(phase(nodes, 'remote-development', 'ci-validation').status).toBe('sleep');
    expect(phase(nodes, 'remote-development', 'comments').status).toBe('sleep');
    expect(node(nodes, 'cleanup').status).toBe('future');
  });

  it('lights Local Development from task phase and approval state', () => {
    const running = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')],
      subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false, paused: false },
      viewingBrain: true,
    });
    expect(node(running, 'local-development').status).toBe('running');

    const awaitingPush = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')],
      subStages: [],
      task: { prNumber: null, currentPhase: 'AWAITING_PUSH' as TaskPhase, terminal: false, paused: false },
    });
    expect(node(awaitingPush, 'local-development').status).toBe('awaiting');
    expect(phase(awaitingPush, 'local-development', 'local-review').status).toBe('awaiting');
    expect(phase(awaitingPush, 'local-development', 'local-review').meta).toBe('approve & push');
    expect(phase(awaitingPush, 'remote-development', 'remote-pr').status).toBe('future');

    const parked = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')],
      subStages: [],
      task: { prNumber: 145, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false, paused: false },
      awaitingApprovalStageId: 'DEVELOPMENT_STAGE-id',
    });
    expect(node(parked, 'local-development').status).toBe('awaiting');
    expect(node(parked, 'local-development').meta).toBe('awaiting approval');
  });

  it("badges Local Development's validation row with a live local ci_fix run", () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRuns: [run('ci_fix', { id: 'local-fix', iterations: 3, headline: 'fixing linter warning' })],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'running', badgeRunId: 'local-fix' }),
        devPhase('brainReview', { status: 'future', meta: 'next' }),
      ],
      task: { prNumber: null, currentPhase: 'VALIDATING' as TaskPhase, terminal: false, paused: false },
    });

    expect(phase(nodes, 'local-development', 'validation').status).toBe('running');
    expect(phase(nodes, 'local-development', 'validation').badge).toBe('fixing linter warning');
  });

  it('keeps completed phases available behind the Local Development disclosure', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'done' }),
        devPhase('brainReview', { status: 'done' }),
      ],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false, paused: false },
    });

    expect(node(nodes, 'local-development').phases).toHaveLength(5);
    expect(phase(nodes, 'local-development', 'validation').status).toBe('done');
    expect(phase(nodes, 'local-development', 'brainReview').status).toBe('done');
  });

  it('preserves a backend-supplied unresolved Brain summary after escalation', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'done' }),
        devPhase('brainReview', { status: 'done', meta: 'brain unresolved · 2' }),
      ],
      task: { prNumber: null, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false, paused: false },
    });

    expect(node(nodes, 'local-development').status).toBe('awaiting');
    expect(phase(nodes, 'local-development', 'brainReview').meta).toBe('brain unresolved · 2');
    expect(phase(nodes, 'local-development', 'local-review').status).toBe('awaiting');
    expect(phase(nodes, 'local-development', 'local-review').meta).toBe('brain unresolved · 2');
  });

  it('does not expose Local review when Development needs attention before Brain finishes', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'future', meta: 'needs attention' }),
        devPhase('brainReview', { status: 'future', meta: 'next' }),
      ],
      task: { prNumber: null, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false, paused: false },
    });

    expect(phase(nodes, 'local-development', 'local-review').status).toBe('future');
  });

  it('shows a parked Brain review as needing attention without regressing validation or starting Remote', () => {
    const nodes = buildLivePlan({
      stages: [
        stage('DEVELOPMENT_STAGE', 'OPEN'),
        stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN'),
      ],
      subStages: [],
      liveRuns: [run('review_round', { id: 'local-brain', source: 'local' })],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'done', meta: 'checks passed' }),
        devPhase('brainReview', { status: 'future', meta: 'review failed' }),
      ],
      task: { prNumber: null, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false, paused: false },
    });

    expect(node(nodes, 'local-development').status).toBe('awaiting');
    expect(phase(nodes, 'local-development', 'validation').status).toBe('done');
    expect(phase(nodes, 'local-development', 'brainReview').status).toBe('awaiting');
    expect(node(nodes, 'remote-development').status).toBe('future');
    expect(phase(nodes, 'remote-development', 'comments').status).toBe('future');
  });

  it('puts live remote CI and review addressing under Remote Development', () => {
    const nodes = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRuns: [run('ci_fix', { status: 'awaiting_gate' }), run('review_round', { status: 'awaiting_gate' })],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
    });

    expect(node(nodes, 'remote-development').status).toBe('awaiting');
    expect(phase(nodes, 'remote-development', 'ci-validation').status).toBe('awaiting');
    expect(phase(nodes, 'remote-development', 'ci-validation').nav)
      .toEqual({ kind: 'run', runId: 'ci_fix-run' });
    expect(phase(nodes, 'remote-development', 'comments').status).toBe('awaiting');
    expect(phase(nodes, 'remote-development', 'comments').nav)
      .toEqual({ kind: 'run', runId: 'review_round-run' });
  });

  it('does not report queued or paused remote runs as actively running', () => {
    const queued = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRuns: [run('ci_fix', { status: 'queued' })],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false, paused: false },
    });
    expect(node(queued, 'remote-development').status).toBe('sleep');
    expect(phase(queued, 'remote-development', 'ci-validation').status).toBe('sleep');
    expect(phase(queued, 'remote-development', 'ci-validation').meta).toBe('queued');

    const paused = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRuns: [run('review_round', { status: 'paused' })],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
    });
    expect(node(paused, 'remote-development').status).toBe('awaiting');
    expect(phase(paused, 'remote-development', 'comments').status).toBe('awaiting');
    expect(phase(paused, 'remote-development', 'comments').meta).toBe('paused');
  });

  it('renders a truthful meta for a persisted zero-workflow rerun', () => {
    const nodes = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRuns: [run('ci_fix', { headline: 'Re-ran 0 failed CI workflows' })],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false, paused: false },
    });

    expect(phase(nodes, 'remote-development', 'ci-validation').meta)
      .toBe('No CI workflow rerun started');
  });

  it('shows the round gate from the authoritative round when its run projection lags', () => {
    const nodes = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRound: {
        id: 'round-1', taskId: 't', idx: 1, reviewers: ['reviewer'], status: 'awaiting_gate',
        stats: { fixed: 1, replied: 1, pushedBack: 0, open: 0 },
        runId: 'review_round-run', openedAt: '2026-01-01T00:00:00Z',
        gatedAt: '2026-01-01T00:10:00Z', postedAt: null,
        origin: 'external', brainVerdict: 'approved', iteration: 1, budget: 3,
      },
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
    });

    expect(phase(nodes, 'remote-development', 'comments').status).toBe('awaiting');
    expect(phase(nodes, 'remote-development', 'comments').meta).toBe('round 1 · 0 open');
  });

  it('ignores stale live runs once the task is terminal', () => {
    const nodes = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      liveRuns: [run('review_round')],
      liveRound: {
        id: 'round-1', taskId: 't', idx: 1, reviewers: ['reviewer'], status: 'addressing',
        stats: { fixed: 0, replied: 0, pushedBack: 0, open: 1 },
        runId: 'review_round-run', openedAt: '2026-01-01T00:00:00Z', gatedAt: null, postedAt: null,
        origin: 'external', brainVerdict: null, iteration: 1, budget: 3,
      },
      task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, terminal: true, paused: false },
      prStatus: 'closed',
    });

    expect(phase(nodes, 'remote-development', 'comments').status).toBe('done');
    expect(node(nodes, 'remote-development').meta).toBe('closed');
    expect(phase(nodes, 'remote-development', 'merge-close').meta).toBe('closed');
    expect(phase(nodes, 'remote-development', 'comments').nav).toEqual({
      kind: 'stage', stageId: 'REMOTE_DEVELOPMENT_STAGE-id',
    });
  });

  it('derives remote CI status from the linked PR when no run is live', () => {
    const green = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
      ciStatus: 'green', ciSummary: 'all checks passed',
    });
    expect(phase(green, 'remote-development', 'ci-validation').status).toBe('done');
    expect(phase(green, 'remote-development', 'ci-validation').meta).toBe('all checks passed');

    const failing = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false, paused: false },
      ciStatus: 'failing',
    });
    expect(phase(failing, 'remote-development', 'ci-validation').status).toBe('errored');

    const noPr = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(noPr, 'remote-development', 'ci-validation').status).toBe('future');
    expect(phase(noPr, 'remote-development', 'ci-validation').nav).toEqual({ kind: 'none' });
  });

  it('routes Merge / Close to the PR overview terminal-action surface', () => {
    const nodes = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
      mergeReady: true,
    });
    expect(phase(nodes, 'remote-development', 'merge-close').nav)
      .toEqual({ kind: 'tab', tab: 'pr', subTab: 'overview' });
  });

  it('disables remote CI once the task is done if it never ran', () => {
    const done = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 30, currentPhase: 'COMPLETED' as TaskPhase, terminal: true, paused: false },
    });
    expect(phase(done, 'remote-development', 'ci-validation').status).toBe('sleep');
    expect(phase(done, 'remote-development', 'ci-validation').nav).toEqual({ kind: 'none' });

    const stillOpen = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 30, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(stillOpen, 'remote-development', 'ci-validation').nav)
      .toEqual({ kind: 'tab', tab: 'pr', subTab: 'checks' });
  });

  it('monitors and closes Comments using the new stage, with legacy review-monitor fallback', () => {
    const remote = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(remote, 'remote-development', 'comments').status).toBe('monitoring');
    expect(phase(remote, 'remote-development', 'comments').meta).toBe('watching review');

    const legacy = buildLivePlan({
      stages: [stage('REVIEW_MONITOR_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, terminal: true, paused: false },
    });
    expect(phase(legacy, 'remote-development', 'comments').status).toBe('done');
  });

  it('keeps Local review as the only gate until the remote draft is pushed', () => {
    const planning = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'PLANNING' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(planning, 'local-development', 'local-review').status).toBe('future');

    const during = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'INTERNAL_REVIEW' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(during, 'local-development', 'local-review').status).toBe('future');

    const addressing = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'ADDRESSING_LOCAL_COMMENTS' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(addressing, 'local-development', 'local-review').status).toBe('running');
    expect(phase(addressing, 'local-development', 'local-review').meta).toBe('in review');

    const awaitingPush = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'AWAITING_PUSH' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(awaitingPush, 'local-development', 'local-review').status).toBe('awaiting');
    expect(phase(awaitingPush, 'local-development', 'local-review').nav).toEqual({ kind: 'tab', tab: 'pr' });
    expect(phase(awaitingPush, 'remote-development', 'remote-pr').status).toBe('future');
    expect(phase(awaitingPush, 'remote-development', 'remote-pr').nav).toEqual({ kind: 'none' });
    expect(phase(awaitingPush, 'remote-development', 'remote-pr').glyph).toBe('○');

    const pushed = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false, paused: false },
    });
    expect(phase(pushed, 'local-development', 'local-review').status).toBe('done');
    expect(phase(pushed, 'remote-development', 'remote-pr').status).toBe('done');
    expect(phase(pushed, 'remote-development', 'remote-pr').glyph).toBe('✓');
  });

  it('drives remote PR and merge rows from PR state', () => {
    const remoteAttention = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false, paused: false },
      prStatus: 'draft',
    });
    expect(phase(remoteAttention, 'local-development', 'local-review').status).toBe('done');

    const queued = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
      prStatus: 'queued',
    });
    expect(phase(queued, 'remote-development', 'remote-pr').status).toBe('done');
    expect(phase(queued, 'remote-development', 'remote-pr').meta).toBe('PR #145');
    expect(phase(queued, 'remote-development', 'merge-close').status).toBe('running');
    expect(phase(queued, 'remote-development', 'merge-close').meta).toBe('queued');

    const ready = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false, paused: false },
      prStatus: 'open',
      mergeReady: true,
    });
    expect(phase(ready, 'remote-development', 'merge-close').status).toBe('awaiting');
    expect(phase(ready, 'remote-development', 'merge-close').meta).toBe('ready to merge');
  });

  it('projects paused active phases as static resumable checkpoints', () => {
    const dev = stage('DEVELOPMENT_STAGE', 'OPEN');
    const local = buildLivePlan({
      stages: [dev], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'running' }),
        devPhase('brainReview'),
      ],
      task: {
        prNumber: null, currentPhase: 'VALIDATING' as TaskPhase, paused: true, terminal: false,
      },
      viewedStageId: dev.id,
      working: true,
      awaitingApprovalStageId: dev.id,
    });

    expect(node(local, 'local-development').status).toBe('awaiting');
    expect(node(local, 'local-development').meta).toBe('paused · resume');
    expect(phase(local, 'local-development', 'validation').status).toBe('awaiting');
    expect(phase(local, 'local-development', 'validation').meta).toBe('paused · resume');
    expect(local.flatMap(item => [item.status, ...(item.phases ?? []).map(child => child.status)]))
      .not.toContain('running');

    const planning = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'OPEN')], subStages: [],
      task: {
        prNumber: null, currentPhase: 'PLANNING' as TaskPhase, paused: true, terminal: false,
      },
      viewingBrain: true,
      working: true,
    });
    expect(node(planning, 'plan').status).toBe('awaiting');
    expect(node(planning, 'plan').meta).toBe('paused · resume');
    expect(planning.map(item => item.status)).not.toContain('planning');
  });

  it('parks remote monitoring and stale live projections while paused', () => {
    const nodes = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRuns: [run('review_round')],
      task: {
        prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, paused: true, terminal: false,
      },
      ciStatus: 'pending',
    });

    expect(node(nodes, 'remote-development').status).toBe('awaiting');
    expect(node(nodes, 'remote-development').meta).toBe('paused · resume');
    expect(phase(nodes, 'remote-development', 'comments').status).toBe('awaiting');
    expect(phase(nodes, 'remote-development', 'comments').meta).toBe('paused · resume');
    const liveStatuses = nodes
      .flatMap(item => [item.status, ...(item.phases ?? []).map(child => child.status)])
      .filter(status => status === 'running' || status === 'planning' || status === 'monitoring');
    expect(liveStatuses).toEqual([]);
  });

  it('tracks active views and working pulses for Plan and stages', () => {
    const dev = stage('DEVELOPMENT_STAGE', 'OPEN');
    const onDev = buildLivePlan({
      stages: [dev], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false, paused: false },
      viewedStageId: dev.id,
    });
    expect(node(onDev, 'local-development').activeView).toBe(true);
    expect(node(onDev, 'plan').activeView).toBe(false);

    const onBrain = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false, paused: false },
      viewingBrain: true,
      working: true,
    });
    expect(node(onBrain, 'plan').nav).toEqual({ kind: 'brain' });
    expect(node(onBrain, 'plan').activeView).toBe(true);
    expect(node(onBrain, 'plan').status).toBe('planning');
  });
});
