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
  ActivityItemDto,
  Bridge,
  ConvIndexPageDto,
  IssueDetailDto,
  IssueDto,
  LocalCommitDetailDto,
  LocalCommitDto,
  LocalCommitFileDto,
  NotificationDto,
  PullRequestCommitDto,
  PullRequestDetailDto,
  PullRequestDto,
  ThreadDto,
  ThreadMessageDto,
  ThreadTurnDto,
  WatchedRepoDto,
  WorkUnitTaskDto,
  WorkspaceApiRequest,
  WorkspaceCardDto,
  WorkspaceInsightsDto,
  WorkspaceRepoDto,
} from '../types';
import type { DashboardPR } from '../types/dashboardPr';
import type {
  BranchComparisonDto,
  BrainBlockDto,
  CanonicalNotificationDto,
  DistillRunDto,
  KnowledgeEntryDto,
  NotificationMuteDto,
  WorkspaceBacklogItemDto,
  WorkspaceBranchDto,
  WorkspaceCreationDto,
  WorkspaceMemoryDto,
  WorkspaceOnboardingDto,
  WorkspaceRepositoryDto,
  WorkspaceSessionDto,
  WorkspaceSettingsDto,
  WorkspaceTrunkDto,
} from './workspaceApi';

export const VISUAL_WORKSPACE_ID = 'workspace-bytequay';
export const VISUAL_TRUNK_ID = 'trunk-codex-v2';
export const VISUAL_ISSUE_NUMBER = 30311;
export const VISUAL_PR_NUMBER = 148;
export const VISUAL_DETAIL_PR_NUMBER = 26603;
export const VISUAL_SESSION_ID = 'session-dev-running';
export const VISUAL_BACKLOG_KEY = 'BQ-23';
export const VISUAL_BRANCH_NAME = 'dev/clamp-fix';

const minute = 60_000;
const hour = 60 * minute;
const day = 24 * hour;
const now = Date.now();
const agoMs = (duration: number) => now - duration;
const agoIso = (duration: number) => new Date(agoMs(duration)).toISOString();
const todayIso = (hours = 1) => agoIso(hours * hour);

function cast<T>(value: unknown): T {
  return value as T;
}

const repositoryIdentity = {
  owner: 'chenjian2664',
  repo: 'ByteQuay',
  fullName: 'chenjian2664/ByteQuay',
  defaultBaseBranch: 'main',
  clonePath: '/Users/chenjian2664/ByteQuay',
  verified: true,
};

const workspaceBase = {
  color: '#24292f',
  isScratch: false,
  memory: {
    decisionCount: 4,
    blockerCount: 1,
    tokensUsed: 1460,
    tokensCap: 2000,
  },
  ready: true,
  syncState: 'ready',
};

export const visualWorkspaces: WorkspaceCardDto[] = [
  {
    ...workspaceBase,
    id: VISUAL_WORKSPACE_ID,
    name: 'bytequay-v3-test',
    repos: ['bytequay'],
    activeThreadCount: 2,
    tasksInFlight: 1,
    spendTodayMilliUsd: 1400,
    needsAttentionCount: 1,
    lastActivityMs: agoMs(4 * hour),
    repository: repositoryIdentity,
    recentActivity: [
      {
        id: 'activity-clamp-merged',
        title: 'Task merged — Use Math.clamp for clamp expressions',
        status: 'RUNNING',
        itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/trunks/${VISUAL_TRUNK_ID}`,
        occurredAt: agoMs(26 * minute),
      },
      {
        id: 'activity-brain-replied',
        title: 'Brain replied in "Codex v2"',
        status: 'AWAITING_REVIEW',
        itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/trunks/${VISUAL_TRUNK_ID}`,
        occurredAt: agoMs(hour),
      },
    ],
  },
  {
    ...workspaceBase,
    id: 'workspace-trino',
    name: 'trino',
    color: '#d8428b',
    repos: ['trino'],
    activeThreadCount: 0,
    tasksInFlight: 0,
    spendTodayMilliUsd: 0,
    needsAttentionCount: 3,
    lastActivityMs: agoMs(3 * day),
    repository: {
      owner: 'trinodb',
      repo: 'trino',
      fullName: 'trinodb/trino',
      defaultBaseBranch: 'master',
      clonePath: '/Users/chenjian2664/trino',
      verified: true,
    },
    recentActivity: [
      {
        id: 'trino-review',
        title: '#30335 requested your review',
        status: 'AWAITING_REVIEW',
        itemPath: '#/workspace/workspace-trino/prs/30335',
        occurredAt: agoMs(9 * hour),
      },
      {
        id: 'trino-release-blocker',
        title: '#30311 RELEASE-BLOCKER opened',
        status: 'NEEDS_ATTENTION',
        itemPath: '#/workspace/workspace-trino/issues/30311',
        occurredAt: agoMs(2 * day),
      },
    ],
  },
  {
    ...workspaceBase,
    id: 'workspace-stargate',
    name: 'stargate',
    color: '#1f9d4d',
    repos: ['stargate'],
    activeThreadCount: 1,
    tasksInFlight: 1,
    spendTodayMilliUsd: 360,
    needsAttentionCount: 0,
    lastActivityMs: agoMs(2 * hour),
    repository: {
      owner: 'starburstdata',
      repo: 'stargate',
      fullName: 'starburstdata/stargate',
      defaultBaseBranch: 'main',
      clonePath: '/Users/chenjian2664/stargate',
      verified: true,
    },
    recentActivity: [
      {
        id: 'stargate-review',
        title: 'Review round posted on #3415',
        status: 'RUNNING',
        itemPath: '#/workspace/workspace-stargate/prs/3415',
        occurredAt: agoMs(2 * hour),
      },
      {
        id: 'stargate-cleanup',
        title: 'branch cleanup — 2 stale removed',
        status: 'COMPLETED',
        itemPath: '#/workspace/workspace-stargate/branches',
        occurredAt: agoMs(day),
      },
    ],
  },
];

export const visualSyncWorkspace: WorkspaceCardDto = {
  ...workspaceBase,
  id: VISUAL_WORKSPACE_ID,
  name: 'trino-python-client',
  repos: ['trino-python-client'],
  activeThreadCount: 0,
  tasksInFlight: 0,
  spendTodayMilliUsd: 0,
  needsAttentionCount: 0,
  lastActivityMs: now,
  syncState: 'syncing',
  repository: {
    owner: 'trinodb',
    repo: 'trino-python-client',
    fullName: 'trinodb/trino-python-client',
    defaultBaseBranch: 'master',
    clonePath: '/Users/chenjian2664/trino-python-client',
    verified: true,
  },
  recentActivity: [],
};

function thread(
  id: string,
  title: string,
  status: ThreadDto['status'],
  updatedAgo: number,
  overrides: Partial<ThreadDto> = {},
): ThreadDto {
  return {
    id,
    kind: 'CLI_AGENT',
    provider: 'anthropic',
    agentSessionId: null,
    title,
    status,
    flow: 'build',
    model: 'claude-sonnet-4.5',
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    createdAt: agoIso(3 * day),
    updatedAt: agoIso(updatedAgo),
    endedAt: status === 'COMPLETED' || status === 'ARCHIVED' ? agoIso(updatedAgo) : null,
    errorMessage: null,
    workspaceId: VISUAL_WORKSPACE_ID,
    workModel: null,
    parallelSlots: 1,
    ...overrides,
  };
}

export const visualThreads: ThreadDto[] = [
  thread(VISUAL_TRUNK_ID, 'Codex v2', 'RUNNING', 26 * minute, {
    costUsdMilli: 310,
    tokensIn: 35_000,
    tokensOut: 6_000,
    activitySummary: 'Brain: JSON payload builder merged — starting clamp expressions next',
    taskCount: 2,
    pullRequestCount: 1,
    unread: true,
  }),
  thread('trunk-codex-test', 'Codex test', 'NEEDS_ATTENTION', 9 * hour, {
    activitySummary: 'Agent asked: "Keep legacy field order in toMessage?" — waiting on you',
    taskCount: 1,
    pullRequestCount: 0,
    unread: true,
  }),
  thread('trunk-clean-code', 'Clean code v2', 'IDLE', 3 * day, {
    activitySummary: 'Both tasks merged — plan approval pending for step 3',
    taskCount: 2,
    pullRequestCount: 2,
  }),
  thread('trunk-auth', 'Clean clean', 'IDLE', 2 * day, {
    activitySummary: 'Plan draft ready — no task cut yet',
  }),
  thread('trunk-docs', "Let's do UI", 'IDLE', 3 * day, {
    activitySummary: 'Idle — last touched by you',
  }),
  thread('trunk-cache', "Let's clean the code", 'ERRORED', 6 * day, {
    activitySummary: '8 tasks errored — needs a restart or close',
    taskCount: 8,
  }),
  thread('trunk-review-19', 'Review #19 — Mange thread safety', 'RUNNING', 18 * minute, {
    kind: 'CLI_AGENT',
    flow: 'review',
  }),
  thread('trunk-serializer', 'Build toMessage JSON payloads with ObjectMapper', 'COMPLETED', 3 * hour),
  thread('trunk-clamp-landed', 'Use Math.clamp for max/min clamp expressions', 'COMPLETED', 26 * minute),
  thread('trunk-review-landed', 'Review round 2 posted on #148', 'ARCHIVED', hour),
  thread('trunk-email', 'Tighten email deep links', 'NEEDS_ATTENTION', 3 * day),
  thread('trunk-sidebar', 'Polish sidebar keyboard navigation', 'NEEDS_ATTENTION', 4 * day),
];

const visualTodayThreads: ThreadDto[] = [
  thread('today-review-148', '#148 Wire clamp validation into CI', 'AWAITING_REVIEW', 9 * hour, {
    flow: 'review',
    model: 'Clean code v2',
  }),
  thread('today-question', '"Keep legacy field order in toMessage?"', 'NEEDS_ATTENTION', hour, {
    model: 'Codex v2',
  }),
  thread('today-plan', 'Plan ready — 4 steps, est. $0.80', 'NEEDS_ATTENTION', 2 * hour, {
    model: 'Clean code v2',
    activitySummary: 'Awaiting approval',
  }),
  thread(VISUAL_TRUNK_ID, 'Codex v2', 'RUNNING', 12 * minute, {
    model: 'claude-sonnet',
    costUsdMilli: 310,
  }),
  thread('today-clamp-landed', 'Use Math.clamp for max/min clamp expressions', 'COMPLETED', 26 * minute),
  thread('today-message-landed', 'Build toMessage JSON payloads with ObjectMapper', 'COMPLETED', 3 * hour),
  thread('today-review-landed', 'Review round 2 posted on #148', 'ARCHIVED', hour, {
    flow: 'review',
    activitySummary: '6 comments',
  }),
];

