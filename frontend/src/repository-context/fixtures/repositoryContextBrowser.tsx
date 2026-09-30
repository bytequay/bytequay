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
import DevelopmentContextActivityPanel from '../../agents/DevelopmentContextActivityPanel';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import type { AgentTaskDto, Bridge, DevelopmentContextActivityDto, WorkspaceDto } from '../../types';
import { workspaceLocationFromRoute } from '../../workspaces/model';
import type {
  ContextBuildViewDto, ContextDiscoveryReportDto, ContextDisputesDto, ContextEntryDto, ContextGradeDto, ContextLearningPlanDto, ContextLearningPlanViewDto,
  ContextMergedCandidatesDto, ContextPlanQuestionDto, ContextReportedNoteDto, ContextRunDto, ContextSettlementDto, ContextSourceDto, ContextTimesDto, ContextUsageOverviewDto,
  ContextUsageWindow, RepositoryNotebookDto, ReportRepositoryContextNoteRequest,
} from '../contracts';
import { TaskContextNotes } from '../ContextCards';
import RepositoryContextPage from '../RepositoryContextPage';
import { CONTEXT_CHANGED, useContextReview } from '../reviewCount';
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

const refreshBefore = note('knowledge:iceberg-refresh', ICEBERG, 'Iceberg table metadata refresh',
  'Table metadata is loaded once per transaction through `TrinoCatalog`; reads never refresh mid-query.', 4);
const refresh: ContextEntryDto = { ...refreshBefore, version: 11, sourceIds: ['ev-code', 'ev-doc'],
  body: '## How it works\n- `IcebergMetadata` loads the table through the catalog on first use in a transaction.\n- Later reads in the same transaction reuse it.\n- `REFRESH` procedures drop the cached table explicitly.',
  claims: [...refreshBefore.claims, { text: 'The `system.flush_metadata_cache` procedure clears cached tables.', basis: 'DOCUMENTED', sourceIds: ['ev-doc'] }] };
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

const commits = note('knowledge:iceberg-commit-retries', ICEBERG, 'How Iceberg commits retry on conflict',
  'Commits retry through `Tasks.foreach` with exponential backoff when the catalog reports a conflicting snapshot.', 10, { sourceIds: ['ev-code', 'ev-doc'] });
const snapshotCheck = note('lesson:snapshot-check', ICEBERG, 'Check the snapshot ID before retrying a commit', 'A retried commit must re-read the current snapshot first.', 9,
  { kind: 'MEMORY', claims: [{ text: 'Retrying with a stale snapshot ID silently drops the other writer’s files.', basis: 'OBSERVED', sourceIds: ['ev-code'] }] });
const deletes = note('knowledge:iceberg-deletes', ICEBERG, 'Positional deletes apply per data file',
  'The positional delete reader filters the rows of one data file at a time through `DeleteManager`.', 12);

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
    entries: full ? [refresh, lesson, layout, spi, rules, commits, snapshotCheck, deletes] : [],
    replaced: full ? [refreshBefore] : [],
    sourceEvidence: [
      { id: 'ev-code', reference: { kind: 'REPOSITORY_FILE', repositoryId: 1, repository: { owner: 'trinodb', repo: 'trino' },
        path: 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java', commit: SHA },
      provenance: 'lines 412-468', capture: 'BYTEQUAY_READ', retrievedAt: ago(40) },
      { id: 'ev-doc', reference: { kind: 'DOCUMENT', sourceId: 'docs-trino', url: 'https://trino.io/docs/current/connector/iceberg.html' },
        provenance: 'Captured page', capture: 'BYTEQUAY_READ', retrievedAt: ago(40) }],
  };
}

const TASK_TITLES: Record<string, string> = { 'task-dev-7': 'Fix flaky Iceberg commit test', 'task-dev-8': 'Prune nested Iceberg fields' };
const QUOTE = 'Inspect the parent struct before selecting a leaf field ID.';

function grade(entry: ContextEntryDto, patch: Partial<ContextGradeDto>): ContextGradeDto {
  return { workspaceId: 'ws-1', taskId: 'task-dev-7', reviewRunId: null, taskKind: 'DEVELOPMENT', grader: 'AGENT', grade: 'WRONG',
    note: { id: entry.id, version: entry.version, kind: entry.kind, scopeId: entry.scopeId, title: entry.title },
    reason: 'Field IDs are unique across the whole schema, so the parent struct never decides which field is meant.', quote: QUOTE,
    citations: [{ path: 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergUtil.java', startLine: 212, endLine: 214, commit: SHA,
      excerpt: '212  // Field IDs are unique across the whole schema\n213  NestedField field = schema.findField(fieldId);\n214  return field.type();\n' }],
    correction: null, gradedAt: ago(40), ...patch };
}

// The lesson the user wrote waits in Needs you; the user's own report on an agent-written note waits for agents.
const reports = (): ContextReportedNoteDto[] => [
  { entryId: lesson.id, entryVersion: lesson.version, title: lesson.title, userWritten: true, contested: null, settlement: null, grades: [
    grade(lesson, { grade: 'NOT_RELEVANT', taskId: 'task-dev-8', reason: 'The task only touched top-level columns.', quote: null, citations: [], gradedAt: ago(10) }),
    grade(lesson, {}),
    grade(lesson, { grade: 'HELPED', taskId: 'task-dev-6', reason: 'Explained duplicate leaf names.', quote: null, citations: [], gradedAt: ago(60 * 30) })] },
  { entryId: commits.id, entryVersion: commits.version, title: commits.title, userWritten: false, contested: null, settlement: null, grades: [
    grade(commits, { grader: 'USER', reason: '', quote: null, citations: [], gradedAt: ago(15) })] },
];

