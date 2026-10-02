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
import { describe, expect, it } from 'vitest';
import {
  parseConfigureRepositoryContextRequest,
  parseContextBuildView,
  parseContextSpecChecks,
  parseContextSpecSources,
  parseDismissContextSpecFindingRequest,
  parseStartContextSpecCheckRequest,
  parseContextDiscoveryReport,
  parseContextChangedPages,
  parseContextDisputes,
  parseContextBaseline,
  parseContextLearningPlanView,
  parseContextReportedNotes,
  parseContextTimes,
  parseContextUsageOverview,
  parseContextUsageWindow,
  parseDisableRepositoryContextRunNotesRequest,
  parseEditRepositoryContextEntryRequest,
  parseReportRepositoryContextNoteRequest,
  parseRepositoryNotebook,
  parseRestoreRepositoryContextEntryRequest,
  parseStartContextDiscoveryRequest,
  parseStartContextLearningRequest,
  parseContinueContextLearningRequest,
  parseDiscardContextLearningPlanRequest,
  parseAddContextLearningQuestionRequest,
  parseEditContextLearningQuestionRequest,
  parseDropContextLearningQuestionRequest,
  parseGoDeeperContextLearningRequest,
  parseAllowContextLearningSourceRequest,
  parseDeclineContextLearningSourceRequest,
  parseContextMergedCandidates,
  parseInvestigateRepositoryContextNoteRequest,
  parseLearnContextMergedCandidateRequest,
  type ContextBuildViewDto,
  type ContextDiscoveryReportDto,
  type ContextLearningPlanDto,
  type ContextMergedCandidateDto,
  type ContextGradeDto,
  type ContextReportedNoteDto,
  type ContextRunDto,
  type ContextTimesDto,
  type ContextUsageOverviewDto,
  type ContextSourceDto,
  type RepositoryNotebookDto,
} from './contracts';

const source: ContextSourceDto = { id: 'docs', kind: 'DOCUMENTATION_ROOT', url: 'https://example.com/docs/', origin: 'USER', discoveryEvidenceRef: null };
const notebook: RepositoryNotebookDto = {
  schemaVersion: 1, revision: 7, useInReviews: true, useInDevelopment: true, sources: [source],
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, discoveryEvidenceRef: null }],
  entries: [{ id: 'knowledge:project', kind: 'KNOWLEDGE', scopeId: 'project', title: 'Project', summary: 'Summary', body: 'Keep the condition.',
    claims: [{ text: 'Conditional claim', basis: 'INFERENCE', sourceIds: ['source'] }], sourceIds: ['source'], origin: 'GENERATED', enabled: false, version: 2 }],
  replaced: [], sourceEvidence: [{ id: 'source', reference: { kind: 'DOCUMENT', sourceId: 'docs', url: 'https://example.com/docs/page' },
    provenance: 'Captured page', capture: 'BYTEQUAY_READ', retrievedAt: null }],
};
const usage: ContextUsageOverviewDto = {
  trackingSince: '2026-09-01T00:00:00Z', window: '30d',
  summary: { eligibleDevelopmentTasks: 2, eligibleReviewRuns: 1, contextOffTasks: 1, developmentTasksFoundOrOpened: 1, reviewRunsFoundOrOpened: 0,
    webLookups: { notesOn: { tasks: 1, lookups: 2 }, notesOff: { tasks: 1, lookups: 5 } } },
  entries: [{ entryId: 'knowledge:project', title: 'Project', currentVersion: 2, foundTasks: 1, openedTasks: 1, lastConfirmedUseAt: '2026-09-02T00:00:00Z',
    helpedTasks: 3, notRelevantTasks: 1, wrongTasks: 1 }],
};
const wrong: ContextGradeDto = { workspaceId: 'workspace', taskId: 'task-dev', reviewRunId: null, taskKind: 'DEVELOPMENT', grader: 'AGENT', grade: 'WRONG',
  note: { id: 'knowledge:project', version: 2, kind: 'KNOWLEDGE', scopeId: 'project', title: 'Project' }, reason: 'The cache is per query.',
  quote: 'Keep the condition.', citations: [{ path: 'src/Cache.java', startLine: 12, endLine: 13, commit: 'a'.repeat(40), excerpt: '12  refresh();\n13  read();\n' }],
  correction: null, gradedAt: '2026-09-29T10:00:00Z' };
