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
  LocalCommitDetailDto,
  LocalCommitDto,
  LocalCommitFileDto,
  LocalRepoStatusDto,
  PullRequestCommitDto,
  PullRequestDetailDto,
  PullRequestDto,
  StartDevelopmentResponse,
  WorkspaceCardDto,
  WorkspaceDto,
} from '../types';

export type WorkspaceSettingsDto = {
  sessionCapUsd: number;
  dailyCapUsd: number;
  pauseAtCap: boolean;
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
  dismissedAt: number | null;
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

export type WorkspaceBranchDto = LocalBranchDto & {
  taskId: string | null;
  taskTitle: string | null;
  trunkId: string | null;
  trunkTitle: string | null;
};

export type CherryPickResultDto = {
  operationId: string;
  status: 'done' | 'conflicted';
  resultBranch: string;
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

export type StartIssueResultDto = {
  trunkId: string;
  turnId: string;
  issueNumber: number;
};

export type ReviewStartDto = {
  reviewId: string;
  trunkId: string;
  roundId: string | null;
  status: string;
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

export type PlanUsageDto = {
  providers: ProviderPlanUsageDto[];
};

export type ProviderPlanUsageDto = {
  provider: string;
  label: string;
  plan: string | null;
  updatedAt: number;
  source: string | null;
  message: string | null;
  limits: PlanLimitDto[];
};

export type PlanLimitDto = {
  id: string;
  label: string;
  usedPercent: number;
  resetsAt: number;
  model: string | null;
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
  pullRequestDetail: (workspaceId: string, number: number) =>
    window.bridge.workspaceApi<PullRequestDetailDto>({
      path: `/api/workspaces/${enc(workspaceId)}/pull-requests/${number}/detail`,
    }),
  pullRequestCommits: (workspaceId: string, number: number) =>
    window.bridge.workspaceApi<PullRequestCommitDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/pull-requests/${number}/commits`,
    }),
  reviewPullRequest: (workspaceId: string, number: number) =>
    window.bridge.workspaceApi<ReviewStartDto>({
      path: `/api/workspaces/${enc(workspaceId)}/pull-requests/${number}/review`,
      method: 'POST',
    }),
  issues: (workspaceId: string, state: 'open' | 'closed') =>
    window.bridge.workspaceApi<IssueDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/issues?state=${state}`,
    }),
  issue: (workspaceId: string, number: number) =>
    window.bridge.workspaceApi<IssueDetailDto>({
      path: `/api/workspaces/${enc(workspaceId)}/issues/${number}`,
    }),
  setIssueState: (
    workspaceId: string,
    number: number,
    state: 'open' | 'closed',
  ) =>
    window.bridge.workspaceApi<IssueDetailDto>({
      path: `/api/workspaces/${enc(workspaceId)}/issues/${number}`,
      method: 'PATCH',
      body: { state },
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
  trunkActivity: (trunkId: string) =>
    window.bridge.workspaceApi<TrunkActivityDto>({
      path: `/api/trunks/${enc(trunkId)}/activity`,
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
  startBacklogItem: (workspaceId: string, key: string, trunkId?: string) =>
    window.bridge.workspaceApi<StartDevelopmentResponse>({
      path: `/api/workspaces/${enc(workspaceId)}/backlog/${enc(key)}/start`,
      method: 'POST',
      body: trunkId === undefined ? {} : { trunkId },
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
  commits: (workspaceId: string, revision?: string, limit = 100) =>
    window.bridge.workspaceApi<LocalCommitDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/commits?${
        revision === undefined ? '' : `revision=${enc(revision)}&`
      }limit=${limit}`,
    }),
  commit: (workspaceId: string, sha: string) =>
    window.bridge.workspaceApi<LocalCommitDetailDto>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/${enc(sha)}`,
    }),
  commitFiles: (workspaceId: string, sha: string) =>
    window.bridge.workspaceApi<LocalCommitFileDto[]>({
      path: `/api/workspaces/${enc(workspaceId)}/commits/${enc(sha)}/files`,
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
  planUsage: () =>
    window.bridge.workspaceApi<PlanUsageDto>({
      path: '/api/ai/plan-usage',
    }),
  refreshClaudeUsage: () =>
    window.bridge.workspaceApi<PlanUsageDto>({
      path: '/api/ai/plan-usage/claude/refresh',
      method: 'POST',
    }),
  session: (sessionId: string) =>
    window.bridge.workspaceApi<WorkspaceSessionDto>({
      path: `/api/sessions/${enc(sessionId)}`,
    }),
  sessionAction: (sessionId: string, action: 'pause' | 'resume' | 'stop' | 'restart') =>
    window.bridge.workspaceApi<WorkspaceSessionDto>({
      path: `/api/sessions/${enc(sessionId)}/${action}`,
      method: 'POST',
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
  dismissOnboarding: (workspaceId: string) =>
    window.bridge.workspaceApi<WorkspaceOnboardingDto>({
      path: `/api/workspaces/${enc(workspaceId)}/onboarding/dismiss`,
      method: 'POST',
    }),
  pauseAll: (workspaceId: string) =>
    window.bridge.workspaceApi<{ paused: number }>({
      path: `/api/workspaces/${enc(workspaceId)}/sessions/pause-all`,
      method: 'POST',
    }),
  detach: (workspaceId: string) =>
    window.bridge.workspaceApi<void>({
      path: `/api/workspaces/${enc(workspaceId)}/detach`,
      method: 'POST',
    }),
  reconnect: (workspaceId: string) =>
    window.bridge.workspaceApi<void>({
      path: `/api/workspaces/${enc(workspaceId)}/reconnect`,
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
  revertDistill: (workspaceId: string, runId: string) =>
    window.bridge.workspaceApi<DistillRunDto>({
      path: `/api/workspaces/${enc(workspaceId)}/memory/distill-runs/${enc(runId)}/revert`,
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
  createWorkspace: (owner: string, repo: string, writeMode: string) =>
    window.bridge.workspaceApi<WorkspaceCreationDto>({
      path: '/api/workspace-creations',
      method: 'POST',
      body: { owner, repo, writeMode },
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
