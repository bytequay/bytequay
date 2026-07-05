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

describe('buildLivePlan', () => {
  it('renders the eight-node checkpoint spine with Review while Development is open', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(nodes.map(n => n.key)).toEqual([
      'root', 'dev', 'review', 'local-review', 'remote-pr', 'ci-validation', 'comments', 'merge-close', 'cleanup',
    ]);
    // Only the Review sub-row — no Checks/Addressing rows.
    expect(nodes.filter(n => n.placement === 'sub').map(n => n.key)).toEqual(['review']);
    expect(nodes.map(n => n.nodeType)).toEqual([
      'stage', 'stage', 'stage', 'gate', 'gate', 'auto', 'stage', 'gate', 'auto',
    ]);
  });

  it('renders zero sub-rows once a nit PR closes Development (matches the mockup)', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: 146, currentPhase: 'COMPLETED' as TaskPhase, terminal: true },
    });
    expect(nodes.some(n => n.placement === 'sub')).toBe(false);
  });

  it('maps stage state to node status (closed→done, active→running, open→sleep, absent→future)', () => {
    const nodes = buildLivePlan({
      stages: [
        stage('PLAN_STAGE', 'CLOSED'),
        stage('DEVELOPMENT_STAGE', 'ACTIVE', { loopIteration: 3 }),
      ],
      subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_READY' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'root').status).toBe('done');
    expect(node(nodes, 'dev').status).toBe('running');
    expect(node(nodes, 'dev').meta).toBe('iter 3');
    expect(node(nodes, 'comments').status).toBe('future');
    expect(node(nodes, 'cleanup').status).toBe('future');
  });

  it('lights Development running from the phase even when its stage row is OPEN', () => {
    // A CLI subprocess turn leaves the dev row OPEN, not ACTIVE — the node
    // must still read "running" (orange) on the root page, not "sleep" (white).
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')],
      subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewingBrain: true,
    });
    expect(node(nodes, 'dev').status).toBe('running');
  });

  it('lights the stage parked for approval orange (awaiting) regardless of view', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'ACTIVE')],
      subStages: [],
      task: { prNumber: 145, currentPhase: 'NEEDS_ATTENTION' as TaskPhase, terminal: false },
      viewingBrain: true,
      awaitingApprovalStageId: 'DEVELOPMENT_STAGE-id',
    });
    expect(node(nodes, 'dev').status).toBe('awaiting');
    expect(node(nodes, 'dev').meta).toBe('awaiting approval');
  });

  it('marks Development awaiting while parked for push approval (AWAITING_PUSH)', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')],
      subStages: [],
      task: { prNumber: null, currentPhase: 'AWAITING_PUSH' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'dev').status).toBe('awaiting');
  });

  it('marks Development running while addressing local PR comments', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')],
      subStages: [],
      task: { prNumber: null, currentPhase: 'ADDRESSING_LOCAL_COMMENTS' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'dev').status).toBe('running');
  });

  it("badges Development's Validation phase with a live local ci_fix run, nested while open", () => {
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
    const phases = node(nodes, 'dev').phases;
    expect(phases?.map(p => p.key)).toEqual(['implementing', 'validation', 'brainReview']);
    const validation = phases?.find(p => p.key === 'validation');
    expect(validation?.status).toBe('running');
    expect(validation?.badge).toBe('fixing linter warning');
    // No separate rail row for it — it only ever badges the phase row.
    expect(nodes.some(n => n.key === 'dev-checks')).toBe(false);
    expect(nodes.some(n => n.key === 'comments-checks')).toBe(false);
  });

  it("collapses Development's phase ladder once the stage closes", () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')], subStages: [],
      devPhases: [
        devPhase('implementing', { status: 'done' }),
        devPhase('validation', { status: 'done' }),
        devPhase('brainReview', { status: 'future', meta: 'next' }),
      ],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'dev').phases).toBeUndefined();
  });

  it('adds an Addressing sub-row under Comments and badges CI validation once out for remote review', () => {
    const nodes = buildLivePlan({
      stages: [], subStages: [],
      liveRuns: [run('ci_fix', { status: 'awaiting_gate' }), run('review_round', { status: 'awaiting_gate' })],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
    });
    expect(nodes.some(n => n.key === 'dev-checks')).toBe(false);
    expect(nodes.some(n => n.key === 'comments-checks')).toBe(false);
    const addressing = node(nodes, 'comments-addressing');
    expect(addressing.placement).toBe('sub');
    expect(addressing.status).toBe('awaiting');
    expect(addressing.meta).toBe('awaiting you');
    const ciValidation = node(nodes, 'ci-validation');
    expect(ciValidation.status).toBe('awaiting');
    expect(ciValidation.meta).toBe('awaiting you');
  });

  it('derives CI validation status from the linked PR ciStatus when no run is live', () => {
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

  it('marks a live run\'s sub-row status by its own status, not the parent stage', () => {
    const nodes = buildLivePlan({
      stages: [stage('CLEANUP_STAGE', 'CLOSED')], subStages: [],
      liveRuns: [run('review_round', { status: 'awaiting_gate' })],
      task: { prNumber: 1, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'comments-addressing').status).toBe('awaiting');
  });

  it('marks Comments "monitoring" while polling the remote after a push with no live round', () => {
    const outForReview = buildLivePlan({
      stages: [stage('REVIEW_MONITOR_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
    });
    expect(node(outForReview, 'comments').status).toBe('monitoring');
    expect(node(outForReview, 'comments').meta).toBe('watching review');
  });

  it('marks Comments done once the review-monitor stage closes', () => {
    const nodes = buildLivePlan({
      stages: [stage('REVIEW_MONITOR_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, terminal: true },
    });
    expect(node(nodes, 'comments').status).toBe('done');
  });

  it('uses the planning variant for an active Plan stage', () => {
    const nodes = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'ACTIVE')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'root').status).toBe('planning');
    expect(node(nodes, 'root').glyph).toBe('●');
  });

  it('drives Local Review from the phase: future before, running during, done after', () => {
    // A task still being planned — before the brain's plan is even approved,
    // let alone Development having reached internal review — must not read
    // as "approved" (regression: PLANNING was missing from the TaskPhase
    // union, so it fell through to the function's done-by-default case).
    const planning = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'PLANNING' as TaskPhase, terminal: false },
    });
    expect(node(planning, 'local-review').status).toBe('future');

    const before = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(before, 'local-review').status).toBe('future');

    const during = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'INTERNAL_REVIEW' as TaskPhase, terminal: false },
    });
    expect(node(during, 'local-review').status).toBe('running');

    const after = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'AWAITING_PUSH' as TaskPhase, terminal: false },
    });
    expect(node(after, 'local-review').status).toBe('done');
    expect(node(after, 'local-review').meta).toBe('approved');
  });

  it('opens the PR tab in place for Local review once Development exists, else disabled', () => {
    const withDev = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'ACTIVE')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(withDev, 'local-review').nav).toEqual({ kind: 'tab', tab: 'pr' });

    const noDev = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'PLANNING' as TaskPhase, terminal: false },
    });
    expect(node(noDev, 'local-review').nav).toEqual({ kind: 'none' });
  });

  it('marks Remote pull request done with the PR number once a PR exists, as a peer node', () => {
    const nodes = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'remote-pr').status).toBe('done');
    expect(node(nodes, 'remote-pr').meta).toBe('PR #145');
    expect(node(nodes, 'remote-pr').placement).toBe('full');
    expect(node(nodes, 'remote-pr').nav).toEqual({ kind: 'tab', tab: 'pr' });

    const early = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(early, 'remote-pr').status).toBe('future');
    expect(node(early, 'remote-pr').nav).toEqual({ kind: 'none' });
  });

  it('drives the Merge / Close node from PR/merge state', () => {
    const queued = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
      prStatus: 'queued',
    });
    expect(node(queued, 'merge-close').status).toBe('running');
    expect(node(queued, 'merge-close').meta).toBe('queued');

    const merged = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, terminal: true },
      prStatus: 'merged',
    });
    expect(node(merged, 'merge-close').status).toBe('done');
  });

  it('lights the Merge / Close node as ready-to-merge when a merge gate is open', () => {
    const ready = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
      prStatus: 'open',
      mergeReady: true,
    });
    expect(node(ready, 'merge-close').status).toBe('monitoring');
    expect(node(ready, 'merge-close').meta).toBe('ready to merge');
  });

  it('shows Review (callable) as not-invoked until a ReviewStage exists, while Development is open', () => {
    const none = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'OPEN')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(none, 'review').status).toBe('future');
    expect(node(none, 'review').meta).toBe('not invoked');
    expect(node(none, 'review').placement).toBe('sub');

    const invoked = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'ACTIVE')],
      subStages: [stage('REVIEW_STAGE', 'ACTIVE', { callerStageId: 'dev-id' })],
      task: { prNumber: null, currentPhase: 'INTERNAL_REVIEW' as TaskPhase, terminal: false },
    });
    expect(node(invoked, 'review').status).toBe('running');
  });

  it('drops the Review (callable) row once Development closes', () => {
    const nodes = buildLivePlan({
      stages: [stage('DEVELOPMENT_STAGE', 'CLOSED')],
      subStages: [stage('REVIEW_STAGE', 'CLOSED', { callerStageId: 'dev-id' })],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });
    expect(nodes.some(n => n.key === 'review')).toBe(false);
  });

  it('flags the currently-viewed stage as the active view', () => {
    const dev = stage('DEVELOPMENT_STAGE', 'ACTIVE');
    const nodes = buildLivePlan({
      stages: [dev], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewedStageId: dev.id,
    });
    expect(node(nodes, 'dev').activeView).toBe(true);
    expect(node(nodes, 'root').activeView).toBe(false);
  });

  it('routes the Root node to the brain page and tracks viewingBrain', () => {
    const onBrain = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewingBrain: true,
    });
    expect(node(onBrain, 'root').nav).toEqual({ kind: 'brain' });
    expect(node(onBrain, 'root').activeView).toBe(true);
  });

  it('pulses the Root node while the brain is thinking, even after the plan is approved', () => {
    const idle = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewingBrain: true,
    });
    // Approved plan reads done when idle…
    expect(node(idle, 'root').status).toBe('done');

    const thinking = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'CLOSED')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewingBrain: true,
      working: true,
    });
    // …but pulses (planning) the moment the brain starts working.
    expect(node(thinking, 'root').status).toBe('planning');
  });

  it('pulses the viewed stage node while its agent works, not the others', () => {
    const dev = stage('DEVELOPMENT_STAGE', 'CLOSED');
    const nodes = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'CLOSED'), dev], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewedStageId: dev.id,
      working: true,
    });
    // The viewed Dev node pulses (running) even though its stage row is closed…
    expect(node(nodes, 'dev').status).toBe('running');
    // …while a node that isn't the active view is untouched by `working`.
    expect(node(nodes, 'cleanup').status).toBe('future');
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

  it('returns null only when no guard row exists yet (no push yet)', () => {
    expect(buildGuardChip(null)).toBeNull();
    expect(buildGuardChip(undefined)).toBeNull();
  });

  it('shows a disabled row as "off" — with a toggle, not hidden — so it can be armed', () => {
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