function task(
  id: string,
  threadId: string,
  seq: number,
  name: string,
  status: string,
  overrides: Partial<WorkUnitTaskDto> = {},
): WorkUnitTaskDto {
  return cast<WorkUnitTaskDto>({
    id,
    threadId,
    seq,
    status,
    branchName: `dev/${name.toLowerCase().replaceAll(' ', '-')}`,
    worktreePath: `/tmp/${id}`,
    baseBranch: 'main',
    workingDir: '/Users/chenjian2664/ByteQuay',
    prNumber: null,
    prState: null,
    ciState: 'PASSING',
    taskType: 'BUILD',
    linkedPrNumber: null,
    linkedIssueNumber: null,
    pushedAt: null,
    phase: 'IMPLEMENTING',
    agendaJson: null,
    consecutiveAutoPushes: 0,
    linkedPrRef: null,
    openingPrompt: null,
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    createdAt: agoIso(day),
    name,
    workModel: null,
    ...overrides,
  });
}

export const visualTasks: WorkUnitTaskDto[] = [
  task('task-14a', VISUAL_TRUNK_ID, 1, 'Use Math.clamp for clamp expressions', 'COMPLETED', {
    branchName: 'dev/clamp-expressions',
    prNumber: 150,
    prState: 'MERGED',
    linkedPrNumber: 150,
    linkedPrRef: 'chenjian2664/ByteQuay#150',
    phase: 'COMPLETED',
    costUsdMilli: 420,
  }),
  task('task-14b', VISUAL_TRUNK_ID, 2, 'Wire clamp validation into CI', 'RUNNING', {
    branchName: VISUAL_BRANCH_NAME,
    prNumber: 148,
    prState: 'OPEN',
    linkedPrNumber: 148,
    linkedIssueNumber: VISUAL_ISSUE_NUMBER,
    linkedPrRef: 'chenjian2664/ByteQuay#148',
    costUsdMilli: 310,
    tokensIn: 35_000,
    tokensOut: 6_000,
  }),
  task('task-clean-1', 'trunk-clean-code', 1, 'Normalize renderer routes', 'COMPLETED', {
    prNumber: 151,
    prState: 'MERGED',
  }),
  task('task-clean-2', 'trunk-clean-code', 2, 'Remove stale repo pages', 'COMPLETED', {
    prNumber: 152,
    prState: 'MERGED',
  }),
  task('task-test-1', 'trunk-codex-test', 1, 'Preserve serializer field order', 'PAUSED'),
  ...Array.from({ length: 8 }, (_, index) =>
    task(`task-error-${index + 1}`, 'trunk-cache', index + 1, `Cleanup step ${index + 1}`, 'ERRORED')),
];

function pr(
  id: number,
  number: number,
  title: string,
  overrides: Partial<PullRequestDto> = {},
): PullRequestDto {
  return {
    id,
    repo: 'trinodb/trino',
    number,
    title,
    author: 'chenjian2664',
    htmlUrl: `https://github.com/trinodb/trino/pull/${number}`,
    createdAt: agoIso(3 * day),
    updatedAt: agoIso(hour),
    origin: 'REVIEW_REQUESTED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: ['chenjian2664'],
    ciStatus: 'PASSING',
    additions: 86,
    deletions: 41,
    commentCount: 2,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: agoIso(2 * hour),
    reviewerVerdicts: {},
    snoozedUntil: null,
    snoozeWakeReason: null,
    headRef: `dev/pr-${number}`,
    ...overrides,
  };
}

export const visualDetailPullRequest = pr(
  VISUAL_DETAIL_PR_NUMBER,
  VISUAL_DETAIL_PR_NUMBER,
  'Add support for JOIN pushdown in Exasol Connector',
  {
    author: 'skyglass',
    createdAt: '2025-09-10T08:00:00.000Z',
    updatedAt: '2025-09-16T08:00:00.000Z',
    labels: ['exasol', 'notable'],
    handledAction: 'COMMENTED',
    commentCount: 18,
    additions: 340,
    deletions: 4,
    reviewerVerdicts: {
      chenjian2664: 'COMMENTED',
      'Math-ias': 'APPROVED',
      ebyhr: 'APPROVED',
    },
    requestedReviewers: ['bytequay-agent'],
    headRef: 'feature/750_to_write_mapping_decimal',
  },
);

export const visualPullRequests: PullRequestDto[] = [
  pr(4038, 4038, 'WIP — Remove dependency on Databricks SDK', {
    author: 'JohnFitzpatrick44',
    labels: ['delta-lake', 'hive', 'connector', 'cleanup', 'breaking'],
    ciStatus: 'FAILING',
    attentionReason: 'CI_FAILING',
    updatedAt: agoIso(hour),
    additions: 214,
    deletions: 38,
  }),
  pr(20077, 20077, 'Remove dependency on Databricks SDK', {
    author: 'JohnFitzpatrick44',
    labels: ['needs-notable-review'],
    handledAction: 'APPROVED',
    attentionReason: 'NEW_COMMENT',
    updatedAt: agoIso(hour),
  }),
  pr(29586, 29586, 'Fix Scan failure due to dropped column used in an equality delete', {
    author: 'mderoy',
    labels: ['iceberg'],
    attentionReason: 'STALE',
    updatedAt: agoIso(9 * hour),
  }),
  {
    ...visualDetailPullRequest,
    labels: ['exasol'],
    updatedAt: agoIso(day),
  },
  pr(3905, 3905, 'ENG-17779 Add Query Tag Support for Snowflake', {
    author: 'wweiss-starburst',
    labels: ['snowflake', 'notable'],
    handledAction: 'COMMENTED',
    updatedAt: agoIso(2 * day),
  }),
  pr(3415, 3415, 'ENG-20180 [480-e] Support Databricks Unity for Iceberg', {
    author: 'wweiss-starburst',
    labels: ['iceberg', 'notable'],
    handledAction: 'APPROVED',
    reviewedAt: todayIso(2),
    updatedAt: agoIso(2 * hour),
  }),
  pr(VISUAL_PR_NUMBER, VISUAL_PR_NUMBER, 'Wire clamp validation into CI', {
    repo: 'chenjian2664/ByteQuay',
    author: 'chenjian2664',
    origin: 'AUTHORED',
    handledAction: 'COMMENTED',
    labels: ['validation'],
    attentionReason: null,
    updatedAt: agoIso(26 * minute),
    additions: 214,
    deletions: 38,
    headRef: VISUAL_BRANCH_NAME,
    reviewRound: 2,
  }),
  pr(30412, 30412, 'Interpreted comparator fallback for wide row types', {
    repo: 'chenjian2664/ByteQuay',
    origin: 'AUTHORED',
    draft: true,
    handledAction: 'COMMENTED',
    updatedAt: agoIso(4 * hour),
    additions: 96,
    deletions: 12,
    headRef: 'dev/partitions-fallback',
  }),
  pr(150, 150, 'Use Math.clamp for max/min clamp expressions', {
    repo: 'chenjian2664/ByteQuay',
    origin: 'AUTHORED',
    state: 'merged',
    mergedAt: agoIso(26 * minute),
    closedAt: agoIso(26 * minute),
    handledAction: 'MERGED',
    updatedAt: agoIso(26 * minute),
    headRef: 'dev/clamp-expressions',
    linkedTaskKey: 'TASK-14',
  }),
  pr(149, 149, 'Build toMessage JSON payloads with ObjectMapper', {
    repo: 'chenjian2664/ByteQuay',
    origin: 'AUTHORED',
    state: 'merged',
    mergedAt: todayIso(3),
    closedAt: todayIso(3),
    handledAction: 'MERGED',
    updatedAt: agoIso(3 * hour),
    additions: 142,
    deletions: 9,
    headRef: 'dev/object-mapper',
    linkedTaskKey: 'TASK-13',
  }),
  pr(141, 141, 'Spike: switch clamp codegen to MethodHandles', {
    repo: 'chenjian2664/ByteQuay',
    origin: 'AUTHORED',
    state: 'closed',
    closedAt: agoIso(6 * day),
    handledAction: 'DISMISSED',
    updatedAt: agoIso(6 * day),
    additions: 51,
    deletions: 3,
    headRef: 'spike/method-handles',
    supersededBy: 150,
  }),
];

export const visualWorkspacePullRequests = visualPullRequests.filter(value =>
  value.repo === 'chenjian2664/ByteQuay');

export const visualIssues: IssueDto[] = [
  {
    id: 29604,
    number: 29604,
    title: 'MERGE planner: MERGE_TARGET_ROW_MULTIPLE_MATCHES false positive when joined unique_id is NULL',
    author: 'fkatelyn',
    state: 'open',
    htmlUrl: 'https://github.com/chenjian2664/ByteQuay/issues/29604',
    updatedAt: agoIso(40 * minute),
    labels: [],
    commentCount: 5,
  },
  {
    id: VISUAL_ISSUE_NUMBER,
    number: VISUAL_ISSUE_NUMBER,
    title: 'Regression in 482: SELECT on Iceberg $partitions fails with MethodTooLargeException',
    author: 'guyco33',
    state: 'open',
    htmlUrl: `https://github.com/chenjian2664/ByteQuay/issues/${VISUAL_ISSUE_NUMBER}`,
    updatedAt: agoIso(4 * day),
    labels: ['RELEASE-BLOCKER'],
    commentCount: 8,
    linkedTrunkId: VISUAL_TRUNK_ID,
    linkedTrunkTitle: 'Fix $partitions regression',
  },
  {
    id: 17,
    number: 17,
    title: 'Add support for case sensitive identifiers',
    author: 'martint',
    state: 'open',
    htmlUrl: 'https://github.com/chenjian2664/ByteQuay/issues/17',
    updatedAt: agoIso(3 * hour),
    labels: ['enhancement', 'roadmap'],
    commentCount: 12,
  },
  {
    id: 21373,
    number: 21373,
    title: 'Support Snowflake SSO authentication',
    author: 'gioruss',
    state: 'open',
    htmlUrl: 'https://github.com/chenjian2664/ByteQuay/issues/21373',
    updatedAt: agoIso(3 * day),
    labels: [],
    commentCount: 2,
  },
  {
    id: 19620,
    number: 19620,
    title: 'Cannot create Iceberg table with sort order having nested column',
    author: 'yeunghl-shoalter',
    state: 'open',
    htmlUrl: 'https://github.com/chenjian2664/ByteQuay/issues/19620',
    updatedAt: agoIso(4 * day),
    labels: ['iceberg'],
    commentCount: 4,
  },
  {
    id: 28052,
    number: 28052,
    title: 'Feature Proposal: JDBC Catalog Store',
    author: 'rohankmr414',
    state: 'open',
    htmlUrl: 'https://github.com/chenjian2664/ByteQuay/issues/28052',
    updatedAt: agoIso(5 * day),
    labels: [],
    commentCount: 1,
  },
  ...Array.from({ length: 8 }, (_, index): IssueDto => ({
    id: 29000 + index,
    number: 29000 + index,
    title: `Additional synchronized issue ${index + 1}`,
    author: 'contributor',
    state: 'open',
    htmlUrl: `https://github.com/chenjian2664/ByteQuay/issues/${29000 + index}`,
    updatedAt: agoIso((6 + index) * day),
    labels: [],
    commentCount: 0,
  })),
];

