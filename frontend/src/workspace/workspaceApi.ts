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
  BacklogItemDto,
  IssueCommentDto,
  IssueDetailDto,
  IssueDto,
  LocalBranchDto,
  LocalCommitDto,
  LocalCommitFileDto,
  LocalFileDiffDto,
  LocalRepoStatusDto,
  PullRequestDto,
  WorkspaceCardDto,
  WorkspaceDto,
} from '../types';

export type WorkspaceSettingsDto = {
  sessionCapUsd: number;
  dailyCapUsd: number;
  pauseAtCap: boolean;
  /** Null or absent inherits the application workspace ceiling. */
  maxRunningTasks?: number | null;
  syncSeconds: number;
  brainBudgetChars: number;
  distillMinutes: number;
  kbAudiences: Array<'plan' | 'dev' | 'review' | 'ci-fix'>;
  providers: Record<string, string>;
  notifyCi: boolean;
  notifyCompletions: boolean;
  /** Missing on settings rows written before workspace automation existed. */
  qualityScanEnabled?: boolean;
  remoteIssueIntakeEnabled?: boolean;
};

type WorkspaceAutomationJobStatusDto = {
  enabled: boolean;
  eligible: boolean;
  reason: string | null;
  running: boolean;
  lastRunAt: string | null;
  expectedNextRunAt: string | null;
  lastOutcome: 'SUCCESS' | 'PAUSED' | 'FAILED' | null;
  lastError: string | null;
};

export type WorkspaceAutomationStatusDto = {
  qualityScan: WorkspaceAutomationJobStatusDto & {
    findingsProposed: number;
  };
  remoteIssueIntake: WorkspaceAutomationJobStatusDto & {
    issuesExamined: number;
    tasksQueued: number;
    implementationsStarted: number;
  };
};

export type WorkspaceOnboardingDto = {
  workspaceId: string;
  cloneComplete: boolean;
  syncState: string;
  syncCurrent: number;
  syncTotal: number;
  memorySeedComplete: boolean;
  firstTrunkComplete: boolean;
  memoryImported: boolean;
  learningState: string | null;
  /** Present when the latest learning run is paused by a fetch/extraction failure. */
  learningLastError?: string | null;
  learningCataloged: number;
  learningAnalyzed: number;
  learningLessons: number;
  learningPendingLessons: number;
  dismissedAt: number | null;
  updatedAt: number;
};

export type DirectoryScopeSuggestionDto = {
  name: string;
  paths: string[];
  evidencePrCount: number;
  confidence: number;
  rationale: string;
  decisionState: 'pending' | 'approved' | 'rejected';
};

export type DirectoryScopeAssignmentDto = {
  threadId: string;
  name: string;
  paths: string[];
  decisionState: 'approved';
  assignedAtMs: number;
};

export type DirectoryScopeOverviewDto = {
  catalogedPrCount: number;
  analyzedPrCount: number;
  requiredAnalyzedPrCount: number;
  historyReady: boolean;
  suggestions: DirectoryScopeSuggestionDto[];
  assignments: DirectoryScopeAssignmentDto[];
};

export type LearnedKnowledgeDto = {
  id: string;
  kind: string;
  title: string | null;
  statement: string;
  rationale: string | null;
  confidence: string;
  lifecycle: 'pending' | 'active' | 'decayed' | 'retired';
  audience: string[];
  sources: { kind: string; ref: string; url?: string; path?: string }[];
  validatedAtCommit: string | null;
  updatedAt: number;
};

export type WorkspaceCreationDto = {
  id: string;
  operationKind: string;
  owner: string;
  repo: string;
  writeMode: string;
  state: 'queued' | 'forking' | 'cloning' | 'syncing' | 'ready' | 'failed';
  stageMessage: string | null;
  progressCurrent: number;
  progressTotal: number;
  workspaceId: string | null;
  clonePath: string | null;
  previousClonePath: string | null;
  errorMessage: string | null;
  attempt: number;
  createdAt: number;
  updatedAt: number;
};

export type WorkspaceRepositoryDto = {
  owner: string;
  repo: string;
  fullName: string;
  defaultBaseBranch: string | null;
  local: LocalRepoStatusDto;
};

export type WorkspaceOverviewDto = {
  workspace: WorkspaceCardDto;
  repository: WorkspaceCardDto['repository'];
  sidebarCounts: {
    todayNeedsYou: number;
    trunks: number;
    pullRequests: number;
    issues: number | null;
    backlog: number;
    branches: number | null;
    sessions: number;
    notifications: number;
  };
  pinnedTrunks: WorkspaceTrunkDto[];
  today: {
    needsYou: WorkspaceTrunkDto[];
    running: WorkspaceTrunkDto[];
    landedToday: WorkspaceTrunkDto[];
    spendTodayMilliUsd: number;
  };
  onboarding: WorkspaceOnboardingDto;
  syncState: string;
};

