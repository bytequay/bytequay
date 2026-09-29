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
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import AppSidebar from '../../AppSidebar';
import { AgentHomeView } from '../../agents/AgentHome';
import AgentTasksView from '../../agents/AgentTasksView';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import type { AgentTaskDto, Bridge, WorkspaceDto } from '../../types';
import { workspaceLocationFromRoute } from '../../workspaces/model';
import type {
  ContextBuildViewDto, ContextDiscoveryReportDto, ContextEntryDto, ContextKnowledgeDraftDto, ContextLearningPlanDto, ContextLearningPlanViewDto,
  ContextMergedCandidatesDto, ContextRunDto, ContextSourceDto, ContextTimesDto, ContextUsageOverviewDto, ContextUsageWindow, RepositoryNotebookDto,
} from '../contracts';
import { TaskContextDrafts } from '../ContextCards';
import RepositoryContextPage from '../RepositoryContextPage';
import { useContextReview } from '../reviewCount';
import '../../index.css';

/* Everything here is an in-memory stand-in for the backend: no model runs, no repository or workspace notes are touched. */

const NOW = Date.now();
const ago = (minutes: number) => new Date(NOW - minutes * 60_000).toISOString();
const SHA = 'a3f9c2e1d4b5a6978812cdef0123456789abcdef';
const workspace = { id: 'ws-1', name: 'Trino', selectedOwner: 'trinodb', selectedRepo: 'trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino',
  localRepositoryPath: '/tmp/fixture/trino', lastCodeSyncAt: ago(30), lastMetadataSyncAt: ago(30) } as WorkspaceDto;
const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
const clone = <T,>(value: T): T => structuredClone(value);
function assert(condition: unknown, message: string): asserts condition { if (!condition) throw new Error(message); }

const ICEBERG = 'path:plugin/trino-iceberg/';
const PLANNER = 'path:core/trino-main/src/main/java/io/trino/sql/planner/';
const SPI = 'path:core/trino-spi/';

function note(id: string, scopeId: string, title: string, summary: string, version: number, patch: Partial<ContextEntryDto> = {}): ContextEntryDto {
  return { id, kind: 'KNOWLEDGE', scopeId, title, summary, version, origin: 'GENERATED', enabled: true, sourceIds: ['ev-code'],
    body: '## How it works\n- `IcebergMetadata` loads the table through the catalog on first use in a transaction.\n- Later reads in the same transaction reuse it.',
    claims: [{ text: 'Metadata is cached per transaction, not per query.', basis: 'OBSERVED', sourceIds: ['ev-code'] },
      { text: 'Refreshing mid-transaction would break snapshot isolation.', basis: 'INFERENCE', sourceIds: [] }], ...patch };
}

const refresh = note('knowledge:iceberg-refresh', ICEBERG, 'Iceberg table metadata refresh',
  'Table metadata is loaded once per transaction through `TrinoCatalog`; reads never refresh mid-query.', 4);
const lesson = note('lesson:nested-fields', ICEBERG, 'Resolve nested fields within their parent',
  'Nested structs can reuse a leaf name; look the field ID up under its parent struct.', 3,
  { kind: 'MEMORY', origin: 'USER', body: 'Inspect the parent struct before selecting a leaf field ID.\nKeep a regression example with duplicate leaf names.',
    claims: [{ text: 'Duplicate leaf names occur in nested structs.', basis: 'USER_LESSON', sourceIds: [] }] });
const layout = note('knowledge:layout', 'project', 'Build and module layout',
  'Maven reactor with `core/`, `plugin/` and `lib/`; connectors live under `plugin/trino-*`.', 2,
  { body: 'Run `./mvnw -pl <module> -am` to build one module with its dependencies.', claims: [{ text: 'Every connector is its own Maven module.', basis: 'DOCUMENTED', sourceIds: ['ev-doc'] }],
    sourceIds: ['ev-doc'] });
const spi = note('knowledge:spi-compat', SPI, 'SPI compatibility rules', 'Changing an SPI method needs a deprecation cycle; connectors outside the tree depend on it.', 5,
  { claims: [{ text: 'SPI changes need a deprecation release.', basis: 'RECORDED_DECISION', sourceIds: [] }], sourceIds: [] });
const rules = note('knowledge:rule-order', PLANNER, 'Optimizer rule ordering', 'Iterative optimizer rules run in the order `PlanOptimizers` registers them.', 6,
  { enabled: false });

const newDraft: ContextKnowledgeDraftDto = { id: 'draft:learn:commit-retries', baselineSha: SHA, replacesVersion: null,
  entry: note('knowledge:iceberg-commit-retries', ICEBERG, 'How Iceberg commits retry on conflict',
    'Commits retry through `Tasks.foreach` with exponential backoff when the catalog reports a conflicting snapshot.', 0, { sourceIds: ['ev-code', 'ev-doc'] }) };
const taskDraft: ContextKnowledgeDraftDto = { id: 'draft:task:snapshot-check', baselineSha: SHA, replacesVersion: null,
  entry: note('lesson:snapshot-check', ICEBERG, 'Check the snapshot ID before retrying a commit', 'A retried commit must re-read the current snapshot first.', 0,
    { kind: 'MEMORY', origin: 'GENERATED', claims: [{ text: 'Retrying with a stale snapshot ID silently drops the other writer’s files.', basis: 'OBSERVED', sourceIds: ['ev-code'] }] }) };
const updateDraft: ContextKnowledgeDraftDto = { id: 'draft:learn:refresh-update', baselineSha: SHA, replacesVersion: 4,
  entry: { ...refresh, version: 0, sourceIds: ['ev-code', 'ev-doc'],
    body: '## How it works\n- `IcebergMetadata` loads the table through the catalog on first use in a transaction.\n- Later reads in the same transaction reuse it.\n- `REFRESH` procedures drop the cached table explicitly.',
    claims: [...refresh.claims, { text: 'The `system.flush_metadata_cache` procedure clears cached tables.', basis: 'DOCUMENTED', sourceIds: ['ev-doc'] }] } };