export const visualIssueDetail: IssueDetailDto = cast<IssueDetailDto>({
  id: VISUAL_ISSUE_NUMBER,
  number: VISUAL_ISSUE_NUMBER,
  title: 'Regression in 482: SELECT on Iceberg $partitions fails with MethodTooLargeException for wide tables',
  body: 'Since upgrading to 482, querying the `$partitions` metadata table on wide Iceberg tables (900+ columns) throws `MethodTooLargeException` during code generation. Worked fine in 481.\n\nRepro: create an Iceberg table with ~1,000 columns partitioned on 3, then `SELECT * FROM "t$partitions"`. Stack trace points at the generated row-type comparator.',
  author: 'guyco33',
  authorAvatarUrl: null,
  state: 'open',
  htmlUrl: `https://github.com/chenjian2664/ByteQuay/issues/${VISUAL_ISSUE_NUMBER}`,
  createdAt: agoIso(4 * day),
  updatedAt: agoIso(4 * day),
  closedAt: null,
  labels: [
    { name: 'RELEASE-BLOCKER', color: 'cf222e' },
    { name: 'iceberg', color: '0969da' },
  ],
  assignees: [],
  milestone: { title: '482', state: 'open' },
  comments: [
    {
      id: 1,
      author: 'mderoy',
      authorAvatarUrl: null,
      body: 'Bisected to the comparator codegen change in #29811. The generated method exceeds 64KB once the partition row type crosses ~800 fields.',
      createdAt: agoIso(4 * day),
      reactions: {},
    },
    {
      id: 2,
      author: 'guyco33',
      authorAvatarUrl: null,
      body: 'Confirmed. Falling back to the interpreted comparator for wide row types would unblock the release.',
      createdAt: agoIso(4 * day),
      reactions: {},
    },
    ...Array.from({ length: 6 }, (_, index) => ({
      id: index + 3,
      author: 'contributor',
      authorAvatarUrl: null as string | null,
      body: `Additional discussion ${index + 1}`,
      createdAt: agoIso(4 * day),
      reactions: {},
    })),
  ],
  timeline: [],
  subscribed: true,
  participants: ['mderoy', 'guyco33', 'skyglass'],
  linkedWork: [
    {
      kind: 'trunk',
      id: VISUAL_TRUNK_ID,
      title: 'Fix $partitions regression',
      status: 'running',
      itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/trunks/${VISUAL_TRUNK_ID}`,
    },
    {
      kind: 'pull-request',
      id: '30412',
      title: '#30412 draft — interpreted fallback',
      status: 'draft',
      itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/prs/30412`,
    },
  ],
});

export const visualRepository: WorkspaceRepositoryDto = {
  owner: repositoryIdentity.owner,
  repo: repositoryIdentity.repo,
  fullName: repositoryIdentity.fullName,
  defaultBaseBranch: 'master',
  local: {
    owner: repositoryIdentity.owner,
    repo: repositoryIdentity.repo,
    localClonePath: repositoryIdentity.clonePath,
    state: 'CLEAN',
    currentBranch: VISUAL_BRANCH_NAME,
    dirtyFileCount: 0,
    errorMessage: null,
    upstreamRemoteName: null,
    defaultBranch: 'master',
    viewFocus: 'fork',
  },
};

export const visualTrunks: WorkspaceTrunkDto[] = visualThreads.map(value => ({
  id: value.id,
  workspaceId: value.workspaceId,
  title: value.title,
  kind: value.flow === 'review' ? 'review' : 'dev',
  status: value.status,
  provider: value.provider,
  model: value.model,
  prRef: value.flow === 'review' ? 'chenjian2664/ByteQuay#19' : null,
  costUsdMilli: value.costUsdMilli,
  tokensIn: value.tokensIn,
  tokensOut: value.tokensOut,
  createdAt: Date.parse(value.createdAt),
  updatedAt: Date.parse(value.updatedAt),
  endedAt: value.endedAt === null ? null : Date.parse(value.endedAt),
  taskCount: value.taskCount ?? 0,
}));

export const visualSessions: WorkspaceSessionDto[] = [
  {
    id: VISUAL_SESSION_ID,
    workspaceId: VISUAL_WORKSPACE_ID,
    trunkId: VISUAL_TRUNK_ID,
    kind: 'dev',
    status: 'running',
    provider: 'anthropic',
    model: 'claude-sonnet-4.5',
    taskId: 'task-14b',
    stageId: null,
    costUsdMilli: 310,
    tokensIn: 35_000,
    tokensOut: 6_000,
    stepCursor: 3,
    budget: 1000,
    headline: 'Codex v2 — dev step 3/6 · writing tests',
    durationMs: 12 * minute,
    launchInput: 'Wire clamp validation into CI',
    pauseReason: null,
    outcome: null,
    startedAt: agoMs(12 * minute),
    finishedAt: null,
    trunkTitle: 'Codex v2',
    taskNumber: 14,
    branch: 'dev/clamp-fix',
    phases: ['plan', 'scaffold', 'tests', 'impl', 'CI', 'summary'],
    timeline: [
      {
        id: 'read-files',
        title: 'Read 6 files',
        detail: 'functions/math/MathFunctions.java, ClampTest.java, …',
        timeLabel: '12:01',
        status: 'done',
      },
      {
        id: 'edit-math-functions',
        title: 'Edited MathFunctions.java',
        detail: '+48 −31 · swapped 4 call sites to Math.clamp',
        timeLabel: '12:05',
        status: 'done',
      },
      {
        id: 'write-boundary-tests',
        title: 'Writing boundary tests',
        detail: 'ClampBoundaryTest.java · streaming',
        timeLabel: 'now',
        status: 'running',
        output: [
          { text: '+ assertClamp(MIN_VALUE, lo, hi)', tone: 'added' },
          {
            text: '+ assertClamp(-0.0d, 0.0d, 1.0d) // negative zero keeps sign',
            tone: 'added',
          },
          { text: '▌ running: mvn -pl functions/math test …', tone: 'muted' },
        ],
      },
    ],
    changes: {
      additions: 86,
      deletions: 41,
      files: [
        { status: 'M', path: 'MathFunctions.java' },
        { status: 'A', path: 'ClampBoundaryTest.java' },
        { status: 'M', path: 'validation.yml' },
        { status: 'M', path: 'ClampTest.java' },
      ],
    },
  },
  {
    id: 'session-review-queued',
    workspaceId: VISUAL_WORKSPACE_ID,
    trunkId: 'trunk-review-19',
    kind: 'review',
    status: 'queued',
    provider: 'auto',
    model: 'auto',
    taskId: null,
    stageId: null,
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    stepCursor: 0,
    budget: 1000,
    headline: 'Review sweep — nightly review round',
    durationMs: 0,
    launchInput: 'Nightly review sweep',
    pauseReason: null,
    outcome: null,
    startedAt: agoMs(10 * minute),
    finishedAt: null,
  },
  {
    id: 'session-dev-done',
    workspaceId: VISUAL_WORKSPACE_ID,
    trunkId: VISUAL_TRUNK_ID,
    kind: 'dev',
    status: 'done',
    provider: 'anthropic',
    model: 'sonnet',
    taskId: 'task-14a',
    stageId: null,
    costUsdMilli: 420,
    tokensIn: 15_000,
    tokensOut: 3_000,
    stepCursor: 2,
    budget: 1000,
    headline: 'Codex v2 — dev step 2/6 · clamp expressions',
    durationMs: 14 * minute,
    launchInput: 'Replace bounds checks with Math.clamp',
    pauseReason: null,
    outcome: 'merged into dev/clamp-fix',
    startedAt: agoMs(hour + 14 * minute),
    finishedAt: agoMs(hour),
  },
  {
    id: 'session-plan-done',
    workspaceId: VISUAL_WORKSPACE_ID,
    trunkId: 'trunk-clean-code',
    kind: 'plan',
    status: 'done',
    provider: 'anthropic',
    model: 'claude-opus-4.8',
    taskId: null,
    stageId: null,
    costUsdMilli: 180,
    tokensIn: 18_000,
    tokensOut: 4_000,
    stepCursor: 4,
    budget: 1000,
    headline: 'Clean code v2 — plan draft, 4 steps',
    durationMs: 6 * minute,
    launchInput: 'Plan the cleanup',
    pauseReason: null,
    outcome: 'plan awaiting your approval',
    startedAt: agoMs(2 * hour + 6 * minute),
    finishedAt: agoMs(2 * hour),
  },
  {
    id: 'session-ci-error',
    workspaceId: VISUAL_WORKSPACE_ID,
    trunkId: 'trunk-codex-test',
    kind: 'ci-fix',
    status: 'errored',
    provider: 'openai',
    model: 'gpt-5.2-codex',
    taskId: 'task-ci',
    stageId: null,
    costUsdMilli: 770,
    tokensIn: 52_000,
    tokensOut: 11_000,
    stepCursor: 3,
    budget: 1000,
    headline: "Let's clean the code — CI fix loop",
    durationMs: 31 * minute,
    launchInput: 'Fix the boundary suite',
    pauseReason: null,
    outcome: 'errored after 3 iterations · needs restart',
    startedAt: agoMs(5 * hour),
    finishedAt: agoMs(4 * hour),
  },
];