export type WorkspaceTrunkDto = {
  id: string;
  workspaceId: string;
  title: string;
  description?: string | null;
  kind: 'dev' | 'review';
  status: string;
  provider: string | null;
  model: string | null;
  prRef: string | null;
  costUsdMilli: number;
  tokensIn: number;
  tokensOut: number;
  createdAt: number;
  updatedAt: number;
  endedAt: number | null;
  /** Optional list projection used by pickers and Today summaries. */
  taskCount?: number;
};

export type TrunkActivityItemDto = {
  id: string;
  kind: string;
  title: string;
  summary: string | null;
  status: string;
  itemPath: string | null;
  taskId: string | null;
  sessionId: string | null;
  occurredAt: number;
  actionable: boolean;
};

export type TrunkActivityDto = {
  trunkId: string;
  pinned: TrunkActivityItemDto[];
  timeline: TrunkActivityItemDto[];
  taskCount?: number;
  pullRequestCount?: number;
  costUsdMilli?: number;
  generatedAt: number;
};

export type WorkspaceBacklogItemDto = BacklogItemDto & {
  key: string;
  summary: string;
  detail: string | null;
  impactRisk: string | null;
  links: Array<{ type: string; id: string }>;
};

export type WorkspaceBacklogInput = {
  trunkId: string;
  title: string;
  summary: string;
  detail: string;
  impactRisk: string;
  tags: string[];
  priority: string;
  links: Array<{ type: string; id: string }>;
};

export type BranchComparisonDto = {
  branch: string;
  resolvedBranch: string;
  base: string;
  mergeBase: string;
  commits: LocalCommitDto[];
  files: LocalCommitFileDto[];
};

/** A commit as the history editor needs it: the plain Commits row plus
 *  the message body, line counts, and whether the remote already has it. */
export type RewritableCommitDto = {
  sha: string;
  shortSha: string;
  subject: string;
  body: string;
  authorName: string;
  authorEmail: string;
  authoredAt: string | null;
  committedAt: string | null;
  additions: number;
  deletions: number;
  pushed: boolean;
};

export type RewritableHistoryDto = {
  branch: string;
  trackingRef: string | null;
  /** False when `branch` isn't checked out — the editor stays read-only,
   *  because rewriting a branch we aren't on isn't supported. */
  editable: boolean;
  commits: RewritableCommitDto[];
};

/** The whole staged queue as one rebase. `commits` runs OLDEST FIRST;
 *  each entry's `picks` are the original shas folded into it, and a null
 *  `message` keeps whatever the first pick already had. */
export type RewritePlanDto = {
  branch: string;
  base: string;
  commits: Array<{ picks: string[]; message: string | null }>;
  forcePush: boolean;
};

export type RewriteResultDto = {
  headSha: string;
  pushed: boolean;
  /** Set when a requested force push was refused — the rewrite still
   *  landed locally, so this is a warning, not a failure. */
  pushError: string | null;
};

/** A fork clone's `upstream/*` remote-tracking refs. `remote` is null for
 *  a direct clone, which has no upstream remote and so no rows. */
export type UpstreamRefsDto = {
  remote: string | null;
  /** Qualified ref (`upstream/master`), not a bare branch name. */
  defaultBranch: string | null;
  branches: string[];
};

export type WorkspaceBranchDto = LocalBranchDto & {
  taskId: string | null;
  taskTitle: string | null;
  trunkId: string | null;
  trunkTitle: string | null;
};

export type CherryPickResultDto = {
  operationId: string;
  status: 'done' | 'conflicted' | 'aborted';
  /** Null on abort — the branch was deleted along with the worktree. */
  resultBranch: string | null;
  targetRef: string;
  commits: string[];
  appliedCount: number;
  conflictPaths: string[];
  worktreePath: string | null;
  trunkId: string | null;
  taskId: string | null;
  sessionId: string | null;
  message: string | null;
};

export type WorkspaceRelationDto = {
  workspaceId: string;
  upstreamWorkspaceId: string;
  upstreamWorkspaceName: string;
  upstreamRepoFullName: string;
  commitsEnabled: boolean;
  tagsEnabled: boolean;
  lastFetchedAt: string | null;
  autoFetchIntervalMinutes: number;
  indexedCommitCount: number;
};

export type WorkspaceRelationCandidateDto = {
  workspaceId: string;
  name: string;
  repoFullName: string;
  suggested: boolean;
  /** Set when this workspace cannot be the upstream — shown on a disabled
   *  row rather than hidden, so a workspace the user expects to find is
   *  explained instead of silently missing. */
  ineligibleReason?: string | null;
};

/** The upstream mirror is read-only history someone else rebased before
 *  pushing, so it carries only the committer date — the same field
 *  github.com's commit list shows. */
export type UpstreamCommitDto = Omit<LocalCommitDto, 'authoredAt'> & {
  tags: string[];
  picked: boolean;
};

export type UpstreamCommitsDto = {
  upstreamWorkspaceId: string;
  upstreamWorkspaceName: string;
  upstreamRepoFullName: string;
  revision: string;
  lastFetchedAt: string | null;
  indexedCommitCount: number;
  notInForkCount: number;
  commits: UpstreamCommitDto[];
  offset: number;
  /** False stops the list asking for another page. */
  hasMore: boolean;
};

