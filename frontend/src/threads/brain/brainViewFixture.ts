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
import type { TaskBrainViewData } from '../../types/brainView';

/**
 * A blank brain view used as the hook's initial state before the first real
 * fetch lands. Returning to the Root node from a stage remounts the brain
 * page, so without this the hook would seed the *mock* fixture and paint fake
 * data ("Cost-meter widget", PR #5680) for one cycle. An empty seed keeps the
 * page chrome but shows nothing fake until the real payload arrives.
 */
export function buildEmptyBrainView(taskId: string): TaskBrainViewData {
  return {
    task: {
      id: taskId, title: '', taskNumber: 0, branch: '', repoFullName: '',
      prNumber: null, prDraft: false, currentPhase: 'QUEUED',
      statusLabel: '', agentRuntime: 'CLI', agentModel: '',
      paused: false, terminal: false,
    },
    aggregate: {
      pushes: 0, activeTimeSec: 0, waitingUserTimeSec: 0, toolCalls: 0,
      turns: 0, messages: 0, panels: 0, costCents: 0, autoPushBudget: null,
    },
    stages: [],
    subStages: [],
    brainThreadId: null,
    brainFeed: [],
    rightRail: {
      approval: null,
      linkedPr: null,
      context: { tokensUsed: 0, tokensLimit: 0, safeBand: 'safe' },
      recentCommits: [],
      panelSpawnable: false,
      parentStageId: null,
      costBreakdown: { totalCents: 0, perStage: [], perAgent: [], costPerPush: null },
      plan: null,
    },
    scrubbers: { stageEvents: [], userMessages: [] },
    liveRuns: [],
    guard: {
      taskId, enabled: false, schedule: 'nightly', state: 'healthy',
      health: { behindBy: 0, mergeable: true, checksGreen: true },
      lastRunId: null, lastCheckedAt: null,
    },
    liveRound: null,
    devPhases: [],
  };
}

/**
 * Static fixture backing the brain view while the backend brain
 * endpoint doesn't exist yet. Matches the locked mockup: the cost-meter
 * task with four lifecycle stages (Dev closed, CiFixing active,
 * ReviewMonitor idle, Cleanup not-yet-opened), two ReviewStage panels,
 * a feed of system events plus one user question and the brain's reply,
 * a pending push approval, and a linked draft PR.
 *
 * Timestamps are computed relative to `nowMs` — the moment the view is
 * rendered — so the running app always shows fresh relative labels
 * ("14m ago", "now") no matter how long the bundle has been loaded.
 * (Anchoring to module-load time instead would drift: a page opened an
 * hour after launch would read "1h ago" for the live row.) Pass a fixed
 * `nowMs` in tests for deterministic relative output.
 */
const DEV = 'stage-dev';
const CIFIX = 'stage-cifix';
const REVMON = 'stage-revmon';
const CLEANUP = 'stage-cleanup';
const REVIEW1 = 'substage-review-1';
const REVIEW2 = 'substage-review-2';