// A review reported a statement of the refresh note wrong and a task answered Holds; an investigation settled an earlier dispute of the note.
const DISPUTED = 'Later reads in the same transaction reuse it.';
const EARLIER: ContextSettlementDto = { version: 4, by: 'INVESTIGATION', statement: 'reads never refresh mid-query',
  rootCause: 'One reader followed the REFRESH procedure; the other only plain reads.' };
const contestedRefresh = (): ContextReportedNoteDto => ({ entryId: refresh.id, entryVersion: refresh.version, title: refresh.title, userWritten: false,
  contested: { statement: DISPUTED, version: refresh.version }, settlement: EARLIER, grades: [
    grade(refresh, { grade: 'HOLDS', taskId: 'task-dev-8', quote: DISPUTED, reason: 'Plain reads still go through the cached table.', gradedAt: ago(5) }),
    grade(refresh, { taskId: 'review-task-3', reviewRunId: 'run-3', taskKind: 'PR_REVIEW', quote: DISPUTED,
      reason: 'The metadata cache is invalidated after each commit.', gradedAt: ago(25) })] });
let contestRefresh = false;
let changedDoc = false;

const times = (full: boolean): ContextTimesDto => full ? {
  entries: { [`4:${refresh.id}`]: { at: ago(60 * 26), taskId: 'task-run-2' }, [`11:${refresh.id}`]: { at: ago(21), taskId: 'task-run-4' },
    [`3:${lesson.id}`]: { at: ago(60 * 24 * 6), taskId: null }, [`2:${layout.id}`]: { at: ago(60 * 24 * 9), taskId: null }, [`5:${spi.id}`]: { at: ago(60 * 3), taskId: null },
    [`10:${commits.id}`]: { at: ago(48), taskId: 'task-run-3' }, [`9:${snapshotCheck.id}`]: { at: ago(95), taskId: 'task-dev-7' },
    [`12:${deletes.id}`]: { at: ago(22), taskId: 'task-run-4' } },
} : { entries: {} };

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

const CORRECTED = 'Does every commit retry use the same backoff?';

function plan(): ContextLearningPlanDto {
  const docs = { id: 'docs:iceberg', kind: 'DOCUMENTATION_PAGE' as const, url: 'https://trino.io/docs/current/connector/iceberg.html', origin: 'GENERATED' as const,
    discoveryEvidenceRef: 'read:1' };
  return { id: 'plan-1', workspaceId: 'ws-1', reportId: 'report-1', baselineSha: SHA,
    topics: [
      { id: 'topic:iceberg', title: 'Iceberg connector', scopeId: ICEBERG, paths: TOPICS[0].paths, notePaths: TOPICS[0].notePaths, sourceIds: [docs.id],
        repositoryFiles: ['plugin/trino-iceberg/README.md', 'plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java'],
        questions: [{ text: TOPICS[0].questions[0], status: 'COMPLETED', progress: 'Answered.', origin: 'DISCOVERY', note: '' },
          { text: TOPICS[0].questions[1], status: 'COMPLETED', progress: 'Answered.', origin: 'DISCOVERY', note: '' },
          { text: TOPICS[0].questions[2], status: 'UNFINISHED', progress: 'Read `DeleteManager` and the positional delete reader.', origin: 'DISCOVERY', note: '' },
          { text: CORRECTED, status: 'CORRECTED', progress: '', origin: 'DISCOVERY',
            note: 'Only metadata commits retry; data files are written once. Cited: IcebergMetadata.java:1204-1236' },
          { text: 'Which conflicts does a commit retry treat as safe?', status: 'PENDING', progress: '', origin: 'LEARNING', note: `Replaces “${CORRECTED}”` }] },
      { id: 'topic:planner', title: 'Query planning and optimizer rules', scopeId: PLANNER, paths: TOPICS[1].paths, notePaths: TOPICS[1].notePaths, sourceIds: [], repositoryFiles: [],
        questions: TOPICS[1].questions.map(text => ({ text, status: 'PENDING' as const, progress: '', origin: 'DISCOVERY' as const, note: '' })) }],
    gaps: ['Delta Lake transaction-log behavior needs its own learning topic.'], documentationSources: [docs],
    documentInventory: { truncated: false,
      items: [{ sourceId: docs.id, resolvedUrl: docs.url, relativePage: null, depth: 0, status: 'READ', detail: '' }],
      suggestions: [{ url: 'https://iceberg.apache.org/docs/latest/', parentUrl: docs.url, topicId: 'topic:iceberg', question: 2, taskId: 'task-run-4', foundAt: ago(21) }] },
    batch: null };
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
    detail: null, investigation: null, ...patch };
}

function binding(taskId: string): NonNullable<ContextBuildViewDto['build']> {
  return { requestKey: 'key-' + taskId, taskId, task: { id: taskId, workspaceId: 'ws-1', state: 'OPEN', mutationClaim: 'TURN', worktreeRemovedAt: null },
    activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, continuation: false };
}

// Run 4 updated the refresh note, which keeps its replaced text, and added the deletes note, which Disable notes from this run turns off.
const HISTORY = [contextRun(1, 'SUBMITTED', 60 * 27, null), contextRun(2, 'ANSWERED', 60 * 26, 0, { noteIds: [refresh.id] }),
  contextRun(3, 'ANSWERED', 48, 1, { noteIds: [commits.id] }), contextRun(4, 'LIMIT', 21, 2, { noteIds: [refresh.id, deletes.id] })];

type Scene = 'fresh' | 'discovering' | 'suggested' | 'review' | 'learning';