function notebook(full: boolean): RepositoryNotebookDto {
  return { schemaVersion: 1, revision: full ? 12 : 1, useInReviews: true, useInDevelopment: true,
    sources: full ? [{ id: 'docs-trino', kind: 'DOCUMENTATION_ROOT', url: 'https://trino.io/docs/current/', origin: 'USER', discoveryEvidenceRef: null }] : [],
    scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, discoveryEvidenceRef: null },
      ...full ? [
        { id: ICEBERG, title: 'Iceberg connector', match: { kind: 'SELECTED' as const, pathPrefixes: ['plugin/trino-iceberg/'], terms: [] }, enabled: true,
          discoveryEvidenceRef: 'read:1' },
        { id: PLANNER, title: 'Query planner', match: { kind: 'SELECTED' as const, pathPrefixes: ['core/trino-main/src/main/java/io/trino/sql/planner/'], terms: [] },
          enabled: true, discoveryEvidenceRef: 'read:2' },
        { id: SPI, title: 'Connector SPI', match: { kind: 'SELECTED' as const, pathPrefixes: ['core/trino-spi/'], terms: [] }, enabled: true,
          discoveryEvidenceRef: null }] : []],
    entries: full ? [refresh, lesson, layout, spi, rules] : [],
    pendingDrafts: full ? [newDraft, taskDraft, updateDraft] : [],
    sourceEvidence: [
      { id: 'ev-code', reference: { kind: 'REPOSITORY_FILE', repositoryId: 1, repository: { owner: 'trinodb', repo: 'trino' },
        path: 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java', commit: SHA },
      provenance: 'lines 412-468', capture: 'BYTEQUAY_READ', retrievedAt: ago(40) },
      { id: 'ev-doc', reference: { kind: 'DOCUMENT', sourceId: 'docs-trino', url: 'https://trino.io/docs/current/connector/iceberg.html' },
        provenance: 'Captured page', capture: 'BYTEQUAY_READ', retrievedAt: ago(40) }],
  };
}

const times = (full: boolean): ContextTimesDto => full ? {
  entries: { [`4:${refresh.id}`]: { at: ago(60 * 26), taskId: 'task-run-2' }, [`3:${lesson.id}`]: { at: ago(60 * 24 * 6), taskId: null },
    [`2:${layout.id}`]: { at: ago(60 * 24 * 9), taskId: null }, [`5:${spi.id}`]: { at: ago(60 * 3), taskId: null } },
  drafts: { [newDraft.id]: { at: ago(48), taskId: 'task-run-3' }, [taskDraft.id]: { at: ago(95), taskId: 'task-dev-7' },
    [updateDraft.id]: { at: ago(21), taskId: 'task-run-4' } },
} : { entries: {}, drafts: {} };

const TOPICS: ContextDiscoveryReportDto['topics'] = [
  { id: 'topic:iceberg', title: 'Iceberg connector', paths: ['plugin/trino-iceberg/', 'plugin/trino-hive/', 'lib/trino-filesystem/'], notePaths: ['plugin/trino-iceberg/'], sourceIds: ['src:iceberg-docs', 'src:iceberg-readme'], evidenceReadIds: ['read:1'],
    questions: ['How does the connector load and cache table metadata?', 'How are commits retried when snapshots conflict?', 'How are delete files applied during reads?'],
    why: 'The README and `IcebergMetadata` show the connector owns table commits and metadata caching.' },
  { id: 'topic:planner', title: 'Query planning and optimizer rules', paths: ['core/trino-main/src/main/java/io/trino/sql/planner/'], notePaths: ['core/trino-main/src/main/java/io/trino/sql/planner/'], sourceIds: ['src:planner-doc'],
    evidenceReadIds: ['read:2'], why: '`PlanOptimizers` registers the rule sets every query runs through.',
    questions: ['In what order do optimizer rules run?', 'How are cost estimates produced?', 'When does the planner add exchanges?', 'How are dynamic filters planned?'] },
  { id: 'topic:spi', title: 'Connector SPI', paths: ['core/trino-spi/'], notePaths: ['core/trino-spi/'], sourceIds: [], evidenceReadIds: [], why: 'Every connector implements these interfaces.',
    questions: ['Which SPI changes need a deprecation cycle?', 'How are connector capabilities declared?'] },
  { id: 'topic:hive', title: 'Hive connector', paths: ['plugin/trino-hive/'], notePaths: ['plugin/trino-hive/'], sourceIds: [], evidenceReadIds: [], why: 'Shares metastore code with Iceberg.',
    questions: ['How are partitions pruned?', 'How does the metastore cache expire?'] },
];

function report(): ContextDiscoveryReportDto {
  return { id: 'report-1', workspaceId: 'ws-1', baselineSha: SHA, topics: clone(TOPICS),
    sources: [
      { id: 'src:iceberg-docs', kind: 'DOCUMENTATION_PAGE', location: 'https://trino.io/docs/current/connector/iceberg.html' },
      { id: 'src:iceberg-readme', kind: 'REPOSITORY_FILE', location: 'plugin/trino-iceberg/README.md' },
      { id: 'src:planner-doc', kind: 'DOCUMENTATION_ROOT', location: 'https://trino.io/docs/current/optimizer/' }],
    evidence: [{ readId: 'read:1', path: 'plugin/trino-iceberg/README.md', startLine: 1, endLine: 24 },
      { readId: 'read:2', path: 'core/trino-main/src/main/java/io/trino/sql/planner/PlanOptimizers.java', startLine: 180, endLine: 240 }],
    remaining: ['`plugin/` modules after `trino-iceberg` were not surveyed'], partial: true,
    coverage: { areas: [{ path: 'core/', surveyed: 9, total: 9 }, { path: 'plugin/', surveyed: 24, total: 61 }, { path: 'lib/', surveyed: 5, total: 12 }],
      unsurveyed: ['plugin/trino-kudu/', 'plugin/trino-pinot/', 'plugin/trino-redis/', 'lib/trino-orc/'], unsurveyedTruncated: true },
    createdAt: ago(60 * 27) };
}