const userReport: ContextGradeDto = { ...wrong, taskId: 'task-other', grader: 'USER', reason: '', quote: null, citations: [], gradedAt: '2026-09-29T11:00:00Z' };
const holds: ContextGradeDto = { ...wrong, taskId: 'review-task', reviewRunId: 'run-1', taskKind: 'PR_REVIEW', grade: 'HOLDS', gradedAt: '2026-09-29T12:00:00Z' };
const reported: ContextReportedNoteDto = { entryId: 'knowledge:project', entryVersion: 2, title: 'Project', userWritten: true, grades: [holds, userReport, wrong],
  contested: null, settlement: null };

describe('spec check contracts', () => {
  it('reads checks with their findings and sends only HTTPS specs', () => {
    const finding = { id: 'c1', type: 'BUG', predicted: 'DIVERGES', title: 'Removes keyed by path', rule: 'MUST key by path and deletion vector',
      specUrl: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md#checkpoints', code: ['plugin/trino-delta-lake/src/Writer.java:10-20'], reasoning: 'why',
      severity: 3, duplicate: { number: 31252, title: 'Checkpoint loses a remove', state: 'closed', url: 'https://github.com/trinodb/trino/issues/31252' },
      status: 'CONFIRMED', test: { path: 'plugin/trino-delta-lake/src/test/TestKeys.java', command: './mvnw -pl plugin/trino-delta-lake test -Dtest=TestKeys',
        exitCode: 1, output: 'expected: 2 but was: 1', source: 'class TestKeys {}' }, reason: 'fails', draft: { title: 'Removes keyed by path', body: 'body', labels: ['bug'] },
      dismissed: null as string | null };
    const check = { id: 'check-1', planId: 'plan', topicId: 'topic:delta', topicTitle: 'Delta Lake', scopeId: 'delta', paths: ['plugin/trino-delta-lake/'],
      baselineSha: 'a'.repeat(40), requestKey: 'key', specs: ['https://github.com/delta-io/delta/blob/master/PROTOCOL.md'],
      checkedAgainst: ['https://github.com/delta-io/delta/blob/master/PROTOCOL.md'], labels: ['bug'], state: 'DONE',
      candidates: [finding, { ...finding, id: 'c2', status: 'NOT_REPRODUCED', test: null, draft: null, duplicate: null, reason: 'Needs a container' }],
      covered: ['Checkpoints'], next: ['Column Mapping'], reproductions: 2, detail: null as string | null, startedAt: '2026-10-01T00:00:00Z', endedAt: '2026-10-01T01:00:00Z' };
    expect(parseContextSpecChecks({ checks: [check] })).toEqual([check]);
    expect(() => parseContextSpecChecks({ checks: [{ ...check, state: 'PAUSED' }] })).toThrow('check state');
    expect(() => parseContextSpecChecks({ checks: [{ ...check, candidates: [{ ...finding, test: { ...finding.test, exitCode: 'failed' } }] }] })).toThrow('test exit code');
    expect(parseContextSpecSources({ sources: [{ url: check.specs[0], kind: 'PAGE', links: 25, sections: ['checkpoints'], approved: false }] }))
      .toEqual([{ url: check.specs[0], kind: 'PAGE', links: 25, sections: ['checkpoints'], approved: false }]);
    expect(() => parseContextSpecSources({ sources: [{ url: check.specs[0], kind: 'SPEC', links: 25, sections: [], approved: false }] })).toThrow('spec kind');
    const start = { requestKey: 'k', expectedRevision: 1, expectedBuildSequence: 2, planId: 'plan', topicId: 'topic:delta', specs: check.specs };
    expect(parseStartContextSpecCheckRequest(start)).toEqual(start);
    expect(() => parseStartContextSpecCheckRequest({ ...start, specs: ['http://example.com/spec'] })).toThrow('specs');
    expect(() => parseStartContextSpecCheckRequest({ ...start, specs: [] })).toThrow('specs');
    expect(() => parseStartContextSpecCheckRequest({ ...start, sections: ['checkpoints'] })).toThrow('unsupported request field');
    expect(parseDismissContextSpecFindingRequest({ reason: 'Filed on GitHub' })).toEqual({ reason: 'Filed on GitHub' });
    expect(() => parseDismissContextSpecFindingRequest({ reason: ' ' })).toThrow('dismissal reason');
  });
});