export const visualBacklog: WorkspaceBacklogItemDto[] = [
  cast<WorkspaceBacklogItemDto>({
    id: 'backlog-1',
    key: 'BQ-1',
    threadId: VISUAL_TRUNK_ID,
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Extract clamp helpers into a shared util',
    summary: 'Math.clamp shows up in 4 connectors with copy-pasted bounds checks — pull into trino-common and swap call sites.',
    body: 'Math.clamp shows up in 4 connectors with copy-pasted bounds checks — pull into trino-common and swap call sites.',
    detail: 'Move the helper and its focused tests into the shared math utility.',
    impactRisk: 'Low implementation risk; watch binary compatibility.',
    tags: ['cleanup'],
    priority: 'medium',
    source: 'agent',
    status: 'open',
    createdBy: 'trunk-agent',
    createdAt: agoMs(2 * hour),
    inProgressAt: null,
    startedAt: null,
    resolvedAt: null,
    rejectedAt: null,
    rejectionReason: null,
    linkedTaskId: null,
    relatedBacklogIds: [],
    links: [{ type: 'trunk', id: VISUAL_TRUNK_ID }],
  }),
  cast<WorkspaceBacklogItemDto>({
    id: 'backlog-2',
    key: 'BQ-2',
    threadId: 'trunk-clean-code',
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Add property-based tests for toMessage',
    summary: 'Round-trip serialize/deserialize with generated payloads; would have caught the legacy field-order bug.',
    body: 'Round-trip serialize/deserialize with generated payloads; would have caught the legacy field-order bug.',
    detail: 'Cover positive/negative infinity and both NaN operands.',
    impactRisk: 'Tests only.',
    tags: ['testing'],
    priority: 'high',
    source: 'user',
    status: 'open',
    createdBy: 'user',
    createdAt: agoMs(day),
    inProgressAt: null,
    startedAt: null,
    resolvedAt: null,
    rejectedAt: null,
    rejectionReason: null,
    linkedTaskId: 'task-tests',
    relatedBacklogIds: [],
    links: [{ type: 'trunk', id: 'trunk-clean-code' }],
  }),
  cast<WorkspaceBacklogItemDto>({
    id: 'backlog-3',
    key: 'BQ-3',
    threadId: VISUAL_TRUNK_ID,
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Cache GitHub file tree between sessions',
    summary: 'Sessions re-fetch the tree on every start; cache keyed by head SHA would cut ~20s of warmup.',
    body: 'Sessions re-fetch the tree on every start; cache keyed by head SHA would cut ~20s of warmup.',
    detail: 'Cache by head SHA and invalidate when the tracked revision moves.',
    impactRisk: 'Keep cached entries workspace-local.',
    tags: ['perf'],
    priority: 'medium',
    source: 'manual',
    status: 'in-progress',
    createdBy: 'user',
    createdAt: agoMs(3 * day),
    inProgressAt: agoMs(day),
    startedAt: agoMs(day),
    resolvedAt: null,
    rejectedAt: null,
    rejectionReason: null,
    linkedTaskId: null,
    relatedBacklogIds: [],
    links: [{ type: 'trunk', id: VISUAL_TRUNK_ID }],
  }),
  cast<WorkspaceBacklogItemDto>({
    id: 'backlog-4',
    key: 'BQ-4',
    threadId: VISUAL_TRUNK_ID,
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Document validation.yml schema',
    summary: '',
    body: 'Document validation.yml schema.',
    detail: null,
    impactRisk: null,
    tags: ['docs'],
    priority: 'low',
    source: 'agent',
    status: 'resolved',
    createdBy: 'trunk-agent',
    createdAt: agoMs(3 * day),
    inProgressAt: agoMs(2 * day),
    startedAt: agoMs(2 * day),
    resolvedAt: agoMs(day),
    rejectedAt: null,
    rejectionReason: null,
    linkedTaskId: '12',
    relatedBacklogIds: [],
    links: [{ type: 'trunk', id: VISUAL_TRUNK_ID }],
  }),
  cast<WorkspaceBacklogItemDto>({
    id: 'backlog-5',
    key: 'BQ-5',
    threadId: 'trunk-codex-test',
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Benchmark MethodHandle prototype',
    summary: 'Confirm whether the discarded spike has measurable value.',
    body: 'Benchmark the discarded spike.',
    detail: null,
    impactRisk: null,
    tags: ['performance'],
    priority: 'low',
    source: 'user',
    status: 'discarded',
    createdBy: 'user',
    createdAt: agoMs(5 * day),
    inProgressAt: null,
    startedAt: null,
    resolvedAt: null,
    rejectedAt: agoMs(4 * day),
    rejectionReason: 'Superseded by the simpler clamp change.',
    linkedTaskId: null,
    relatedBacklogIds: [],
    links: [{ type: 'trunk', id: 'trunk-codex-test' }],
  }),
];

export const visualBacklogDetail = cast<WorkspaceBacklogItemDto>({
  id: 'backlog-23',
  key: VISUAL_BACKLOG_KEY,
  threadId: VISUAL_TRUNK_ID,
  workspaceId: VISUAL_WORKSPACE_ID,
  title: 'Extract clamp helpers into a shared util',
  summary: 'Math.clamp bounds checks are copy-pasted across 4 connectors; centralize in trino-common.',
  body: 'Math.clamp bounds checks are copy-pasted across 4 connectors; centralize in trino-common.',
  detail: 'Call sites: exasol, snowflake, redshift, iceberg partition pruning. Keep NaN propagation identical — see ClampBoundaryTest for the contract.',
  impactRisk: 'Low risk, behavior-preserving. Touches connector API surface → needs notable review per playbook.',
  tags: ['cleanup', 'good-first-task'],
  priority: 'medium',
  source: 'agent',
  status: 'open',
  createdBy: 'trunk-agent',
  createdAt: agoMs(2 * hour),
  inProgressAt: null,
  startedAt: null,
  resolvedAt: null,
  rejectedAt: null,
  rejectionReason: null,
  linkedTaskId: null,
  relatedBacklogIds: ['BQ-19'],
  links: [
    { type: 'trunk', id: VISUAL_TRUNK_ID },
    { type: 'task', id: '14' },
    { type: 'backlog', id: 'BQ-19' },
  ],
});

function branch(
  name: string,
  overrides: Partial<WorkspaceBranchDto> = {},
): WorkspaceBranchDto {
  return {
    name,
    isCurrent: false,
    lastCommitAt: agoIso(hour),
    hasUpstream: true,
    ahead: 0,
    behind: 0,
    linkedPrNumber: null,
    cleanupReason: null,
    commitCount: 0,
    rebasePreview: 'CLEAN',
    remoteOnly: false,
    taskId: null,
    taskTitle: null,
    trunkId: null,
    trunkTitle: null,
    ...overrides,
  };
}

export const visualBranches: WorkspaceBranchDto[] = [
  branch('master', { isCurrent: true }),
  branch(VISUAL_BRANCH_NAME, {
    ahead: 3,
    linkedPrNumber: VISUAL_PR_NUMBER,
    commitCount: 3,
    taskId: 'task-14b',
    taskTitle: 'Wire clamp validation into CI',
    trunkId: VISUAL_TRUNK_ID,
    trunkTitle: 'Codex v2 · task #14',
    lastCommitAt: agoIso(26 * minute),
  }),
  branch('dev/tomessage-builder', {
    cleanupReason: 'REMOTE_GONE',
    lastCommitAt: agoIso(3 * hour),
  }),
  branch('spike/method-handles', {
    ahead: 1,
    commitCount: 1,
    lastCommitAt: agoIso(6 * day),
  }),
];

function commit(
  sha: string,
  subject: string,
  ago: number,
  authorName = 'ByteQuay Agent',
  workspace: Partial<Pick<
    LocalCommitDto,
    'ciStatus' | 'agentOwned' | 'onBehalfOf' | 'displayTime' | 'groupLabel'
  >> = {},
): LocalCommitDto {
  return {
    sha: sha.padEnd(40, '0'),
    shortSha: sha,
    subject,
    authorName,
    authorEmail: authorName.toLowerCase().includes('agent')
      ? 'agent@bytequay.local'
      : 'chenjian2664@example.com',
    authoredAt: agoIso(ago),
    ...workspace,
  };
}

export const visualCommits: LocalCommitDto[] = [
  commit('a4f21c9', 'Use Math.clamp for max/min clamp expressions (#150)', 26 * minute, 'bytequay-agent', {
    ciStatus: 'passed',
    agentOwned: true,
    onBehalfOf: 'Codex v2',
    displayTime: '26m',
    groupLabel: 'Today',
  }),
  commit('91bd03e', 'Build toMessage JSON payloads with ObjectMapper (#149)', 3 * hour, 'bytequay-agent', {
    ciStatus: 'passed',
    agentOwned: true,
    onBehalfOf: 'Codex v2',
    displayTime: '3h',
    groupLabel: 'Today',
  }),
  commit('3c7ff21', 'Fix flaky clamp boundary test', day, 'chenjian2664', {
    ciStatus: 'passed',
    agentOwned: false,
    displayTime: '1d',
    groupLabel: 'Yesterday',
  }),
  commit('de81a02', 'Bump jackson to 2.19.1', day + hour, 'chenjian2664', {
    ciStatus: 'failed',
    agentOwned: false,
    displayTime: '1d',
    groupLabel: 'Yesterday',
  }),
];

