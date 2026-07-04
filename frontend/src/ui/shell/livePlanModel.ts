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
import type { StageDto, StageType, TaskPhase } from '../../types/brainView';

/** Visual state of one node in the live-plan diagram. {@code monitoring} is a
 *  looping stage that has pushed and is now polling remote CI / review (no
 *  local agent turn running) — distinct from {@code running} (agent working)
 *  and {@code sleep} (idle/open). */
export type LivePlanStatus =
  | 'done' | 'running' | 'monitoring' | 'planning' | 'sleep' | 'future' | 'errored'
  /** Parked for the user's approval (a gate) — rendered orange. */
  | 'awaiting';

/** Where a node sits in the lifecycle layout. `split-*` are the parallel
 *  CI-Fix / Comments branch; `sub` is the indented Review (callable) node. */
export type LivePlanPlacement = 'full' | 'sub' | 'split-left' | 'split-right';

/** Where clicking a node navigates. */
export type LivePlanNav =
  | { kind: 'stage'; stageId: string }
  | { kind: 'code' }
  | { kind: 'pr' }
  | { kind: 'brain' }
  | { kind: 'none' };

export type LivePlanNode = {
  key: string;
  label: string;
  status: LivePlanStatus;
  glyph: string;
  meta?: string;
  placement: LivePlanPlacement;
  /** True when this node's stage is the one currently being viewed. */
  activeView: boolean;
  nav: LivePlanNav;
};

export type LivePlanInput = {
  stages: StageDto[];
  subStages: StageDto[];
  task: { prNumber: number | null; currentPhase: TaskPhase; terminal: boolean };
  /** The PR's merge-state, when known — drives the Merge node. */
  prStatus?: 'open' | 'draft' | 'queued' | 'merged' | null;
  /** True when a ready-to-merge gate is open (CI green, no unresolved
   *  comments, mergeable) — lights the Merge node as actionable. */
  mergeReady?: boolean;
  /** The stage currently open on screen, highlighted as the active view. */
  viewedStageId?: string | null;
  /** True on the brain page — marks the Root node as the active view (the
   *  brain/root conversation, not any stage). */
  viewingBrain?: boolean;
  /** True while the active surface's agent is mid-turn (the brain is thinking,
   *  or the viewed stage's agent is working). Pulses that node so the user can
   *  see it's working immediately, ahead of the stage-state poll. Background
   *  stages still pulse from their own polled {@code ACTIVE} state. */
  working?: boolean;
  /** Stage id currently parked for the user's approval (a NEEDS_ATTENTION
   *  gate), lit orange as `awaiting`. Null when nothing is parked. */
  awaitingApprovalStageId?: string | null;
};

/** Status for a stage-backed node: closed → done, active → running (or
 *  planning for the Plan stage), open/paused → sleep, absent → future. */
function stageStatus(stage: StageDto | undefined, planning = false): LivePlanStatus {
  if (stage === undefined) return 'future';
  switch (stage.state) {
    case 'CLOSED': return 'done';
    case 'ACTIVE': return planning ? 'planning' : 'running';
    default: return 'sleep';
  }
}

function glyphFor(status: LivePlanStatus, future = '○'): string {
  if (status === 'done') return '✓';
  if (status === 'monitoring') return '◎';
  if (status === 'running' || status === 'planning' || status === 'errored' || status === 'awaiting') return '●';
  if (status === 'sleep') return '○';
  return future;
}

/** Task phases during which the Development stage is the active work — the
 *  node must read "running" then even if its stage row sits OPEN between
 *  turns (a CLI subprocess turn leaves the row OPEN, not ACTIVE), which is
 *  why the plain stage-state mapping under-lit it. AWAITING_PUSH is the
 *  dev-finished, parked-for-push-approval state → "awaiting";
 *  ADDRESSING_LOCAL_COMMENTS is a reactive detour off it where the agent is
 *  actively addressing local PR comments, so it reads "running" too. */
const DEV_RUNNING_PHASES = new Set<TaskPhase>(
  ['IMPLEMENTING', 'VALIDATING', 'INTERNAL_REVIEW', 'ADDRESSING_LOCAL_COMMENTS']);