/** A selection is either an explicit sha list or an inclusive from/to range. */
export type UpstreamCherryPickSelection = {
  shas?: string[];
  fromSha?: string | null;
  toSha?: string | null;
  skipStartsWith?: string[];
  skipContains?: string[];
};

export type PlannedCommitDto = {
  sha: string;
  shortSha: string;
  subject: string;
  authorName: string;
  pick: boolean;
  skipReason: string | null;
};

export type CherryPickPlanDto = {
  commits: PlannedCommitDto[];
  pickCount: number;
  skipCount: number;
};

export type UpstreamCherryPickStatus =
  'QUEUED' | 'RUNNING' | 'PAUSED_CONFLICT' | 'COMPLETED' | 'FAILED' | 'CLOSED';

export type UpstreamCherryPickJobDto = {
  jobId: string;
  /**
   * Which engine owns the run. Absent means the retired path, which is still
   * the owner of every run started before the cutover and must finish there.
   */
  source?: 'flow';
  /** The flow Task the run picks into; greenfield runs only. */
  taskId?: string;
  /** The run's "RUN #12" label — creation order in the workspace, not an id. */
  runNumber: number;
  status: UpstreamCherryPickStatus;
  sourceBranch: string;
  resultBranch: string;
  baseRef: string;
  requestedCount: number;
  /** The range's oldest and newest selected commit; null for a run with no range. */
  rangeFromSha: string | null;
  rangeToSha: string | null;
  appliedCount: number;
  skippedCount: number;
  /** Applied picks whose conflict was carried forward with git's resolution. */
  conflictedCount: number;
  /** A stop was asked for and takes effect at the next commit boundary. */
  pauseRequested: boolean;
  /**
   * Null on a greenfield run: phase 1 is bounded by conflict-repair turns, so
   * a dollar ceiling is a number that model cannot keep.
   */
  budgetMilliUsd?: number;
  spentMilliUsd: number;
  /** Conflict-repair turns a capped run may still spend; greenfield runs
   *  only, and null when the run was started without a cap. */
  remainingRepairTurns?: number | null;
  /**
   * CI fix rounds on the pull request. Absent on the retired path, which never
   * reported them — the list says so rather than showing a zero.
   */
  roundCount?: number;
  /** The local compile could not run, so CI carries the verdict from here on. */
  localGateUnavailable?: boolean;
  /** The CLI session the whole run shares, null until the first turn. */
  agentSessionId: string | null;
  conflictPaths: string[];
  worktreePath: string | null;
  prNumber: number | null;
  prUrl: string | null;
  /**
   * What the pull request is called — the title the user typed when they
   * confirmed the range, and the draft's own once the run has named it. Absent
   * on the retired path, which never carried one.
   */
  prTitle?: string | null;
  harnessWatchId?: string | null;
  /** How the pull request ended, once it did; null while it is open. */
  prResult?: 'merged' | 'closed' | null;
  errorMessage: string | null;
  /** Set once the user closed the run; every action but reading is refused after. */
  closedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type UpstreamCherryPickCommitState =
  'applied' | 'conflicted' | 'skipped' | 'current' | 'waiting';

export type UpstreamCherryPickCommitDto = {
  index: number;
  sha: string;
  shortSha: string;
  subject: string;
  state: UpstreamCherryPickCommitState;
};

/** One line of the run log — a command the run executed, or a note about it. */
export type UpstreamCherryPickEventDto = {
  id: string;
  ordinal: number;
  /** The pick this line belongs under; null for run-level lines. */
  pickIndex: number | null;
  kind: 'start' | 'command' | 'note' | 'skip' | 'park' | 'guidance' | 'agent'
    | 'agent_log' | 'fixup' | 'push' | 'pr' | 'watch' | 'done' | 'error' | 'closed'
    /** One resource the teardown released, or could not; phase 3's receipt. */
    | 'cleanup';
  title: string;
  detail: string | null;
  exitCode: number | null;
  durationMs: number | null;
  at: string;
};

/** One tool use the agent asked the user to approve, pending an answer. */
export type AgentToolApprovalDto = {
  approvalId: string;
  runId: string;
  toolName: string;
  inputJson: string;
  requestedAtEpochMilli: number;
};

export type UpstreamCherryPickRunDto = {
  job: UpstreamCherryPickJobDto;
  baseBranch: string;
  commits: UpstreamCherryPickCommitDto[];
  events: UpstreamCherryPickEventDto[];
  /** Phase 2's data; absent on the retired path, which never reported it. */
  rounds?: SyncRoundDto[];
  fixups?: SyncFixupDto[];
  compileProof?: SyncCompileProofDto | null;
  publishGate?: SyncPublishGateDto | null;
};

/** One CI round on the pull request, oldest first. */
export type SyncRoundDto = {
  ordinal: number;
  roundId: string;
  remoteHead: string;
  state: string;
  /** Frozen required checks in this round, not the whole provider board. */
  observedCount: number;
  failingCount: number;
  createdAt: string;
};

/** Which fixup repaired which pick, and where the repair came from. */
export type SyncFixupDto = {
  pickIndex: number;
  upstreamSha: string;
  targetSubject: string;
  kind: 'ADJACENT_FIXUP' | 'STANDALONE';
  commitSha: string;
  changedPaths: string[];
  /** A later repair amended the same fixup rather than adding a second. */
  amendCount: number;
  origin: 'CONFLICT_REPAIR' | 'CI_REPAIR';
  at: string;
};

export type SyncBoundaryDto = {
  ordinal: number;
  commitSha: string;
  /** A bare target followed by its fixup is deliberately not a boundary. */
  kind: 'TARGET_WITH_FIXUP' | 'FIXUP' | 'PLAIN';
  exitState: 'PASSED' | 'FAILED';
  evidenceRef: string;
};

/**
 * The program's own evidence that the rewritten series compiles where it
 * matters — the only thing that may excuse a red per-commit compile check.
 */
export type SyncCompileProofDto = {
  proofId: string;
  headSha: string;
  provedAt: string;
  boundaries: SyncBoundaryDto[];
  compileSelectors: string[];
  /** The repository CI configuration the selectors were read from. */
  compileSourceRef: string | null;
  excusedTargets: string[];
};

/** The publish gate, exactly as displayed; authorizing echoes both digests. */
export type SyncPublishGateDto = {
  gateId: string;
  revision: number;
  subjectDigest: string;
  actionDigest: string;
  state: string;
  proposedHead: string | null;
  branchRef: string | null;
  targetBaseRef: string | null;
};

export type SyncSelectedCommit = { sha: string; subject: string };

export type StartIssueResultDto = {
  trunkId: string;
  turnId: string;
  issueNumber: number;
};

export type WorkspaceSessionDto = {
  id: string;
  workspaceId: string;
  trunkId: string | null;
  kind: 'plan' | 'dev' | 'review' | 'ci-fix';
  status: 'queued' | 'running' | 'paused' | 'done' | 'errored';
  provider: string | null;
  model: string | null;
  taskId: string | null;
  stageId: string | null;
  reviewRoundId?: string | null;
  durableReview: boolean;
  controls: {
    pause: boolean;
    resume: boolean;
    stop: boolean;
    restart: boolean;
  };
  costUsdMilli: number;
  tokensIn: number;
  tokensOut: number;
  stepCursor: number;
  budget: number | null;
  headline: string | null;
  durationMs: number;
  launchInput: string | null;
  pauseReason: string | null;
  outcome: string | null;
  startedAt: number;
  finishedAt: number | null;
  /**
   * Optional detail emitted by richer session producers. Older AgentRun rows
   * remain valid and the UI derives a truthful compact timeline from the
   * lifecycle fields above.
   */
  trunkTitle?: string;
  taskNumber?: number;
  branch?: string;
  phases?: string[];
  timeline?: WorkspaceSessionTimelineItemDto[];
  changes?: WorkspaceSessionChangesDto;
};

export type WorkspaceSessionTimelineItemDto = {
  id: string;
  title: string;
  detail: string | null;
  timeLabel: string;
  status: 'done' | 'running' | 'pending' | 'errored';
  output?: Array<{
    text: string;
    tone: 'added' | 'removed' | 'muted';
  }>;
};

export type WorkspaceSessionChangesDto = {
  additions: number;
  deletions: number;
  files: Array<{
    status: 'A' | 'M' | 'D' | 'R';
    path: string;
  }>;
};

export type CanonicalNotificationDto = {
  id: string;
  workspaceId: string;
  publicType: string;
  title: string;
  summary: string | null;
  itemPath: string | null;
  status: string;
  createdAt: string;
  threadId: string | null;
};

export type NotificationMuteDto = {
  publicType: string;
  muted: boolean;
};

export type BrainBlockDto = {
  id: number;
  category: 'Conventions' | 'Decisions' | 'Gotchas';
  body: string;
  provenance: string;
  tags: string[];
  createdAt: number;
};

export type KnowledgeEntryDto = {
  id: string;
  workspaceId: string;
  title: string;
  body: string;
  audience: Array<'plan' | 'dev' | 'review' | 'ci-fix'>;
  provenance: Record<string, unknown>;
  createdAt: number;
  updatedAt: number;
};

export type DistillOperationDto = {
  id: string;
  target: 'brain' | 'kb';
  action: 'add' | 'replace' | 'delete';
  brainItemId: number | null;
  kbEntryId: string | null;
  category: string | null;
  title: string | null;
  body: string | null;
  audience: Array<'plan' | 'dev' | 'review' | 'ci-fix'>;
  decision: 'pending' | 'accepted' | 'edited' | 'skipped';
  originalBody: string | null;
};

export type DistillRunDto = {
  id: string;
  workspaceId: string;
  trigger: string;
  status: 'pending' | 'applied' | 'no-changes' | 'reverted';
  sources: Array<Record<string, unknown>>;
  operations: DistillOperationDto[];
  createdAt: number;
  appliedAt: number | null;
  revertedAt: number | null;
  /** Preview-only net character change shown before applying the run. */
  characterDelta?: number;
};

export type WorkspaceMemoryDto = {
  markdown: string;
  characters: number;
  characterBudget: number;
  blocks: BrainBlockDto[];
  knowledge: KnowledgeEntryDto[];
  distillRuns: DistillRunDto[];
};

const enc = encodeURIComponent;

export const workspaceApi = {
  overview: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceOverviewDto>({
      path: `/api/workspaces/${enc(workspaceId)}/overview`,
    }),
  repository: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceRepositoryDto>({
      path: `/api/workspaces/${enc(workspaceId)}/repository`,
    }),
  pullRequests: (workspaceId: string) =>
    window.bridge.workspaceApi<PullRequestDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/pull-requests`,
    }),
  pullRequest: (workspaceId: string, number: number) =>
    window.bridge.workspaceApi<PullRequestDto>({
      path: `/api/workspaces/${enc(workspaceId)}/pull-requests/${number}`,
    }),
  issues: (workspaceId: string, state: 'open' | 'closed') =>
    window.bridge.workspaceApi<IssueDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/issues?state=${state}`,
    }),
  issue: (workspaceId: string, number: number) =>
    window.bridge.workspaceApi<IssueDetailDto>({
      path: `/api/workspaces/${enc(workspaceId)}/issues/${number}`,
    }),
  commentOnIssue: (workspaceId: string, number: number, body: string) =>
    window.bridge.workspaceApi<IssueCommentDto>({
      path: `/api/workspaces/${enc(workspaceId)}/issues/${number}/comments`,
      method: 'POST',
      body: { body },
    }),
  issueTrunks: (workspaceId: string, number: number) =>
    window.bridge.workspaceApi<string[]>({
      path: `/api/workspaces/${enc(workspaceId)}/issues/${number}/trunks`,
    }),
  startIssue: (workspaceId: string, number: number, trunkId?: string) =>
    window.bridge.workspaceApi<StartIssueResultDto>({
      path: `/api/workspaces/${enc(workspaceId)}/issues/${number}/start`,
      method: 'POST',
      body: trunkId === undefined ? {} : { trunkId },
    }),
  trunks: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceTrunkDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/trunks`,
    }),
  addIssueToBacklog: (workspaceId: string, number: number, trunkId?: string) =>
    window.bridge.workspaceApi<BacklogItemDto>({
      path: `/api/workspaces/${enc(workspaceId)}/issues/${number}/backlog`,
      method: 'POST',
      body: trunkId === undefined ? {} : { trunkId },
    }),
  backlog: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceBacklogItemDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/backlog`,
    }),
  backlogItem: (workspaceId: string, key: string) =>
    window.bridge.workspaceApi<WorkspaceBacklogItemDto>({
      path: `/api/workspaces/${enc(workspaceId)}/backlog/${enc(key)}`,
    }),
  createBacklogItem: (workspaceId: string, input: WorkspaceBacklogInput) =>
    window.bridge.workspaceApi<WorkspaceBacklogItemDto>({
      path: `/api/workspaces/${enc(workspaceId)}/backlog`,
      method: 'POST',
      body: input,
    }),
  updateBacklogItem: (
    workspaceId: string,
    key: string,
    input: WorkspaceBacklogInput,
  ) =>
    window.bridge.workspaceApi<WorkspaceBacklogItemDto>({
      path: `/api/workspaces/${enc(workspaceId)}/backlog/${enc(key)}`,
      method: 'PATCH',
      body: input,
    }),
  discardBacklogItem: (workspaceId: string, key: string, reason?: string) =>
    window.bridge.workspaceApi<WorkspaceBacklogItemDto>({
      path: `/api/workspaces/${enc(workspaceId)}/backlog/${enc(key)}/discard`,
      method: 'POST',
      body: reason === undefined ? {} : { reason },
    }),
  reopenBacklogItem: (workspaceId: string, key: string) =>
    window.bridge.workspaceApi<WorkspaceBacklogItemDto>({
      path: `/api/workspaces/${enc(workspaceId)}/backlog/${enc(key)}/reopen`,
      method: 'POST',
    }),
  branches: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceBranchDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/branches`,
    }),
  upstreamBranches: (workspaceId: string) =>
    window.bridge.workspaceApi<UpstreamRefsDto>({
      path: `/api/workspaces/${enc(workspaceId)}/branches/upstream`,
    }),
  /** Branches of the linked upstream workspace, unqualified. */
  relationBranches: (workspaceId: string) =>
    window.bridge.workspaceApi<string[]>({
      path: `/api/workspaces/${enc(workspaceId)}/relation/branches`,
    }),
  compareBranch: (workspaceId: string, branch: string, base?: string) =>
    window.bridge.workspaceApi<BranchComparisonDto>({
      path: `/api/workspaces/${enc(workspaceId)}/branches/comparison?branch=${enc(branch)}${
        base === undefined ? '' : `&base=${enc(base)}`}`,
    }),
  deleteBranches: (workspaceId: string, names: string[], deleteRemote = false) =>
    window.bridge.workspaceApi<string[]>({
      path: `/api/workspaces/${enc(workspaceId)}/branches`,
      method: 'DELETE',
      body: { names, deleteRemote },
    }),
  cherryPick: (
    workspaceId: string,
    sourceBranch: string,
    targetBranch: string,
    shas: string[],
  ) =>
    window.bridge.workspaceApi<CherryPickResultDto>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/cherry-pick`,
      method: 'POST',
      body: { sourceBranch, targetBranch, shas },
    }),
  abortCherryPick: (workspaceId: string, operationId: string) =>
    window.bridge.workspaceApi<CherryPickResultDto>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/cherry-pick/${
        enc(operationId)}/abort`,
      method: 'POST',
    }),
  commitFiles: (workspaceId: string, sha: string) =>
    window.bridge.workspaceApi<LocalCommitFileDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/${enc(sha)}/files`,
    }),
  /** `skip` pages backwards through the history as the list scrolls. */
  rewritableCommits: (workspaceId: string, revision?: string, limit = 100, skip = 0) =>
    window.bridge.workspaceApi<RewritableHistoryDto>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/rewritable?${
        revision === undefined ? '' : `revision=${enc(revision)}&`
      }limit=${limit}&skip=${skip}`,
    }),
  workingTreeFiles: (workspaceId: string) =>
    window.bridge.workspaceApi<LocalCommitFileDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/working-tree/files`,
    }),
  workingTreeDiff: (workspaceId: string, path: string) =>
    window.bridge.workspaceApi<LocalFileDiffDto>({
      path: `/api/workspaces/${enc(workspaceId)}/working-tree/diff?path=${enc(path)}`,
    }),
  /** One file's diff across `base`..`head`. Pass `<sha>^` as the base for
   *  a single commit — the editor uses the same call for both panes. */
  commitRangeDiff: (workspaceId: string, base: string, head: string, path: string) =>
    window.bridge.workspaceApi<LocalFileDiffDto>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/diff?base=${enc(base)}&head=${
        enc(head)}&path=${enc(path)}`,
    }),
  rewriteHistory: (workspaceId: string, plan: RewritePlanDto) =>
    window.bridge.workspaceApi<RewriteResultDto>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/rewrite`,
      method: 'POST',
      body: plan,
    }),
  relation: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceRelationDto | null>({
      path: `/api/workspaces/${enc(workspaceId)}/relation`,
    }),
  relationCandidates: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceRelationCandidateDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/relation/candidates`,
    }),
  saveRelation: (
    workspaceId: string,
    input: {
      upstreamWorkspaceId: string;
      commitsEnabled: boolean;
      tagsEnabled: boolean;
      autoFetchIntervalMinutes: number;
    },
  ) => window.bridge.workspaceApi<WorkspaceRelationDto>({
    path: `/api/workspaces/${enc(workspaceId)}/relation`,
    method: 'PUT',
    body: input,
  }),
  unlinkRelation: (workspaceId: string) =>
    window.bridge.workspaceApi<null>({
      path: `/api/workspaces/${enc(workspaceId)}/relation`,
      method: 'DELETE',
    }),
  fetchRelation: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceRelationDto>({
      path: `/api/workspaces/${enc(workspaceId)}/relation/fetch`,
      method: 'POST',
    }),
  upstreamCommits: (workspaceId: string, revision?: string, limit = 200, offset = 0) =>
    window.bridge.workspaceApi<UpstreamCommitsDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/commits?${
        revision === undefined ? '' : `revision=${enc(revision)}&`
      }limit=${limit}&offset=${offset}`,
    }),
  previewUpstreamCherryPick: (
    workspaceId: string,
    input: UpstreamCherryPickSelection & { sourceBranch: string },
  ) => window.bridge.workspaceApi<CherryPickPlanDto>({
    path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/preview`,
    method: 'POST',
    body: input,
  }),
  createUpstreamCherryPick: (
    workspaceId: string,
    input: UpstreamCherryPickSelection & {
      sourceBranch: string;
      targetBranch: string;
      prDescription: string | null;
      openDraftPr: boolean;
      budgetMilliUsd: number;
    },
  ) => window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
    path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks`,
    method: 'POST',
    body: input,
  }),
  /**
   * Starts one greenfield sync run over the confirmed selection. This is the
   * only entry point; nothing reaches GitHub until the run's publish gate is
   * authorized by hand.
   */
  createUpstreamSync: (
    workspaceId: string,
    input: {
      commits: SyncSelectedCommit[];
      goalText: string;
      /** Omitted: the agent names the PR when it requests the review. */
      prTitle?: string;
      sourceRemote: string;
      sourceFromRef: string;
      sourceToRef: string;
      targetRef: string;
      /** Omitted: no cap — the run never parks over spent repair turns. */
      repairTurnBudget?: number;
    },
  ) => window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
    path: `/api/workspaces/${enc(workspaceId)}/upstream/syncs`,
    method: 'POST',
    body: input,
  }),
  upstreamSyncs: (workspaceId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/syncs`,
    }),
  upstreamSyncRun: (workspaceId: string, runId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/syncs/${enc(runId)}`,
    }),
  /**
   * Asks a running sync to park at its next pick boundary. It does not stop
   * where it stands: there is no head to wait at in the middle of a pick.
   */
  parkUpstreamSync: (workspaceId: string, runId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/syncs/${
        enc(runId)}/park`,
      method: 'POST',
    }),
  /**
   * Stops the run for good and releases its worktree. A run with a turn in
   * flight closes at its next pick boundary, not on this call.
   */
  closeUpstreamSync: (workspaceId: string, runId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/syncs/${
        enc(runId)}/close`,
      method: 'POST',
    }),
  /** Closes the run and drops it from the list; the branch is kept. */
  deleteUpstreamSync: (workspaceId: string, runId: string) =>
    window.bridge.workspaceApi<void>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/syncs/${enc(runId)}`,
      method: 'DELETE',
    }),
  /** Tool uses the agent wants approved — each renders as a card on the run. */
  syncRunPermissions: (runId: string) =>
    window.bridge.workspaceApi<AgentToolApprovalDto[]>({
      path: `/api/new-flow/runs/${enc(runId)}/permissions`,
    }),
  answerSyncPermission: (approvalId: string, allow: boolean) =>
    window.bridge.workspaceApi<void>({
      path: `/api/new-flow/permissions/${enc(approvalId)}`,
      method: 'POST',
      body: { allow },
    }),
  /** The user's own authorization of the first push, against what they saw. */
  authorizeUpstreamSyncPublish: (
    workspaceId: string,
    runId: string,
    gate: { revision: number; subjectDigest: string; actionDigest: string },
  ) => window.bridge.workspaceApi<UpstreamCherryPickRunDto>({
    path: `/api/workspaces/${enc(workspaceId)}/upstream/syncs/${
      enc(runId)}/publish`,
    method: 'POST',
    body: gate,
  }),
  upstreamCherryPick: (workspaceId: string, jobId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}`,
    }),
  upstreamCherryPicks: (workspaceId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks`,
    }),
  resumeUpstreamCherryPick: (workspaceId: string, jobId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}/resume`,
      method: 'POST',
    }),
  retryUpstreamCherryPick: (workspaceId: string, jobId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}/retry`,
      method: 'POST',
    }),
  // The other answer to a park: carry on with a higher ceiling, same session.
  raiseUpstreamCherryPickBudget: (
    workspaceId: string, jobId: string, additionalMilliUsd: number,
  ) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}/budget`,
      method: 'POST',
      body: { additionalMilliUsd },
    }),
  upstreamCherryPickRun: (workspaceId: string, jobId: string, events = 400) =>
    window.bridge.workspaceApi<UpstreamCherryPickRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${
        enc(jobId)}/run?events=${events}`,
    }),
  pauseUpstreamCherryPick: (workspaceId: string, jobId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}/pause`,
      method: 'POST',
    }),
  skipUpstreamCherryPickCommit: (workspaceId: string, jobId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}/skip`,
      method: 'POST',
    }),
  closeUpstreamCherryPick: (workspaceId: string, jobId: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}/close`,
      method: 'POST',
    }),
  // Close plus forget: same teardown, and the run's record and log go too.
  deleteUpstreamCherryPick: (workspaceId: string, jobId: string) =>
    window.bridge.workspaceApi<void>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}`,
      method: 'DELETE',
    }),
  guideUpstreamCherryPick: (workspaceId: string, jobId: string, text: string) =>
    window.bridge.workspaceApi<UpstreamCherryPickJobDto>({
      path: `/api/workspaces/${enc(workspaceId)}/upstream/cherry-picks/${enc(jobId)}/guidance`,
      method: 'POST',
      body: { text },
    }),
  refreshRepository: (workspaceId: string) =>
    window.bridge.workspaceApi<LocalRepoStatusDto>({
      path: `/api/workspaces/${enc(workspaceId)}/refresh`,
      method: 'POST',
    }),
  sessions: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceSessionDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/sessions`,
    }),
  settings: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceSettingsDto>({
      path: `/api/workspaces/${enc(workspaceId)}/settings`,
    }),
  automation: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceAutomationStatusDto>({
      path: `/api/workspaces/${enc(workspaceId)}/automation`,
    }),
  saveSettings: (workspaceId: string, settings: WorkspaceSettingsDto) =>
    window.bridge.workspaceApi<WorkspaceSettingsDto>({
      path: `/api/workspaces/${enc(workspaceId)}/settings`,
      method: 'PUT',
      body: settings,
    }),
  rename: (workspaceId: string, name: string) =>
    window.bridge.workspaceApi<WorkspaceDto>({
      path: `/api/workspaces/${enc(workspaceId)}`,
      method: 'PATCH',
      body: { name },
    }),
  workModelOptions: () => window.bridge.getWorkModelOptions(),
  refreshWorkModelOptions: () => window.bridge.refreshWorkModelOptions(),
  onboarding: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceOnboardingDto>({
      path: `/api/workspaces/${enc(workspaceId)}/onboarding`,
    }),
  directoryScopeSuggestions: (workspaceId: string) =>
    window.bridge.workspaceApi<DirectoryScopeOverviewDto>({
      path: `/api/workspaces/${enc(workspaceId)}/directory-scopes/suggestions`,
    }),
  decideDirectoryScope: (
    workspaceId: string, path: string, decision: 'approved' | 'rejected',
  ) => window.bridge.workspaceApi<{ paths: string[]; decisionState: string; decidedAtMs: number }>({
    path: `/api/workspaces/${enc(workspaceId)}/directory-scopes/decisions`,
    method: 'POST',
    body: { path, decision },
  }),
  assignDirectoryScope: (workspaceId: string, threadId: string, path: string) =>
    window.bridge.workspaceApi<DirectoryScopeAssignmentDto>({
      path: `/api/workspaces/${enc(workspaceId)}/directory-scopes/threads/${enc(threadId)}`,
      method: 'PUT',
      body: { path },
    }),
  listLearned: (workspaceId: string, lifecycle?: string) =>
    window.bridge.workspaceApi<LearnedKnowledgeDto[]>({
      path:
        `/api/workspaces/${enc(workspaceId)}/memory/learned` +
        (lifecycle ? `?lifecycle=${enc(lifecycle)}` : ''),
    }),
  decideLearned: (workspaceId: string, itemId: string, action: 'activate' | 'retire') =>
    window.bridge.workspaceApi<LearnedKnowledgeDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/learned/${enc(itemId)}/decision`,
      method: 'POST',
      body: { action },
    }),
  detach: (workspaceId: string) =>
    window.bridge.workspaceApi<void>({
      path: `/api/workspaces/${enc(workspaceId)}/detach`,
      method: 'POST',
    }),
  notifications: (workspaceId: string) =>
    window.bridge.workspaceApi<CanonicalNotificationDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/notifications`,
    }),
  markAllRead: (workspaceId: string) =>
    window.bridge.workspaceApi<number>({
      path: `/api/workspaces/${enc(workspaceId)}/notifications/mark-all-read`,
      method: 'POST',
    }),
  notificationMutes: (workspaceId: string) =>
    window.bridge.workspaceApi<NotificationMuteDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/notifications/mutes`,
    }),
  setNotificationMute: (workspaceId: string, publicType: string, muted: boolean) =>
    window.bridge.workspaceApi<NotificationMuteDto>({
      path: `/api/workspaces/${enc(workspaceId)}/notifications/mutes/${enc(publicType)}`,
      method: 'POST',
      body: { muted },
    }),
  memory: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceMemoryDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/aggregate`,
    }),
  saveMemory: (workspaceId: string, markdown: string) =>
    window.bridge.workspaceApi<WorkspaceMemoryDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/document`,
      method: 'PUT',
      body: { markdown },
    }),
  saveKnowledge: (
    workspaceId: string,
    entry: Pick<KnowledgeEntryDto, 'title' | 'body' | 'audience' | 'provenance'>,
    entryId?: string,
  ) =>
    window.bridge.workspaceApi<KnowledgeEntryDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/knowledge${
        entryId === undefined ? '' : `/${enc(entryId)}`}`,
      method: entryId === undefined ? 'POST' : 'PUT',
      body: entry,
    }),
  deleteKnowledge: (workspaceId: string, entryId: string) =>
    window.bridge.workspaceApi<void>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/knowledge/${enc(entryId)}`,
      method: 'DELETE',
    }),
  seedMemory: (workspaceId: string) =>
    window.bridge.workspaceApi<DistillRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/distill-runs/seed`,
      method: 'POST',
    }),
  distillThreads: (workspaceId: string) =>
    window.bridge.workspaceApi<DistillRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/distill-runs/threads`,
      method: 'POST',
    }),
  decideDistill: (workspaceId: string, runId: string, operations: DistillOperationDto[]) =>
    window.bridge.workspaceApi<DistillRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/distill-runs/${enc(runId)}/decisions`,
      method: 'PUT',
      body: { operations },
    }),
  applyDistill: (workspaceId: string, runId: string) =>
    window.bridge.workspaceApi<DistillRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/distill-runs/${enc(runId)}/apply`,
      method: 'POST',
    }),
  creations: () =>
    window.bridge.workspaceApi<WorkspaceCreationDto[]>({
      path: '/api/workspace-creations',
    }),
  creation: (operationId: string) =>
    window.bridge.workspaceApi<WorkspaceCreationDto>({
      path: `/api/workspace-creations/${enc(operationId)}`,
    }),
  createWorkspace: (
    owner: string,
    repo: string,
    writeMode: string,
    existingForkRepo?: string,
  ) =>
    window.bridge.workspaceApi<WorkspaceCreationDto>({
      path: '/api/workspace-creations',
      method: 'POST',
      body: { owner, repo, writeMode, existingForkRepo },
    }),
  retryCreation: (operationId: string) =>
    window.bridge.workspaceApi<WorkspaceCreationDto>({
      path: `/api/workspace-creations/${enc(operationId)}/retry`,
      method: 'POST',
    }),
  reclone: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceCreationDto>({
      path: `/api/workspaces/${enc(workspaceId)}/reclone`,
      method: 'POST',
    }),
};