export const visualComparison: BranchComparisonDto = {
  branch: VISUAL_BRANCH_NAME,
  resolvedBranch: VISUAL_BRANCH_NAME,
  base: 'master',
  mergeBase: '5ab31cc',
  commits: [
    commit('a4f21c9', 'Use Math.clamp for max/min clamp expressions', 26 * minute, 'bytequay-agent', {
      displayTime: '26m',
    }),
    commit('7be90d4', 'Add clamp boundary tests for MIN/MAX_VALUE', hour, 'bytequay-agent', {
      displayTime: '1h',
    }),
    commit('c2ae511', 'Wire clamp suite into local validation', 2 * hour, 'bytequay-agent', {
      displayTime: '2h',
    }),
  ],
  files: [
    { path: 'core/trino-main/src/main/java/io/trino/operator/scalar/MathFunctions.java', status: 'M', additions: 86, deletions: 20 },
    { path: 'core/trino-main/src/test/java/io/trino/operator/scalar/TestMathFunctions.java', status: 'M', additions: 48, deletions: 8 },
    { path: 'core/trino-main/src/test/java/io/trino/operator/scalar/ClampBoundaryTest.java', status: 'A', additions: 32, deletions: 5 },
    { path: '.github/workflows/validation.yml', status: 'M', additions: 24, deletions: 3 },
    { path: 'docs/functions.md', status: 'M', additions: 14, deletions: 2 },
    { path: 'testing/trino-tests/src/test/java/io/trino/tests/TestClamp.java', status: 'M', additions: 10, deletions: 0 },
  ],
};

const brainBlocks: BrainBlockDto[] = [
  {
    id: 1,
    category: 'Conventions',
    body: 'Java 21, Guice modules per connector. Message DTOs serialize via the central `ObjectMapper` factory — never new mappers inline.',
    provenance: 'Codex v2 · 2h',
    tags: ['java', 'serialization'],
    createdAt: agoMs(2 * hour),
  },
  {
    id: 2,
    category: 'Decisions',
    body: 'Clamp semantics follow `Math.clamp` (JDK 21) — no custom bounds helpers. Legacy field order in toMessage is load-bearing for downstream consumers; keep until 490.',
    provenance: 'Clean code v2 · 1d',
    tags: ['math', 'serializer'],
    createdAt: agoMs(day),
  },
  {
    id: 3,
    category: 'Gotchas',
    body: 'CI needs `-Pci-validate` locally or the clamp test suite silently skips. Wide-row codegen breaks past ~800 fields (see #30311).',
    provenance: 'edited by you · 3d',
    tags: ['ci', 'codegen'],
    createdAt: agoMs(3 * day),
  },
];

const knowledge: KnowledgeEntryDto[] = [
  {
    id: 'kb-review',
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Review playbook — PR type & scope',
    body: 'Classify each PR before review: **type** (feature / bugfix / refactor / docs / dep-bump) from title + diff shape; **scope** from touched modules mapped against the module map; flag **notable** when it crosses connector API or SPI boundaries. Depth per type: bugfix → regression test present; refactor → behavior-preserving check; dep-bump → changelog scan only.',
    audience: ['review'],
    provenance: {
      display: 'distilled from "Deduplicate AI review preparation" · edited by you 1d ago',
    },
    createdAt: agoMs(2 * day),
    updatedAt: agoMs(day),
  },
  {
    id: 'kb-module-map',
    workspaceId: VISUAL_WORKSPACE_ID,
    title: 'Module map — ownership & blast radius',
    body: '`core/messaging` → toMessage consumers in 3 services; `functions/math` → codegen-sensitive (wide-row limits). Changes here always get boundary tests.',
    audience: ['plan', 'review'],
    provenance: { display: '' },
    createdAt: agoMs(2 * day),
    updatedAt: agoMs(2 * hour),
  },
];

const pendingDistill: DistillRunDto = {
  id: 'distill-pending',
  workspaceId: VISUAL_WORKSPACE_ID,
  trigger: 'auto',
  status: 'pending',
  sources: [
    {
      trunkId: VISUAL_TRUNK_ID,
      label: 'Codex v2',
      detail: '14 new messages since last distill',
    },
    {
      trunkId: 'trunk-clean-code',
      label: 'Clean code v2',
      detail: 'plan approved + 2 tasks merged',
    },
    {
      trunkId: 'trunk-codex-test',
      label: 'Codex test',
      detail: 'nothing new',
      disabled: true,
    },
  ],
  operations: [
    {
      id: 'operation-1',
      target: 'brain',
      action: 'add',
      brainItemId: null,
      kbEntryId: null,
      category: 'Decision',
      title: null,
      body: 'legacy field order in toMessage is load-bearing until 490 — do not "clean up".',
      audience: [],
      decision: 'accepted',
      originalBody: null,
    },
    {
      id: 'operation-2',
      target: 'kb',
      action: 'replace',
      brainItemId: null,
      kbEntryId: 'kb-review',
      category: null,
      title: 'Review playbook',
      body: 'add rule — clamp/codegen diffs require wide-row boundary tests (from #148 round 2).',
      audience: ['review'],
      decision: 'accepted',
      originalBody: 'Review playbook',
    },
  ],
  characterDelta: 214,
  createdAt: agoMs(5 * minute),
  appliedAt: null,
  revertedAt: null,
};

export function visualMemory(frame: string): WorkspaceMemoryDto {
  const rich = frame === '4e' || frame === '4f';
  const applied: DistillRunDto = {
    ...pendingDistill,
    id: 'distill-applied',
    status: 'applied',
    sources: [{
      label: 'Codex v2',
      summary: 'Folded **Codex v2**, **Clean code v2** → +1 convention, 1 decision updated',
    }],
    createdAt: agoMs(2 * hour),
    appliedAt: agoMs(110 * minute),
    operations: pendingDistill.operations.map(operation => ({
      ...operation,
      decision: 'accepted',
    })),
  };
  const edited: DistillRunDto = {
    ...pendingDistill,
    id: 'distill-edited',
    status: 'applied',
    trigger: 'manual',
    sources: [{
      label: 'Deduplicate AI review preparation',
      summary: 'Folded **Deduplicate AI review preparation** → new KB entry "Review playbook"; you trimmed 2 rules',
    }],
    createdAt: agoMs(day),
    appliedAt: agoMs(day - minute),
    operations: pendingDistill.operations.slice(0, 1).map(operation => ({
      ...operation,
      target: 'kb',
      decision: 'edited',
    })),
  };
  const noChanges: DistillRunDto = {
    ...pendingDistill,
    id: 'distill-no-changes',
    status: 'no-changes',
    trigger: 'auto',
    sources: [{
      label: '3 threads',
      summary: 'Scanned 3 threads — nothing new to fold',
    }],
    createdAt: agoMs(3 * day),
    operations: [],
  };
  return {
    markdown: '# Conventions\n\nUse strict TypeScript.\n\n# Decisions\n\nKeep legacy field order.\n',
    characters: rich ? 1148 : 612,
    characterBudget: 8000,
    blocks: rich
      ? [{
          ...brainBlocks[0],
          body: 'Java 21, Guice modules per connector. DTOs serialize via the central `ObjectMapper` factory — never inline mappers.',
        }]
      : brainBlocks,
    knowledge: rich ? knowledge : [],
    distillRuns: frame === '4f'
      ? [pendingDistill]
      : rich ? [applied, edited, noChanges] : [applied],
  };
}

