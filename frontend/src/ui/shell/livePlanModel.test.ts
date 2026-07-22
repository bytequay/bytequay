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
import { buildGuardChip, buildLivePlan } from './livePlanModel';
import type {
  AgentRunDto, AgentRunKind, BranchGuardDto, DevPhaseDto, StageDto, StageState, StageType, TaskPhase,
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
  it('renders the canonical eight-node lifecycle with phases only under Development', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });

    expect(nodes.map(n => n.key)).toEqual([
      'plan', 'local-development', 'local-review', 'remote-pr',
      'ci-validation', 'comments', 'merge-close', 'cleanup',
    ]);
    expect(nodes.filter(n => n.placement === 'sub')).toEqual([]);
    expect(nodes.map(n => n.nodeType)).toEqual([
      'stage', 'stage', 'gate', 'auto', 'auto', 'stage', 'gate', 'auto',
    ]);
    expect(node(nodes, 'local-development').phases?.map(p => p.key))
      .toEqual(['implementing', 'validation', 'brainReview']);
    expect(nodes.filter(n => n.key !== 'local-development').every(n => n.phases === undefined)).toBe(true);
  });

  it('maps the remote half onto CI and Comments without reopening Development', () => {
    const nodes = buildLivePlan({
      stages: [
        stage('PLAN_STAGE', 'CLOSED'),
        stage('DEVELOPMENT_STAGE', 'ACTIVE', { loopIteration: 3 }),
        stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN'),
      ],
      subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_READY' as TaskPhase, terminal: false },
    });

    expect(node(nodes, 'plan').status).toBe('done');
    expect(node(nodes, 'local-development').status).toBe('done');
    expect(node(nodes, 'local-development').meta).toBe('validated ✓ · brain review complete');
    expect(node(nodes, 'ci-validation').status).toBe('sleep');
    expect(node(nodes, 'comments').status).toBe('sleep');
    expect(node(nodes, 'cleanup').status).toBe('future');
  });

  it('lights Local Development from task phase and approval state', () => {
    const running = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')],
      subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewingBrain: true,
    });
    expect(node(running, 'local-development').status).toBe('running');

    const awaitingPush = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')],
      subStages: [],
      task: { prNumber: null, currentPhase: 'AWAITING_PUSH' as TaskPhase, terminal: false },
    });
    expect(node(awaitingPush, 'local-development').status).toBe('done');
    expect(node(awaitingPush, 'local-review').status).toBe('awaiting');
    expect(node(awaitingPush, 'local-review').meta).toBe('approve & push');
    expect(node(awaitingPush, 'remote-pr').status).toBe('future');

    const parked = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'ACTIVE')],
      subStages: [],
      task: { prNumber: 145, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false },
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
      task: { prNumber: null, currentPhase: 'VALIDATING' as TaskPhase, terminal: false },
    });

    expect(phase(nodes, 'local-development', 'validation').status).toBe('running');
    expect(phase(nodes, 'local-development', 'validation').badge).toBe('fixing linter warning');
  });

  it('collapses Development phases into summary meta once the stage closes', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'done' }),
        devPhase('brainReview', { status: 'done' }),
      ],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });

    expect(node(nodes, 'local-development').phases).toBeUndefined();
    expect(node(nodes, 'local-development').meta).toBe('validated ✓ · brain review complete');
  });

  it('preserves a backend-supplied unresolved Brain summary after escalation', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'done' }),
        devPhase('brainReview', { status: 'done', meta: 'brain unresolved · 2' }),
      ],
      task: { prNumber: null, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false },
    });

    expect(node(nodes, 'local-development').meta).toBe('validated ✓ · brain unresolved · 2');
    expect(node(nodes, 'local-review').status).toBe('awaiting');
    expect(node(nodes, 'local-review').meta).toBe('brain unresolved · 2');
  });

  it('does not expose Local review when Development needs attention before Brain finishes', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'future', meta: 'needs attention' }),
        devPhase('brainReview', { status: 'future', meta: 'next' }),
      ],
      task: { prNumber: null, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false },
    });

    expect(node(nodes, 'local-review').status).toBe('future');
  });

  it('puts live remote CI and review addressing on their top-level nodes', () => {
    const nodes = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      liveRuns: [run('ci_fix', { status: 'awaiting_gate' }), run('review_round', { status: 'awaiting_gate' })],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
    });

    expect(node(nodes, 'ci-validation').status).toBe('awaiting');
    expect(node(nodes, 'ci-validation').nav)
      .toEqual({ kind: 'run', runId: 'ci_fix-run' });
    expect(node(nodes, 'comments').status).toBe('awaiting');
    expect(node(nodes, 'comments').nav)
      .toEqual({ kind: 'run', runId: 'review_round-run' });
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
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
    });

    expect(node(nodes, 'comments').status).toBe('awaiting');
    expect(node(nodes, 'comments').meta).toBe('round 1 · 0 open');
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
      task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, terminal: true },
    });

    expect(node(nodes, 'comments').status).toBe('done');
    expect(node(nodes, 'comments').nav).toEqual({
      kind: 'stage', stageId: 'REMOTE_DEVELOPMENT_STAGE-id',
    });
  });

  it('derives remote CI status from the linked PR when no run is live', () => {
    const green = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
      ciStatus: 'green', ciSummary: 'all checks passed',
    });
    expect(node(green, 'ci-validation').status).toBe('done');
    expect(node(green, 'ci-validation').meta).toBe('all checks passed');

    const failing = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
      ciStatus: 'failing',
    });
    expect(node(failing, 'ci-validation').status).toBe('errored');

    const noPr = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(noPr, 'ci-validation').status).toBe('future');
    expect(node(noPr, 'ci-validation').nav).toEqual({ kind: 'none' });
  });

  it('routes Merge / Close to the PR overview terminal-action surface', () => {
    const nodes = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
      mergeReady: true,
    });
    expect(node(nodes, 'merge-close').nav)
      .toEqual({ kind: 'tab', tab: 'pr', subTab: 'overview' });
  });

  it('disables remote CI once the task is done if it never ran', () => {
    const done = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 30, currentPhase: 'COMPLETED' as TaskPhase, terminal: true },
    });
    expect(node(done, 'ci-validation').status).toBe('sleep');
    expect(node(done, 'ci-validation').nav).toEqual({ kind: 'none' });

    const stillOpen = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 30, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });
    expect(node(stillOpen, 'ci-validation').nav)
      .toEqual({ kind: 'tab', tab: 'pr', subTab: 'checks' });
  });

  it('monitors and closes Comments using the new stage, with legacy review-monitor fallback', () => {
    const remote = buildLivePlan({
      stages: [stage('REMOTE_DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
    });
    expect(node(remote, 'comments').status).toBe('monitoring');
    expect(node(remote, 'comments').meta).toBe('watching review');

    const legacy = buildLivePlan({
      stages: [stage('REVIEW_MONITOR_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, terminal: true },
    });
    expect(node(legacy, 'comments').status).toBe('done');
  });

  it('keeps Local review as the only gate until the remote draft is pushed', () => {
    const planning = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'PLANNING' as TaskPhase, terminal: false },
    });
    expect(node(planning, 'local-review').status).toBe('future');

    const during = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'INTERNAL_REVIEW' as TaskPhase, terminal: false },
    });
    expect(node(during, 'local-review').status).toBe('future');

    const addressing = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'ADDRESSING_LOCAL_COMMENTS' as TaskPhase, terminal: false },
    });
    expect(node(addressing, 'local-review').status).toBe('running');
    expect(node(addressing, 'local-review').meta).toBe('in review');

    const awaitingPush = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'AWAITING_PUSH' as TaskPhase, terminal: false },
    });
    expect(node(awaitingPush, 'local-review').status).toBe('awaiting');
    expect(node(awaitingPush, 'local-review').nav).toEqual({ kind: 'tab', tab: 'pr' });
    expect(node(awaitingPush, 'remote-pr').status).toBe('future');
    expect(node(awaitingPush, 'remote-pr').nav).toEqual({ kind: 'none' });
    expect(node(awaitingPush, 'remote-pr').nodeType).toBe('auto');
    expect(node(awaitingPush, 'remote-pr').glyph).toBe('○');

    const pushed = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });
    expect(node(pushed, 'local-review').status).toBe('done');
    expect(node(pushed, 'remote-pr').status).toBe('done');
    expect(node(pushed, 'remote-pr').glyph).toBe('✓');
  });

  it('drives remote PR and merge rows from PR state', () => {
    const remoteAttention = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false },
      prStatus: 'draft',
    });
    expect(node(remoteAttention, 'local-review').status).toBe('done');

    const queued = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
      prStatus: 'queued',
    });
    expect(node(queued, 'remote-pr').status).toBe('done');
    expect(node(queued, 'remote-pr').meta).toBe('PR #145');
    expect(node(queued, 'merge-close').status).toBe('running');
    expect(node(queued, 'merge-close').meta).toBe('queued');

    const ready = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
      prStatus: 'open',
      mergeReady: true,
    });
    expect(node(ready, 'merge-close').status).toBe('awaiting');
    expect(node(ready, 'merge-close').meta).toBe('ready to merge');
  });

  it('tracks active views and working pulses for Plan and stages', () => {
    const dev = stage('DEVELOPMENT_STAGE', 'ACTIVE');
    const onDev = buildLivePlan({
      stages: [dev], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewedStageId: dev.id,
    });
    expect(node(onDev, 'local-development').activeView).toBe(true);
    expect(node(onDev, 'plan').activeView).toBe(false);

    const onBrain = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewingBrain: true,
      working: true,
    });
    expect(node(onBrain, 'plan').nav).toEqual({ kind: 'brain' });
    expect(node(onBrain, 'plan').activeView).toBe(true);
    expect(node(onBrain, 'plan').status).toBe('planning');
  });
});

