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

/** Visual state of one node in the live-plan diagram. */
export type LivePlanStatus = 'done' | 'running' | 'planning' | 'sleep' | 'future' | 'errored';

/** Where a node sits in the lifecycle layout. `split-*` are the parallel
 *  CI-Fix / Comments branch; `sub` is the indented Review (callable) node. */
export type LivePlanPlacement = 'full' | 'sub' | 'split-left' | 'split-right';

/** Where clicking a node navigates. */
export type LivePlanNav =
  | { kind: 'stage'; stageId: string }
  | { kind: 'code' }
  | { kind: 'pr' }
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
  /** The stage currently open on screen, highlighted as the active view. */
  viewedStageId?: string | null;
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
  if (status === 'running' || status === 'planning' || status === 'errored') return '●';
  if (status === 'sleep') return '○';
  return future;
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
  const { stages, subStages, task, prStatus = null, viewedStageId = null } = input;
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

  const planStatus = stageStatus(plan, true);
  const devStatus = stageStatus(dev);
  const reviewStatus = stageStatus(review);
  const ciStatus = stageStatus(ciFix);
  const commentsStatus = stageStatus(comments);
  const cleanupStatus = stageStatus(cleanup);

  // Push isn't a stage — it's the milestone of having opened the PR.
  const pushStatus: LivePlanStatus = task.prNumber !== null ? 'done' : 'future';

  // Merge is user-gated and pseudo: done once merged/completed, running while
  // it's queued, sleeping while it's out for remote review, future otherwise.
  const mergeStatus: LivePlanStatus =
    task.currentPhase === 'COMPLETED' || prStatus === 'merged' ? 'done'
      : prStatus === 'queued' ? 'running'
        : task.currentPhase === 'AWAITING_REMOTE_REVIEW' || task.currentPhase === 'ADDRESSING_COMMENTS'
          ? 'sleep'
          : 'future';
  const mergeMeta = prStatus === 'queued' ? 'queued'
    : mergeStatus === 'done' ? 'merged'
      : mergeStatus === 'sleep' ? 'awaiting' : undefined;

  const nodes: LivePlanNode[] = [
    {
      key: 'plan', label: 'Plan', status: planStatus, glyph: glyphFor(planStatus),
      meta: planStatus === 'done' ? 'done' : undefined,
      placement: 'full', activeView: isViewed(plan), nav: stageNav(plan),
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
      meta: iterMeta(ciFix), placement: 'split-left', activeView: isViewed(ciFix), nav: stageNav(ciFix),
    },
    {
      key: 'comments', label: 'Comments', status: commentsStatus, glyph: glyphFor(commentsStatus),
      meta: comments === undefined ? undefined : commentsStatus === 'sleep' ? 'armed' : undefined,
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
  return nodes;
}