function plan(): ContextLearningPlanDto {
  const docs = { id: 'docs:iceberg', kind: 'DOCUMENTATION_PAGE' as const, url: 'https://trino.io/docs/current/connector/iceberg.html', origin: 'GENERATED' as const,
    discoveryEvidenceRef: 'read:1' };
  return { id: 'plan-1', workspaceId: 'ws-1', reportId: 'report-1', baselineSha: SHA,
    topics: [
      { id: 'topic:iceberg', title: 'Iceberg connector', scopeId: ICEBERG, paths: TOPICS[0].paths, notePaths: TOPICS[0].notePaths, sourceIds: [docs.id],
        repositoryFiles: ['plugin/trino-iceberg/README.md', 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java'],
        questions: [{ text: TOPICS[0].questions[0], status: 'COMPLETED', progress: 'Answered.' }, { text: TOPICS[0].questions[1], status: 'COMPLETED', progress: 'Answered.' },
          { text: TOPICS[0].questions[2], status: 'UNFINISHED', progress: 'Read `DeleteManager` and the positional delete reader.' }] },
      { id: 'topic:planner', title: 'Query planning and optimizer rules', scopeId: PLANNER, paths: TOPICS[1].paths, notePaths: TOPICS[1].notePaths, sourceIds: [], repositoryFiles: [],
        questions: TOPICS[1].questions.map(text => ({ text, status: 'PENDING' as const, progress: '' })) }],
    gaps: ['Delta Lake transaction-log behavior needs its own learning topic.'], documentationSources: [docs],
    documentInventory: { truncated: false,
      items: [{ sourceId: docs.id, resolvedUrl: docs.url, relativePage: null, depth: 0, status: 'READ', detail: '' }],
      suggestions: [{ url: 'https://iceberg.apache.org/docs/latest/', parentUrl: docs.url, topicId: 'topic:iceberg', question: 2, taskId: 'task-run-4', foundAt: ago(21) }] } };
}

function contextRun(sequence: number, outcome: ContextRunDto['outcome'], minutesAgo: number, question: number | null, patch: Partial<ContextRunDto> = {}): ContextRunDto {
  const topic = TOPICS[0];
  return { sequence, taskId: `task-run-${sequence}`, mode: question === null ? 'DISCOVER' : 'LEARN', requestedAt: ago(minutesAgo + 12),
    endedAt: outcome === 'RUNNING' ? null : ago(minutesAgo), pullRequest: null, outcome, sourceOperations: outcome === 'LIMIT' ? 100 : 17, documentOpens: 2, noteIds: [],
    question: question === null ? null : { planId: 'plan-1', topicId: topic.id, topicTitle: topic.title, index: question, text: topic.questions[question] },
    activity: [{ at: ago(minutesAgo + 11), text: 'Listed plugin/trino-iceberg/src/main/java', tone: 'DONE' },
      { at: ago(minutesAgo + 9), text: 'Read IcebergMetadata.java lines 412–468', tone: 'DONE' },
      { at: ago(minutesAgo + 6), text: 'Opened trino.io/docs/current/connector/iceberg.html', tone: 'DONE' },
      ...outcome === 'LIMIT' ? [{ at: ago(minutesAgo), text: 'Reached 100 source operations · progress saved', tone: 'LIMIT' as const }] : []],
    detail: null, ...patch };
}

function binding(taskId: string): NonNullable<ContextBuildViewDto['build']> {
  return { requestKey: 'key-' + taskId, taskId, task: { id: taskId, workspaceId: 'ws-1', state: 'OPEN', mutationClaim: 'TURN', worktreeRemovedAt: null },
    activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, continuation: false };
}

const HISTORY = [contextRun(1, 'SUBMITTED', 60 * 27, null), contextRun(2, 'ANSWERED', 60 * 26, 0, { noteIds: [refresh.id] }),
  contextRun(3, 'ANSWERED', 48, 1, { noteIds: [newDraft.entry.id] }), contextRun(4, 'LIMIT', 21, 2, { noteIds: [refresh.id] })];

type Scene = 'fresh' | 'discovering' | 'suggested' | 'review' | 'learning';

type State = {
  notebook: RepositoryNotebookDto; times: ContextTimesDto; build: ContextBuildViewDto; report: ContextDiscoveryReportDto | null;
  planView: ContextLearningPlanViewDto; candidates: ContextMergedCandidatesDto; staleOnce: boolean;
  calls: { starts: Array<{ kind: string; request: unknown }>; approvals: string[]; dismissals: string[]; allows: unknown[]; declines: unknown[];
    configures: unknown[]; stops: string[] };
};

function scene(name: Scene): State {
  const full = name === 'review' || name === 'learning';
  const history = full ? HISTORY : name === 'suggested' ? [HISTORY[0]] : [];
  const runs = name === 'discovering' ? [contextRun(1, 'RUNNING', 2, null, { activity: [{ at: ago(2), text: 'Listed the repository root', tone: 'DONE' },
    { at: ago(1), text: 'Read README.md', tone: 'DONE' }, { at: ago(0.5), text: 'Reading pom.xml', tone: 'DONE' }] })]
    : name === 'learning' ? [...history, contextRun(5, 'RUNNING', 3, 2, { sourceOperations: 137, documentOpens: 12 })] : history;
  const running = runs.find(value => value.outcome === 'RUNNING');
  return {
    notebook: notebook(full), times: times(full), report: name === 'fresh' || name === 'discovering' ? null : report(),
    planView: full ? { plan: plan(), next: { topicId: 'topic:iceberg', question: 2 } } : { plan: null, next: null },
    build: { buildRequestSequence: runs.length, notebookRevision: full ? 12 : 1, canStart: running === undefined, runs,
      build: running === undefined ? null : binding(running.taskId) },
    candidates: { candidates: full ? [{ id: 'pr:trinodb/trino#27315', repo: 'trinodb/trino', number: 27315, title: 'Retry Iceberg commits on conflicting snapshots',
      mergedAt: ago(60 * 5), taskTitle: 'Fix flaky Iceberg commit test', taskResult: 'Tests pass', issue: { key: '#27290', title: 'Concurrent commits lose files' },
      review: { combinedResult: 'Approved after 2 rounds' }, checks: [{ conclusion: 'success' }, { conclusion: 'success' }] }] : [] },
    staleOnce: false,
    calls: { starts: [], approvals: [], dismissals: [], allows: [], declines: [], configures: [], stops: [] },
  };
}

function usage(window: ContextUsageWindow): ContextUsageOverviewDto {
  return { trackingSince: ago(60 * 24 * 40), window,
    summary: { eligibleDevelopmentTasks: window === '30d' ? 14 : 31, eligibleReviewRuns: window === '30d' ? 9 : 22, contextOffTasks: 2,
      developmentTasksFoundOrOpened: 8, reviewRunsFoundOrOpened: 5 },
    entries: [refresh, layout, lesson, spi].map((entry, index) => ({ entryId: entry.id, title: entry.title, currentVersion: entry.version,
      foundTasks: 9 - index * 2, openedTasks: 6 - index * 2, lastConfirmedUseAt: ago(60 * (index + 2)) })) };
}

let state = scene('fresh');

function install() {
  const mutate = (expectedRevision: number, change: (notebook: RepositoryNotebookDto) => void) => {
    if (state.staleOnce) {
      state.staleOnce = false;
      state.notebook.revision += 1;
      throw new Error('Repository context changed in another window. Reload and review the latest version.');
    }
    if (expectedRevision !== state.notebook.revision) throw new Error('Repository context revision is stale.');
    change(state.notebook);
    state.notebook.revision += 1;
    return Promise.resolve(clone(state.notebook));
  };
  const start = (kind: string, request: { requestKey: string }, mode: 'DISCOVER' | 'LEARN') => {
    state.calls.starts.push({ kind, request: clone(request) });
    const sequence = state.build.runs.length + 1;
    const next = contextRun(sequence, 'RUNNING', 0, mode === 'DISCOVER' ? null : 2, { activity: [] });
    state.build = { ...state.build, buildRequestSequence: sequence, canStart: false, runs: [...state.build.runs, next],
      build: { ...binding(next.taskId), requestKey: request.requestKey } };
    return Promise.resolve(clone(state.build));
  };
  const planView = () => clone(state.planView);
  const bridge: Partial<Bridge> = {
    getRepositoryContext: async () => clone(state.notebook),
    getRepositoryContextTimes: async () => clone(state.times),
    getRepositoryContextBuild: async () => clone(state.build),
    getRepositoryContextDiscoveryReport: async () => clone(state.report),
    getRepositoryContextLearningPlan: async () => planView(),
    getRepositoryContextBaseline: async () => ({ branch: 'master', sha: SHA }),
    getRepositoryContextMergedCandidates: async () => clone(state.candidates),
    getRepositoryContextUsage: async (_workspaceId, window) => usage(window),
    getModelSettings: () => createPreviewBridge('ws-1').getModelSettings(),
    getAgentTask: async taskId => ({ id: taskId, workspaceId: 'ws-1', title: taskId === 'task-dev-7' ? 'Fix flaky Iceberg commit test' : 'Task' }) as unknown as AgentTaskDto,
    startRepositoryContextDiscovery: async (_workspaceId, request) => start('discover', request, 'DISCOVER'),
    startRepositoryContextLearning: async (_workspaceId, request) => start('learn', request, 'LEARN'),
    continueRepositoryContextLearning: async (_workspaceId, request) => start('continue', request, 'LEARN'),
    learnRepositoryContextMergedCandidate: async (_workspaceId, request) => start('candidate', request, 'LEARN'),
    stopRepositoryContextBuild: async (_workspaceId, taskId) => {
      state.calls.stops.push(taskId);
      state.build = { ...state.build, canStart: true, build: null,
        runs: state.build.runs.map(value => value.taskId === taskId ? { ...value, outcome: 'STOPPED', endedAt: new Date().toISOString() } : value) };
      return clone(state.build);
    },
    configureRepositoryContext: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      state.calls.configures.push(clone(request));
      if (request.useInReviews !== undefined) current.useInReviews = request.useInReviews;
      if (request.useInDevelopment !== undefined) current.useInDevelopment = request.useInDevelopment;
      if (request.sources !== undefined) current.sources = request.sources.map((source): ContextSourceDto => ({ ...source, origin: 'USER', discoveryEvidenceRef: null }));
    }),
    approveRepositoryContextDraft: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      const draft = current.pendingDrafts.find(value => value.id === request.draftId);
      assert(draft !== undefined, 'Approved draft must exist');
      state.calls.approvals.push(draft.id);
      const approved = { ...draft.entry, version: current.revision + 1 };
      current.entries = [...current.entries.filter(value => value.id !== approved.id), approved];
      current.pendingDrafts = current.pendingDrafts.filter(value => value.id !== draft.id);
      state.times.entries[`${approved.version}:${approved.id}`] = { at: new Date().toISOString(), taskId: state.times.drafts[draft.id]?.taskId ?? null };
    }),
    dismissRepositoryContextDraft: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      state.calls.dismissals.push(request.draftId);
      current.pendingDrafts = current.pendingDrafts.filter(value => value.id !== request.draftId);
    }),
    editRepositoryContextDraft: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      current.pendingDrafts = current.pendingDrafts.map(value => value.id !== request.draftId ? value
        : { ...value, entry: { ...value.entry, title: request.title, summary: request.summary, body: request.body, claims: request.claims } });
    }),
    editRepositoryContextEntry: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      current.entries = current.entries.map(value => value.id !== request.id ? value
        : { ...value, title: request.title, summary: request.summary, body: request.body, version: current.revision + 1 });
    }),
    setRepositoryContextEntryEnabled: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      current.entries = current.entries.map(value => value.id === request.entryId ? { ...value, enabled: request.enabled } : value);
    }),
    discardRepositoryContextLearningPlan: async () => { state.planView = { plan: null, next: null }; return planView(); },
    allowRepositoryContextLearningSource: async (_workspaceId, request) => {
      state.calls.allows.push(clone(request));
      const current = state.planView.plan;
      assert(current !== null, 'A plan must exist');
      current.documentInventory.suggestions = current.documentInventory.suggestions.filter(value => value.url !== request.suggestedUrl);
      current.documentationSources.push({ id: 'docs:approved', kind: request.boundary === 'ROOT' ? 'DOCUMENTATION_ROOT' : 'DOCUMENTATION_PAGE',
        url: request.suggestedUrl, origin: 'USER', discoveryEvidenceRef: null });
      return planView();
    },
    declineRepositoryContextLearningSource: async (_workspaceId, request) => {
      state.calls.declines.push(clone(request));
      const current = state.planView.plan;
      assert(current !== null, 'A plan must exist');
      current.documentInventory.suggestions = current.documentInventory.suggestions.filter(value => value.url !== request.suggestedUrl);
      return planView();
    },
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
}