describe('buildGuardChip', () => {
  function guard(over: Partial<BranchGuardDto> = {}): BranchGuardDto {
    return {
      taskId: 't', enabled: true, schedule: 'nightly', state: 'healthy',
      health: { behindBy: 0, mergeable: true, checksGreen: true },
      lastRunId: null, lastCheckedAt: null, ...over,
    };
  }

  it('returns null only when no guard row exists yet', () => {
    expect(buildGuardChip(null)).toBeNull();
    expect(buildGuardChip(undefined)).toBeNull();
  });

  it('hides guard rows for terminal tasks', () => {
    expect(buildGuardChip(guard(), true)).toBeNull();
  });

  it('shows a disabled row as "off" so it can be armed', () => {
    const chip = buildGuardChip(guard({ enabled: false, state: 'healthy' }));
    expect(chip?.enabled).toBe(false);
    expect(chip?.label).toBe('guard off');
  });

  it('labels each guard state while enabled', () => {
    expect(buildGuardChip(guard({ state: 'healthy' }))?.label).toBe('in sync with main');
    expect(buildGuardChip(guard({ state: 'drifting' }))?.label).toBe('drifting from main');
    expect(buildGuardChip(guard({ state: 'conflicted' }))?.label).toBe('conflicts with main');
    expect(buildGuardChip(guard({ state: 'fixing' }))?.label).toBe('fixing drift');
    expect(buildGuardChip(guard({ state: 'needs_attention' }))?.label).toBe('needs attention');
  });
});