function devNodeStatus(dev: StageDto | undefined, phase: TaskPhase): LivePlanStatus {
  if (dev === undefined) return 'future';
  if (dev.state === 'CLOSED') return 'done';
  if (DEV_RUNNING_PHASES.has(phase)) return 'running';
  if (phase === 'AWAITING_PUSH') return 'awaiting';
  return stageStatus(dev);
}

/** Status for a monitor (looping) stage. It loops while its phase is the
 *  current work, so its row can sit OPEN (not ACTIVE) between turns — which
 *  would otherwise dim the node to `sleep`. When the task has pushed and is
 *  polling the remote (CI re-running / out for review) it's `monitoring`;
 *  when an agent turn is actively fixing it's `running`; otherwise fall back
 *  to the generic state mapping. */
function monitorStatus(
  stage: StageDto | undefined, activePhase: boolean, waitingPhase: boolean): LivePlanStatus {
  if (stage === undefined || stage.state === 'CLOSED') {
    return stageStatus(stage);
  }
  if (waitingPhase) return 'monitoring';
  if (activePhase) return 'running';
  return stageStatus(stage);
}

/** Meta hint for a looping stage ("iter N"), omitted at iteration 0. */
function iterMeta(stage: StageDto | undefined): string | undefined {
  if (stage === undefined || stage.loopIteration <= 0) return undefined;
  return `iter ${stage.loopIteration}`;
}

/**
 * Derives the ordered live-plan node list from the task's actual stages,
 * phase, and PR state. The lifecycle shape is fixed (Plan → Development →
 * {CI Fix ‖ Comments} → Merge → Cleanup), with Review (callable) and Push
 * hanging as sub-nodes under Development; only each node's status/meta is
 * data-driven. Stages that don't exist yet render as `future` (dashed),
 * matching lazy stage instantiation.
 */
