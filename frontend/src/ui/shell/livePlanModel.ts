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
  AgentRunDto, AgentRunKind, BranchGuardDto, BranchGuardState, DevPhaseDto, ReviewRoundDto, StageDto,
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
 *  under the preceding `full` node — a live/gated {@link AgentRunDto}
 *  sub-row (R3: lazy, shown only while live). */
export type LivePlanPlacement = 'full' | 'sub';

/** Where clicking a node navigates. `tab` force-switches the host page's own
 *  right-pane tab (e.g. the PR tab) rather than leaving the page — used by
 *  the gate nodes, which stay on whatever stage owns the composer (R27). */
export type LivePlanNav =
  | { kind: 'stage'; stageId: string }
  | { kind: 'run'; runId: string }
  | { kind: 'pr' }
  /** `subTab: 'checks'` also forces the PR tab's own Checks sub-tab open
   *  (the Remote CI row) — see PRView's `openSubTabRequest`. */
  | { kind: 'tab'; tab: 'pr'; subTab?: 'checks' }
  | { kind: 'brain' }
  | { kind: 'none' };

/** Explicit node kind (R26/R30): a 🤖 `stage` owns a conversation + composer;
 *  a ◆ `gate` opens an action surface and waits on the user; an ○ `auto`
 *  checkpoint is machine-driven with no owner of its own. */
export type LivePlanNodeType = 'stage' | 'gate' | 'auto';

/** One row in a lifecycle node's phase ladder, rendered nested under one of
 *  the four top-level task phases behind a click-to-expand toggle. */