export const visualCanonicalNotifications: CanonicalNotificationDto[] = [
  {
    id: 'notification-review',
    workspaceId: VISUAL_WORKSPACE_ID,
    publicType: 'review-request',
    title: 'Review requested on #148 Wire clamp validation into CI',
    summary: null,
    itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/prs/${VISUAL_PR_NUMBER}`,
    status: 'UNREAD',
    createdAt: agoIso(9 * hour),
    threadId: 'trunk-review-19',
  },
  {
    id: 'notification-question',
    workspaceId: VISUAL_WORKSPACE_ID,
    publicType: 'agent-question',
    title: 'Agent question in Codex v2',
    summary: '"Keep legacy field order in toMessage?"',
    itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/trunks/${VISUAL_TRUNK_ID}`,
    status: 'UNREAD',
    createdAt: agoIso(hour),
    threadId: VISUAL_TRUNK_ID,
  },
  {
    id: 'notification-ci',
    workspaceId: VISUAL_WORKSPACE_ID,
    publicType: 'ci',
    title: `CI failed on ${VISUAL_BRANCH_NAME}`,
    summary: 'clamp boundary suite, 2 failures',
    itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/branches/${encodeURIComponent(VISUAL_BRANCH_NAME)}`,
    status: 'UNREAD',
    createdAt: agoIso(3 * hour),
    threadId: VISUAL_TRUNK_ID,
  },
  {
    id: 'notification-merge',
    workspaceId: VISUAL_WORKSPACE_ID,
    publicType: 'agent-update',
    title: 'Task merged',
    summary: 'Use Math.clamp for max/min clamp expressions',
    itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/prs/150`,
    status: 'READ',
    createdAt: agoIso(26 * minute),
    threadId: VISUAL_TRUNK_ID,
  },
  {
    id: 'notification-distill',
    workspaceId: VISUAL_WORKSPACE_ID,
    publicType: 'memory',
    title: 'Memory distilled from 3 threads',
    summary: null,
    itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/memory`,
    status: 'READ',
    createdAt: agoIso(2 * hour),
    threadId: null,
  },
];

export const visualNotificationMutes: NotificationMuteDto[] = [
  { publicType: 'agent-update', muted: false },
];

export const visualSettings: WorkspaceSettingsDto = {
  sessionCapUsd: 1,
  dailyCapUsd: 10,
  pauseAtCap: true,
  syncSeconds: 60,
  brainBudgetChars: 8000,
  distillMinutes: 30,
  kbAudiences: ['plan', 'dev', 'review', 'ci-fix'],
  providers: {
    plan: 'claude-opus-4.8',
    dev: 'claude-sonnet-4.5',
    review: 'claude-sonnet-4.5',
    'ci-fix': 'gpt-5.2-codex',
  },
  notifyCi: true,
  notifyCompletions: false,
};

export function visualOnboarding(frame: string): WorkspaceOnboardingDto {
  const syncing = frame === '6a';
  return {
    workspaceId: VISUAL_WORKSPACE_ID,
    cloneComplete: true,
    syncState: syncing ? 'syncing' : 'ready',
    syncCurrent: syncing ? 14 : 3,
    syncTotal: syncing ? 38 : 3,
    memorySeedComplete: !syncing,
    firstTrunkComplete: !syncing,
    memoryImported: !syncing,
    dismissedAt: syncing ? null : agoMs(day),
    updatedAt: now,
  };
}

export const visualCreationReady: WorkspaceCreationDto = {
  id: 'creation-ready',
  operationKind: 'create',
  owner: 'trinodb',
  repo: 'trino-python-client',
  writeMode: 'FORK',
  state: 'ready',
  stageMessage: 'clone + first sync complete · 38 PRs · 112 issues',
  progressCurrent: 3,
  progressTotal: 3,
  workspaceId: VISUAL_WORKSPACE_ID,
  clonePath: '/Users/chenjian2664/trino-python-client',
  previousClonePath: null,
  errorMessage: null,
  attempt: 1,
  createdAt: agoMs(minute),
  updatedAt: now,
};

export const visualCreationLive: WorkspaceCreationDto = {
  id: 'creation-live',
  operationKind: 'create',
  owner: 'trinodb',
  repo: 'trino-python-client',
  writeMode: 'FORK',
  state: 'cloning',
  stageMessage: 'fork created · fetching objects',
  progressCurrent: 62,
  progressTotal: 100,
  workspaceId: VISUAL_WORKSPACE_ID,
  clonePath: '/Users/chenjian2664/trino-python-client',
  previousClonePath: null,
  errorMessage: null,
  attempt: 1,
  createdAt: agoMs(2 * minute),
  updatedAt: now,
};

export const visualInsights: WorkspaceInsightsDto = {
  window: '7d',
  activeThreads: 2,
  tasksInFlight: 2,
  reposInWorkspace: 1,
  spendTodayMilli: 1400,
  spendInWindowMilli: 131870,
  tasksShippedInWindow: 5,
  spendByDay: [
    { date: '2026-07-10', label: 'Fri', costUsdMilli: 18000 },
    { date: '2026-07-11', label: 'Sat', costUsdMilli: 9000 },
    { date: '2026-07-12', label: 'Sun', costUsdMilli: 4000 },
    { date: '2026-07-13', label: 'Mon', costUsdMilli: 46000 },
    { date: '2026-07-14', label: 'Tue', costUsdMilli: 31000 },
    { date: '2026-07-15', label: 'Wed', costUsdMilli: 22000 },
    { date: '2026-07-16', label: 'Thu', costUsdMilli: 1400 },
  ],
  tasksByRepo: [
    { repoFullName: 'chenjian2664/ByteQuay', tasksShipped: 5, tasksOpen: 2 },
  ],
  usageByProvider: [
    {
      key: 'anthropic',
      costUsdMilli: 102400,
      tokensIn: 560000,
      tokensOut: 190000,
      sessions: 9,
    },
    {
      key: 'openai',
      costUsdMilli: 24610,
      tokensIn: 300000,
      tokensOut: 100000,
      sessions: 4,
    },
    {
      key: 'local',
      costUsdMilli: 4860,
      tokensIn: 40000,
      tokensOut: 10000,
      sessions: 1,
    },
  ],
  usageByKind: [
    { key: 'dev', costUsdMilli: 92000, tokensIn: 600000, tokensOut: 210000, sessions: 8 },
    { key: 'review', costUsdMilli: 28000, tokensIn: 180000, tokensOut: 80000, sessions: 4 },
    { key: 'plan', costUsdMilli: 12000, tokensIn: 100000, tokensOut: 30000, sessions: 2 },
  ],
  githubRateLimit: {
    remaining: 4995,
    limit: 5000,
    resetAt: '2026-07-17T03:17:00.000Z',
  },
};

export const visualPullRequestDetail: PullRequestDetailDto = cast<PullRequestDetailDto>({
  repo: 'chenjian2664/ByteQuay',
  number: VISUAL_PR_NUMBER,
  body: 'Adds support for **clamp validation in CI** and preserves the legacy serializer field order.\n\n- replaces hand-written bounds checks\n- adds boundary and NaN coverage\n- wires the focused suite into CI',
  labels: ['validation', 'tests'],
  draft: false,
  mergeable: true,
  mergeableState: 'clean',
  additions: 214,
  deletions: 38,
  changedFiles: 6,
  approvalCount: 1,
  changesRequestedCount: 0,
  pendingReviewerCount: 1,
  requestedReviewers: ['martint'],
  ciStatus: 'PASSING',
  files: [
    { filename: 'frontend/src/workspace/WorkspaceRepoPage.tsx', additions: 62, deletions: 18, status: 'modified' },
    { filename: 'backend/src/test/java/TestClamp.java', additions: 152, deletions: 20, status: 'modified' },
  ],
  recentActivity: [
    cast({
      actor: 'martint',
      eventType: 'reviewed',
      timestamp: agoIso(hour),
      body: 'The field-order compatibility note looks good. Please keep the NaN case explicit.',
      state: 'APPROVED',
      githubId: 1,
    }),
    cast({
      actor: 'ebyhr',
      eventType: 'commented',
      timestamp: agoIso(35 * minute),
      body: 'Could we also cover the nullable aggregation path from #30311?',
      state: null,
      githubId: 2,
    }),
  ],
  checkRuns: [],
  reviewThreads: [],
  linkedIssues: [
    {
      number: VISUAL_ISSUE_NUMBER,
      title: visualIssues[0].title,
      state: 'open',
      htmlUrl: visualIssues[0].htmlUrl,
    },
  ],
  viewerCanWrite: true,
  headRef: VISUAL_BRANCH_NAME,
  headRepo: 'chenjian2664/ByteQuay',
  baseRef: 'main',
  baseRepo: 'chenjian2664/ByteQuay',
  mergeQueueState: null,
  mergeQueueEnabled: false,
});

export const visualPullRequestCommits: PullRequestCommitDto[] = visualCommits.slice(0, 3).map(value => ({
  sha: value.sha,
  authorLogin: value.authorName.includes('Agent') ? 'bytequay-agent' : 'chenjian2664',
  authorName: value.authorName,
  authoredAt: value.authoredAt,
  message: value.subject,
}));

export const visualDetailPullRequestDetail: PullRequestDetailDto = cast<PullRequestDetailDto>({
  repo: 'trinodb/trino',
  number: VISUAL_DETAIL_PR_NUMBER,
  body: [
    '## Description',
    '',
    '- Add support for JOIN pushdown in Exasol Connector',
    '- Implement toWriteMapping in ExasolClient for DECIMAL type',
    '- Implement basic convertPredicate in ExasolClient',
  ].join('\n'),
  labels: ['exasol', 'notable'],
  draft: false,
  mergeable: true,
  mergeableState: 'clean',
  additions: 340,
  deletions: 4,
  changedFiles: 4,
  approvalCount: 2,
  changesRequestedCount: 0,
  pendingReviewerCount: 1,
  requestedReviewers: ['bytequay-agent'],
  ciStatus: 'PASSING',
  files: [
    { filename: 'plugin/trino-exasol/src/main/java/io/trino/plugin/exasol/ExasolClient.java', additions: 198, deletions: 2, status: 'modified' },
    { filename: 'plugin/trino-exasol/src/test/java/io/trino/plugin/exasol/TestExasolClientToWriteMapping.java', additions: 142, deletions: 2, status: 'modified' },
  ],
  recentActivity: [
    cast<ActivityItemDto>({
      actor: 'github-actions',
      eventType: 'labeled',
      timestamp: '2025-09-10T09:00:00.000Z',
      body: null,
      state: null,
      labelName: 'exasol',
      labelColor: 'ddf4ff',
      githubId: 2660301,
    }),
    cast<ActivityItemDto>({
      actor: 'skyglass',
      eventType: 'head_ref_force_pushed',
      timestamp: '2025-09-10T10:00:00.000Z',
      body: null,
      state: null,
      beforeSha: '88c13fe',
      afterSha: '13bff5d',
      githubId: 2660302,
    }),
    cast<ActivityItemDto>({
      actor: 'skyglass',
      eventType: 'cross-referenced',
      timestamp: '2025-09-11T09:00:00.000Z',
      body: null,
      state: null,
      crossRefNumber: 26558,
      crossRefTitle: '[Draft] Add support for JOIN PUSHDOWN',
      crossRefUrl: 'https://github.com/trinodb/trino/pull/26558',
      crossRefIsPullRequest: true,
      githubId: 2660303,
    }),
    cast<ActivityItemDto>({
      actor: 'ebyhr',
      eventType: 'reviewed',
      timestamp: '2025-09-11T10:00:00.000Z',
      body: null,
      state: 'COMMENTED',
      reviewId: 2660304,
      githubId: 2660304,
    }),
    cast<ActivityItemDto>({
      actor: 'skyglass',
      eventType: 'commented',
      timestamp: '2025-09-16T09:00:00.000Z',
      body: 'Thanks for the review! Implemented integration tests; also added a basic convertPredicate — a prerequisite for the upcoming JOIN_PUSHDOWN feature.',
      state: null,
      authorAssociation: 'OWNER',
      githubId: 2660305,
    }),
  ],
  checkRuns: [],
  reviewThreads: [
    cast({
      rootGithubId: 1,
      filePath: 'plugin/trino-exasol/src/test/java/io/trino/plugin/exasol/TestExasolClientToWriteMapping.java',
      resolved: true,
      outdated: false,
      messages: [],
    }),
    cast({
      rootGithubId: 2,
      filePath: 'plugin/trino-exasol/src/test/java/io/trino/plugin/exasol/TestExasolClientToWriteMapping.java',
      resolved: true,
      outdated: false,
      messages: [],
    }),
  ],
  linkedIssues: [
    {
      number: 26558,
      title: '[Draft] Add support for JOIN PUSHDOWN',
      state: 'open',
      htmlUrl: 'https://github.com/trinodb/trino/pull/26558',
    },
  ],
  viewerCanWrite: true,
  headRef: 'feature/750_to_write_mapping_decimal',
  headRepo: 'trinodb/trino',
  baseRef: 'master',
  baseRepo: 'trinodb/trino',
  mergeQueueState: null,
  mergeQueueEnabled: false,
  workspaceLinks: [
    { kind: 'trunk', title: 'Exasol pushdown', detail: 'thread', trunkId: VISUAL_TRUNK_ID },
    { kind: 'task', title: 'Task #21', detail: 'shipped this PR' },
    { kind: 'agent-review', title: 'Agent review round 1', detail: '6 comments', trunkId: VISUAL_TRUNK_ID },
  ],
  reviewers: [
    { login: 'chenjian2664', state: 'commented' },
    { login: 'Math-ias', state: 'approved' },
    { login: 'ebyhr', state: 'approved' },
    { login: 'bytequay-agent', state: 'requested' },
  ],
  assignees: [],
  milestone: { title: '481-cork-20260517', progressPercent: 64 },
  developmentLinks: [
    { number: 26558, title: '[Draft] Add support for JOIN PUSHDOWN', closes: true },
  ],
  participants: ['skyglass', 'ebyhr', 'chenjian2664', 'bytequay-agent', 'electrum', 'Math-ias', 'martint'],
  conversationCount: 18,
  checkCount: 17,
  syncedLabel: '24s ago',
  subscriptionReason: "You're notified because your review was requested.",
});

export const visualDetailPullRequestCommits: PullRequestCommitDto[] = [
  {
    sha: '13bff5d379a432d4b125e2892da4106aac859534',
    authorLogin: 'skyglass',
    authorName: 'skyglass',
    authoredAt: '2025-09-10T08:00:00.000Z',
    message: 'Add support for JOIN pushdown in Exasol Connector',
  },
];

export const visualThreadMessages: ThreadMessageDto[] = [
  cast<ThreadMessageDto>({
    id: 'message-user',
    threadId: VISUAL_TRUNK_ID,
    taskId: null,
    seq: 1,
    role: 'user',
    type: 'text',
    contentJson: JSON.stringify({
      text: "Let's replace the hand-rolled bounds checks with Math.clamp, and keep NaN behavior identical.",
    }),
    durationMs: null,
    tokensIn: null,
    tokensOut: null,
    costUsdMilli: null,
    ts: agoIso(30 * minute),
  }),
  cast<ThreadMessageDto>({
    id: 'message-assistant',
    threadId: VISUAL_TRUNK_ID,
    taskId: null,
    seq: 2,
    role: 'assistant',
    type: 'text',
    contentJson: JSON.stringify({
      text: 'Agreed — I scanned `functions/math` and found 4 call sites. Plan: swap to Math.clamp, add boundary tests, wire the suite into CI. Cutting task #14 for the first two steps.',
    }),
    durationMs: null,
    tokensIn: null,
    tokensOut: null,
    costUsdMilli: null,
    ts: agoIso(24 * minute),
  }),
  cast<ThreadMessageDto>({
    id: 'message-question',
    threadId: VISUAL_TRUNK_ID,
    taskId: null,
    seq: 3,
    role: 'assistant',
    type: 'text',
    contentJson: JSON.stringify({
      text: 'Question before I touch serialization: keep the legacy field order in `toMessage`? Downstream consumers may rely on it.',
    }),
    durationMs: null,
    tokensIn: null,
    tokensOut: null,
    costUsdMilli: null,
    ts: agoIso(12 * minute),
  }),
];

export const visualIndexPage: ConvIndexPageDto = {
  threadId: VISUAL_TRUNK_ID,
  totalUserMessages: 1,
  entries: [{
    seq: 1,
    preview: "Let's replace the hand-rolled bounds checks with Math.clamp",
    tsMs: agoMs(30 * minute),
  }],
  messages: visualThreadMessages,
  loadedFromSeq: 1,
  nextCursor: null,
};

export const visualThreadTurns: ThreadTurnDto[] = [
  cast<ThreadTurnDto>({
    id: 'turn-running',
    threadId: VISUAL_TRUNK_ID,
    input: 'dev step 3/6 · writing tests',
    status: 'RUNNING',
    queuedAt: agoIso(12 * minute),
    startedAt: agoIso(12 * minute),
    finishedAt: null,
  }),
];

export const visualLegacyNotifications: NotificationDto[] = [
  cast<NotificationDto>({
    id: 'home-question',
    kind: 'NEEDS_ATTENTION',
    threadId: VISUAL_TRUNK_ID,
    taskId: 'task-14b',
    status: 'UNREAD',
    payloadJson: JSON.stringify({
      title: 'Agent question in Codex v2',
      message: '"Keep legacy field order in toMessage?" — session paused, waiting on you',
    }),
    createdAt: agoIso(hour),
    readAt: null,
  }),
  cast<NotificationDto>({
    id: 'home-ci',
    kind: 'NEEDS_ATTENTION',
    threadId: VISUAL_TRUNK_ID,
    taskId: 'task-14b',
    status: 'UNREAD',
    payloadJson: JSON.stringify({
      title: `CI failed on ${VISUAL_BRANCH_NAME}`,
      message: 'clamp boundary suite · 2 failures · task #14',
    }),
    createdAt: agoIso(3 * hour),
    readAt: null,
  }),
];

export const visualDashboardPrs: DashboardPR[] = visualPullRequests.slice(0, 6).map(value => ({
  ...value,
  id: `${value.repo}#${value.number}`,
  updatedAt: value.updatedAt,
}));