let root: Root | null = null;
let host: HTMLDivElement | null = null;

async function render(name: Scene, initialRunId: string | null = null, missingNoteTargets = false): Promise<HTMLDivElement> {
  if (root !== null) flushSync(() => root?.unmount());
  host?.remove();
  document.querySelectorAll('.bq-toast').forEach(toast => toast.remove());
  state = scene(name);
  if (missingNoteTargets) {
    if (state.report !== null) state.report.topics = state.report.topics.map(topic => ({ ...topic, notePaths: null as null }));
    if (state.planView.plan !== null) state.planView.plan.topics = state.planView.plan.topics.map(topic => ({ ...topic, notePaths: null as null }));
  }
  host = document.createElement('div');
  host.className = 'app-shell';
  host.style.cssText = 'display:flex;height:100vh;width:100vw;overflow:hidden';
  document.body.append(host);
  root = createRoot(host);
  flushSync(() => root?.render(<>
    <AppSidebar page="workspaces" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}} canGoBack={false} canGoForward={false}
      onBack={() => {}} onForward={() => {}} workspace={workspace} workspaceTab="context" onWorkspaceTab={() => {}} />
    <main className="app-content"><RepositoryContextPage workspace={workspace} initialRunId={initialRunId} /></main>
  </>));
  for (let attempt = 0; attempt < 40 && host.querySelector('.rcp-status') === null; attempt++) await settle();
  await settle();
  return host;
}