export type LivePlanPhaseNode = {
  key: string;
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
  /** Nested task-phase rows for this top-level lifecycle node. The rail shows
   *  closed nodes collapsed behind a disclosure toggle, expandable on click. */
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
  /** The task's live-or-gated runs (rail shows now, never history) — drives
   *  CI/comment phase rows. Already filtered to live/awaiting_gate by the
   *  backend; this model doesn't re-filter by status. Defaults to none. */
  liveRuns?: AgentRunDto[];
  guard?: BranchGuardDto | null;
  /** The task's currently-open review round — drives the Remote Development
   *  comments row's "round N · M open" meta while it's live. */
  liveRound?: ReviewRoundDto | null;
  task: { prNumber: number | null; currentPhase: TaskPhase; terminal: boolean };
  /** The PR's merge-state, when known — drives the Merge / Close row. */
  prStatus?: 'open' | 'draft' | 'queued' | 'merged' | null;
  /** True when a ready-to-merge gate is open (CI green, no unresolved
   *  comments, mergeable) — lights the Merge / Close row as actionable. */
  mergeReady?: boolean;
  /** The stage currently open on screen, highlighted as the active view. */
  viewedStageId?: string | null;
  /** True on the brain page — marks the Plan node as the active view (the
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
  /** Local Development's core phase ladder (Implementing/Validation/Brain
   *  review), present whenever a Development stage exists. */
  devPhases?: DevPhaseDto[];
  /** The linked PR's CI status/summary — drives the Remote CI checkpoint;
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
 *  progress via the node's color/opacity). */
const ROBOT_KEYS = new Set(['plan', 'local-development', 'remote-development']);

/** Task phases during which the Development stage is the active work — the
 *  node must read "running" then even if its stage row sits OPEN between
 *  turns (a CLI subprocess turn leaves the row OPEN, not ACTIVE), which is
 *  why the plain stage-state mapping under-lit it. AWAITING_PUSH is the
 *  dev-finished, parked-for-push-approval state → "awaiting";
 *  ADDRESSING_LOCAL_COMMENTS is a reactive detour off it where the agent is
 *  actively addressing local PR comments, so it reads "running" too. */
const DEV_RUNNING_PHASES = new Set<TaskPhase>(
  ['IMPLEMENTING', 'VALIDATING', 'INTERNAL_REVIEW', 'ADDRESSING_LOCAL_COMMENTS']);

const REMOTE_DEVELOPMENT_PHASES = new Set<TaskPhase>(
  ['PUSHED_AWAITING_CI', 'AWAITING_READY', 'AWAITING_REMOTE_REVIEW']);

function devNodeStatus(dev: StageDto | undefined, phase: TaskPhase): LivePlanStatus {
  if (dev === undefined) return 'future';
  if (DEV_RUNNING_PHASES.has(phase)) return 'running';
  if (phase === 'AWAITING_PUSH') return 'awaiting';
  if (dev.state === 'CLOSED') return 'done';
  return stageStatus(dev);
}

function remoteRunStatus(...runs: Array<AgentRunDto | undefined>): LivePlanStatus | null {
  const live = runs.filter((r): r is AgentRunDto => r !== undefined);
  if (live.length === 0) return null;
  return live.some(r => r.status === 'awaiting_gate') ? 'awaiting' : 'running';
}

function remoteDevelopmentStatus(
  remote: StageDto | undefined,
  phase: TaskPhase,
  prNumber: number | null,
  liveRunStatus: LivePlanStatus | null,
): LivePlanStatus {
  if (liveRunStatus !== null) return liveRunStatus;
  if (REMOTE_DEVELOPMENT_PHASES.has(phase)) return 'monitoring';
  if (remote !== undefined) return stageStatus(remote);
  if (phase === 'COMPLETED' && prNumber !== null) return 'done';
  if (prNumber !== null) return 'sleep';
  return 'future';
}

/** Local Review is a phase-derived milestone, not its own StageType — it
 *  tracks the INTERNAL_REVIEW portion of Development specifically. */
function localReviewStatus(phase: TaskPhase): LivePlanStatus {
  if (phase === 'PLANNING' || phase === 'QUEUED' || phase === 'IMPLEMENTING' || phase === 'VALIDATING') {
    return 'future';
  }
  if (phase === 'INTERNAL_REVIEW') return 'running';
  return 'done';
}

/** Status for the Remote Development comments row: running while a review
 *  round works, monitoring while just watching for the next batch, otherwise
 *  falling back to the Remote Development stage row's own state. */
function commentsStatus(
  remote: StageDto | undefined,
  phase: TaskPhase,
  roundRun: AgentRunDto | undefined,
  hasLiveRound: boolean,
): LivePlanStatus {
  if (roundRun !== undefined) return roundRun.status === 'awaiting_gate' ? 'awaiting' : 'running';
  if (hasLiveRound) return 'running';
  if (phase === 'AWAITING_REMOTE_REVIEW') return 'monitoring';
  if (remote !== undefined && remote.state === 'CLOSED') return 'done';
  return stageStatus(remote);
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

const PHASE_LABEL: Record<DevPhaseDto['key'], string> = {
  implementing: 'Implementing',
  validation: 'Validation',
  brainReview: 'Brain review',
};

/** Builds Local Development's nested work rows from the backend's `devPhases`
 *  (already in rail vocabulary — no reinterpretation) — a phase's
 *  `badgeRunId` looks up its live run in `liveRuns` for the inline badge. */
function buildDevCorePhases(devPhases: DevPhaseDto[], liveRuns: AgentRunDto[]): LivePlanPhaseNode[] {
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

function derivedDevCorePhases(phase: TaskPhase): LivePlanPhaseNode[] {
  const implementing: LivePlanStatus =
    phase === 'PLANNING' || phase === 'QUEUED' ? 'future'
      : phase === 'IMPLEMENTING' ? 'running' : 'done';
  const validation: LivePlanStatus =
    phase === 'VALIDATING' ? 'running'
      : phase === 'PLANNING' || phase === 'QUEUED' || phase === 'IMPLEMENTING' ? 'future' : 'done';
  const brainReview: LivePlanStatus =
    phase === 'INTERNAL_REVIEW' ? 'running'
      : phase === 'PLANNING' || phase === 'QUEUED' || phase === 'IMPLEMENTING' || phase === 'VALIDATING'
        ? 'future' : 'done';
  return [
    { key: 'implementing', label: 'Implementing', status: implementing, glyph: glyphFor(implementing), nav: { kind: 'none' } },
    { key: 'validation', label: 'Validation', status: validation, glyph: glyphFor(validation), nav: { kind: 'none' } },
    { key: 'brainReview', label: 'Brain review', status: brainReview, glyph: glyphFor(brainReview), nav: { kind: 'none' } },
  ];
}

function pushPrStatus(phase: TaskPhase, prNumber: number | null): LivePlanStatus {
  if (prNumber !== null) return 'done';
  if (phase === 'AWAITING_PUSH') return 'awaiting';
  if (REMOTE_DEVELOPMENT_PHASES.has(phase)) return 'sleep';
  return 'future';
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
export function buildGuardChip(guard: BranchGuardDto | null | undefined, taskTerminal = false): GuardChipData | null {
  if (taskTerminal || guard === null || guard === undefined) return null;
  return {
    state: guard.state,
    label: guard.enabled ? GUARD_LABELS[guard.state] : 'guard off',
    meta: guard.lastCheckedAt !== null ? new Date(guard.lastCheckedAt).toLocaleTimeString() : null,
    enabled: guard.enabled,
  };
}

/**
 * Derives the ordered live-plan node list from the task's actual stages,
 * phase, and live runs. The spine is the four-stage lifecycle:
 * Plan → Local Development → Remote Development → Cleanup. The older
 * checkpoints now render as nested phase rows inside Local/Remote
 * Development so CI and reviewer-comment work stays visually attached to
 * the stage whose agent owns that loop.
 */
export function buildLivePlan(input: LivePlanInput): LivePlanNode[] {
  const {
    stages, liveRuns: inputLiveRuns = [], liveRound: inputLiveRound = null, task, prStatus = null, mergeReady = false,
    viewedStageId = null, viewingBrain = false, working = false,
    awaitingApprovalStageId = null, devPhases = [], ciStatus = null, ciSummary = null,
  } = input;
  const liveRuns = task.terminal ? [] : inputLiveRuns;
  const liveRound = task.terminal ? null : inputLiveRound;
  const byType = (type: StageType): StageDto | undefined => stages.find(s => s.type === type);
  const isViewed = (stage: StageDto | undefined): boolean =>
    stage !== undefined && stage.id === viewedStageId;
  const stageNav = (stage: StageDto | undefined): LivePlanNav =>
    stage !== undefined ? { kind: 'stage', stageId: stage.id } : { kind: 'none' };

  const plan = byType('PLAN_STAGE');
  const dev = byType('DEVELOPMENT_STAGE');
  const remote = byType('REMOTE_DEVELOPMENT_STAGE') ?? byType('REVIEW_MONITOR_STAGE');
  const cleanup = byType('CLEANUP_STAGE');

  const ciFixRun = findLiveRun(liveRuns, 'ci_fix');
  const roundRun = findLiveRun(liveRuns, 'review_round');
  const remoteCiFixRun = REMOTE_DEVELOPMENT_PHASES.has(task.currentPhase) || task.prNumber !== null
    ? ciFixRun
    : undefined;
  const remoteLiveStatus = remoteRunStatus(remoteCiFixRun, roundRun);

  // The Plan node stands in for the plan "stage" — which is really the
  // brain/root conversation, not a drill-in stage. It tracks the plan's
  // progress (planning while the PlanStage is active, done once approved)
  // but navigates back to the brain page instead of a stage page.
  // A CLI subprocess turn leaves the stage row OPEN, not ACTIVE, the same
  // reason Local Development needs DEV_RUNNING_PHASES below — so the Plan node
  // must read "planning" straight off the phase while it's actually the
  // active work, not solely off the (possibly stale) stage row state.
  const planStatus: LivePlanStatus =
    (plan === undefined || plan.state !== 'CLOSED') && task.currentPhase === 'PLANNING'
      ? 'planning'
      : stageStatus(plan, true);
  const devStatus = devNodeStatus(dev, task.currentPhase);
  const localReviewStat = localReviewStatus(task.currentPhase);
  const remoteStatus = remoteDevelopmentStatus(remote, task.currentPhase, task.prNumber, remoteLiveStatus);
  const commentsStat = commentsStatus(remote, task.currentPhase, roundRun, liveRound !== null);
  const cleanupStatus = stageStatus(cleanup);

  // Local review / Remote PR / Merge-Close all open the same PR surface
  // (PRView already renders the right actions for whatever state the PR is
  // in), so they share the same `tab` nav once there's something to show.
  const prTabNav: LivePlanNav = { kind: 'tab', tab: 'pr' };
  const remotePrStatus: LivePlanStatus = task.prNumber !== null ? 'done' : 'future';

  const ciValidationStatus: LivePlanStatus = remoteCiFixRun !== undefined
    ? (remoteCiFixRun.status === 'awaiting_gate' ? 'awaiting' : 'running')
    : task.prNumber === null ? 'future'
      : ciStatus === 'green' ? 'done'
        : ciStatus === 'failing' ? 'errored'
          : ciStatus === 'pending' ? 'running'
            : 'sleep';
  const ciValidationMeta = remoteCiFixRun !== undefined ? runMeta(remoteCiFixRun) : (ciSummary ?? undefined);
  const ciValidationNav: LivePlanNav = remoteCiFixRun !== undefined
    ? { kind: 'run', runId: remoteCiFixRun.id }
    : task.prNumber !== null && !(ciValidationStatus === 'sleep' && task.terminal)
      ? { kind: 'tab', tab: 'pr', subTab: 'checks' }
      : { kind: 'none' };

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

  const localCorePhases = devPhases.length > 0 ? buildDevCorePhases(devPhases, liveRuns) : derivedDevCorePhases(task.currentPhase);
  const pushStatus = pushPrStatus(task.currentPhase, task.prNumber);
  const localPhases: LivePlanPhaseNode[] = [
    ...localCorePhases,
    {
      key: 'local-review',
      label: 'Local review',
      status: localReviewStat,
      glyph: localReviewStat === 'done' ? '✓' : '◆',
      meta: localReviewStat === 'done' ? 'approved' : localReviewStat === 'running' ? 'in review' : undefined,
      nav: dev !== undefined ? prTabNav : { kind: 'none' },
    },
    {
      key: 'push-pr',
      label: 'Push / PR',
      status: pushStatus,
      glyph: pushStatus === 'done' ? '✓' : '◆',
      meta: task.prNumber !== null ? `PR #${task.prNumber}` : pushStatus === 'awaiting' ? 'awaiting push' : undefined,
      nav: dev !== undefined ? prTabNav : { kind: 'none' },
    },
  ];

  const commentsMeta = liveRound !== null ? `round ${liveRound.idx} · ${liveRound.stats.open} open`
    : roundRun !== undefined ? runMeta(roundRun)
      : commentsStat === 'monitoring' ? 'watching review'
        : commentsStat === 'sleep' ? 'armed' : undefined;
  const remotePhases: LivePlanPhaseNode[] = [
    {
      key: 'remote-pr',
      label: 'Remote PR',
      status: remotePrStatus,
      glyph: remotePrStatus === 'done' ? '✓' : '◆',
      meta: task.prNumber !== null ? `PR #${task.prNumber}` : undefined,
      nav: task.prNumber !== null ? prTabNav : { kind: 'none' },
    },
    {
      key: 'ci-validation',
      label: 'Remote CI',
      status: ciValidationStatus,
      glyph: glyphFor(ciValidationStatus),
      meta: ciValidationMeta,
      nav: ciValidationNav,
    },
    {
      key: 'comments',
      label: 'Review comments',
      status: commentsStat,
      glyph: roundRun !== undefined ? glyphFor(commentsStat) : '🤖',
      meta: commentsMeta,
      nav: roundRun !== undefined ? { kind: 'run', runId: roundRun.id } : stageNav(remote),
    },
    {
      key: 'merge-close',
      label: 'Merge / Close',
      status: mergeStatus,
      glyph: mergeStatus === 'done' ? '✓' : '◆',
      meta: mergeMeta,
      nav: task.prNumber !== null ? prTabNav : { kind: 'none' },
    },
  ];

  const remoteMeta = remoteLiveStatus === 'awaiting' ? 'awaiting you'
    : remoteCiFixRun !== undefined ? runMeta(remoteCiFixRun)
      : roundRun !== undefined ? runMeta(roundRun)
        : task.currentPhase === 'PUSHED_AWAITING_CI' ? 'checking CI'
          : task.currentPhase === 'AWAITING_REMOTE_REVIEW' ? 'watching review'
            : iterMeta(remote);

  const nodes: LivePlanNode[] = [
    {
      key: 'plan', label: 'Plan', status: planStatus, glyph: '🤖',
      meta: planStatus === 'done' ? 'approved' : 'planning',
      placement: 'full', activeView: viewingBrain, nav: { kind: 'brain' }, nodeType: 'stage',
    },
    {
      key: 'local-development', label: 'Local Development', status: devStatus, glyph: '🤖',
      meta: devStatus === 'awaiting' ? 'awaiting push' : iterMeta(dev),
      placement: 'full', activeView: isViewed(dev), nav: stageNav(dev),
      nodeType: 'stage',
      phases: localPhases,
    },
    {
      key: 'remote-development', label: 'Remote Development', status: remoteStatus, glyph: '🤖',
      meta: remoteMeta, placement: 'full', activeView: isViewed(remote), nav: stageNav(remote), nodeType: 'stage',
      phases: remotePhases,
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
        node.status = node.key === 'plan' ? 'planning' : 'running';
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
