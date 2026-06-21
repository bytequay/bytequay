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

/**
 * Wire format for the task brain view — the LOCKED contract shared with
 * the backend. The brain endpoint produces these shapes; the mock hook
 * returns them; the eventual real-data swap consumes them. Do not add
 * fields here without coordinating the change on both sides — a field
 * the UI needs that isn't here is a design question, not a local patch.
 *
 * Source: GET /api/tasks/{taskId}/brain
 */

/** Lifecycle phases an agentic task moves through. Mirrors the backend
 *  TaskPhase enum; server-computed and surfaced as a status label. */
export type TaskPhase =
  | 'IMPLEMENTING' | 'VALIDATING' | 'INTERNAL_REVIEW' | 'AWAITING_PUSH'
  | 'PUSHED_AWAITING_CI' | 'CI_FIXING' | 'AWAITING_READY'
  | 'AWAITING_REMOTE_REVIEW' | 'ADDRESSING_COMMENTS' | 'AGENT_RE_REVIEW'
  | 'AWAITING_UPDATE_PUSH' | 'COMPLETED' | 'NEEDS_ATTENTION' | 'QUEUED';

export type StageType =
  | 'DEVELOPMENT_STAGE' | 'CI_FIXING_STAGE' | 'REVIEW_MONITOR_STAGE'
  | 'CLEANUP_STAGE' | 'REVIEW_STAGE';

export type StageState = 'OPEN' | 'ACTIVE' | 'PAUSED' | 'CLOSED';

export type StageDto = {
  id: string;
  taskId: string;
  type: StageType;
  state: StageState;
  openedAt: string;                   // ISO 8601
  closedAt: string | null;
  callerStageId: string | null;       // back-pointer for sub-stages
  summary: string;                    // brain-view summary line
  loopIteration: number;              // current iteration (0 for non-loop stages)
  // Per-stage metrics live on the stage detail endpoint, not here.
};

export type BrainFeedRowType =
  | 'STAGE_OPENED' | 'STAGE_CLOSED' | 'PANEL_REVIEW_COMPLETED'
  | 'PUSHED_PR_CREATED' | 'ITERATION_SUMMARY' | 'USER_MESSAGE'
  | 'BRAIN_AGENT_RESPONSE' | 'NEEDS_ATTENTION' | 'NOTIFY_READY_FOR_MERGE';

export type BrainFeedRow = {
  id: string;
  type: BrainFeedRowType;
  stageId: string | null;             // null only for USER_MESSAGE/BRAIN_AGENT_RESPONSE
  stageType: StageType | null;        // mirrors stageId for the row's stage tag
  ts: string;                         // ISO 8601
  body: string;                       // markdown
  referencedStageId: string | null;   // for the "🔍 Open stage" drill-in chip
};

export type ApprovalDto = {
  stageId: string;
  stageTitle: string;                 // "CiFixingStage · iter #3 · push"
  reasonShort: string;                // "Auto-push budget exhausted (5/5)"
  pendingArtifact: string;            // "CostMeter.tsx — remove unused import"
  primaryAction: { label: string; href: string };  // "Review & approve push"
};

export type LinkedPrDto = {
  number: number;
  branch: string;
  status: 'draft' | 'open' | 'merged' | 'closed';
  ciStatus: 'green' | 'failing' | 'pending' | 'unknown';
  ciSummary: string;                  // "1 failing"
  reviewersApproved: number;
  reviewersTotal: number;
  conflictsState: 'none' | 'has_conflicts' | 'unknown';
  mergeable: boolean;
};

export type ContextWindowDto = {
  tokensUsed: number;
  tokensLimit: number;
  safeBand: 'safe' | 'warn' | 'danger';   // server-classified
};

export type CommitDto = {
  sha: string;
  subject: string;
  authoredAt: string;                 // ISO 8601
};

export type ScrubberDash = {
  id: string;                         // anchor to scroll to
  label: string;                      // hover tooltip text
  active: boolean;                    // only one row should have active=true
};