export function buildLivePlan(input: LivePlanInput): LivePlanNode[] {
  const {
    stages, subStages, task, prStatus = null, mergeReady = false,
    viewedStageId = null, viewingBrain = false, working = false,
    awaitingApprovalStageId = null,
  } = input;
  const byType = (type: StageType): StageDto | undefined => stages.find(s => s.type === type);
  const isViewed = (stage: StageDto | undefined): boolean =>
    stage !== undefined && stage.id === viewedStageId;
  const stageNav = (stage: StageDto | undefined): LivePlanNav =>
    stage !== undefined ? { kind: 'stage', stageId: stage.id } : { kind: 'none' };

  const plan = byType('PLAN_STAGE');
  const dev = byType('DEVELOPMENT_STAGE');
  const review = subStages.find(s => s.type === 'REVIEW_STAGE');
  const ciFix = byType('CI_FIXING_STAGE');
  const comments = byType('REVIEW_MONITOR_STAGE');
  const cleanup = byType('CLEANUP_STAGE');

  // The Root node stands in for the plan "stage" — which is really the
  // brain/root conversation, not a drill-in stage. It tracks the plan's
  // progress (planning while the PlanStage is active, done once approved)
  // but navigates back to the brain page instead of a stage page.
  const rootStatus: LivePlanStatus = plan === undefined ? 'planning' : stageStatus(plan, true);
  const devStatus = devNodeStatus(dev, task.currentPhase);
  const reviewStatus = stageStatus(review);
  // PUSHED_AWAITING_CI = pushed, polling remote CI (monitoring); CI_FIXING =
  // agent actively fixing. AWAITING_REMOTE_REVIEW = out for review (monitoring);
  // ADDRESSING_COMMENTS = agent fixing comments.
  const ciStatus = monitorStatus(
    ciFix, task.currentPhase === 'CI_FIXING', task.currentPhase === 'PUSHED_AWAITING_CI');
  const commentsStatus = monitorStatus(
    comments, task.currentPhase === 'ADDRESSING_COMMENTS',
    task.currentPhase === 'AWAITING_REMOTE_REVIEW');
  const cleanupStatus = stageStatus(cleanup);

  // Push isn't a stage — it's the milestone of having opened the PR.
  const pushStatus: LivePlanStatus = task.prNumber !== null ? 'done' : 'future';

  // Merge is user-gated and pseudo: done once merged/completed, running while
  // it's queued, sleeping while it's out for remote review, future otherwise.
  const mergeStatus: LivePlanStatus =
    task.currentPhase === 'COMPLETED' || prStatus === 'merged' ? 'done'
      : prStatus === 'queued' ? 'running'
        : mergeReady ? 'monitoring'
          : task.currentPhase === 'AWAITING_REMOTE_REVIEW' || task.currentPhase === 'ADDRESSING_COMMENTS'
            ? 'sleep'
            : 'future';
  const mergeMeta = prStatus === 'queued' ? 'queued'
    : mergeStatus === 'done' ? 'merged'
      : mergeStatus === 'monitoring' ? 'ready to merge'
        : mergeStatus === 'sleep' ? 'awaiting' : undefined;

  const nodes: LivePlanNode[] = [
    {
      key: 'root', label: 'Root', status: rootStatus, glyph: glyphFor(rootStatus),
      meta: rootStatus === 'done' ? 'plan approved' : 'planning',
      placement: 'full', activeView: viewingBrain, nav: { kind: 'brain' },
    },
    {
      key: 'dev', label: 'Development', status: devStatus, glyph: glyphFor(devStatus),
      meta: iterMeta(dev), placement: 'full', activeView: isViewed(dev), nav: stageNav(dev),
    },
    {
      key: 'review', label: 'Review (callable)', status: reviewStatus,
      glyph: glyphFor(reviewStatus), meta: review === undefined ? 'not invoked' : undefined,
      placement: 'sub', activeView: isViewed(review), nav: stageNav(review),
    },
    {
      // Push isn't its own stage — it's the tail of Development (the dev agent
      // opens the PR). So it hangs as a sub-node under Development and a click
      // routes to the Development conversation rather than a dead/empty view.
      key: 'push', label: 'Push', status: pushStatus, glyph: glyphFor(pushStatus),
      meta: task.prNumber !== null ? `PR #${task.prNumber}` : undefined,
      placement: 'sub', activeView: false, nav: stageNav(dev),
    },
    {
      key: 'ci-fix', label: 'CI Fix', status: ciStatus, glyph: glyphFor(ciStatus),
      meta: ciStatus === 'monitoring' ? 'watching CI' : iterMeta(ciFix),
      placement: 'split-left', activeView: isViewed(ciFix), nav: stageNav(ciFix),
    },
    {
      key: 'comments', label: 'Comments', status: commentsStatus, glyph: glyphFor(commentsStatus),
      meta: comments === undefined ? undefined
        : commentsStatus === 'monitoring' ? 'watching review'
          : commentsStatus === 'sleep' ? 'armed' : undefined,
      placement: 'split-right', activeView: isViewed(comments), nav: stageNav(comments),
    },
    {
      key: 'merge', label: 'Merge (user-gated)', status: mergeStatus, glyph: glyphFor(mergeStatus, '◆'),
      meta: mergeMeta, placement: 'full', activeView: false,
      nav: task.prNumber !== null ? { kind: 'pr' } : { kind: 'none' },
    },
    {
      key: 'cleanup', label: 'Cleanup', status: cleanupStatus, glyph: glyphFor(cleanupStatus),
      placement: 'full', activeView: isViewed(cleanup), nav: stageNav(cleanup),
    },
  ];

  // While the active surface's agent is mid-turn, light its node so the user
  // sees it working without waiting for the stage-state poll: the Root (brain)
  // pulses purple ('planning'), a stage node pulses orange ('running').
  if (working) {
    for (const node of nodes) {
      if (node.activeView) {
        node.status = node.key === 'root' ? 'planning' : 'running';
        node.glyph = glyphFor(node.status);
      }
    }
  }

  // A stage parked for the user's approval wins over any working/state colour:
  // it needs the user, so light it orange regardless of which node is viewed.
  if (awaitingApprovalStageId !== null) {
    for (const node of nodes) {
      if (node.nav.kind === 'stage' && node.nav.stageId === awaitingApprovalStageId) {
        node.status = 'awaiting';
        node.glyph = glyphFor('awaiting');
        node.meta = 'awaiting approval';
      }
    }
  }
  return nodes;
}