describe('repository context contracts', () => {
  it('parses usage without treating an absent, removed or disabled note as confirmed use', () => {
    expect(parseContextUsageOverview(usage, '30d')).toEqual(usage);
    expect(parseContextUsageOverview({ ...usage, trackingSince: null,
      entries: [{ ...usage.entries[0], currentVersion: null, foundTasks: 0, openedTasks: 0, lastConfirmedUseAt: null }] }, '30d').entries[0].currentVersion)
      .toBeNull();
    expect(parseContextUsageWindow('all')).toBe('all');
  });

  it('rejects malformed usage identities, evidence stages and counts', () => {
    for (const malformed of [{ ...usage, window: 'all' }, { ...usage, trackingSince: undefined },
      { ...usage, summary: { ...usage.summary, developmentTasksFoundOrOpened: undefined } },
      { ...usage, summary: { ...usage.summary, eligibleReviewRuns: -1 } },
      { ...usage, summary: { ...usage.summary, webLookups: undefined } },
      { ...usage, summary: { ...usage.summary, webLookups: { ...usage.summary.webLookups, notesOff: { tasks: 1, lookups: -2 } } } },
      { ...usage, entries: [{ ...usage.entries[0], currentVersion: 0 }] },
      { ...usage, entries: [{ ...usage.entries[0], currentVersion: null }] },
      { ...usage, entries: [{ ...usage.entries[0], foundTasks: -1 }] },
      { ...usage, entries: [{ ...usage.entries[0], helpedTasks: undefined }] },
      { ...usage, entries: [{ ...usage.entries[0], wrongTasks: 1.5 }] }])
      expect(() => parseContextUsageOverview(malformed, '30d')).toThrow('Malformed repository context');
    for (const bad of ['month', null]) expect(() => parseContextUsageWindow(bad)).toThrow();
  });

  it('keeps graded counts of a removed note, since grades count every version', () => {
    const removed: ContextUsageOverviewDto['entries'][number] = { ...usage.entries[0], currentVersion: null, foundTasks: 0, openedTasks: 0, lastConfirmedUseAt: null };
    expect(parseContextUsageOverview({ ...usage, entries: [removed] }, '30d').entries[0]).toEqual(removed);
  });

  it('parses reported notes with user reports, review answers and cited lines exactly', () => {
    expect(parseContextReportedNotes([reported], 'workspace')).toEqual([reported]);
    expect(parseContextReportedNotes([], 'workspace')).toEqual([]);
    const corrected = { ...wrong, correction: { version: 3, statement: 'The cache is per transaction.' } };
    expect(parseContextReportedNotes([{ ...reported, grades: [corrected] }], 'workspace')[0].grades[0].correction).toEqual(corrected.correction);
  });

  it('parses a contested note with the grades of its earlier versions and how its last dispute was settled', () => {
    const earlier = { ...wrong, note: { ...wrong.note, version: 1 }, correction: { version: 2, statement: 'Keep the condition.' } };
    const settlement = { version: 1, by: 'INVESTIGATION' as const, statement: 'The condition holds.', rootCause: 'Each side read a different branch.' };
    const contested = { ...reported, userWritten: false, grades: [holds, earlier], contested: { statement: 'Keep the condition.', version: 2 }, settlement };
    expect(parseContextReportedNotes([contested], 'workspace')).toEqual([contested]);
    expect(() => parseContextReportedNotes([{ ...contested, grades: [{ ...holds, note: { ...holds.note, version: 3 } }] }], 'workspace')).toThrow('Malformed');
    expect(() => parseContextReportedNotes([{ ...contested, settlement: { ...settlement, rootCause: null } }], 'workspace')).toThrow('Malformed');
    expect(parseContextReportedNotes([{ ...contested, settlement: { ...settlement, by: 'USER', rootCause: null } }], 'workspace')[0].settlement?.by).toBe('USER');
    expect(parseContextDisputes({ contested: { 'knowledge:project': contested.contested }, settlements: { 'knowledge:project': settlement } }))
      .toEqual({ contested: { 'knowledge:project': contested.contested }, settlements: { 'knowledge:project': settlement } });
    for (const value of [null, { contested: {} }, { contested: { 'knowledge:project': { statement: 'Keep the condition.', version: 0 } }, settlements: {} }])
      expect(() => parseContextDisputes(value)).toThrow('Malformed repository context');
    expect(parseContextChangedPages({ changed: { 'context-read:page': '2026-09-30T00:00:00Z' } })).toEqual({ changed: { 'context-read:page': '2026-09-30T00:00:00Z' } });
    for (const value of [null, {}, { changed: { 'context-read:page': 30 } }])
      expect(() => parseContextChangedPages(value)).toThrow('Malformed repository context');
  });

  it('rejects reports whose grades belong to another workspace or version, or carry unknown grades', () => {
    for (const grades of [[{ ...wrong, workspaceId: 'other' }], [{ ...wrong, note: { ...wrong.note, version: 1 } }], [{ ...wrong, note: { ...wrong.note, id: 'other' } }],
      [{ ...wrong, grade: 'CONFIRMED' }], [{ ...wrong, grader: 'SYSTEM' }], [{ ...wrong, quote: 7 }], [{ ...wrong, gradedAt: undefined }],
      [{ ...wrong, citations: [{ ...wrong.citations[0], endLine: 11 }] }], [{ ...wrong, citations: [{ ...wrong.citations[0], excerpt: undefined }] }],
      [{ ...wrong, correction: { version: 0, statement: 'x' } }], 'none'])
      expect(() => parseContextReportedNotes([{ ...reported, grades }], 'workspace')).toThrow('Malformed repository context');
    for (const value of [null, {}, [{ ...reported, userWritten: 'yes' }], [{ ...reported, entryVersion: 0 }]])
      expect(() => parseContextReportedNotes(value, 'workspace')).toThrow('Malformed repository context');
  });

  it('sends a report with the exact task and note version and an optional reason', () => {
    const request = { taskId: 'task-dev', entryId: 'knowledge:project', entryVersion: 2, reason: '' };
    expect(parseReportRepositoryContextNoteRequest(request)).toEqual(request);
    for (const invalid of [{ ...request, entryVersion: 0 }, { ...request, taskId: ' ' }, { ...request, reason: null }, { ...request, grade: 'HELPED' },
      { ...request, quote: 'Keep the condition.' }])
      expect(() => parseReportRepositoryContextNoteRequest(invalid)).toThrow('Malformed repository context');
  });

  it('retains ownership, disabled notes, conditional claims and incomplete source provenance', () => {
    expect(parseRepositoryNotebook(notebook)).toEqual(notebook);
    expect(parseRepositoryNotebook({ ...notebook, useInReviews: false }).useInReviews).toBe(false);
    expect(parseRepositoryNotebook({ ...notebook, useInDevelopment: false }).useInDevelopment).toBe(false);
  });

  it('parses a replaced version like an entry and binds restore and run-note requests to a revision', () => {
    const previous = { ...notebook.entries[0], summary: 'Earlier summary', version: 1 };
    expect(parseRepositoryNotebook({ ...notebook, replaced: [previous] }).replaced).toEqual([previous]);
    expect(() => parseRepositoryNotebook({ ...notebook, replaced: undefined })).toThrow('replaced versions');
    expect(() => parseRepositoryNotebook({ ...notebook, replaced: [{ ...previous, kind: 'UNKNOWN' }] })).toThrow('entry kind');
    expect(parseRestoreRepositoryContextEntryRequest({ expectedRevision: 7, entryId: previous.id })).toEqual({ expectedRevision: 7, entryId: previous.id });
    expect(parseDisableRepositoryContextRunNotesRequest({ expectedRevision: 7, taskId: 'learn-task', noteIds: [previous.id] }))
      .toEqual({ expectedRevision: 7, taskId: 'learn-task', noteIds: [previous.id] });
    expect(() => parseDisableRepositoryContextRunNotesRequest({ expectedRevision: 7, taskId: 'learn-task' })).toThrow('run notes');
    expect(() => parseRestoreRepositoryContextEntryRequest({ expectedRevision: 7, entryId: previous.id, version: 1 })).toThrow('unsupported request field');
    expect(() => parseDisableRepositoryContextRunNotesRequest({ expectedRevision: 7, taskId: ' ', noteIds: [] })).toThrow('run task');
    expect(() => parseDisableRepositoryContextRunNotesRequest({ taskId: 'learn-task', noteIds: [] })).toThrow('expected revision');
  });

  it('requires nullable discovery provenance on stored scopes and registered sources', () => {
    const malformed: unknown[] = [{ ...notebook, scopes: [{ ...notebook.scopes[0], discoveryEvidenceRef: undefined }] },
      { ...notebook, sources: [{ ...source, discoveryEvidenceRef: undefined }] }, { ...notebook, sources: [{ ...source, discoveryEvidenceRef: false }] }];
    for (const value of malformed) {
      expect(() => parseRepositoryNotebook(value)).toThrow('Malformed repository context');
    }
    const retained = { ...notebook, scopes: [{ ...notebook.scopes[0], discoveryEvidenceRef: 'source' }], sources: [{ ...source, discoveryEvidenceRef: 'source' }] };
    expect(parseRepositoryNotebook(retained)).toEqual(retained);
  });

  it('rejects malformed saved content instead of rendering an empty notebook or coercing preferences', () => {
    for (const value of [null, {}, { ...notebook, schemaVersion: 2 }, { ...notebook, useInReviews: 'false' }, { ...notebook, useInDevelopment: 'false' },
      { ...notebook, revision: 1.5 }, { ...notebook, entries: null }, { ...notebook, entries: [{ ...notebook.entries[0], origin: 'VERIFIED' }] }]) {
      expect(() => parseRepositoryNotebook(value)).toThrow('Malformed repository context');
    }
  });

  it('keeps optimistic revisions and absent configuration fields unchanged', () => {
    expect(parseConfigureRepositoryContextRequest({ expectedRevision: 7, useInReviews: false }))
      .toEqual({ expectedRevision: 7, useInReviews: false });
    expect(parseConfigureRepositoryContextRequest({ expectedRevision: 7, useInDevelopment: false }))
      .toEqual({ expectedRevision: 7, useInDevelopment: false });
    expect(() => parseConfigureRepositoryContextRequest({ useInReviews: false })).toThrow('expected revision');
    expect(() => parseConfigureRepositoryContextRequest({ expectedRevision: 7, sources: [source] })).toThrow('origin');
    expect(() => parseConfigureRepositoryContextRequest({ expectedRevision: 7, scopes: [] })).toThrow('unsupported request field');
    expect(() => parseEditRepositoryContextEntryRequest({ expectedRevision: 7, ...notebook.entries[0] })).toThrow('unsupported request field');
  });

  it('retains builder status without manufacturing tasks and validates task ownership', () => {
    const empty: ContextBuildViewDto = { buildRequestSequence: 0, notebookRevision: 7, canStart: true, runs: [], build: null };
    expect(parseContextBuildView(empty, 'workspace')).toEqual(empty);
    const build: NonNullable<ContextBuildViewDto['build']> = { requestKey: 'learn-key', taskId: 'learn-task',
      task: { id: 'learn-task', workspaceId: 'workspace', state: 'OPEN', mutationClaim: 'TURN', worktreeRemovedAt: null },
      activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, continuation: false };
    const run: ContextRunDto = { sequence: 1, taskId: 'learn-task', mode: 'LEARN', requestedAt: '2026-09-01T00:00:00Z', endedAt: null,
      question: { planId: 'plan-1', topicId: 'topic:1', topicTitle: 'Iceberg connector', index: 0, text: 'How are tables read?' },
      pullRequest: null, outcome: 'RUNNING', sourceOperations: 3, documentOpens: 1, noteIds: [],
      activity: [{ at: '2026-09-01T00:00:01Z', text: 'Read README.md', tone: 'DONE' }], detail: null, investigation: null, check: null };
    const view = { ...empty, buildRequestSequence: 1, canStart: false, runs: [run], build };
    expect(parseContextBuildView(view, 'workspace')).toEqual(view);
    const reproduction: ContextRunDto = { ...run, mode: 'CHECK', question: null, check: { checkId: 'check-1', topicTitle: 'Delta Lake', reproduced: 'Removes keyed by path' } };
    expect(parseContextBuildView({ ...view, runs: [reproduction] }, 'workspace').runs[0].check).toEqual(reproduction.check);
    const { check: _absent, ...older } = run;
    expect(parseContextBuildView({ ...view, runs: [older] }, 'workspace').runs[0].check).toBeNull();
    expect(parseContextBuildView({ ...view, build: { ...view.build, task: null, activeTurn: null } }, 'workspace').build?.task).toBeNull();
    for (const value of [{ ...view, canStart: 'true' }, { ...view, build: undefined },
      { ...view, build: { ...view.build, task: { ...view.build.task, workspaceId: 'other' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, id: 'other' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, state: 'VERIFIED' } } },
      { ...view, build: { ...view.build, task: { ...view.build.task, mutationClaim: 7 } } },
      { ...view, build: { ...view.build, pendingApprovals: -1 } }, { ...view, build: { ...view.build, continuation: 'yes' } },
      { ...view, runs: [{ ...run, outcome: 'HELPED' }] }, { ...view, runs: [{ ...run, sequence: 0 }] },
      { ...view, runs: [{ ...run, activity: [{ ...run.activity[0], tone: 'GUESS' }] }] }])
      expect(() => parseContextBuildView(value, 'workspace')).toThrow('Malformed repository context');
  });

  it('keeps agent discovery and accepted learning tied to saved identities and source bounds', () => {
    const report: ContextDiscoveryReportDto = { id: 'discovery-task', workspaceId: 'workspace', baselineSha: 'a'.repeat(40),
      topics: [{ id: 'topic:1', title: 'Iceberg connector', questions: ['How are tables read?', 'How are snapshots committed?'],
        paths: ['plugin/trino-iceberg/', 'plugin/trino-hive/'], notePaths: ['plugin/trino-iceberg/'], sourceIds: ['source:1'], evidenceReadIds: ['read:1'], why: 'README identifies the connector.' }],
      sources: [{ id: 'source:1', kind: 'DOCUMENTATION_ROOT', location: 'https://trino.io/docs/current/connector/' }],
      evidence: [{ readId: 'read:1', path: 'plugin/trino-iceberg/README.md', startLine: 1, endLine: 2 }], remaining: ['Other connectors'], partial: true,
      coverage: { areas: [{ path: 'plugin/', surveyed: 1, total: 40 }], unsurveyed: ['plugin/trino-hive/'], unsurveyedTruncated: false },
      createdAt: '2026-09-01T00:00:00Z' };
    expect(parseContextDiscoveryReport({ report }, 'workspace')).toEqual(report);
    expect(parseContextDiscoveryReport({ report: null }, 'workspace')).toBeNull();
    for (const notePaths of [undefined, null, []] as Array<string[] | null | undefined>) {
      expect(parseContextDiscoveryReport({ report: { ...report, topics: [{ ...report.topics[0], notePaths }] } }, 'workspace')?.topics[0].notePaths)
        .toEqual(notePaths ?? null);
    }
    for (const invalid of [{ report: { ...report, workspaceId: 'another' } },
      { report: { ...report, topics: [{ ...report.topics[0], notePaths: 'plugin/trino-iceberg/' }] } },
      { report: { ...report, topics: [{ ...report.topics[0], sourceIds: ['unproposed'] }] } },
      { report: { ...report, evidence: [{ ...report.evidence[0], endLine: 0 }] } },
      { report: { ...report, topics: [{ ...report.topics[0], questions: [] }] } },
      { report: { ...report, topics: [{ ...report.topics[0], questions: Array(7).fill('Why?') }] } }])
      expect(() => parseContextDiscoveryReport(invalid, 'workspace')).toThrow('Malformed repository context');
    const plan: ContextLearningPlanDto = { id: 'plan-1', workspaceId: 'workspace', reportId: report.id, baselineSha: report.baselineSha,
      topics: [{ id: 'topic:1', title: 'Iceberg connector', scopeId: 'path:plugin/trino-iceberg/', paths: ['plugin/trino-iceberg/', 'plugin/trino-hive/'], notePaths: ['plugin/trino-iceberg/'],
        repositoryFiles: [], sourceIds: ['source:1'], questions: [{ text: 'How are tables read?', status: 'UNFINISHED', progress: 'Read the split manager.',
          origin: 'DISCOVERY', note: '' },
          { text: 'How are snapshots committed?', status: 'PENDING', progress: '', origin: 'LEARNING', note: 'Raised by run 2 · cited IcebergMetadata.java:40-41' }] }],
      gaps: ['Delta Lake transaction-log behavior needs its own learning topic.'], documentationSources: [source], documentInventory: { items: [{ sourceId: source.id, resolvedUrl: source.url,
        relativePage: null, depth: 0, status: 'PENDING', detail: '' }],
        suggestions: [{ url: 'https://example.org/iceberg/', parentUrl: source.url, topicId: 'topic:1', question: 0, taskId: 'learn-task',
          foundAt: '2026-09-01T00:00:00Z' }], truncated: false }, batch: { key: 'learn-key', queue: [{ topicId: 'topic:1', question: 1 }],
          ran: [{ topicId: 'topic:1', question: 0 }], total: 2, alone: false } };
    const planView = { plan, next: { topicId: 'topic:1', question: 0 } };
    expect(parseContextLearningPlanView(planView, 'workspace')).toEqual(planView);
    expect(parseContextLearningPlanView({ plan: null, next: null }, 'workspace')).toEqual({ plan: null, next: null });
    const closed = { ...plan, topics: [{ ...plan.topics[0], questions: [...plan.topics[0].questions,
      { text: 'Is there one commit path?', status: 'CORRECTED' as const, progress: 'Two paths.', origin: 'DISCOVERY' as const, note: 'There are two. Cited: A.java:1-9' },
      { text: 'How are releases cut?', status: 'DROPPED' as const, progress: '', origin: 'USER' as const, note: 'Dropped by you' }] }] };
    expect(parseContextLearningPlanView({ plan: closed, next: null }, 'workspace').plan).toEqual(closed);
    for (const notePaths of [undefined, null, []] as Array<string[] | null | undefined>) {
      expect(parseContextLearningPlanView({ ...planView, plan: { ...plan, topics: [{ ...plan.topics[0], notePaths }] } }, 'workspace').plan?.topics[0].notePaths)
        .toEqual(notePaths ?? null);
    }
    for (const invalid of [{ plan, next: { topicId: 'topic:1', question: 2 } }, { plan: null, next: planView.next },
      { plan: { ...plan, topics: [{ ...plan.topics[0], notePaths: 'plugin/trino-iceberg/' }] }, next: null },
      { plan: { ...plan, topics: [{ ...plan.topics[0], questions: [{ ...plan.topics[0].questions[0], status: 'GUESS' }] }] }, next: null },
      { plan: { ...plan, topics: [{ ...plan.topics[0], questions: [{ ...plan.topics[0].questions[1], progress: 'Started' }] }] }, next: null },
      { plan: { ...plan, topics: [{ ...plan.topics[0], questions: [{ ...plan.topics[0].questions[0], origin: 'GUESS' }] }] }, next: null },
      { plan: { ...plan, batch: { ...plan.batch, ran: [] } }, next: null },
      { plan: { ...plan, topics: [{ ...plan.topics[0], questions: Array(31).fill(plan.topics[0].questions[1]) }] }, next: null },
      { plan: { ...plan, documentInventory: { ...plan.documentInventory, suggestions: [{ ...plan.documentInventory.suggestions[0], topicId: 'other' }] } },
        next: null }] as unknown[])
      expect(() => parseContextLearningPlanView(invalid, 'workspace')).toThrow('Malformed repository context');
    const base = { requestKey: 'key', expectedRevision: 7, expectedBuildSequence: 1 };
    expect(parseStartContextDiscoveryRequest({ ...base, continuation: true }).continuation).toBe(true);
    expect(parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: ['topic:1'], excludedSourceIds: ['source:1'] }).excludedSourceIds)
      .toEqual(['source:1']);
    const excludedSourceIds = Array.from({ length: 200 }, (_, index) => `source:${index + 1}`);
    expect(parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: ['topic:1'], excludedSourceIds }).excludedSourceIds).toHaveLength(200);
    expect(() => parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: ['topic:1'], excludedSourceIds: [...excludedSourceIds, 'source:201'] }))
      .toThrow('Malformed repository context');
    expect(parseContinueContextLearningRequest({ ...base, planId: plan.id }).planId).toBe(plan.id);
    expect(parseDiscardContextLearningPlanRequest({ planId: plan.id, expectedRevision: 7, expectedBuildSequence: 1 }).planId).toBe(plan.id);
    const question = { planId: plan.id, topicId: 'topic:1', text: 'How are releases cut?' };
    expect(parseAddContextLearningQuestionRequest(question)).toEqual(question);
    expect(parseEditContextLearningQuestionRequest({ ...question, question: 1 })).toEqual({ ...question, question: 1 });
    expect(parseDropContextLearningQuestionRequest({ planId: plan.id, topicId: 'topic:1', question: 0 }).question).toBe(0);
    expect(parseGoDeeperContextLearningRequest({ ...base, planId: plan.id, topicId: 'topic:1' })).toEqual({ ...base, planId: plan.id, topicId: 'topic:1' });
    for (const invalid of [() => parseAddContextLearningQuestionRequest({ ...question, text: ' ' }),
      () => parseEditContextLearningQuestionRequest({ ...question, question: -1 }), () => parseDropContextLearningQuestionRequest({ ...question, question: 0 }),
      () => parseGoDeeperContextLearningRequest({ ...base, planId: plan.id, topicId: 'topic:1', prompt: 'forged' })])
      expect(invalid).toThrow();
    const allow = { planId: plan.id, suggestedUrl: 'https://example.org/iceberg/', boundary: 'ROOT', expectedRevision: 7, expectedBuildSequence: 1 };
    expect(parseAllowContextLearningSourceRequest(allow)).toEqual(allow);
    expect(parseAllowContextLearningSourceRequest({ ...allow, suggestedUrl: 'https://example.org/page.html', boundary: 'PAGE' }).boundary).toBe('PAGE');
    for (const invalid of [{ ...allow, suggestedUrl: 'https://example.org/page.html' }, { ...allow, boundary: 'SITE' },
      { ...allow, suggestedUrl: 'http://example.org/iceberg/' }, { ...allow, sourceId: source.id }])
      expect(() => parseAllowContextLearningSourceRequest(invalid)).toThrow('Malformed repository context');
    expect(parseDeclineContextLearningSourceRequest({ planId: plan.id, suggestedUrl: allow.suggestedUrl }).suggestedUrl).toBe(allow.suggestedUrl);
    expect(() => parseDeclineContextLearningSourceRequest({ planId: plan.id, suggestedUrl: allow.suggestedUrl, boundary: 'PAGE' })).toThrow();
    for (const invalid of [{ ...base, continuation: false, prompt: 'explore' }, { ...base, continuation: false, expectedRevision: -1 }, base])
      expect(() => parseStartContextDiscoveryRequest(invalid)).toThrow('Malformed repository context');
    expect(() => parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: [], excludedSourceIds: [] })).toThrow();
    expect(() => parseStartContextLearningRequest({ ...base, reportId: report.id, topicIds: ['topic:1'] })).toThrow();
    expect(() => parseContinueContextLearningRequest({ ...base, planId: '' })).toThrow();
    expect(() => parseDiscardContextLearningPlanRequest({ planId: plan.id, expectedRevision: 7, expectedBuildSequence: 1,
      deleteNotes: true })).toThrow();
  });

  it('reads baseline and save times without inventing them', () => {
    expect(parseContextBaseline(null)).toBeNull();
    expect(parseContextBaseline({ branch: 'master', sha: 'a'.repeat(40) })).toEqual({ branch: 'master', sha: 'a'.repeat(40) });
    expect(() => parseContextBaseline({ branch: 'master', sha: 'abc' })).toThrow('baseline commit');
    const times: ContextTimesDto = { entries: { '2:knowledge:project': { at: '2026-09-01T00:00:00Z', taskId: null },
      '3:knowledge:iceberg': { at: '2026-09-02T00:00:00Z', taskId: 'learn-task' } } };
    expect(parseContextTimes(times)).toEqual(times);
    expect(() => parseContextTimes({ entries: { '3:knowledge:iceberg': { taskId: null } } })).toThrow('Malformed repository context');
    expect(() => parseContextTimes({})).toThrow('Malformed repository context');
  });

  it('keeps merged PR selection separate from reading retained candidates', () => {
    const candidate: ContextMergedCandidateDto = { id: 'pr:trinodb/trino#123', repo: 'trinodb/trino', number: 123, title: 'Refresh Iceberg metadata',
      mergedAt: '2026-09-01T00:00:00Z', taskTitle: 'Refresh metadata', taskResult: null, issue: { key: '#120', title: 'Stale metadata' },
      review: { combinedResult: 'Approved' }, checks: [{ conclusion: 'success' }] };
    expect(parseContextMergedCandidates({ candidates: [candidate], gaps: [] }).candidates[0]).toEqual(candidate);
    expect(() => parseContextMergedCandidates({ candidates: [candidate, candidate], gaps: [] })).toThrow('candidate identity');
    const request = { candidateId: candidate.id, requestKey: 'candidate-key', expectedRevision: 7, expectedBuildSequence: 2 };
    expect(parseLearnContextMergedCandidateRequest(request)).toEqual(request);
    expect(() => parseLearnContextMergedCandidateRequest({ ...request, prompt: 'Use this PR' })).toThrow('unsupported request field');
  });

  it('parses a settled investigation run and binds Investigate to one note and the shown revisions', () => {
    const view = parseContextBuildView({ buildRequestSequence: 3, notebookRevision: 9, canStart: true, build: null, runs: [{ sequence: 3, taskId: 'investigation',
      mode: 'LEARN', requestedAt: '2026-09-29T09:00:00Z', endedAt: '2026-09-29T09:10:00Z', question: null, pullRequest: null, outcome: 'SETTLED',
      sourceOperations: 6, documentOpens: 0, noteIds: ['knowledge:project'], activity: [], detail: null,
      investigation: { entryId: 'knowledge:project', title: 'Project', statement: 'Keep the condition.', outcome: 'NARROW' } }] }, 'workspace');
    expect(view.runs[0].investigation).toEqual({ entryId: 'knowledge:project', title: 'Project', statement: 'Keep the condition.', outcome: 'NARROW' });
    expect(view.runs[0].outcome).toBe('SETTLED');
    const request = { entryId: 'knowledge:project', requestKey: 'investigate-key', expectedRevision: 9, expectedBuildSequence: 3 };
    expect(parseInvestigateRepositoryContextNoteRequest(request)).toEqual(request);
    expect(() => parseInvestigateRepositoryContextNoteRequest({ ...request, prompt: 'Rewrite it' })).toThrow('unsupported request field');
    expect(() => parseInvestigateRepositoryContextNoteRequest({ ...request, entryId: ' ' })).toThrow('Malformed repository context');
  });

});