export const visualWatchedRepos: WatchedRepoDto[] = visualWorkspaces.map((workspace, index) => ({
  id: index + 1,
  owner: workspace.repository?.owner ?? 'owner',
  repo: workspace.repository?.repo ?? workspace.repos[0],
  displayOrder: index,
  localClonePath: workspace.repository?.clonePath ?? null,
}));

export const visualWorkspaceRepos: WorkspaceRepoDto[] = visualWorkspaces.map(workspace => ({
  workspaceId: workspace.id,
  repoFullName: workspace.repository?.fullName ?? workspace.repos[0],
  defaultBaseBranch: workspace.repository?.defaultBaseBranch ?? null,
  autoFixEnabled: false,
  addedAt: agoIso(30 * day),
}));

export function installWorkspaceVisualBridge(frame: string): void {
  const route = async <T>(request: WorkspaceApiRequest): Promise<T> =>
    cast<T>(workspaceResponse(frame, request));
  const noEvent = () => () => {};
  const empty = async (): Promise<never[]> => [];
  const base: Partial<Bridge> = {
    workspaceApi: route,
    hasPat: async () => true,
    getFullScreenState: async () => false,
    onFullScreenChange: noEvent,
    onInAppOpenRequest: noEvent,
    onAppNavRequest: noEvent,
    onReviewAuthBlocked: noEvent,
    onReviewSignInPage: noEvent,
    onReviewNavState: noEvent,
    onInAppNavState: noEvent,
    onGitHubOauthComplete: noEvent,
    listWorkspaces: async () => frame === '6a' ? [visualSyncWorkspace] : visualWorkspaces,
    listTasks: async () => frame === '6a' ? [] : frame === '2b' ? visualTodayThreads : visualThreads,
    listTasksForThread: async (threadId: string) =>
      visualTasks.filter(value => value.threadId === threadId),
    listActiveTaskTurns: async () => visualThreadTurns,
    getWatchedRepos: async () => visualWatchedRepos,
    listWorkspaceRepos: async (workspaceId: string) =>
      visualWorkspaceRepos.filter(value => value.workspaceId === workspaceId),
    fetchPrs: async () => visualPullRequests,
    getFootprints: async () => ({
      date: '2026-07-17',
      stops: [
        {
          surfaceType: 'PR',
          surfaceId: 'trinodb/trino#30336',
          title: 'trinodb/trino #30336',
          context: 'trinodb/trino',
          latestVisitAt: new Date(agoMs(5 * hour)).toISOString(),
          visitCount: 1,
        },
        {
          surfaceType: 'TASK',
          surfaceId: 'recent-eng-17779',
          title: 'ENG-17779 Add Query Tag Support',
          context: 'starburstdata/stargate',
          latestVisitAt: new Date(agoMs(4 * hour)).toISOString(),
          visitCount: 1,
        },
        {
          surfaceType: 'THREAD',
          surfaceId: VISUAL_TRUNK_ID,
          title: 'Codex v2',
          context: 'bytequay',
          latestVisitAt: new Date(agoMs(3 * hour)).toISOString(),
          visitCount: 4,
        },
        {
          surfaceType: 'TASK',
          surfaceId: 'recent-starburst-credentials',
          title: 'Add starburst-credentials shared co…',
          context: 'stargate',
          latestVisitAt: new Date(agoMs(hour)).toISOString(),
          visitCount: 2,
        },
      ],
      totalStops: 4,
    }),
    fetchDashboardPrs: async () => visualDashboardPrs,
    listNotifications: async () => visualLegacyNotifications,
    listUnreadNotifications: async () => visualLegacyNotifications,
    listNotificationsForThread: async () => [],
    getTask: async () => visualThreads.find(value => value.id === VISUAL_TRUNK_ID) ?? null,
    getTaskIndex: async () => visualIndexPage,
    listBacklog: async () => [],
    listThreadSignals: async () => [],
    listThreadQuestions: async () => [{
      id: 'question-field-order',
      threadId: VISUAL_TRUNK_ID,
      taskId: null,
      question: 'Keep legacy field order in toMessage?',
      context: 'Downstream consumers may rely on it.',
      options: [
        { id: 'keep', label: 'Keep order', extra: null },
        { id: 'reorder', label: 'Reorder freely', extra: null },
      ],
      allowFreeForm: false,
      status: 'open',
      answerOptionId: null,
      answerFreeForm: null,
      createdAt: agoMs(12 * minute),
      answeredAt: null,
    }],
    subscribeTaskStream: () => () => {},
    getReviewPassByThread: async () => null,
    getAgentReviewByThread: async () => null,
    getWorkspaceInsights: async () => visualInsights,
    getUserProfile: async () => cast({
      login: 'chenjian2664',
      name: 'Jack Chen',
      avatarUrl: '',
      htmlUrl: 'https://github.com/chenjian2664',
      publicRepos: 72,
      followers: 18,
      following: 21,
      bio: 'Builder',
      location: 'Singapore',
      company: null,
      email: null,
      hasSponsors: false,
    }),
    getRecentActivity: empty,
    getFollowingActivity: empty,
    listTeams: empty,
    recordSurfaceVisit: async () => undefined,
    openInAppBrowser: async () => undefined,
    openExternal: async () => undefined,
    getDs4Status: async () => cast({
      state: 'DISABLED',
      endpoint: '',
      pid: 0,
      startedAt: null,
      spawnedByUs: false,
      restartAttempts: 0,
      uptimeSec: 0,
      lastError: null,
    }),
    getThreadWorkModel: async () => cast({
      override: null,
      effective: {
        kind: 'API',
        agentOrProvider: 'anthropic',
        model: 'claude-sonnet-4.5',
        account: null,
      },
      provenance: {
        source: 'WORKSPACE',
        scopeId: VISUAL_WORKSPACE_ID,
        scopeLabel: 'bytequay-v3-test',
      },
    }),
    getWorkModelOptions: async () => cast({
      cliAgents: [],
      apiProviders: [],
    }),
  };
  const eventNames = new Set([
    'onFullScreenChange',
    'onInAppOpenRequest',
    'onAppNavRequest',
    'onReviewAuthBlocked',
    'onReviewSignInPage',
    'onReviewNavState',
    'onInAppNavState',
    'onGitHubOauthComplete',
  ]);
  window.bridge = new Proxy(base as Record<PropertyKey, unknown>, {
    get(target, property) {
      const found = Reflect.get(target, property);
      if (found !== undefined) return found;
      if (typeof property === 'string' && (property.startsWith('on') || eventNames.has(property))) {
        return noEvent;
      }
      return async (): Promise<never[]> => [];
    },
  }) as unknown as Bridge;
}