export type TaskBrainViewData = {
  task: {
    id: string;                       // UUID
    title: string;                    // "Cost-meter widget · workspace sidebar"
    taskNumber: number;               // 2  (sequential within thread)
    branch: string;                   // "jack/cost-meter"
    repoFullName: string;             // "trinodb/trino"
    prNumber: number | null;          // 5680
    prDraft: boolean;
    currentPhase: TaskPhase;
    statusLabel: string;              // "CI FIXING · iter #3" (server-computed)
    agentRuntime: 'CLI' | 'API';
    agentModel: string;               // "sonnet-3.7"
    paused: boolean;                  // true at TaskStatus.PAUSED → rail shows Resume
  };
  aggregate: {
    pushes: number;
    activeTimeSec: number;
    waitingUserTimeSec: number;
    toolCalls: number;
    turns: number;
    messages: number;
    panels: number;                   // ReviewStage instance count
    costCents: number;
    autoPushBudget: {                 // CiFixingStage's current budget
      used: number;
      limit: number;
    } | null;                         // null if no CiFixingStage exists yet
  };
  stages: StageDto[];                 // top-level stages (callerStageId === null)
  subStages: StageDto[];              // ReviewStage instances (callerStageId !== null)
  brainFeed: BrainFeedRow[];          // ORDER: chronological ascending
  rightRail: {
    approval: ApprovalDto | null;     // present when any stage is in NEEDS_ATTENTION
    linkedPr: LinkedPrDto | null;
    context: ContextWindowDto;        // brain-agent's own (Task, Agent) context
    recentCommits: CommitDto[];       // limit 5
    panelSpawnable: boolean;          // true in an internal-review phase over a PR
    parentStageId: string | null;     // the stage a panel review is called from
  };
  scrubbers: {
    stageEvents: ScrubberDash[];      // for the LEFT scrubber
    userMessages: ScrubberDash[];     // for the RIGHT scrubber
  };
};

/** Result of posting a brain message: the answering turn id and the
 *  task's brain thread id (to subscribe to its SSE stream). */
export type BrainMessageResult = {
  turnId: string;
  brainThreadId: string;
};

/** Handles returned by spawning a panel review: the opened review stage,
 *  the seated pass, and the review thread the panel page routes by. */
export type SpawnReviewResult = {
  reviewStageId: string;
  reviewPassId: string;
  reviewThreadId: string;
};

// ── Stage detail (drill-in page) ─────────────────────────────────────

export type StageDetailData = {
  task: {
    id: string;
    taskNumber: number;
    title: string;
    branch: string;
    repoFullName: string;
    prNumber: number | null;
    prDraft: boolean;
    currentPhase: string;
    agentRuntime: 'CLI' | 'API';
    agentModel: string;
  };
  stage: {
    id: string;
    type: StageType;
    state: StageState;
    openedAt: string;
    closedAt: string | null;
    callerStageId: string | null;
    iterationCount: number;
    currentIterationNumber: number | null;
    config: {
      autoPushBudget?: { used: number; limit: number };
      internalReviewEnabled: boolean;
    };
    metrics: StageMetricsSubset;
  };
  allStages: StageDto[];
  subStages: StageDto[];
  iterations: IterationDetail[];
  realtimeCi: RealtimeCi | null;
  ciFixHistory: CiFixHistoryEntry[];
  context: ContextWindowDto;
  scrubber: { userMessages: ScrubberDash[] };
};

/** Uncomputed catalog fields are absent (not zero); panelInvocationsCount
 *  is a genuine 0 until review panels exist. */
export type StageMetricsSubset = {
  wallTimeSec?: number;
  loopIterations?: number;
  toolCallsCount?: number;
  turnsCount?: number;
  messagesCount?: number;
  tokensCount?: number;
  costCents?: number;
  panelInvocationsCount: number;
  activeTimeSec?: number;
  waitingUserTimeSec?: number;
  operationsCount?: Record<string, number>;  // by inferred op kind
  interventionsCount?: number;
  backflowsCount?: number;
  terminalState?: 'succeeded' | 'failed' | 'paused' | 'aborted';
};

export type IterationDetail = {
  id: string;
  iterationNumber: number;
  trigger: string;
  startedAt: string;
  endedAt: string | null;
  endedReason: string | null;
  summaryText: string | null;
  recordedBy: 'agent' | 'orchestrator_fallback' | 'synthesized' | null;
  log: StageLogRow[];
};

export type StageLogRow = {
  id: string;
  ts: string;
  kind: 'tool_call' | 'stage_event' | 'iteration_summary' | 'user_message' | 'operation';
  toolCall?: { tag: string; label: string; detail: string | null };
  stageEvent?: { eventType: string; message: string; dataJson: string | null };
  iterationSummary?: { text: string; recordedBy: string | null; recordedAt: string };
  userMessage?: { text: string };
  // Operation card: a run of same-kind tool calls grouped at read time.
  operation?: {
    operation: 'code' | 'validate' | 'push' | 'publish' | string;
    startedAt: string;
    completedAt: string;
    durationSec: number;
    toolCallCount: number;
    status: 'ok' | 'failed' | string;
    toolCalls: StageLogRow[];
  };
};

export type RealtimeCi = {
  status: 'green' | 'failing' | 'pending' | 'unknown';
  prUrl: string;
  checks: Array<{ name: string; status: 'ok' | 'fail' | 'pending'; durationSec: number | null }>;
  lastPolledAt: string;
};

export type CiFixHistoryEntry = {
  iterationNumber: number;
  endedReason: string | null;
  summaryText: string | null;
  failedCheck?: string;     // enriched red-CI iters; absent on older ones
  errorMessage?: string;    // full text for the hover tooltip
  actionsRunUrl?: string;   // GitHub Actions run link
};