const text = (scope: Element | null = host) => scope?.textContent ?? '';
function control(label: string, scope: ParentNode = document): HTMLElement {
  const found = [...scope.querySelectorAll<HTMLElement>('button, [role=button], [role=tab], [role=switch], [role=menuitem], a')]
    .find(item => item.getAttribute('aria-label') === label || item.textContent?.replace(/\s+/g, ' ').trim() === label);
  assert(found !== undefined, `Missing control: ${label}`);
  return found;
}
async function click(label: string, scope: ParentNode = document) {
  const found = control(label, scope);
  assert(!(found instanceof HTMLButtonElement) || !found.disabled, `Control ${label} must be enabled`);
  found.click();
  await settle();
  await settle();
}
async function check(label: string, scope: ParentNode = document) {
  const input = [...scope.querySelectorAll('label')].find(item => item.textContent?.includes(label))?.control;
  assert(input instanceof HTMLInputElement, `Missing choice: ${label}`);
  input.click();
  await settle();
}
function reviewRow(title: string): HTMLButtonElement {
  const found = [...document.querySelectorAll<HTMLButtonElement>('button.rcp-review')].find(item => item.querySelector('.rcp-review__title')?.textContent === title);
  assert(found !== undefined, `Missing review item: ${title}`);
  return found;
}
async function review(title: string) {
  reviewRow(title).click();
  await settle();
  await settle();
}
const drawer = () => { const found = document.querySelector<HTMLElement>('.rcp-drawer'); assert(found !== null, 'Drawer must be open'); return found; };
async function waitFor(condition: () => boolean, message: string) {
  for (let attempt = 0; attempt < 40 && !condition(); attempt++) await settle();
  assert(condition(), message);
}

