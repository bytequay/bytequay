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
import type {
  AgentRunDto, AgentRunKind, BranchGuardDto, BranchGuardState, DevPhaseDto, DevPhaseKey, ReviewRoundDto, StageDto,
  StageType, TaskPhase,
} from '../../types/brainView';

/** Visual state of one node in the live-plan diagram. {@code monitoring} is a
 *  looping stage that has pushed and is now polling remote CI / review (no
 *  local agent turn running) — distinct from {@code running} (agent working)
 *  and {@code sleep} (idle/open). */
export type LivePlanStatus =
  | 'done' | 'running' | 'monitoring' | 'planning' | 'sleep' | 'future' | 'errored'
  /** Parked for the user's approval (a gate) — rendered orange. */
  | 'awaiting';

/** Where a node sits in the lifecycle layout. `sub` is an indented `└─` row
 *  under the preceding `full` node — either the callable Review panel or a
 *  live/gated {@link AgentRunDto} sub-row (R3: lazy, shown only while live). */
export type LivePlanPlacement = 'full' | 'sub';

/** Where clicking a node navigates. `tab` force-switches the host page's own
 *  right-pane tab (e.g. the PR tab) rather than leaving the page — used by
 *  the gate nodes, which stay on whatever stage owns the composer (R27). */
export type LivePlanNav =
  | { kind: 'stage'; stageId: string }
  | { kind: 'run'; runId: string }
  | { kind: 'code' }
  | { kind: 'pr' }
  | { kind: 'tab'; tab: 'pr' }
  | { kind: 'brain' }
  | { kind: 'none' };

/** Explicit node kind (R26/R30): a 🤖 `stage` owns a conversation + composer;
 *  a ◆ `gate` opens an action surface and waits on the user; an ○ `auto`
 *  checkpoint is machine-driven with no owner of its own. */
export type LivePlanNodeType = 'stage' | 'gate' | 'auto';

/** One row of Development's in-stage phase ladder, rendered nested under the
 *  `dev` node behind a click-to-expand toggle (R29). */
export type LivePlanPhaseNode = {
  key: DevPhaseKey;
  label: string;
  status: LivePlanStatus;
  glyph: string;
  meta?: string;
  /** A live run's badge (e.g. "⚙ CI FIX · iter 2"), shown inline. */
  badge?: string;
  nav: LivePlanNav;
};

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
  nodeType: LivePlanNodeType;
  /** Development's phase ladder — present whenever the backend has phase
   *  data (live or closed); the rail shows it collapsed behind a disclosure
   *  toggle once Development is done, expandable on click. */
  phases?: LivePlanPhaseNode[];
};

/** Display data for the branch-guard chip above the rail (R4) — always
 *  present once a guard row exists (created lazily on first push), so
 *  `null` here just means "no guard yet" (task never pushed). */
export type GuardChipData = {
  state: BranchGuardState;
  label: string;
  meta: string | null;
  enabled: boolean;
};