type State = {
  notebook: RepositoryNotebookDto; times: ContextTimesDto; build: ContextBuildViewDto; report: ContextDiscoveryReportDto | null;
  planView: ContextLearningPlanViewDto; candidates: ContextMergedCandidatesDto; reports: ContextReportedNoteDto[]; disputes: ContextDisputesDto; staleOnce: boolean;
  changedPages: Record<string, string>;
  calls: { starts: Array<{ kind: string; request: unknown }>; restores: string[]; disables: string[]; allows: unknown[]; declines: unknown[];
    configures: unknown[]; stops: string[]; keeps: string[]; reports: ReportRepositoryContextNoteRequest[]; questions: unknown[] };
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
    planView: full ? { plan: name === 'learning' ? { ...plan(), batch: { key: 'learn-key', queue: [0, 1, 2, 3].map(question => ({ topicId: 'topic:planner', question })),
      ran: [{ topicId: 'topic:iceberg', question: 2 }], total: 6, alone: false } } : plan(), next: { topicId: 'topic:iceberg', question: 2 } } : { plan: null, next: null },
    build: { buildRequestSequence: runs.length, notebookRevision: full ? 12 : 1, canStart: running === undefined, runs,
      build: running === undefined ? null : binding(running.taskId) },
    candidates: { candidates: full ? [{ id: 'pr:trinodb/trino#27315', repo: 'trinodb/trino', number: 27315, title: 'Retry Iceberg commits on conflicting snapshots',
      mergedAt: ago(60 * 5), taskTitle: 'Fix flaky Iceberg commit test', taskResult: 'Tests pass', issue: { key: '#27290', title: 'Concurrent commits lose files' },
      review: { combinedResult: 'Approved after 2 rounds' }, checks: [{ conclusion: 'success' }, { conclusion: 'success' }] }] : [] },
    reports: full ? [...reports(), ...contestRefresh ? [contestedRefresh()] : []] : [],
    disputes: full && contestRefresh ? { contested: { [refresh.id]: { statement: DISPUTED, version: refresh.version } }, settlements: { [refresh.id]: EARLIER } }
      : { contested: {}, settlements: {} },
    staleOnce: false,
    changedPages: full && changedDoc ? { 'ev-doc': ago(45) } : {},
    calls: { starts: [], restores: [], disables: [], allows: [], declines: [], configures: [], stops: [], keeps: [], reports: [], questions: [] },
  };
}

function usage(window: ContextUsageWindow): ContextUsageOverviewDto {
  return { trackingSince: ago(60 * 24 * 40), window,
    summary: { eligibleDevelopmentTasks: window === '30d' ? 14 : 31, eligibleReviewRuns: window === '30d' ? 9 : 22, contextOffTasks: 2,
      developmentTasksFoundOrOpened: 8, reviewRunsFoundOrOpened: 5,
      webLookups: { notesOn: { tasks: window === '30d' ? 11 : 26, lookups: window === '30d' ? 9 : 24 }, notesOff: { tasks: 2, lookups: 7 } } },
    entries: [refresh, layout, lesson, spi].map((entry, index) => ({ entryId: entry.id, title: entry.title, currentVersion: entry.version,
      foundTasks: 9 - index * 2, openedTasks: 6 - index * 2, lastConfirmedUseAt: ago(60 * (index + 2)),
      helpedTasks: 4 - index, notRelevantTasks: index, wrongTasks: entry === lesson ? 1 : 0 })) };
}