const scenarios: Array<[string, () => Promise<void>]> = [
  ['opening the page reads saved state and starts nothing', async () => {
    const page = await render('fresh');
    assert(text(page).includes('Find what’s worth learning in trinodb/trino'), 'The new-repository card must explain discovery');
    assert(control('Start discovery').querySelector('svg') !== null, 'Run-starting actions carry the lightning mark');
    assert(state.calls.starts.length === 0, 'Opening must not start a run');
  }],
  ['discovery starts only from its explicit action', async () => {
    await render('fresh');
    await click('Start discovery');
    assert(state.calls.starts.length === 1 && state.calls.starts[0].kind === 'discover', 'Start discovery sends one request');
    assert((state.calls.starts[0].request as { continuation: boolean }).continuation === false, 'A first discovery is not a continuation');
    await waitFor(() => text().includes('Discovering topics'), 'The status card follows the running discovery');
    assert(control('Stop').closest('.rcp-status') !== null, 'A running discovery can be stopped');
  }],
  ['learning sends the selected topics and leaves unchecked sources out', async () => {
    await render('suggested');
    assert(!(control('Learn selected') as HTMLButtonElement).disabled === false, 'Learning needs a selection');
    await check('Iceberg connector');
    await click('Inspect Iceberg connector');
    const topic = document.querySelector('.rcp-sugg');
    assert(topic !== null, 'The selected topic is visible');
    assert(text(topic.querySelector('.rcp-note-caption')).includes('plugin/trino-iceberg/')
      && !text(topic.querySelector('.rcp-note-caption')).includes('plugin/trino-hive/'), 'Only Iceberg is a note target');
    assert(text(topic).includes('CODE AVAILABLE TO READ') && text(topic).includes('plugin/trino-hive/'), 'Hive remains available as source code');
    assert(!text(topic.querySelector('.rcp-sugg__meta')).includes('+2'), 'The collapsed applicability does not count source-only paths');
    await check('https://trino.io/docs/current/connector/iceberg.html');
    assert(text().includes('1 topic selected · 3 questions · 1 source'), 'The selection bar counts questions and approved sources');
    await click('How runs work');
    assert(text().includes('configured agent timeout') && text().includes('up to 2 hops'), 'The learning action explains its timeout and approved source boundary');
    assert(!text().includes('100 source operations') && !text().includes('10 initial documentation opens'), 'Learning does not advertise source-operation quotas');
    await click('How runs work');
    await click('Learn selected');
    const request = state.calls.starts[0]?.request as { topicIds: string[]; excludedSourceIds: string[] };
    assert(state.calls.starts[0]?.kind === 'learn' && request.topicIds.join() === 'topic:iceberg', 'Only the selected topic is learned');
    assert(request.excludedSourceIds.join() === 'src:iceberg-docs', 'The unchecked source is excluded from approval');
  }],
  ['earlier suggestions require discovery without clearing notes', async () => {
    await render('suggested', null, true);
    assert(text().includes('Update suggestions before learning'), 'Earlier suggestions explain why discovery is needed');
    const checkbox = document.querySelector<HTMLInputElement>('.rcp-sugg input[type=checkbox]');
    assert(checkbox?.disabled === true && (control('Learn selected') as HTMLButtonElement).disabled, 'Unknown note targets cannot start learning');
    assert(state.calls.starts.length === 0, 'Opening earlier suggestions starts no run');
    await click('Discover again');
    assert(state.calls.starts.map(start => start.kind).join() === 'discover', 'Discovery remains explicit');
  }],
  ['earlier plans require reselection and keep their notes and drafts', async () => {
    await render('review', null, true);
    const notes = state.notebook.entries.length;
    const drafts = state.notebook.pendingDrafts.length;
    assert(text().includes('Reselect topics before continuing'), 'Earlier plans are not shown as complete or resumable');
    await click('Review learning plan');
    assert(text(drawer()).includes('Approved notes and drafts are kept'), 'The plan explains preservation');
    assert(text(drawer()).includes('OPEN QUESTIONS AND GAPS') && text(drawer()).includes('Delta Lake transaction-log behavior needs its own learning topic.'),
      'Deferred learning opportunities remain visible');
    assert(![...drawer().querySelectorAll('button')].some(button => button.textContent?.includes('Continue learning')), 'No continuation is offered');
    assert(state.calls.starts.length === 0 && state.notebook.entries.length === notes && state.notebook.pendingDrafts.length === drafts,
      'Inspecting earlier data neither starts a run nor removes notes');
  }],
  ['discovery and learning activity never offer an interactive task route', async () => {
    for (const name of ['discovering', 'learning'] as const) {
      await render(name);
      assert(document.querySelector('.rcp-feed__line.is-live')?.textContent?.includes('last recorded activity'), 'The live row describes the run, not a finished read');
      await click(name === 'discovering' ? 'View activity' : 'View details');
      assert(drawer().querySelectorAll('.rcp-event .rcp-spin').length === 0, 'Completed source reads never spin');
      assert(drawer().querySelector('a[href*="/tasks/agent/"]') === null, 'Workflow activity never links to an interactive task');
      assert(text(drawer()).includes('messages and steering are unavailable'), 'The run names its read-only boundary');
    }
  }],
  ['Learning cards open the selected read-only run and retain Stop', async () => {
    await render('learning');
    const bridge = createPreviewBridge(workspace.id);
    bridge.getRepositoryContextBuild = window.bridge.getRepositoryContextBuild;
    let interactiveOpens = 0;
    flushSync(() => root?.render(<AgentTasksView workspaceId={workspace.id} bridge={bridge}
      onOpenTask={() => { interactiveOpens++; }} onOpenReview={() => {}} />));
    await waitFor(() => document.querySelector('[aria-label="Learning: Learning run 5 · Iceberg connector"]') !== null, 'Learning runs are listed separately');
    await click(`Learning${state.build.runs.length}`);
    assert(document.querySelector('textarea') === null, 'The Learning filter has no message composer');
    await click('Learning: Learning run 5 · Iceberg connector');
    const location = workspaceLocationFromRoute(window.location.hash);
    assert(location?.contextRunId === 'task-run-5' && location.agentTaskId === null && interactiveOpens === 0, 'The card opens context activity instead of an interactive task');
    await render('learning', location.contextRunId);
    assert(text(drawer()).includes('Read-only') && drawer().querySelector('textarea') === null, 'The activity drawer has no composer');
    await click('Stop run', drawer());
    assert(state.calls.stops.join() === 'task-run-5', 'Stop targets this workflow run');
    window.location.hash = '';
  }],
  ['approving a draft makes it available without a run and moves to the next item', async () => {
    await render('review');
    const sidebar = document.querySelector('[aria-label^="Repository context, "]');
    assert(sidebar?.getAttribute('aria-label') === 'Repository context, 4 to review', 'The sidebar counts drafts and source proposals');
    await review(newDraft.entry.title);
    assert(drawer().textContent?.includes('Draft · not available to agents'), 'A draft says it is not available');
    assert(drawer().textContent?.includes('1 of 3 to review'), 'The drawer pages through the review queue');
    await click('Approve and use', drawer());
    assert(state.calls.approvals.join() === newDraft.id && state.calls.starts.length === 0, 'Approval saves the note and starts no run');
    await waitFor(() => drawer().textContent?.includes(taskDraft.entry.title) === true, 'The drawer advances to the next draft');
    assert(text(document.body).includes('no agent run'), 'The confirmation says no run started');
    await waitFor(() => document.querySelector('[aria-label="Repository context, 3 to review"]') !== null, 'The sidebar count follows the review queue');
  }],
  ['dismissing asks first, and an update keeps the approved version in use', async () => {
    await render('review');
    await review(updateDraft.entry.title);
    assert(drawer().textContent?.includes('Update draft · v4 stays in use'), 'An update names the version that stays in use');
    assert(control('Changes', drawer()).getAttribute('aria-selected') === 'true', 'An update opens on its changes');
    assert(drawer().querySelector('.rcp-diff__line.is-add')?.textContent?.includes('REFRESH') === true, 'The diff shows the added line');
    await click('Dismiss update', drawer());
    assert(drawer().textContent?.includes('Dismiss this update? v4 stays in use.'), 'Dismissal asks for confirmation');
    await click('Keep', drawer());
    assert(state.calls.dismissals.length === 0, 'Keeping the update sends nothing');
  }],
  ['a stale revision keeps the drawer open with its error', async () => {
    await render('review');
    await review(newDraft.entry.title);
    state.staleOnce = true;
    await click('Approve and use', drawer());
    await waitFor(() => drawer().querySelector('[role=alert]') !== null, 'The conflict is shown in the drawer');
    assert(state.calls.approvals.length === 0 && drawer().textContent?.includes(newDraft.entry.title), 'Nothing is approved on a stale revision');
  }],
  ['a proposed source is approved for its exact boundary without a run', async () => {
    await render('review');
    await review('https://iceberg.apache.org/docs/latest/');
    assert(control('Sources · 1', drawer()).getAttribute('aria-selected') === 'true', 'The source proposal opens the Sources tab');
    await check('Documentation root', drawer());
    await click('Approve source', drawer());
    const request = state.calls.allows[0] as { boundary: string; suggestedUrl: string };
    assert(request.boundary === 'ROOT' && request.suggestedUrl === 'https://iceberg.apache.org/docs/latest/', 'The exact root is approved');
    assert(state.calls.starts.length === 0, 'Approving a source starts no run');
  }],
  ['continuing learning starts one run on the saved next question', async () => {
    await render('review');
    assert(text().includes('Run 4 reached its source limit'), 'Saved historical limit outcomes remain truthful');
    assert(text().includes('Next: Iceberg connector · “How are delete files applied during reads?” · resumes saved progress'), 'The run line names the next question');
    await click('Continue learning');
    assert(state.calls.starts.length === 1 && state.calls.starts[0].kind === 'continue', 'Continue sends one request');
  }],
  ['a running learning question shows its question and can be stopped', async () => {
    await render('learning');
    assert(text().includes('Learning · Iceberg connector') && text().includes('“How are delete files applied during reads?”'), 'The card names the running question');
    await click('View details');
    await click('Plan', drawer());
    await click('How runs work', drawer());
    assert(text(drawer()).includes('137 source operations') && text(drawer()).includes('12 documentation opens'), 'The plan reports observed source counts');
    assert(!text(drawer()).includes('of 100 source operations') && !text(drawer()).includes('of 10 documentation opens'), 'Observed counts have no quota denominator');
    await click('Close', drawer());
    await click('Stop');
    assert(state.calls.stops.join() === 'task-run-5', 'Stop names the running task');
  }],
  ['escape closes a drawer and returns focus to its opener', async () => {
    await render('review');
    const opener = reviewRow(newDraft.entry.title);
    opener.focus();
    opener.click();
    await settle();
    assert(document.activeElement === drawer(), 'The drawer takes focus');
    drawer().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
    assert(document.querySelector('.rcp-drawer') === null && document.activeElement === opener, 'Esc closes the drawer and restores focus');
  }],
  ['the notes tab filters literally by area, kind and text', async () => {
    await render('review');
    await click('Notes5');
    await click('Iceberg connector2');
    assert(document.querySelectorAll('.rcp-note').length === 2, 'The area rail narrows to that area');
    await click('Lessons 1');
    assert(document.querySelectorAll('.rcp-note').length === 1 && text().includes(lesson.title), 'The kind filter keeps lessons');
    await click('All notes5');
    await click('All 5');
    const input = document.querySelector<HTMLInputElement>('input[aria-label="Filter notes"]');
    assert(input !== null, 'The notes filter exists');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, 'SPI');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    assert(document.querySelectorAll('.rcp-note').length === 1, 'Text filtering is literal');
  }],
  ['agent use switches save configuration without a run', async () => {
    await render('review');
    await click('Used by agents');
    await click('Use in PR reviews');
    assert((state.calls.configures[0] as { useInReviews: boolean }).useInReviews === false && state.calls.starts.length === 0, 'The switch saves only configuration');
    await waitFor(() => text().includes('Used in development only'), 'The header reflects the saved setting');
  }],
  ['Home offers the waiting review and task drafts open the same review drawer', async () => {
    await renderHome();
    assert(text(document.querySelector('.item-row.is-context')).includes('3 drafts and 1 source to review'), 'Home counts drafts and sources');
    assert(text().includes('Sending starts a task with Codex · 5.6 Terra High.'), 'Explore mode names the run it starts');
    await renderTaskDraft();
    const card = document.querySelector('.rcx-draft');
    assert(card !== null && text(card).includes(taskDraft.entry.title) && document.querySelectorAll('.rcx-draft').length === 1, 'Only this task’s draft is shown');
    await click('Review draft', card);
    assert(window.location.hash === '#/workspaces/ws-1/context/draft/draft%3Atask%3Asnapshot-check', `Review draft opens the drawer route (${window.location.hash})`);
    assert(state.calls.starts.length === 0, 'Nothing here starts a run');
    window.location.hash = '';
  }],
  ['the page column and selection bar stay centered in a wide window', async () => {
    const page = await render('suggested');
    page.style.width = '1900px';
    await settle();
    const scroller = document.querySelector('.rcp-scroll')?.getBoundingClientRect();
    const column = document.querySelector('.rcp-inner')?.getBoundingClientRect();
    const bar = document.querySelector('.rcp-bar__main')?.getBoundingClientRect();
    assert(scroller !== undefined && column !== undefined && bar !== undefined, 'The page and its selection bar render');
    const left = column.left - scroller.left;
    const right = scroller.right - column.right;
    // A classic scrollbar narrows one side of the scroller only, so allow its width.
    assert(column.width === 920 && Math.abs(left - right) <= 16, `The column is centered (${left} left, ${right} right)`);
    assert(Math.abs(bar.left - column.left) <= 10, `The selection bar starts at the column edge (${bar.left} vs ${column.left})`);
  }],
  ['the page fits a narrow window without horizontal scrolling', async () => {
    const page = await render('review');
    page.style.width = '640px';
    await settle();
    const scroller = document.querySelector('.rcp-scroll');
    assert(scroller !== null && scroller.scrollWidth <= scroller.clientWidth + 1, `The narrow page must not scroll sideways (${scroller?.scrollWidth} > ${scroller?.clientWidth})`);
  }],
];