export function buildMockBrainView(nowMs: number): TaskBrainViewData {
  const ago = (minutes: number): string =>
    new Date(nowMs - minutes * 60_000).toISOString();

  return {
  task: {
    id: '7c5cff00-0000-4000-8000-000000000002',
    title: 'Cost-meter widget · workspace sidebar',
    taskNumber: 2,
    branch: 'jack/cost-meter',
    repoFullName: 'trinodb/trino',
    prNumber: 5680,
    prDraft: true,
    currentPhase: 'PUSHED_AWAITING_CI',
    statusLabel: 'Fixing CI · iter #3',
    agentRuntime: 'CLI',
    agentModel: 'sonnet-3.7',
    paused: false,
    terminal: false,
  },
  aggregate: {
    pushes: 4,
    activeTimeSec: 12 * 60,
    waitingUserTimeSec: 23 * 60,
    toolCalls: 89,
    turns: 31,
    messages: 412,
    panels: 2,
    costCents: 147,
    autoPushBudget: { used: 5, limit: 5 },
  },
  stages: [
    {
      id: DEV, taskId: '...', type: 'DEVELOPMENT_STAGE', state: 'CLOSED',
      openedAt: ago(14), closedAt: ago(12), callerStageId: null,
      summary: 'Implemented the cost-meter widget and wired it to the metrics service.',
      loopIteration: 1,
    },
    {
      id: CIFIX, taskId: '...', type: 'CI_FIXING_STAGE', state: 'ACTIVE',
      openedAt: ago(10), closedAt: null, callerStageId: null,
      summary: 'Driving CI back to green; iteration #3 awaiting a push approval.',
      loopIteration: 3,
    },
    {
      id: REVMON, taskId: '...', type: 'REVIEW_MONITOR_STAGE', state: 'PAUSED',
      openedAt: ago(10), closedAt: null, callerStageId: null,
      summary: 'Watching for new review comments; addressed one so far.',
      loopIteration: 1,
    },
    {
      id: CLEANUP, taskId: '...', type: 'CLEANUP_STAGE', state: 'OPEN',
      openedAt: ago(10), closedAt: null, callerStageId: null,
      summary: 'Final tidy-up before ready-for-merge.',
      loopIteration: 0,
    },
  ],
  subStages: [
    {
      id: REVIEW1, taskId: '...', type: 'REVIEW_STAGE', state: 'CLOSED',
      openedAt: ago(13), closedAt: ago(13), callerStageId: DEV,
      summary: 'from Dev · 3 findings · agreed',
      loopIteration: 0,
    },
    {
      id: REVIEW2, taskId: '...', type: 'REVIEW_STAGE', state: 'CLOSED',
      openedAt: ago(5), closedAt: ago(5), callerStageId: REVMON,
      summary: 'from ReviewMon · 1 finding',
      loopIteration: 0,
    },
  ],
  brainThreadId: 'brain-thread-fixture',
  brainFeed: [
    {
      id: 'feed-1', messageSeq: null, type: 'STAGE_OPENED', stageId: DEV, stageType: 'DEVELOPMENT_STAGE',
      ts: ago(14), referencedStageId: null, images: [],
      body: 'First iteration: implement the cost-meter widget below the memory peek '
        + 'card. Wire to `WorkspaceMetricsService.totalCostMilli()`.',
    },
    {
      id: 'feed-2', messageSeq: null, type: 'PANEL_REVIEW_COMPLETED', stageId: REVIEW1, stageType: 'REVIEW_STAGE',
      ts: ago(13), referencedStageId: REVIEW1, images: [],
      body: 'Internal review panel ran with **DeepSeek + Java expertise X + Skimmed '
        + 'reviewer**. 3 findings, all AGREED. Addressed them in the follow-up code pass.',
    },
    {
      id: 'feed-3', messageSeq: null, type: 'PUSHED_PR_CREATED', stageId: DEV, stageType: 'DEVELOPMENT_STAGE',
      ts: ago(12), referencedStageId: null, images: [],
      body: 'Branch `jack/cost-meter` pushed to origin. Draft PR `#5680` created. '
        + '**DevelopmentStage closed**. **CiFixingStage** and **ReviewMonitorStage** armed.',
    },
    {
      id: 'feed-4', messageSeq: null, type: 'ITERATION_SUMMARY', stageId: CIFIX, stageType: 'CI_FIXING_STAGE',
      ts: ago(6), referencedStageId: CIFIX, images: [],
      body: '**Fix #1:** bumped retry-count default 3 → 5 in `RetryConfig.java`; tests '
        + 'pass. Auto-pushed (1/5 used). CI green after the fix.',
    },
    {
      id: 'feed-5', messageSeq: null, type: 'ITERATION_SUMMARY', stageId: REVMON, stageType: 'REVIEW_MONITOR_STAGE',
      ts: ago(5), referencedStageId: REVMON, images: [],
      body: '**Iter #1:** @jane left an inline comment on `CostMeter.tsx#L42` asking for '
        + 'a memoization. Addressed it; you approved the push. Pushed (1 push used).',
    },
    {
      id: 'feed-6', messageSeq: 6, type: 'USER_MESSAGE', stageId: null, stageType: null,
      ts: ago(2), referencedStageId: null, images: [],
      body: 'Are all the changes covered by tests?',
    },
    {
      id: 'feed-7', messageSeq: 7, type: 'BRAIN_AGENT_RESPONSE', stageId: null, stageType: null,
      ts: ago(2), referencedStageId: null, images: [],
      body: 'Yes — **4 new test files** were added in DevelopmentStage and **2 more** in '
        + 'ReviewMonitorStage iter #1. The retry-count change in CiFixingStage iter #1 '
        + "doesn't have a dedicated test — covered transitively by the existing "
        + '`RetryConfigTest` suite (10 tests).',
    },
    {
      id: 'feed-8', messageSeq: null, type: 'ITERATION_SUMMARY', stageId: CIFIX, stageType: 'CI_FIXING_STAGE',
      ts: ago(0), referencedStageId: CIFIX, images: [],
      body: 'Detected red CI: `linter warning unused-import` in `CostMeter.tsx`. Agent '
        + 'addressing now — awaiting push approval (budget exhausted: **5/5 used**).',
    },
  ],
  rightRail: {
    approval: {
      stageId: CIFIX,
      stageTitle: 'CiFixingStage · iter #3 · push',
      reasonShort: 'Auto-push budget exhausted (5/5)',
      pendingArtifact: 'CostMeter.tsx — remove unused import',
      primaryAction: { label: 'Review & approve push', href: '#approve' },
    },
    linkedPr: {
      number: 5680,
      branch: 'jack/cost-meter',
      status: 'draft',
      ciStatus: 'failing',
      ciSummary: '1 failing',
      reviewersApproved: 2,
      reviewersTotal: 3,
      conflictsState: 'none',
      mergeable: false,
    },
    context: {
      tokensUsed: 86_000,
      tokensLimit: 200_000,
      safeBand: 'safe',
    },
    recentCommits: [
      {
        sha: '6be742d9',
        subject: 'Remove raw backend thread starts',
        authoredAt: ago(21 * 60),
      },
    ],
    panelSpawnable: false,
    parentStageId: null,
    costBreakdown: {
      totalCents: 147,
      perAgent: [{ agentKind: 'dev', costCents: 132 }, { agentKind: 'brain', costCents: 15 }],
      perStage: [{ stageId: 'cifix', stageType: 'CI_FIXING_STAGE', costCents: 88 }],
      costPerPush: 49,
    },
    // The mock fixture is a mid-development task whose plan is already
    // approved-and-locked; PlanStage-specific frames are exercised in the
    // PlanCard component test.
    plan: null,
  },
  scrubbers: {
    stageEvents: [
      { id: 'feed-1', label: '14m · Dev stage opened', active: false },
      { id: 'feed-2', label: '13m · Panel review completed', active: false },
      { id: 'feed-3', label: '12m · Pushed · PR #5680 created', active: false },
      { id: 'feed-4', label: '6m · CI fix #1 summary', active: false },
      { id: 'feed-5', label: '5m · Review comment addressed', active: false },
      { id: 'feed-8', label: 'now · CI fix #3 running', active: true },
    ],
    userMessages: [
      { id: 'feed-6', label: "2m · 'Are all changes covered by tests?'", active: true },
    ],
  },
  liveRuns: [
    {
      id: CIFIX, taskId: '...', kind: 'ci_fix', source: 'remote', parentStageId: null,
      reviewRoundId: null, stageId: CIFIX, status: 'running', iterations: 3, budget: 5,
      headline: 'iter #3 — fixing linter warning', startedAt: ago(10), finishedAt: null,
    },
  ],
  guard: {
    taskId: '...', enabled: true, schedule: 'nightly', state: 'healthy',
    health: { behindBy: 0, mergeable: true, checksGreen: true },
    lastRunId: null, lastCheckedAt: ago(20 * 60),
  },
  liveRound: null,
  devPhases: [
    { key: 'implementing', status: 'done', meta: null, badgeRunId: null },
    { key: 'validation', status: 'done', meta: null, badgeRunId: null },
    { key: 'brainReview', status: 'future', meta: 'next', badgeRunId: null },
  ],
  };
}