function workspaceResponse(frame: string, request: WorkspaceApiRequest): unknown {
  const path = request.path;
  if (path === '/api/workspaces') {
    return frame === '6a' ? [visualSyncWorkspace] : visualWorkspaces;
  }
  if (path === '/api/workspace-creations') {
    return frame === '6d' ? [visualCreationLive, visualCreationReady] : [];
  }
  if (path.includes('/repository')) return visualRepository;
  if (path.endsWith('/pull-requests')) return visualWorkspacePullRequests;
  if (new RegExp(`/pull-requests/${VISUAL_DETAIL_PR_NUMBER}$`).test(path)) {
    return visualDetailPullRequest;
  }
  if (path.endsWith(`/pull-requests/${VISUAL_DETAIL_PR_NUMBER}/detail`)) {
    return visualDetailPullRequestDetail;
  }
  if (path.endsWith(`/pull-requests/${VISUAL_DETAIL_PR_NUMBER}/commits`)) {
    return visualDetailPullRequestCommits;
  }
  if (new RegExp(`/pull-requests/${VISUAL_PR_NUMBER}$`).test(path)) {
    return visualWorkspacePullRequests.find(value => value.number === VISUAL_PR_NUMBER);
  }
  if (path.endsWith(`/pull-requests/${VISUAL_PR_NUMBER}/detail`)) return visualPullRequestDetail;
  if (path.endsWith(`/pull-requests/${VISUAL_PR_NUMBER}/commits`)) return visualPullRequestCommits;
  if (path.includes('/issues?')) return visualIssues;
  if (path.endsWith(`/issues/${VISUAL_ISSUE_NUMBER}`)) return visualIssueDetail;
  if (path.endsWith(`/issues/${VISUAL_ISSUE_NUMBER}/trunks`)) return [VISUAL_TRUNK_ID];
  if (path.endsWith('/trunks')) {
    if (frame === '5b') {
      const order = [VISUAL_TRUNK_ID, 'trunk-clean-code', 'trunk-codex-test'];
      return order.map(id => visualTrunks.find(value => value.id === id));
    }
    return visualTrunks;
  }
  if (path.includes('/backlog/')) {
    const key = decodeURIComponent(path.slice(path.lastIndexOf('/') + 1));
    if (key === VISUAL_BACKLOG_KEY) return visualBacklogDetail;
    return visualBacklog.find(value => value.key === key) ?? visualBacklog[0];
  }
  if (path.endsWith('/backlog')) return visualBacklog;
  if (path.includes('/branches/comparison')) return visualComparison;
  if (path.endsWith('/branches')) {
    return frame === '4d'
      ? [...visualBranches, branch('release/482', { lastCommitAt: agoIso(2 * day) })]
      : visualBranches;
  }
  if (path.includes('/commits/') && path.endsWith('/files')) {
    if (frame === '4a') {
      return [
        { path: 'core/trino-main/src/main/java/io/trino/operator/scalar/MathFunctions.java', status: 'M', additions: 42, deletions: 24 },
        { path: 'core/trino-main/src/test/java/io/trino/operator/scalar/TestMathFunctions.java', status: 'M', additions: 31, deletions: 11 },
        { path: 'core/trino-main/src/test/java/io/trino/operator/scalar/ClampBoundaryTest.java', status: 'A', additions: 13, deletions: 0 },
        { path: '.github/workflows/validation.yml', status: 'M', additions: 0, deletions: 6 },
      ] satisfies LocalCommitFileDto[];
    }
    return [
      { path: 'frontend/src/workspace/WorkspaceRepoPage.tsx', status: 'M', additions: 62, deletions: 18 },
      { path: 'backend/src/test/java/TestClamp.java', status: 'M', additions: 152, deletions: 20 },
    ] satisfies LocalCommitFileDto[];
  }
  if (path.includes('/commits/')) {
    const sha = decodeURIComponent(path.slice(path.lastIndexOf('/') + 1));
    const selected = visualCommits.find(value => value.sha === sha) ?? visualCommits[0];
    return {
      sha: selected.sha,
      subject: selected.subject,
      body: frame === '4a'
        ? 'Replace the hand-rolled bounds checks in MathFunctions with JDK 21 Math.clamp. Keeps NaN propagation identical; adds boundary tests for MIN/MAX_VALUE and negative-zero.\n\nRefs task #14 · reviewed in round 2 of #148.'
        : `${selected.subject}\n\nPreserve compatibility while moving validation into the shared CI path.`,
    } satisfies LocalCommitDetailDto;
  }
  if (path.includes('/commits?')) {
    return frame === '4a'
      ? visualCommits.slice(0, 3).map(value => ({ ...value, groupLabel: 'Today' }))
      : visualCommits;
  }
  if (path.endsWith('/sessions')) return visualSessions;
  if (path.includes('/api/sessions/')) {
    const id = path.split('/')[3];
    return visualSessions.find(value => value.id === id) ?? visualSessions[0];
  }
  if (path.endsWith('/memory/aggregate')) return visualMemory(frame);
  if (path.endsWith('/settings')) return visualSettings;
  if (path.endsWith('/onboarding')) return visualOnboarding(frame);
  if (path.endsWith('/notifications')) return visualCanonicalNotifications;
  if (path.endsWith('/notifications/mutes')) return visualNotificationMutes;
  const activityPath = path.match(/^\/api\/trunks\/([^/]+)\/activity$/);
  if (activityPath !== null) {
    return {
      trunkId: decodeURIComponent(activityPath[1]),
      pinned: [
        {
          id: 'activity-question',
          kind: 'question',
          title: 'Agent question — field order in toMessage',
          summary: 'Downstream consumers may rely on it.',
          status: 'needs-you',
          itemPath: null,
          taskId: null,
          sessionId: VISUAL_SESSION_ID,
          occurredAt: agoMs(12 * minute),
          actionable: true,
        },
        {
          id: 'activity-review',
          kind: 'review',
          title: '#148 review round 2 — 2 unresolved',
          summary: '6 comments · 2 blocking',
          status: 'awaiting-review',
          itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/prs/${VISUAL_PR_NUMBER}`,
          taskId: null,
          sessionId: null,
          occurredAt: agoMs(hour),
          actionable: true,
        },
      ],
      timeline: [
        {
          id: 'activity-session',
          kind: 'session',
          title: 'Session running — dev step 3/6, writing tests',
          summary: '$0.31 · 12m',
          status: 'running',
          itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/sessions/${VISUAL_SESSION_ID}`,
          taskId: null,
          sessionId: VISUAL_SESSION_ID,
          occurredAt: now,
          actionable: false,
        },
        {
          id: 'activity-task',
          kind: 'task',
          title: 'Task #14 merged — Math.clamp expressions',
          summary: 'PR #150 · +86 −41',
          status: 'done',
          itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/prs/150`,
          taskId: 'task-14a',
          sessionId: null,
          occurredAt: agoMs(26 * minute),
          actionable: false,
        },
        {
          id: 'activity-review-posted',
          kind: 'review',
          title: 'Review round 2 posted on #148',
          summary: '6 comments · 2 blocking',
          status: 'posted',
          itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/prs/${VISUAL_PR_NUMBER}`,
          taskId: null,
          sessionId: null,
          occurredAt: agoMs(hour),
          actionable: false,
        },
        {
          id: 'activity-backlog',
          kind: 'backlog',
          title: 'Backlog +1 — "Extract clamp helpers into shared util"',
          summary: 'suggested by agent',
          status: 'open',
          itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/backlog/BQ-1`,
          taskId: null,
          sessionId: null,
          occurredAt: agoMs(2 * hour),
          actionable: false,
        },
        {
          id: 'activity-ci',
          kind: 'ci',
          title: `CI failed on ${VISUAL_BRANCH_NAME}`,
          summary: 'boundary suite · fixed by 7be90d4',
          status: 'failed',
          itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/branches/${encodeURIComponent(VISUAL_BRANCH_NAME)}`,
          taskId: 'task-14b',
          sessionId: null,
          occurredAt: agoMs(3 * hour),
          actionable: false,
        },
        {
          id: 'activity-memory',
          kind: 'memory',
          title: 'Distilled into memory — 1 decision added',
          summary: 'view diff',
          status: 'applied',
          itemPath: `#/workspace/${VISUAL_WORKSPACE_ID}/memory`,
          taskId: null,
          sessionId: null,
          occurredAt: agoMs(2 * hour),
          actionable: false,
        },
      ],
      taskCount: 2,
      pullRequestCount: 1,
      costUsdMilli: 910,
      generatedAt: now,
    };
  }
  if (path.endsWith('/overview')) {
    return {
      workspace: frame === '6a' ? visualSyncWorkspace : visualWorkspaces[0],
      repository: frame === '6a'
        ? {
            owner: 'trinodb',
            repo: 'trino-python-client',
            fullName: 'trinodb/trino-python-client',
            defaultBaseBranch: 'master',
            clonePath: '/Users/chenjian2664/trino-python-client',
            verified: true,
          }
        : repositoryIdentity,
      sidebarCounts: {
        todayNeedsYou: 3,
        trunks: 12,
        pullRequests: 7,
        issues: 14,
        backlog: 5,
        branches: 3,
        sessions: 1,
        notifications: 28,
      },
      pinnedTrunks: visualTrunks.slice(0, 2),
      today: {
        needsYou: visualTrunks.slice(1, 3),
        running: visualTrunks.slice(0, 1),
        landedToday: visualTrunks.slice(4, 7),
        spendTodayMilliUsd: 1400,
      },
      onboarding: visualOnboarding(frame),
      syncState: frame === '6a' ? 'syncing' : 'ready',
    };
  }
  if (path.includes('/refresh')) return visualRepository.local;
  return [];
}