/** Workspace Home in Explore mode, with the Repository context card first in Needs attention. */
async function renderHome(): Promise<void> {
  await render('review');
  const catalog = await createPreviewBridge('ws-1').getModelSettings();
  const provider = catalog.providers[0];
  function Home() {
    const review = useContextReview('ws-1', null);
    return <AgentHomeView repository="trinodb/trino" workspaceId="ws-1" laneId={provider.id} providers={catalog.providers} tasks={[]} models={provider.models}
      selection={{ model: provider.models[0].id, effort: 'high', serviceTier: null }} draft="How does Trino prune Iceberg data files during planning?" blocker={null}
      onDraftChange={() => {}} onSelectProvider={() => {}} onSelectModel={() => {}} onCreate={() => {}} onOpenTask={() => {}} onShowAll={() => {}}
      explore onExplore={() => {}} contextReview={review} />;
  }
  flushSync(() => root?.render(<>
    <AppSidebar page="workspaces" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}} canGoBack={false} canGoForward={false}
      onBack={() => {}} onForward={() => {}} workspace={workspace} workspaceTab="home" onWorkspaceTab={() => {}} />
    <main className="app-content"><Home /></main>
  </>));
  await waitFor(() => document.querySelector('.item-row.is-context') !== null, 'The Home row appears');
}