/** The development task's context activity: it opened the refresh note and only found the SPI note in a search. */
const taskActivity = (): DevelopmentContextActivityDto => ({ hasMore: false, usageTruncated: false, cards: [
  { reference: 'context-read:1', turnId: 'turn-1', kind: 'READ', createdAt: ago(30), notebookRevision: 12, delivery: 'CONFIRMED', bytes: 900, query: '', paths: [],
    entries: [{ id: refresh.id, version: refresh.version, kind: 'KNOWLEDGE', scopeId: refresh.scopeId, title: refresh.title, matchReasons: [], included: true }],
    omittedCount: 0, scopeStates: [], warnings: [] },
  { reference: 'context-search:1', turnId: 'turn-1', kind: 'SEARCH', createdAt: ago(31), notebookRevision: 12, delivery: 'CONFIRMED', bytes: 400, query: 'SPI',
    paths: [], entries: [{ id: spi.id, version: spi.version, kind: 'KNOWLEDGE', scopeId: spi.scopeId, title: spi.title, matchReasons: ['Search text'], included: true }],
    omittedCount: 0, scopeStates: [], warnings: [] }] });

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
  const start = (kind: string, request: { requestKey: string }, mode: 'DISCOVER' | 'LEARN', patch: Partial<ContextRunDto> = {}) => {
    state.calls.starts.push({ kind, request: clone(request) });
    const sequence = state.build.runs.length + 1;
    const next = contextRun(sequence, 'RUNNING', 0, mode === 'DISCOVER' ? null : 2, { activity: [], ...patch });
    state.build = { ...state.build, buildRequestSequence: sequence, canStart: false, runs: [...state.build.runs, next],
      build: { ...binding(next.taskId), requestKey: request.requestKey } };
    return Promise.resolve(clone(state.build));
  };
  const planView = () => clone(state.planView);
  const changeQuestions = (request: { topicId: string }, change: (questions: ContextPlanQuestionDto[]) => ContextPlanQuestionDto[]) => {
    state.calls.questions.push(clone(request));
    const current = state.planView.plan;
    assert(current !== null, 'A plan must exist');
    current.topics = current.topics.map(topic => topic.id === request.topicId ? { ...topic, questions: change(topic.questions) } : topic);
    return planView();
  };
  const bridge: Partial<Bridge> = {
    getRepositoryContext: async () => clone(state.notebook),
    getRepositoryContextTimes: async () => clone(state.times),
    getRepositoryContextBuild: async () => clone(state.build),
    getRepositoryContextDiscoveryReport: async () => clone(state.report),
    getRepositoryContextLearningPlan: async () => planView(),
    getRepositoryContextBaseline: async () => ({ branch: 'master', sha: SHA }),
    getRepositoryContextMergedCandidates: async () => clone(state.candidates),
    getRepositoryContextUsage: async (_workspaceId, window) => usage(window),
    getRepositoryContextReports: async () => clone(state.reports),
    getRepositoryContextDisputes: async () => clone(state.disputes),
    getRepositoryContextChangedPages: async () => ({ changed: clone(state.changedPages) }),
    reportRepositoryContextNote: async (_workspaceId, request) => {
      // The first report is refused the way the backend refuses a version the task never received.
      if (state.calls.reports.push(clone(request)) === 1) {
        throw new Error('Error invoking remote method \'repository-context:report\': Error: Report only a note version this task received or opened');
      }
      return clone(state.reports);
    },
    keepRepositoryContextEntry: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      state.calls.keeps.push(request.entryId);
      current.entries = current.entries.map(value => value.id === request.entryId ? { ...value, version: current.revision + 1 } : value);
      state.reports = state.reports.filter(value => value.entryId !== request.entryId);
      const contest = state.disputes.contested[request.entryId];
      if (contest !== undefined) {
        delete state.disputes.contested[request.entryId];
        state.disputes.settlements[request.entryId] = { version: current.revision + 1, by: 'USER', statement: contest.statement, rootCause: null };
      }
    }),
    getModelSettings: () => createPreviewBridge('ws-1').getModelSettings(),
    getAgentTask: async taskId => ({ id: taskId, workspaceId: 'ws-1', title: TASK_TITLES[taskId] ?? 'Task' }) as unknown as AgentTaskDto,
    listAgentTasks: async () => Object.entries(TASK_TITLES).map(([id, title]) => ({ id, workspaceId: 'ws-1', title }) as unknown as AgentTaskDto),
    startRepositoryContextDiscovery: async (_workspaceId, request) => start('discover', request, 'DISCOVER'),
    startRepositoryContextLearning: async (_workspaceId, request) => start('learn', request, 'LEARN'),
    continueRepositoryContextLearning: async (_workspaceId, request) => start('continue', request, 'LEARN'),
    learnRepositoryContextMergedCandidate: async (_workspaceId, request) => start('candidate', request, 'LEARN'),
    investigateRepositoryContextNote: async (_workspaceId, request) => start('investigate', request, 'LEARN', { question: null,
      investigation: { entryId: request.entryId, title: refresh.title, statement: DISPUTED, outcome: null } }),
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
    restoreRepositoryContextEntry: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      const active = current.entries.find(value => value.id === request.entryId);
      const previous = current.replaced.find(value => value.id === request.entryId);
      assert(active !== undefined && previous !== undefined, 'A restored note must have a replaced version');
      state.calls.restores.push(request.entryId);
      const restored = { ...previous, version: current.revision + 1 };
      current.entries = current.entries.map(value => value.id === restored.id ? restored : value);
      current.replaced = current.replaced.map(value => value.id === restored.id ? active : value);
      state.times.entries[`${restored.version}:${restored.id}`] = { at: new Date().toISOString(), taskId: null };
    }),
    disableRepositoryContextRunNotes: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      state.calls.disables.push(request.taskId);
      current.entries = current.entries.map(value => state.times.entries[`${value.version}:${value.id}`]?.taskId === request.taskId
        && request.noteIds.includes(value.id) && !current.replaced.some(previous => previous.id === value.id) ? { ...value, enabled: false } : value);
    }),
    editRepositoryContextEntry: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      current.entries = current.entries.map(value => value.id !== request.id ? value
        : { ...value, title: request.title, summary: request.summary, body: request.body, version: current.revision + 1 });
    }),
    setRepositoryContextEntryEnabled: async (_workspaceId, request) => mutate(request.expectedRevision, current => {
      current.entries = current.entries.map(value => value.id === request.entryId ? { ...value, enabled: request.enabled } : value);
    }),
    discardRepositoryContextLearningPlan: async () => { state.planView = { plan: null, next: null }; return planView(); },
    addRepositoryContextQuestion: async (_workspaceId, request) => changeQuestions(request, questions => [...questions,
      { text: request.text, status: 'PENDING', progress: '', origin: 'USER', note: '' }]),
    editRepositoryContextQuestion: async (_workspaceId, request) => changeQuestions(request, questions => questions.map((value, index) =>
      index === request.question ? { ...value, text: request.text } : value)),
    dropRepositoryContextQuestion: async (_workspaceId, request) => changeQuestions(request, questions => questions.map((value, index) =>
      index === request.question ? { ...value, status: 'DROPPED', note: 'Dropped by you' } : value)),
    goDeeperRepositoryContextTopic: async (_workspaceId, request) => start('deeper', request, 'LEARN'),
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
  ['earlier plans require reselection and keep their notes', async () => {
    await render('review', null, true);
    const notes = state.notebook.entries.length;
    assert(text().includes('Reselect topics before continuing'), 'Earlier plans are not shown as complete or resumable');
    await click('Review learning plan');
    assert(text(drawer()).includes('Saved notes are kept'), 'The plan explains preservation');
    assert(text(drawer()).includes('OPEN QUESTIONS AND GAPS') && text(drawer()).includes('Delta Lake transaction-log behavior needs its own learning topic.'),
      'Deferred learning opportunities remain visible');
    assert(![...drawer().querySelectorAll('button')].some(button => button.textContent?.includes('Continue learning')), 'No continuation is offered');
    assert(state.calls.starts.length === 0 && state.notebook.entries.length === notes, 'Inspecting earlier data neither starts a run nor removes notes');
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
  ['a finished run names its saved notes and disables the ones it added only after confirming, without a run', async () => {
    await render('review');
    await waitFor(() => document.querySelector('[aria-label^="Repository context, "]')?.getAttribute('aria-label') === 'Repository context, 2 to review',
      'The sidebar counts the source proposal and the report on a note you wrote, not the report on an agent-written note');
    assert(!/draft/i.test(text()), 'Nothing on the Overview mentions drafts');
    assert(text(document.querySelector('.rcp-status')).includes('Run 4 saved 2 notes.'), 'The status card names the notes the last run saved');
    await click('Disable notes from this run');
    const dialog = document.querySelector('dialog[open]');
    assert(dialog !== null && text(dialog).includes('No agent run starts'), 'Disabling asks first and says no agent runs');
    assert(state.calls.disables.length === 0, 'Nothing is disabled before confirming');
    await click('Disable notes', dialog);
    await waitFor(() => !text().includes('Disable notes from this run'), 'The action goes away once the run’s notes are disabled');
    assert(state.calls.disables.join() === 'task-run-4' && state.calls.starts.length === 0, 'Disabling names the run’s task and starts no run');
    const enabled = (id: string) => state.notebook.entries.find(value => value.id === id)?.enabled;
    assert(enabled(deletes.id) === false && enabled(refresh.id) === true, 'Only the note the run added and nobody changed is disabled');
    assert(text(document.body).includes('no agent run'), 'The confirmation says no run started');
  }],
  ['Restore makes the replaced text current again without a run', async () => {
    await render('review');
    const rows = [...document.querySelectorAll<HTMLElement>('.rcp-recent')];
    const restorable = rows.filter(row => [...row.querySelectorAll('button')].some(button => button.textContent === 'Restore'));
    assert(restorable.length === 1 && text(restorable[0]).includes(refresh.title), 'Only the note with a replaced version offers Restore');
    await click(refresh.title);
    assert(control(`Current v${refresh.version}`, drawer()).getAttribute('aria-selected') === 'true', 'The note opens on its current version');
    await click('Changes', drawer());
    assert(drawer().querySelector('.rcp-diff__line.is-add')?.textContent?.includes('REFRESH') === true, 'Changes show what the last change added');
    await click(`Previous v${refreshBefore.version}`, drawer());
    assert(drawer().querySelectorAll('.rcp-claim').length === refreshBefore.claims.length, 'The previous version shows its own claims');
    await click('Close', drawer());
    await click('Restore', restorable[0]);
    assert(document.querySelector('.rcp-drawer') === null, 'Restore in the list does not open the note');
    assert(state.calls.restores.join() === refresh.id && state.calls.starts.length === 0, 'Restore sends one request and starts no run');
    await waitFor(() => text(document.body).includes('Previous version restored'), 'The confirmation names what changed');
    assert(text(document.body).includes('no agent run'), 'The confirmation says no run started');
    const current = state.notebook.entries.find(value => value.id === refresh.id);
    assert(current !== undefined && !current.body.includes('REFRESH') && state.notebook.replaced.some(value => value.body.includes('REFRESH')),
      'The replaced text is current again, and the text it replaced can be restored in turn');
  }],
  ['a stale revision keeps the drawer open with its error', async () => {
    await render('review');
    await click(refresh.title);
    state.staleOnce = true;
    await click('Restore previous version', drawer());
    await waitFor(() => drawer().querySelector('[role=alert]') !== null, 'The conflict is shown in the drawer');
    assert(state.calls.restores.length === 0 && drawer().textContent?.includes(refresh.title), 'Nothing is restored on a stale revision');
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
  ['one Continue learning click runs every open question, starting with the saved next one', async () => {
    await render('review');
    assert(text().includes('Run 4 reached its source limit'), 'Saved historical limit outcomes remain truthful');
    assert(text().includes('6 runs, one per open question, plus any the runs add (up to 30)') && text().includes('Stop ends the batch'), 'The run line counts the batch');
    assert(document.querySelector('.rcp-q__order.is-next[aria-label="Runs next in this batch"]') !== null
      && document.querySelector('.rcp-q__order[aria-label="Runs last in this batch, #6"]') !== null, 'The plan previews what one click runs and where it ends');
    await click('Continue learning');
    assert(state.calls.starts.length === 1 && state.calls.starts[0].kind === 'continue', 'Continue sends one request');
  }],
  ['questions grow, change and drop without a run, and Go deeper starts one', async () => {
    await render('review');
    const topic = () => {
      const found = [...document.querySelectorAll('.rcp-topic')].find(item => item.querySelector('.rcp-topic__title')?.textContent === 'Iceberg connector');
      assert(found !== undefined, 'The Iceberg topic is listed');
      return found;
    };
    const type = (input: HTMLInputElement | null, value: string) => {
      assert(input !== null, 'The question field is visible');
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const origin = (label: string) => [...topic().querySelectorAll('.rcp-q__origin')].some(icon => icon.getAttribute('aria-label') === label);
    assert(topic().querySelector('.rcp-q__meta[title^="Only metadata commits retry"]') !== null && !text(topic()).includes('Only metadata commits retry'),
      'A corrected question keeps its reason on hover');
    assert(origin(`From a learning run\nReplaces “${CORRECTED}”`) && !text(topic()).includes('Replaces'), 'A follow-up says where it came from on hover');
    assert(!text(topic()).includes('Not started'), 'Rows leave unstarted questions to their glyph');
    assert(topic().querySelector('.rcp-stat[aria-label="2 of 4 questions answered"]')?.textContent === '2/4' && !text(topic()).includes('questions answered'),
      'The corrected question no longer counts, and the count is an icon and a number');
    assert(![...topic().querySelectorAll('button')].some(button => button.getAttribute('aria-label') === 'Edit “How are delete files applied during reads?”'),
      'A started question cannot be edited');
    type(topic().querySelector('input[aria-label="Add a question to Iceberg connector"]'), '  How are orphan files removed?  ');
    await settle();
    await click('Add question', topic());
    assert((state.calls.questions[0] as { text: string }).text === 'How are orphan files removed?', 'Add sends the trimmed question');
    assert(text(topic()).includes('How are orphan files removed?') && origin('Added by you'), 'The added question appears at once');
    await click('Edit “How are orphan files removed?”', topic());
    type(topic().querySelector('input[aria-label="Question"]'), 'How are orphan and expired files removed?');
    await settle();
    await click('Save', topic());
    assert((state.calls.questions[1] as { question: number }).question === 5 && text(topic()).includes('How are orphan and expired files removed?'), 'Edit rewrites that question');
    await click('Drop “How are orphan and expired files removed?”', topic());
    assert(topic().querySelector('.rcp-q__meta[title="Dropped by you"]') !== null, 'A dropped question stays visible');
    assert(state.calls.starts.length === 0, 'Changing questions starts no run');
    await click('Go deeper', topic());
    assert(state.calls.starts.map(start => start.kind).join() === 'deeper'
      && (state.calls.starts[0].request as { topicId: string }).topicId === 'topic:iceberg', 'Go deeper starts one run for the topic');
  }],
  ['a running learning question shows its question and can be stopped', async () => {
    await render('learning');
    assert(text().includes('Learning · Iceberg connector') && text().includes('“How are delete files applied during reads?”'), 'The card names the running question');
    assert(text().includes('run 1 of 6 in this batch'), 'The card counts the batch');
    assert(document.querySelector('.rcp-q__order[aria-label="Runs last in this batch, #5"]') !== null, 'The plan marks the question the batch ends with');
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
    const opener = reviewRow('https://iceberg.apache.org/docs/latest/');
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
    await click('Notes8');
    await click('Iceberg connector5');
    assert(document.querySelectorAll('.rcp-note').length === 5, 'The area rail narrows to that area');
    await click('Lessons 2');
    assert(document.querySelectorAll('.rcp-note').length === 2 && text().includes(lesson.title) && text().includes(snapshotCheck.title), 'The kind filter keeps lessons');
    await click('All notes8');
    await click('All 8');
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
  ['Home offers the waiting source review and a task’s saved notes open Repository context', async () => {
    await renderHome();
    await waitFor(() => text(document.querySelector('.item-row.is-context')).includes('1 source · 1 note to review'),
      'Home counts source proposals and reports on notes you wrote');
    assert(text().includes('Sending starts a task with Codex · 5.6 Terra High.'), 'Explore mode names the run it starts');
    await renderTaskNotes();
    const card = document.querySelector<HTMLElement>('.rcx-note');
    assert(card !== null && text(card).includes(snapshotCheck.title) && document.querySelectorAll('.rcx-note').length === 1, 'Only the note this task saved is shown');
    card.click();
    await settle();
    assert(window.location.hash === '#/workspaces/ws-1/context', `The card opens Repository context (${window.location.hash})`);
    assert(state.calls.starts.length === 0, 'Nothing here starts a run');
    window.location.hash = '';
  }],
  ['a report on a note you wrote waits in Needs you, shows its evidence, and Keep closes it without a run', async () => {
    await render('review');
    await waitFor(() => document.querySelector('.rcp-review.rcp-tone--amber') !== null, 'The reported note appears in Needs you');
    assert(text(document.querySelector('[aria-label="Needs you"] .rcp-label')) === 'NEEDS YOU · 2', 'Needs you counts the source and the reported note');
    assert(text(document.querySelector('.rcp-head')).includes('2 to review'), 'The header counts both');
    const row = reviewRow(lesson.title);
    await waitFor(() => text(row).includes('Reported wrong') && text(row).includes('by task “Fix flaky Iceberg commit test”'), 'The row names the reporting task');
    assert(![...document.querySelectorAll('.rcp-review__title')].some(title => title.textContent === commits.title), 'A report on an agent-written note waits for agents');
    await review(lesson.title);
    const panel = drawer();
    assert(text(panel).includes('REPORTS ON V3 · 1') && text(panel).includes(`“${QUOTE}”`), 'The drawer quotes the reported statement');
    assert(text(panel).includes('IcebergUtil.java:212–214') && panel.querySelector('.rcp-excerpt')?.textContent?.includes('213  NestedField field = schema.findField(fieldId);'),
      'The cited lines show beside the quote');
    assert(text(panel).includes('Reported by task “Fix flaky Iceberg commit test”') && text(panel).includes('Field IDs are unique across the whole schema'),
      'The report shows its reason and reporter');
    assert(text(panel).includes('Not relevant · The task only touched top-level columns.') && text(panel).includes('by task “Prune nested Iceberg fields”')
      && !text(panel).includes('Explained duplicate leaf names'), 'Grades after the report show as short lines; earlier ones do not');
    assert(text(panel).includes('opened in 2 tasks · helped 2 · not relevant 2 · wrong 1 · last opened 4h ago'), 'The drawer shows the grade line');
    assert(text(panel).includes('No agent runs.') && control('Keep', panel).querySelector('svg') === null, 'Keep says no agent runs and carries no lightning mark');
    await click('Keep', panel);
    assert(state.calls.keeps.join() === lesson.id && state.calls.starts.length === 0, 'Keep sends one request and starts no run');
    await waitFor(() => text(document.body).includes(`Kept as v13 · reports closed · no agent run`), 'The confirmation says no agent ran');
    assert(!text(drawer()).includes('REPORTS ON') && text(drawer()).includes('not opened'), 'The new version has no open report and no opens yet');
    await click('Close', drawer());
    await waitFor(() => !document.querySelector('[aria-label="Needs you"]')?.textContent?.includes(lesson.title), 'The note leaves Needs you');
    await waitFor(() => document.querySelector('[aria-label^="Repository context, "]')?.getAttribute('aria-label') === 'Repository context, 1 to review',
      'The sidebar count follows');
  }],
  ['a note agents disagree about waits in Needs you with both sides and its earlier settlement, and Keep ends the dispute without a run', async () => {
    contestRefresh = true;
    try {
      await render('review');
      await waitFor(() => text(document.querySelector('[aria-label="Needs you"] .rcp-label')) === 'NEEDS YOU · 3', 'Needs you counts the contested note');
      const row = reviewRow(refresh.title);
      assert(text(row).includes('Agents disagree') && text(row).includes(`“${DISPUTED}”`), 'The row quotes the disputed statement');
      await review(refresh.title);
      const panel = drawer();
      await waitFor(() => text(panel).includes('AGENTS DISAGREE · SINCE V11'), 'The drawer shows the dispute');
      assert(text(panel).includes(`Holds on v11 · “${DISPUTED}”`) && text(panel).includes(`Wrong on v11 · “${DISPUTED}”`), 'Both sides show their quotes');
      assert(text(panel).includes('By task “Prune nested Iceberg fields”') && text(panel).includes('The metadata cache is invalidated after each commit.'),
        'Each side shows its reason and who said it');
      assert(text(panel).includes('Settled before: An investigation settled it in v4: One reader followed the REFRESH procedure'), 'The earlier root cause shows');
      assert(text(panel).includes('Restore brings back v4') && text(panel).includes('No agent runs.') && control('Keep', panel).querySelector('svg') === null,
        'Keep and Restore say no agent runs');
      await click('Keep', panel);
      assert(state.calls.keeps.join() === refresh.id && state.calls.starts.length === 0, 'Keep sends one request and starts no run');
      await waitFor(() => !text(drawer()).includes('AGENTS DISAGREE') && text(drawer()).includes('You kept v13 when agents disagreed'),
        'The dispute ends and the note shows your choice');
    }
    finally {
      contestRefresh = false;
    }
  }],
  ['usage compares web lookups per task with notes on and off', async () => {
    await render('review');
    await click('More options');
    await click('Usage');
    await waitFor(() => text(drawer()).includes('WEB LOOKUPS'), 'Usage shows web lookups');
    assert(text(drawer()).includes('0.8 per task with notes on (11 tasks) · 3.5 per task with notes off (2 tasks)'), 'Both settings show their per-task lookups');
    assert(state.calls.starts.length === 0, 'Reading usage starts no run');
  }],
  ['a note whose cited page changed is flagged in the notes list and its sources', async () => {
    changedDoc = true;
    try {
      await render('review');
      await click('Notes8');
      const row = [...document.querySelectorAll<HTMLButtonElement>('button.rcp-note')].find(item => item.querySelector('.rcp-note__title')?.textContent === refresh.title);
      assert(row !== undefined && text(row).includes('Page changed'), 'The note row is flagged');
      const cites = state.notebook.entries.filter(entry => entry.sourceIds.includes('ev-doc')).length;
      assert([...document.querySelectorAll('button.rcp-note')].filter(item => text(item).includes('Page changed')).length === cites, 'Exactly the notes citing the page are flagged');
      row.click();
      await settle();
      await settle();
      assert(text(drawer()).includes('A page this note cites changed after it was saved'), 'The drawer explains the change');
      assert(text(drawer().querySelector('.rcp-source__changed')).includes('changed since'), 'The changed page is marked among the sources');
      assert(state.calls.starts.length === 0, 'Flagging starts no run');
    }
    finally {
      changedDoc = false;
    }
  }],
  ['Investigate starts one named run for a note agents disagree about, and only from its lightning action', async () => {
    contestRefresh = true;
    try {
      await render('review');
      await waitFor(() => text().includes('Agents disagree'), 'The dispute appears');
      await review(refresh.title);
      const panel = drawer();
      await waitFor(() => text(panel).includes('AGENTS DISAGREE'), 'The drawer shows the dispute');
      assert(control('Investigate', panel).querySelector('svg') !== null, 'Investigate carries the lightning mark');
      assert(text(panel).includes('Starts 1 run · reads the code at the monitored branch head · Codex'), 'The run line names the run and model');
      const starts = () => state.calls.starts.length;
      assert(starts() === 0, 'Opening the dispute starts nothing');
      await click('Investigate', panel);
      const started = state.calls.starts.at(-1) as { kind: string; request: { entryId: string; expectedRevision: number } } | undefined;
      assert(started?.kind === 'investigate' && started.request.entryId === refresh.id && started.request.expectedRevision === state.notebook.revision,
        'Investigate sends the note and the shown revision');
      await waitFor(() => text(document.querySelector('.rcp-status')).includes(`Investigating · ${refresh.title}`)
        && text(document.querySelector('.rcp-status')).includes(DISPUTED), 'The status card names the investigation and its statement');
      assert(starts() === 1, 'One click starts one run');
    }
    finally {
      contestRefresh = false;
    }
  }],
  ['notes show grade lines, and your report on an agent-written note shows in its drawer', async () => {
    await render('review');
    await click(commits.title);
    await waitFor(() => text(drawer()).includes('REPORTS ON V10 · 1'), 'The agent-written note shows its open report');
    assert(text(drawer()).includes('No statement quoted') && text(drawer()).includes('Reported by you'), 'A user report quotes nothing and names you');
    assert(text(drawer()).includes('Agents that open this note see open reports') && control('Keep', drawer()) !== null, 'Keep is offered on any reported note');
    await click('Close', drawer());
    await click('Notes8');
    const row = (title: string) => [...document.querySelectorAll('.rcp-note')].find(note => note.querySelector('.rcp-note__title')?.textContent === title);
    assert(text(row(refresh.title)).includes('opened in 6 tasks · helped 4 · not relevant 0 · wrong 0 · last opened 2h ago'), 'A row shows its grade line');
    assert(text(row(spi.title)).includes('not opened') && !text(row(spi.title)).includes('helped'), 'An unopened note says so');
  }],
  ['Wrong on a note the task opened reports that exact version, shows a refusal inline, and never offers search hits', async () => {
    await renderTaskActivity();
    const changes: unknown[] = [];
    const onChange = (event: Event) => changes.push(event instanceof CustomEvent ? event.detail : null);
    window.addEventListener(CONTEXT_CHANGED, onChange);
    try {
      assert(document.querySelectorAll('.agt-context__wrong').length === 1, 'Only the opened note offers Wrong');
      await click(`Report ${refresh.title} v${refresh.version} as wrong`);
      const input = document.querySelector<HTMLInputElement>('.agt-context__report input');
      assert(input !== null && input.maxLength === 300, 'The optional reason is one bounded line');
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, '  Refresh happens per query now  ');
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await click('Report');
      await waitFor(() => text(document.querySelector('.agt-context__report [role=alert]')) === 'Report only a note version this task received or opened',
        'A refusal shows inline without the IPC prefix');
      await click('Report');
      await waitFor(() => text().includes('Reported. Agents that open this note see the report.'), 'The report is confirmed');
      const sent = state.calls.reports.at(-1);
      assert(sent?.taskId === 'task-dev-7' && sent.entryId === refresh.id && sent.entryVersion === refresh.version && sent.reason === 'Refresh happens per query now',
        'The report names the task and the exact version with the trimmed reason');
      assert(changes.join() === 'ws-1' && state.calls.starts.length === 0, 'Reporting refreshes the Repository context count once and starts no run');
    }
    finally {
      window.removeEventListener(CONTEXT_CHANGED, onChange);
    }
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

/** A task's transcript end, with the note that task saved. */
async function renderTaskNotes(): Promise<void> {
  await render('review');
  flushSync(() => root?.render(<>
    <AppSidebar page="workspaces" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}} canGoBack={false} canGoForward={false}
      onBack={() => {}} onForward={() => {}} workspace={workspace} workspaceTab="tasks" onWorkspaceTab={() => {}} />
    <main className="app-content"><div style={{ maxWidth: 760, margin: '32px auto', padding: '0 24px' }}>
      <p style={{ fontSize: 13, lineHeight: 1.6 }}>Pruning happens while splits are generated, before any data file is opened. It compares partition values and column bounds
        stored in the manifests with the pushed-down constraint.</p>
      <TaskContextNotes workspaceId="ws-1" taskId="task-dev-7" refreshKey="0" />
    </div></main>
  </>));
  await waitFor(() => document.querySelector('.rcx-note') !== null, 'The task note card appears');
}

/** A development task's opened context activity panel. */
async function renderTaskActivity(): Promise<void> {
  await render('review');
  flushSync(() => root?.render(<>
    <AppSidebar page="workspaces" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}} canGoBack={false} canGoForward={false}
      onBack={() => {}} onForward={() => {}} workspace={workspace} workspaceTab="tasks" onWorkspaceTab={() => {}} />
    <main className="app-content"><div style={{ maxWidth: 760, margin: '32px auto', padding: '0 24px' }}>
      <DevelopmentContextActivityPanel taskId="task-dev-7" workspaceId="ws-1" refreshKey="0" bridge={{ getAgentTaskRepositoryContext: async () => taskActivity() }} />
    </div></main>
  </>));
  document.querySelector<HTMLElement>('.agt-context summary')?.click();
  await waitFor(() => document.querySelector('.agt-context-card') !== null, 'The activity loads once the panel opens');
}

/** Renders one named state for a screenshot, e.g. `review:note`. */
export async function show(mode: string) {
  install();
  if (mode === 'home') { await renderHome(); return; }
  if (mode === 'task') { await renderTaskNotes(); return; }
  if (mode === 'task-report') { await renderTaskActivity(); await click(`Report ${refresh.title} v${refresh.version} as wrong`); return; }
  const [base, extra] = mode.split(':') as [Scene, string | undefined];
  contestRefresh = extra === 'contested';
  changedDoc = extra === 'page-changed';
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
    case 'note': await click(refresh.title); break;
    case 'page-changed': await click(refresh.title); break;
    case 'reported': await waitFor(() => document.querySelector('.rcp-review.rcp-tone--amber') !== null, 'The report appears'); await review(lesson.title); break;
    case 'contested': await waitFor(() => text().includes('Agents disagree'), 'The dispute appears'); await review(refresh.title); break;
    case 'changes': await click(refresh.title); await click('Changes', drawer()); break;
    case 'disable': await click('Disable notes from this run'); break;
    case 'plan': await click('Plan details'); break;
    case 'questions': document.querySelector('.rcp-q-add')?.scrollIntoView({ block: 'center' }); break;
    case 'sources': await review('https://iceberg.apache.org/docs/latest/'); break;
    case 'coverage': await click('See coverage'); break;
    case 'usage': await click('More options'); await click('Usage'); break;
    case 'advanced': await click('Advanced configuration'); break;
    case 'notes': await click('Notes8'); break;
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