export type LivePlanInput = {
  stages: StageDto[];
  subStages: StageDto[];
  /** The task's live-or-gated runs (R2: rail shows now, never history) —
   *  drives the Checks / Addressing sub-rows. Already filtered to
   *  live/awaiting_gate by the backend; this model doesn't re-filter by
   *  status, only by kind + which era the task is currently in. Defaults to
   *  none. */
  liveRuns?: AgentRunDto[];
  guard?: BranchGuardDto | null;
  /** The task's currently-open review round — drives the Comments node's
   *  "round N · M open" meta while it's live. */
  liveRound?: ReviewRoundDto | null;
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
  /** Development's phase ladder (Implementing/Validation/Brain review),
   *  present whenever a Development stage exists (R29). */
  devPhases?: DevPhaseDto[];
  /** The linked PR's CI status/summary — drives the CI validation checkpoint;
   *  absent/null before the task has pushed. */
  ciStatus?: 'green' | 'failing' | 'pending' | 'unknown' | null;
  ciSummary?: string | null;
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

function glyphFor(status: LivePlanStatus): string {
  if (status === 'done') return '✓';
  if (status === 'monitoring') return '◎';
  if (status === 'running' || status === 'planning' || status === 'errored' || status === 'awaiting') return '●';
  return '○';
}

/** Which spine nodes always render the 🤖 glyph — done or not, they're
 *  AI-owned stages, so the shape never swaps to ✓ (status still conveys
 *  progress via the node's color/opacity). Review (callable) is also
 *  `nodeType: 'stage'` but stays a plain status glyph. */
const ROBOT_KEYS = new Set(['root', 'dev', 'comments']);

/** Task phases during which the Development stage is the active work — the
 *  node must read "running" then even if its stage row sits OPEN between
 *  turns (a CLI subprocess turn leaves the row OPEN, not ACTIVE), which is
 *  why the plain stage-state mapping under-lit it. AWAITING_PUSH is the
 *  dev-finished, parked-for-push-approval state → "awaiting";
 *  ADDRESSING_LOCAL_COMMENTS is a reactive detour off it where the agent is
 *  actively addressing local PR comments, so it reads "running" too.
 *  PUSHED_AWAITING_CI / AWAITING_READY stay attached to Development too — a
 *  ci_fix AgentRun doesn't move the phase off them (plan-rail-runs.md R7),
 *  so Development remains the active node throughout. */
const DEV_RUNNING_PHASES = new Set<TaskPhase>(
  ['IMPLEMENTING', 'VALIDATING', 'INTERNAL_REVIEW', 'ADDRESSING_LOCAL_COMMENTS']);

/** Phases during which any live ci_fix / review activity still belongs to
 *  the dev era (Development's Checks sub-row), rather than Comments'. */
const DEV_ERA_PHASES = new Set<TaskPhase>(
  ['IMPLEMENTING', 'VALIDATING', 'INTERNAL_REVIEW', 'AWAITING_PUSH', 'ADDRESSING_LOCAL_COMMENTS',
    'PUSHED_AWAITING_CI', 'AWAITING_READY']);

function devNodeStatus(dev: StageDto | undefined, phase: TaskPhase): LivePlanStatus {
  if (dev === undefined) return 'future';
  if (dev.state === 'CLOSED') return 'done';
  if (DEV_RUNNING_PHASES.has(phase)) return 'running';
  if (phase === 'AWAITING_PUSH') return 'awaiting';
  return stageStatus(dev);
}

/** Local Review is a phase-derived milestone, not its own StageType — it
 *  tracks the INTERNAL_REVIEW portion of Development specifically (the panel
 *  review, if any, is a separate `Review (callable)` sub-row under
 *  Development either way). */
function localReviewStatus(phase: TaskPhase): LivePlanStatus {
  if (phase === 'PLANNING' || phase === 'QUEUED' || phase === 'IMPLEMENTING' || phase === 'VALIDATING') {
    return 'future';
  }
  if (phase === 'INTERNAL_REVIEW') return 'running';
  return 'done';
}

/** Status for the Comments milestone: running while a review_round works,
 *  monitoring while just watching for the next batch, otherwise falls back
 *  to the REVIEW_MONITOR_STAGE row's own state (done once merged/closed). */
function commentsStatus(
  comments: StageDto | undefined, phase: TaskPhase, hasLiveRound: boolean): LivePlanStatus {
  if (comments !== undefined && comments.state === 'CLOSED') return 'done';
  if (hasLiveRound) return 'running';
  if (phase === 'AWAITING_REMOTE_REVIEW') return 'monitoring';
  return stageStatus(comments);
}

/** Meta hint for a looping stage ("iter N"), omitted at iteration 0. */
function iterMeta(stage: StageDto | undefined): string | undefined {
  if (stage === undefined || stage.loopIteration <= 0) return undefined;
  return `iter ${stage.loopIteration}`;
}

/** The task's one live-or-gated run of `kind`, if any — AgentRunService.open
 *  is idempotent per (task, kind), so there's never more than one. */
function findLiveRun(runs: AgentRunDto[], kind: AgentRunKind): AgentRunDto | undefined {
  return runs.find(r => r.kind === kind && (r.status === 'running' || r.status === 'awaiting_gate'));
}

function runMeta(run: AgentRunDto): string | undefined {
  if (run.status === 'awaiting_gate') return 'awaiting you';
  if (run.headline !== null && run.headline !== '') return run.headline;
  return run.iterations > 0 ? `iter ${run.iterations}` : undefined;
}

/** A live/gated run's sub-row: `awaiting_gate` reads as a user gate (amber
 *  `awaiting`), otherwise `running`. */
function runSubNode(key: string, label: string, run: AgentRunDto): LivePlanNode {
  const status: LivePlanStatus = run.status === 'awaiting_gate' ? 'awaiting' : 'running';
  return {
    key, label, status, glyph: glyphFor(status), meta: runMeta(run),
    placement: 'sub', activeView: false, nav: { kind: 'run', runId: run.id }, nodeType: 'auto',
  };
}

const PHASE_LABEL: Record<DevPhaseKey, string> = {
  implementing: 'Implementing',
  validation: 'Validation',
  brainReview: 'Brain review',
};

/** Builds Development's nested phase rows from the backend's `devPhases`
 *  (already in rail vocabulary — no reinterpretation) — a phase's
 *  `badgeRunId` looks up its live run in `liveRuns` for the inline badge. */
function buildDevPhases(devPhases: DevPhaseDto[], liveRuns: AgentRunDto[]): LivePlanPhaseNode[] {
  return devPhases.map(p => {
    const status = p.status as LivePlanStatus;
    const run = p.badgeRunId !== null ? liveRuns.find(r => r.id === p.badgeRunId) : undefined;
    return {
      key: p.key, label: PHASE_LABEL[p.key], status, glyph: glyphFor(status),
      meta: p.meta ?? undefined,
      badge: run !== undefined ? runMeta(run) : undefined,
      nav: { kind: 'none' },
    };
  });
}

const GUARD_LABELS: Record<BranchGuardDto['state'], string> = {
  healthy: 'in sync with main',
  drifting: 'drifting from main',
  conflicted: 'conflicts with main',
  fixing: 'fixing drift',
  needs_attention: 'needs attention',
};

/** Derives the guard chip's display data from the raw DTO, or null when the
 *  task hasn't pushed yet (no row exists). Shown — with a toggle — even
 *  while disabled, so the user can see it's off and turn it on; hiding it
 *  outright left no way to arm a guard that never enabled itself. */
export function buildGuardChip(guard: BranchGuardDto | null | undefined): GuardChipData | null {
  if (guard === null || guard === undefined) return null;
  return {
    state: guard.state,
    label: guard.enabled ? GUARD_LABELS[guard.state] : 'guard off',
    meta: guard.lastCheckedAt !== null ? new Date(guard.lastCheckedAt).toLocaleTimeString() : null,
    enabled: guard.enabled,
  };
}

/**
 * Derives the ordered live-plan node list from the task's actual stages,
 * phase, and live runs. The spine is the 8-node checkpoint amendment
 * (plan-rail-runs.md R29): Plan → Development → Local review → Remote
 * pull request → CI validation → Comments → Merge / Close → Cleanup.
 * Development nests its own phase ladder (Implementing → Validation →
 * Brain review), which the rail keeps behind a click-to-expand toggle once
 * Development is done (R29). Review (callable) and the round-addressing run still hang
 * as lazy `sub` rows — shown only while live/gated (R2/R3).
 */
export function buildLivePlan(input: LivePlanInput): LivePlanNode[] {
  const {
    stages, subStages, liveRuns = [], liveRound = null, task, prStatus = null, mergeReady = false,
    viewedStageId = null, viewingBrain = false, working = false,
    awaitingApprovalStageId = null, devPhases = [], ciStatus = null, ciSummary = null,
  } = input;
  const byType = (type: StageType): StageDto | undefined => stages.find(s => s.type === type);
  const isViewed = (stage: StageDto | undefined): boolean =>
    stage !== undefined && stage.id === viewedStageId;
  const stageNav = (stage: StageDto | undefined): LivePlanNav =>
    stage !== undefined ? { kind: 'stage', stageId: stage.id } : { kind: 'none' };

  const plan = byType('PLAN_STAGE');
  const dev = byType('DEVELOPMENT_STAGE');
  const review = subStages.find(s => s.type === 'REVIEW_STAGE');
  const comments = byType('REVIEW_MONITOR_STAGE');
  const cleanup = byType('CLEANUP_STAGE');

  const ciFixRun = findLiveRun(liveRuns, 'ci_fix');
  const roundRun = findLiveRun(liveRuns, 'review_round');
  const devEra = DEV_ERA_PHASES.has(task.currentPhase);
  // Review (callable) is only invokable while Development is still open —
  // once it closes the row drops off the rail entirely (R3: lazy, "a nit PR
  // never grows them"), matching the mockup's zero-sub-row nit-PR rail.
  const devOpen = dev !== undefined && dev.state !== 'CLOSED';

  // The Plan node stands in for the plan "stage" — which is really the
  // brain/root conversation, not a drill-in stage. It tracks the plan's
  // progress (planning while the PlanStage is active, done once approved)
  // but navigates back to the brain page instead of a stage page.
  // A CLI subprocess turn leaves the stage row OPEN, not ACTIVE, the same
  // reason Development needs DEV_RUNNING_PHASES below — so the Plan node
  // must read "planning" straight off the phase while it's actually the
  // active work, not solely off the (possibly stale) stage row state.
  const rootStatus: LivePlanStatus =
    (plan === undefined || plan.state !== 'CLOSED') && task.currentPhase === 'PLANNING'
      ? 'planning'
      : stageStatus(plan, true);
  const devStatus = devNodeStatus(dev, task.currentPhase);
  const reviewStatus = stageStatus(review);
  const localReviewStat = localReviewStatus(task.currentPhase);
  const commentsStat = commentsStatus(comments, task.currentPhase, roundRun !== undefined);
  const cleanupStatus = stageStatus(cleanup);

  // Remote pull request isn't a stage — it's the milestone of having opened
  // the PR. Local review / Remote PR / Merge-Close all open the same PR
  // surface (PRView already renders the right actions for whatever state
  // the PR is in), so they share the same `tab` nav once there's something
  // to show.
  const prTabNav: LivePlanNav = { kind: 'tab', tab: 'pr' };
  const remotePrStatus: LivePlanStatus = task.prNumber !== null ? 'done' : 'future';

  // A live remote ci_fix run badges CI validation directly (the old
  // Comments "Checks" sub-row folded into this node instead, R29).
  const remoteCiFixRun = !devEra ? ciFixRun : undefined;
  const ciValidationStatus: LivePlanStatus = remoteCiFixRun !== undefined
    ? (remoteCiFixRun.status === 'awaiting_gate' ? 'awaiting' : 'running')
    : task.prNumber === null ? 'future'
      : ciStatus === 'green' ? 'done'
        : ciStatus === 'failing' ? 'errored'
          : ciStatus === 'pending' ? 'running'
            : 'sleep';
  const ciValidationMeta = remoteCiFixRun !== undefined ? runMeta(remoteCiFixRun) : (ciSummary ?? undefined);

  // Merge/Close is user-gated and pseudo: done once merged/completed, running
  // while it's queued, sleeping while it's out for remote review, future
  // otherwise.
  const mergeStatus: LivePlanStatus =
    task.currentPhase === 'COMPLETED' || prStatus === 'merged' ? 'done'
      : prStatus === 'queued' ? 'running'
        : mergeReady ? 'monitoring'
          : task.currentPhase === 'AWAITING_REMOTE_REVIEW'
            ? 'sleep'
            : 'future';
  const mergeMeta = prStatus === 'queued' ? 'queued'
    : mergeStatus === 'done' ? 'merged'
      : mergeStatus === 'monitoring' ? 'ready to merge'
        : mergeStatus === 'sleep' ? 'awaiting' : undefined;

  const nodes: LivePlanNode[] = [
    {
      key: 'root', label: 'Root', status: rootStatus, glyph: '🤖',
      meta: rootStatus === 'done' ? 'plan approved' : 'planning',
      placement: 'full', activeView: viewingBrain, nav: { kind: 'brain' }, nodeType: 'stage',
    },
    {
      key: 'dev', label: 'Development', status: devStatus, glyph: '🤖',
      meta: iterMeta(dev), placement: 'full', activeView: isViewed(dev), nav: stageNav(dev),
      nodeType: 'stage',
      phases: devPhases.length > 0 ? buildDevPhases(devPhases, liveRuns) : undefined,
    },
    ...(devOpen ? [{
      key: 'review', label: 'Review (callable)', status: reviewStatus,
      glyph: glyphFor(reviewStatus), meta: review === undefined ? 'not invoked' : undefined,
      placement: 'sub' as const, activeView: isViewed(review), nav: stageNav(review),
      nodeType: 'stage' as const,
    }] : []),
    {
      key: 'local-review', label: 'Local review', status: localReviewStat,
      glyph: '◆', meta: localReviewStat === 'done' ? 'approved' : undefined,
      placement: 'full', activeView: false, nodeType: 'gate',
      nav: dev !== undefined ? prTabNav : { kind: 'none' },
    },
    {
      key: 'remote-pr', label: 'Remote pull request', status: remotePrStatus, glyph: '◆',
      meta: task.prNumber !== null ? `PR #${task.prNumber}` : undefined,
      placement: 'full', activeView: false, nodeType: 'gate',
      nav: task.prNumber !== null ? prTabNav : { kind: 'none' },
    },
    {
      key: 'ci-validation', label: 'CI validation', status: ciValidationStatus,
      glyph: glyphFor(ciValidationStatus), meta: ciValidationMeta,
      placement: 'full', activeView: false, nodeType: 'auto',
      nav: task.prNumber !== null ? { kind: 'pr' } : { kind: 'none' },
    },
    {
      key: 'comments', label: 'Comments', status: commentsStat, glyph: '🤖',
      meta: liveRound !== null ? `round ${liveRound.idx} · ${liveRound.stats.open} open`
        : comments === undefined ? undefined
          : commentsStat === 'monitoring' ? 'watching review'
            : commentsStat === 'sleep' ? 'armed' : undefined,
      placement: 'full', activeView: isViewed(comments), nav: stageNav(comments), nodeType: 'stage',
    },
    ...(!devEra && roundRun !== undefined ? [runSubNode('comments-addressing', 'Addressing', roundRun)] : []),
    {
      key: 'merge-close', label: 'Merge / Close', status: mergeStatus, glyph: '◆',
      meta: mergeMeta, placement: 'full', activeView: false, nodeType: 'gate',
      nav: task.prNumber !== null ? prTabNav : { kind: 'none' },
    },
    {
      key: 'cleanup', label: 'Cleanup', status: cleanupStatus, glyph: glyphFor(cleanupStatus),
      placement: 'full', activeView: isViewed(cleanup), nav: stageNav(cleanup), nodeType: 'auto',
    },
  ];

  // While the active surface's agent is mid-turn, light its node so the user
  // sees it working without waiting for the stage-state poll: the Plan node
  // pulses purple ('planning'), a stage node pulses orange ('running').
  if (working) {
    for (const node of nodes) {
      if (node.activeView) {
        node.status = node.key === 'root' ? 'planning' : 'running';
        node.glyph = ROBOT_KEYS.has(node.key) ? '🤖' : glyphFor(node.status);
      }
    }
  }

  // A stage parked for the user's approval wins over any working/state colour:
  // it needs the user, so light it orange regardless of which node is viewed.
  if (awaitingApprovalStageId !== null) {
    for (const node of nodes) {
      if (node.nav.kind === 'stage' && node.nav.stageId === awaitingApprovalStageId) {
        node.status = 'awaiting';
        node.glyph = ROBOT_KEYS.has(node.key) ? '🤖' : glyphFor('awaiting');
        node.meta = 'awaiting approval';
      }
    }
  }
  return nodes;
}