/** A task's transcript end, with the draft that task proposed. */
async function renderTaskDraft(): Promise<void> {
  await render('review');
  flushSync(() => root?.render(<>
    <AppSidebar page="workspaces" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}} canGoBack={false} canGoForward={false}
      onBack={() => {}} onForward={() => {}} workspace={workspace} workspaceTab="tasks" onWorkspaceTab={() => {}} />
    <main className="app-content"><div style={{ maxWidth: 760, margin: '32px auto', padding: '0 24px' }}>
      <p style={{ fontSize: 13, lineHeight: 1.6 }}>Pruning happens while splits are generated, before any data file is opened. It compares partition values and column bounds
        stored in the manifests with the pushed-down constraint.</p>
      <TaskContextDrafts workspaceId="ws-1" taskId="task-dev-7" refreshKey="0" />
    </div></main>
  </>));
  await waitFor(() => document.querySelector('.rcx-draft') !== null, 'The task draft card appears');
}

/** Renders one named state for a screenshot, e.g. `review:draft`. */
export async function show(mode: string) {
  install();
  if (mode === 'home') { await renderHome(); return; }
  if (mode === 'task') { await renderTaskDraft(); return; }
  const [base, extra] = mode.split(':') as [Scene, string | undefined];
  await render(base, extra === 'run' ? 'task-run-5' : null);
  switch (extra) {
    case 'tasks': {
      const bridge = createPreviewBridge(workspace.id);
      bridge.getRepositoryContextBuild = window.bridge.getRepositoryContextBuild;
      flushSync(() => root?.render(<AgentTasksView workspaceId={workspace.id} bridge={bridge} onOpenTask={() => {}} onOpenReview={() => {}} />));
      await waitFor(() => document.querySelector('[aria-label="Learning: Learning run 5 · Iceberg connector"]') !== null, 'Learning runs appear');
      await click(`Learning${state.build.runs.length}`);
      control(`Learning${state.build.runs.length}`).scrollIntoView({ block: 'nearest', inline: 'nearest' });
      break;
    }
    case 'select': await check('Iceberg connector'); await click('Inspect Iceberg connector'); break;
    case 'draft': await review(newDraft.entry.title); break;
    case 'update': await review(updateDraft.entry.title); break;
    case 'plan': await click('Plan details'); break;
    case 'sources': await review('https://iceberg.apache.org/docs/latest/'); break;
    case 'coverage': await click('See coverage'); break;
    case 'usage': await click('More options'); await click('Usage'); break;
    case 'advanced': await click('Advanced configuration'); break;
    case 'notes': await click('Notes5'); break;
    case 'use': await click('Used by agents'); break;
    default: break;
  }
  await settle();
  document.querySelectorAll('.bq-toast').forEach(toast => toast.remove());
}

export async function run() {
  install();
  const results: Array<{ name: string; error?: string }> = [];
  for (const [name, scenario] of scenarios) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
