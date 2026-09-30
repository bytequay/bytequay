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
import type {
  ContextBuildViewDto, ContextDiscoveryReportDto, ContextEntryDto, ContextGradeDto, ContextLearningPlanDto, ContextMergedCandidateDto, ContextReportedNoteDto, ContextRunDto,
  ContextScopeDto, ContextTimesDto, ContextUsageOverviewDto, RepositoryNotebookDto,
} from './contracts';
import {
  batchLine, batchNext, batchOrder, batchRemaining, batchRuns, disputeSides, entryOrigin, spotTitle, filterNotes, gradeLine, grader, latestReport, runOrigin, needsTopicReselection, noteDiff, notesSavedBy, openCandidates, openReports, pageChangedAt, pendingCounts, planCounts, previousVersion, webLookupLine,
  questionRow, reportsNeedingYou, reviewItems, runNotesToDisable, scopeForTopic, settledLine, statusCard, topicSummary, type StatusInput,
} from './model';

const NOW = Date.parse('2026-09-29T12:00:00Z');
const entry: ContextEntryDto = { id: 'knowledge:iceberg', kind: 'KNOWLEDGE', scopeId: 'iceberg', title: 'Iceberg metadata refresh',
  summary: 'Refresh reads the latest snapshot.', body: 'Read the snapshot.\nCache per transaction.', claims: [{ text: 'Refresh is per query', basis: 'OBSERVED', sourceIds: [] }],
  sourceIds: ['s1'], origin: 'GENERATED', enabled: true, version: 3 };
const notebook: RepositoryNotebookDto = { schemaVersion: 1, revision: 8, useInReviews: true, useInDevelopment: true, sources: [],
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, discoveryEvidenceRef: null },
    { id: 'iceberg', title: 'Iceberg connector', match: { kind: 'SELECTED', pathPrefixes: ['plugin/trino-iceberg/'], terms: [] }, enabled: true,
      discoveryEvidenceRef: null }],
  entries: [], replaced: [], sourceEvidence: [] };
const report: ContextDiscoveryReportDto = { id: 'report', workspaceId: 'w',
  baselineSha: 'a'.repeat(40), topics: [{ id: 'topic:iceberg', title: 'Iceberg connector', questions: ['How is metadata refreshed?', 'How are commits retried?'],
    paths: ['plugin/trino-iceberg/', 'plugin/trino-hive/'], notePaths: ['plugin/trino-iceberg/'], sourceIds: [], evidenceReadIds: [], why: '' }], sources: [], evidence: [], remaining: [], partial: true,
  coverage: { areas: [{ path: 'plugin/', surveyed: 10, total: 40 }], unsurveyed: [], unsurveyedTruncated: false }, createdAt: '2026-09-29T09:00:00Z' };
const plan: ContextLearningPlanDto = { id: 'plan', workspaceId: 'w', reportId: 'report', baselineSha: report.baselineSha, topics: [{ id: 'topic:iceberg', title: 'Iceberg connector', scopeId: 'iceberg', paths: ['plugin/trino-iceberg/', 'plugin/trino-hive/'], notePaths: ['plugin/trino-iceberg/'],
    repositoryFiles: [], sourceIds: [], questions: [{ text: 'How is metadata refreshed?', status: 'COMPLETED', progress: 'Answered.', origin: 'DISCOVERY', note: '' },
      { text: 'How are commits retried?', status: 'UNFINISHED', progress: 'Read the commit loop.', origin: 'DISCOVERY', note: '' }] }],
  gaps: [], documentationSources: [], documentInventory: { items: [], suggestions: [], truncated: false }, batch: null };
const run = (sequence: number, outcome: ContextRunDto['outcome'], index: number, at: string, noteIds: string[] = []): ContextRunDto => ({
  sequence, taskId: `task-${sequence}`, mode: 'LEARN', requestedAt: at, endedAt: outcome === 'RUNNING' ? null : at,
  question: { planId: 'plan', topicId: 'topic:iceberg', topicTitle: 'Iceberg connector', index, text: plan.topics[0].questions[index].text },
  pullRequest: null, outcome, sourceOperations: 17, documentOpens: 0, noteIds, activity: [], detail: null, investigation: null });
const build = (runs: ContextRunDto[]): ContextBuildViewDto => ({ buildRequestSequence: runs.length, notebookRevision: 8, canStart: runs.every(value => value.outcome !== 'RUNNING'),
  runs, build: null });
const input = (patch: Partial<StatusInput>): StatusInput => ({ repository: 'trinodb/trino', baseline: { branch: 'master', sha: 'a'.repeat(40) }, model: 'Codex · 5.6',
  notebook, times: { entries: {} }, build: build([]), report: null, planView: { plan: null, next: null }, now: NOW, ...patch });
const source = { url: 'https://iceberg.apache.org/docs/latest/', parentUrl: 'https://trino.io/docs/', topicId: 'topic:iceberg', question: 1, taskId: 'task-2',
  foundAt: '2026-09-20T11:00:00Z' };
const wrong: ContextGradeDto = { workspaceId: 'w', taskId: 'task-dev', reviewRunId: null, taskKind: 'DEVELOPMENT', grader: 'AGENT', grade: 'WRONG',
  note: { id: entry.id, version: 3, kind: 'KNOWLEDGE', scopeId: 'iceberg', title: entry.title }, reason: 'Refresh is per transaction.', quote: 'Refresh is per query',
  citations: [{ path: 'IcebergMetadata.java', startLine: 40, endLine: 41, commit: 'a'.repeat(40), excerpt: '40  load();\n41  cache();\n' }], correction: null,
  gradedAt: '2026-09-29T10:00:00Z' };

describe('repository context status card', () => {
  it('starts discovery only from an explicit lightning action on a new repository', () => {
    const card = statusCard(input({}));
    expect(card.primary).toEqual({ label: 'Start discovery', bolt: true, action: 'discover' });
    expect(card.runLine).toContain('Starts 1 discovery run');
    expect(statusCard(input({ notebook: { ...notebook, entries: [entry] } })).secondary?.label).toBe('Find topics to learn');
  });

  it('offers partial-survey continuation beside suggested topics', () => {
    const card = statusCard(input({ report }));
    expect(card.headline).toBe('1 topic suggested');
    expect(card.secondary).toEqual({ label: 'Discover remaining areas', bolt: true, action: 'discover-rest' });
    expect(card.foot?.text).toContain('30 of 40 module directories');
  });

  it('requires explicit note targets before learning from earlier suggestions or plans', () => {
    const earlierReport = { ...report, topics: report.topics.map(topic => ({ ...topic, notePaths: null as null })) };
    const earlierPlan = { ...plan, topics: plan.topics.map(topic => ({ ...topic, notePaths: null as null })) };
    expect(statusCard(input({ report: earlierReport })).primary).toEqual({ label: 'Discover again', bolt: true, action: 'discover' });
    const card = statusCard(input({ report, planView: { plan: earlierPlan, next: { topicId: 'topic:iceberg', question: 1 } } }));
    expect(card.headline).toBe('Reselect topics before continuing');
    expect(card.primary).toEqual({ label: 'Review learning plan', bolt: false, action: 'plan' });
    expect(card.body).toContain('Saved notes are kept');
    expect(needsTopicReselection(earlierPlan)).toBe(true);
    expect(needsTopicReselection(plan)).toBe(false);
  });

  it('keeps a finished run distinct from a finished topic and resumes the unfinished question', () => {
    const limited = run(2, 'LIMIT', 1, '2026-09-29T11:00:00Z');
    const card = statusCard(input({ report, build: build([limited]), planView: { plan, next: { topicId: 'topic:iceberg', question: 1 } } }));
    expect(card.headline).toBe('Run 2 reached its source limit');
    expect(card.primary).toEqual({ label: 'Continue learning', bolt: true, action: 'continue' });
    expect(card.body).toMatch(/is unfinished; Continue picks it up\.$/);
  });

  it('names the notes a run saved and offers to disable only the ones it added that nobody changed, without a run', () => {
    const at = '2026-09-29T11:00:00Z';
    const added = { ...entry, id: 'knowledge:added', version: 9 };
    const updated = { ...entry, id: 'knowledge:updated', version: 9 };
    const edited = { ...entry, id: 'knowledge:edited', version: 10 };
    const book = { ...notebook, entries: [added, updated, edited], replaced: [{ ...updated, summary: 'Earlier summary', version: 4 }] };
    const times: ContextTimesDto = { entries: { '9:knowledge:added': { at, taskId: 'task-1' }, '9:knowledge:updated': { at, taskId: 'task-1' },
      '10:knowledge:edited': { at, taskId: null } } };
    const answered = run(1, 'ANSWERED', 0, at, [added.id, updated.id, edited.id]);
    const view = { plan, next: { topicId: 'topic:iceberg', question: 1 } };
    const card = statusCard(input({ notebook: book, times, report, build: build([answered]), planView: view }));
    expect(card.primary).toEqual({ label: 'Continue learning', bolt: true, action: 'continue' });
    expect(card.foot).toEqual({ text: 'Run 1 saved 3 notes.', link: { label: 'Disable notes from this run', action: 'disable-notes' } });
    expect(card.disable).toEqual({ taskId: 'task-1', sequence: 1, noteIds: [added.id] });
    expect(runNotesToDisable(book, times, answered).map(value => value.id)).toEqual([added.id]);
    expect(previousVersion(book, updated.id)?.summary).toBe('Earlier summary');
    expect(previousVersion(book, added.id)).toBeUndefined();
    const disabled = statusCard(input({ notebook: { ...book, entries: [{ ...added, enabled: false }, updated, edited] }, times, report, build: build([answered]), planView: view }));
    expect(disabled.foot).toEqual({ text: 'Run 1 saved 3 notes.', link: null });
    expect(disabled.disable).toBeNull();
  });

  it('summarizes old work with the source proposals that need the user', () => {
    const view = { plan, next: { topicId: 'topic:iceberg', question: 1 } };
    const card = statusCard(input({ report, build: build([run(1, 'ANSWERED', 0, '2026-09-20T11:00:00Z')]), planView: view }));
    expect(card.headline).toBe('Learning is paused');
    expect(card.foot?.text).toMatch(/^Last run · Sep 20 · answered/);
    const proposed = { ...plan, documentInventory: { ...plan.documentInventory, suggestions: [source] } };
    const waiting = statusCard(input({ report, build: build([run(2, 'LIMIT', 1, '2026-09-20T11:00:00Z')]), planView: { ...view, plan: proposed } }));
    expect(waiting.headline).toBe('1 source proposal needs your review');
    expect(waiting.sourceApproval).toBe(source.url);
    expect(pendingCounts(proposed)).toEqual({ sources: 1, reports: 0, total: 1 });
    expect(pendingCounts(null)).toEqual({ sources: 0, reports: 0, total: 0 });
    expect(reviewItems(proposed, [run(2, 'LIMIT', 1, '2026-09-20T11:00:00Z')])).toEqual([{ url: source.url, origin: 'found in Learning run 2', at: source.foundAt }]);
  });

  it('reports a running learning question with its stop control', () => {
    const card = statusCard(input({ report, build: build([run(3, 'RUNNING', 1, '2026-09-29T11:59:00Z')]), planView: { plan, next: null } }));
    expect(card).toMatchObject({ headline: 'Learning · Iceberg connector', question: 'How are commits retried?', stop: true });
    expect(card.body).toContain('resumed from saved progress');
  });

  it('names a running investigation by its note and disputed statement with its activity', () => {
    const investigation: ContextRunDto = { ...run(4, 'RUNNING', 0, '2026-09-29T11:59:00Z'), question: null,
      investigation: { entryId: 'knowledge:iceberg', title: 'Iceberg metadata refresh', statement: 'Later reads reuse the table.', outcome: null } };
    const card = statusCard(input({ report, build: build([investigation]), planView: { plan, next: null } }));
    expect(card).toMatchObject({ headline: 'Investigating · Iceberg metadata refresh', question: 'Later reads reuse the table.', stop: true,
      details: { label: 'View activity', action: 'run-activity' } });
    expect(runOrigin(investigation)).toBe('Investigation 4 · Iceberg metadata refresh');
  });

  it('counts one run per open question for a batch, capped, and shows batch progress while a run works', () => {
    expect(batchRuns(plan)).toBe(1);
    expect(batchRuns(plan, 40)).toBe(30);
    expect(batchRuns(null)).toBe(0);
    const batched: ContextLearningPlanDto = { ...plan, batch: { key: 'learn-key', queue: [],
      ran: [{ topicId: 'topic:iceberg', question: 0 }, { topicId: 'topic:iceberg', question: 1 }], total: 2, alone: false } };
    const card = statusCard(input({ report, build: build([run(3, 'RUNNING', 1, '2026-09-29T11:59:00Z')]), planView: { plan: batched, next: null } }));
    expect(card.body).toContain('run 2 of 2 in this batch');
    expect(batchLine(3, 'Codex · 5.6')).toBe('3 runs, one per open question, plus any the runs add (up to 30) · Codex · 5.6 · Stop ends the batch');
  });

  it('keeps completed and failed source operations separate from the running agent', () => {
    const running = { ...run(3, 'RUNNING', 1, '2026-09-29T11:50:00Z'), activity: [
      { at: '2026-09-29T11:51:00Z', text: 'Read pom.xml', tone: 'DONE' as const },
      { at: '2026-09-29T11:52:00Z', text: 'Couldn’t read missing.md', tone: 'FAILED' as const },
    ] };
    const card = statusCard(input({ build: build([running]) }));
    expect(card.feed).toEqual(running.activity);
    expect(card.live).toContain('last recorded activity');
    expect(card.live).not.toContain('pom.xml');
    expect(card.live).not.toContain('missing.md');
  });
});

describe('repository context notes', () => {
  it('associates discovery topics with the full path rule instead of the first path', () => {
    const paths = ['plugin/trino-iceberg/', 'plugin/trino-delta-lake/'];
    const shared: ContextScopeDto = { id: 'discovery:shared', title: 'Lakehouse connectors', enabled: true, discoveryEvidenceRef: null,
      match: { kind: 'SELECTED', pathPrefixes: paths.toReversed(), terms: [] } };
    const withTerms: ContextScopeDto = { ...shared, id: 'custom:lakehouse', match: { kind: 'SELECTED', pathPrefixes: paths, terms: ['lakehouse'] } };
    expect(scopeForTopic(notebook, paths)).toBeUndefined();
    expect(scopeForTopic({ ...notebook, scopes: [...notebook.scopes, withTerms] }, paths)).toBeUndefined();
    expect(scopeForTopic({ ...notebook, scopes: [...notebook.scopes, shared] }, paths)).toBe(shared);
    expect(scopeForTopic(notebook, [])?.id).toBe('project');
    expect(scopeForTopic(notebook, null)).toBeUndefined();
    expect(scopeForTopic(notebook, [paths[0]])?.id).toBe('iceberg');
    expect(scopeForTopic(notebook, report.topics[0].notePaths)?.id).toBe('iceberg');
    expect(scopeForTopic(notebook, report.topics[0].paths)).toBeUndefined();
  });

  it('prefers an enabled exact area and retains a disabled match when no enabled match exists', () => {
    const enabled = notebook.scopes[1];
    const disabled = { ...enabled, id: 'disabled:iceberg', enabled: false };
    const book = { ...notebook, scopes: [disabled, ...notebook.scopes] };
    expect(scopeForTopic(book, ['plugin/trino-iceberg/'])).toBe(enabled);
    expect(scopeForTopic({ ...book, scopes: [disabled] }, ['plugin/trino-iceberg/'])).toBe(disabled);
  });

  it('links an answered question to the note its run saved and counts the topic’s saved notes', () => {
    const answered = [run(1, 'ANSWERED', 0, '2026-09-29T11:00:00Z', [entry.id])];
    expect(questionRow(plan.topics[0], 0, answered, null, { ...notebook, entries: [entry] })).toMatchObject({ state: 'ANSWERED', meta: '', noteId: entry.id });
    expect(questionRow(plan.topics[0], 0, answered, null, notebook)).toMatchObject({ state: 'ANSWERED', meta: '', noteId: null });
    expect(questionRow(plan.topics[0], 1, answered, null, notebook).meta).toBe('Unfinished');
    expect(topicSummary(plan.topics[0], answered, null, { ...notebook, entries: [entry] }).stats).toEqual([
      { icon: 'check', value: '1/2', label: '1 of 2 questions answered' }, { icon: 'pause', value: 1, label: '1 unfinished · progress saved' },
      { icon: 'doc', value: 1, label: '1 note saved' }]);
  });

  it('keeps corrected and dropped questions visible without counting them', () => {
    const grown = { ...plan.topics[0], questions: [...plan.topics[0].questions,
      { text: 'Is refresh per query?', status: 'CORRECTED' as const, progress: '', origin: 'DISCOVERY' as const, note: 'It is per transaction. Cited: A.java:40-41' },
      { text: 'When is the cache cleared?', status: 'PENDING' as const, progress: '', origin: 'LEARNING' as const, note: 'Replaces “Is refresh per query?”' },
      { text: 'How are releases cut?', status: 'DROPPED' as const, progress: '', origin: 'USER' as const, note: 'Dropped by you' }] };
    expect(questionRow(grown, 2, [], null, notebook)).toMatchObject({ state: 'CORRECTED', meta: 'Corrected', origin: null });
    expect(questionRow(grown, 3, [], null, notebook)).toMatchObject({ state: 'PENDING', meta: '', origin: 'LEARNING', note: 'Replaces “Is refresh per query?”' });
    expect(questionRow(grown, 4, [], null, notebook)).toMatchObject({ state: 'DROPPED', origin: 'USER' });
    expect(topicSummary(grown, [], null, notebook).stats.map(stat => stat.value)).toEqual(['1/3', 1]);
    expect(planCounts({ ...plan, topics: [grown] })).toEqual({ answered: 1, total: 3 });
    expect(batchRuns({ ...plan, topics: [grown] })).toBe(2);
    const corrected = { ...plan, topics: [grown] };
    const card = statusCard(input({ report, planView: { plan: corrected, next: { topicId: grown.id, question: 1 } },
      build: build([{ ...run(2, 'CORRECTED', 0, '2026-09-29T11:59:00Z'), question: { planId: 'plan', topicId: grown.id, topicTitle: grown.title, index: 2, text: grown.questions[2].text } }]) }));
    expect(card.headline).toBe('Run 2 corrected its question');
    expect(card.body).toBe('It is per transaction. Cited: A.java:40-41');
  });

  it('flags a note by the latest change among the pages it cites', () => {
    const cited = { ...entry, sourceIds: ['s1', 's2', 's3'] };
    expect(pageChangedAt(cited, {})).toBeNull();
    expect(pageChangedAt(cited, { s3: '2026-09-30T10:00:00Z', s2: '2026-09-29T10:00:00Z', other: '2026-10-01T00:00:00Z' })).toBe('2026-09-30T10:00:00Z');
  });

  it('shows the question a batch starts next while the finished run is cleaned up', () => {
    const batched: ContextLearningPlanDto = { ...plan, batch: { key: 'learn-key', queue: [{ topicId: 'topic:iceberg', question: 1 }],
      ran: [{ topicId: 'topic:iceberg', question: 0 }], total: 2, alone: false } };
    const finished = run(1, 'ANSWERED', 0, '2026-09-29T11:59:00Z');
    const view: ContextBuildViewDto = { ...build([finished]), build: { requestKey: 'learn-key', taskId: finished.taskId,
      task: { id: finished.taskId, workspaceId: 'w', state: 'COMPLETED', mutationClaim: null, worktreeRemovedAt: null }, activeTurn: null, pendingApprovals: 0,
      continuation: false } };
    expect(batchNext(batched, view)).toEqual({ question: 'How are commits retried?', topic: 'Iceberg connector', run: 2, total: 2 });
    const card = statusCard(input({ report, planView: { plan: batched, next: { topicId: 'topic:iceberg', question: 1 } }, build: view }));
    expect(card).toMatchObject({ headline: 'Starting the next question', question: 'How are commits retried?', primary: null, icon: 'spin' });
    expect(batchNext(batched, { ...view, runs: [{ ...finished, outcome: 'STOPPED' }] })).toBeNull();
    expect(batchNext({ ...batched, batch: { key: 'learn-key', queue: [], ran: [{ topicId: 'topic:iceberg', question: 0 }, { topicId: 'topic:iceberg', question: 1 }],
      total: 2, alone: false } }, view)).toBeNull();
    expect(batchNext(batched, { ...view, build: { ...view.build, requestKey: 'another-click' } })).toBeNull();
    expect(batchNext(batched, { ...view, build: { ...view.build, requestKey: 'learn-key#2' } })).not.toBeNull();
    expect(batchNext(batched, build([run(1, 'RUNNING', 0, '2026-09-29T11:59:00Z')]))).toBeNull();
  });

  it('shows where a batch goes next and ends, with questions added while it runs at the end', () => {
    const topic = { ...plan.topics[0], questions: [...plan.topics[0].questions,
      { text: 'Where are integration tests?', status: 'PENDING' as const, progress: '', origin: 'LEARNING' as const, note: '' },
      { text: 'How are releases cut?', status: 'PENDING' as const, progress: '', origin: 'USER' as const, note: '' }] };
    const batched: ContextLearningPlanDto = { ...plan, topics: [topic],
      batch: { key: 'learn-key', queue: [{ topicId: 'topic:iceberg', question: 1 }], ran: [{ topicId: 'topic:iceberg', question: 0 }], total: 2, alone: false } };
    expect(batchRemaining(batched)).toEqual([{ topicId: 'topic:iceberg', question: 1 }, { topicId: 'topic:iceberg', question: 2 }, { topicId: 'topic:iceberg', question: 3 }]);
    const order = batchOrder(batched);
    expect([1, 2, 3].map(index => questionRow(topic, index, [], null, notebook, order).spot))
      .toEqual([{ position: 1, count: 3 }, { position: 2, count: 3 }, { position: 3, count: 3 }]);
    expect([1, 2, 3].map(position => spotTitle({ position, count: 3 }))).toEqual(['Runs next in this batch', 'Runs #2 in this batch', 'Runs last in this batch, #3']);
    expect(questionRow(topic, 2, [], null, notebook).spot).toBeNull();
    expect([...batchOrder({ ...batched, batch: null }, true).keys()]).toEqual(['topic:iceberg:1', 'topic:iceberg:2', 'topic:iceberg:3']);
    expect(batchOrder({ ...batched, batch: null }).size).toBe(0);
    expect(topicSummary(topic, [], null, notebook, order).stats.map(stat => stat.label))
      .toEqual(['1 of 4 questions answered', '1 unfinished · progress saved', '3 questions queued in this batch']);
    const alone: ContextLearningPlanDto = { ...batched, batch: { key: 'deeper', queue: [], ran: [{ topicId: 'topic:iceberg', question: 3 }], total: 1, alone: true } };
    expect(batchRemaining(alone)).toEqual([]);

  });

  it('compares web lookups per task with notes on and off', () => {
    expect(webLookupLine({ notesOn: { tasks: 0, lookups: 0 }, notesOff: { tasks: 0, lookups: 0 } })).toBe('No finished development task in this period recorded its web lookups.');
    expect(webLookupLine({ notesOn: { tasks: 12, lookups: 9 }, notesOff: { tasks: 3, lookups: 7 } }))
      .toBe('0.8 per task with notes on (12 tasks) · 2.3 per task with notes off (3 tasks)');
    expect(webLookupLine({ notesOn: { tasks: 1, lookups: 2 }, notesOff: { tasks: 0, lookups: 0 } })).toBe('2.0 per task with notes on (1 task) · no task with notes off');
  });

  it('shows a task only the notes whose current version it saved', () => {
    const lesson = { ...entry, id: 'lesson:1', kind: 'MEMORY' as const, version: 5 };
    const times: ContextTimesDto = { entries: { [`3:${entry.id}`]: { at: '2026-09-29T11:00:00Z', taskId: 'task-dev' },
      '5:lesson:1': { at: '2026-09-29T11:30:00Z', taskId: 'task-other' } } };
    expect(notesSavedBy({ ...notebook, entries: [entry, lesson] }, times, 'task-dev').map(value => value.id)).toEqual([entry.id]);
    expect(notesSavedBy({ ...notebook, entries: [{ ...entry, version: 6 }, lesson] }, times, 'task-dev')).toEqual([]);
  });

  it('names a note by the run of its task that saved it, a titled task, or earlier learning', () => {
    const times: ContextTimesDto = { entries: { [`3:${entry.id}`]: { at: '2026-09-29T11:00:00Z', taskId: 'task-2' } } };
    const shared = [{ ...run(1, 'ANSWERED', 0, '2026-09-29T10:00:00Z'), taskId: 'task-2' }, run(2, 'ANSWERED', 1, '2026-09-29T11:00:00Z', [entry.id])];
    expect(entryOrigin(entry, times, shared, {})).toBe('Learning run 2 · Iceberg connector');
    expect(entryOrigin(entry, times, [], { 'task-2': 'Fix flaky Iceberg commit test' })).toBe('Task “Fix flaky Iceberg commit test”');
    // A learning task whose runs left the history has no title to show.
    expect(entryOrigin(entry, times, [], { 'task-2': '' })).toBe('Earlier learning');
  });

  it('diffs the current version against the one it replaced line by line', () => {
    const current = { ...entry, body: 'Read the snapshot.\nCache per query.', sourceIds: ['s1', 's2'] };
    const diff = noteDiff(entry, current);
    expect(diff.lines.filter(line => line.sign !== ' ').map(line => line.sign + line.text)).toEqual(['+Cache per query.', '−Cache per transaction.']);
    expect(diff.summary).toBe('1 line added or changed · 1 removed · 1 source added');
  });

  it('filters literally by kind, area and text without ranking', () => {
    const lesson = { ...entry, id: 'lesson:1', kind: 'MEMORY' as const, scopeId: 'project', title: 'Keep retries bounded' };
    const book = { ...notebook, entries: [entry, lesson] };
    expect(filterNotes(book, { kind: 'all', area: 'all', query: 'TRINO-ICEBERG' }).map(value => value.id)).toEqual([entry.id]);
    expect(filterNotes(book, { kind: 'MEMORY', area: 'all', query: '' }).map(value => value.id)).toEqual(['lesson:1']);
    expect(filterNotes(book, { kind: 'all', area: 'project', query: '' }).map(value => value.id)).toEqual(['lesson:1']);
  });

  it('sends only open reports on notes the user wrote to Needs you', () => {
    const report = (entryId: string, entryVersion: number, userWritten: boolean): ContextReportedNoteDto => ({ entryId, entryVersion, title: entryId, userWritten,
      grades: [{ ...wrong, note: { ...wrong.note, id: entryId, version: entryVersion } }], contested: null, settlement: null });
    const lesson = { ...entry, id: 'lesson:mine', origin: 'USER' as const, version: 4 };
    const disabled = { ...entry, id: 'lesson:off', origin: 'USER' as const, enabled: false };
    const book = { ...notebook, entries: [entry, lesson, disabled] };
    const reports = [report(entry.id, 3, false), report(lesson.id, 4, true), report(lesson.id, 3, true), report(disabled.id, 3, true), report('lesson:gone', 1, true)];
    expect(openReports(book, reports).map(value => `${value.entryId}@${value.entryVersion}`)).toEqual([`${entry.id}@3`, 'lesson:mine@4']);
    const mine = reportsNeedingYou(book, reports);
    expect(mine.map(value => value.entryId)).toEqual(['lesson:mine']);
    expect(pendingCounts({ ...plan, documentInventory: { ...plan.documentInventory, suggestions: [source] } }, mine)).toEqual({ sources: 1, reports: 1, total: 2 });
    expect(reportsNeedingYou({ ...book, entries: [entry, { ...lesson, version: 5 }] }, reports)).toEqual([]);
  });

  it('sends every contested note to Needs you with both sides and reads a settlement as its root cause or the user’s choice', () => {
    const holds = { ...wrong, grade: 'HOLDS' as const, taskId: 'task-later', gradedAt: '2026-09-29T11:30:00Z' };
    const helped = { ...wrong, grade: 'HELPED' as const, quote: null as string | null, citations: [] as ContextGradeDto['citations'], taskId: 'task-other' };
    const contested: ContextReportedNoteDto = { entryId: entry.id, entryVersion: 3, title: entry.title, userWritten: false, grades: [holds, helped, wrong],
      contested: { statement: 'Keep the condition.', version: 3 }, settlement: null };
    const book = { ...notebook, entries: [entry] };
    expect(reportsNeedingYou(book, [contested])).toEqual([contested]);
    expect(reportsNeedingYou(book, [{ ...contested, contested: null }])).toEqual([]);
    expect(disputeSides(contested)).toEqual([holds, wrong]);
    expect(settledLine({ version: 4, by: 'INVESTIGATION', statement: 'Keep the condition.', rootCause: 'Each side read a different branch.' }))
      .toBe('An investigation settled it in v4: Each side read a different branch.');
    expect(settledLine({ version: 5, by: 'USER', statement: 'Keep the condition.', rootCause: null })).toBe('You kept v5 when agents disagreed');
  });

  it('names the newest report and its reporter without claiming a title it doesn’t know', () => {
    const answered = { ...wrong, grade: 'HOLDS' as const, taskId: 'task-later', gradedAt: '2026-09-29T11:30:00Z' };
    const byUser = { ...wrong, grader: 'USER' as const, reason: '', quote: null as string | null, citations: [] as ContextGradeDto['citations'], gradedAt: '2026-09-29T11:00:00Z' };
    const note: ContextReportedNoteDto = { entryId: entry.id, entryVersion: 3, title: entry.title, userWritten: true, grades: [answered, byUser, wrong],
      contested: null, settlement: null };
    expect(latestReport(note)).toBe(byUser);
    expect(latestReport({ ...note, grades: [answered] })).toBeUndefined();
    expect(grader(byUser, { 'task-dev': 'Fix refresh' })).toBe('you');
    expect(grader(wrong, { 'task-dev': 'Fix refresh' })).toBe('task “Fix refresh”');
    expect(grader(wrong, { 'task-dev': '' })).toBe('a task');
    expect(grader({ ...wrong, taskKind: 'PR_REVIEW', reviewRunId: 'run' }, {})).toBe('a review');
  });

  it('writes the grade line from the usage window and says not opened for an unopened version', () => {
    const row = { entryId: entry.id, title: entry.title, currentVersion: 3, foundTasks: 14, openedTasks: 12, lastConfirmedUseAt: '2026-09-27T12:00:00Z',
      helpedTasks: 7, notRelevantTasks: 3, wrongTasks: 1 };
    const usage: ContextUsageOverviewDto = { trackingSince: null, window: '30d',
      summary: { eligibleDevelopmentTasks: 0, eligibleReviewRuns: 0, contextOffTasks: 0, developmentTasksFoundOrOpened: 0, reviewRunsFoundOrOpened: 0,
        webLookups: { notesOn: { tasks: 0, lookups: 0 }, notesOff: { tasks: 0, lookups: 0 } } }, entries: [row] };
    expect(gradeLine(usage, entry, NOW)).toBe('opened in 12 tasks · helped 7 · not relevant 3 · wrong 1 · last opened 2d ago');
    expect(gradeLine({ ...usage, entries: [{ ...row, openedTasks: 1, lastConfirmedUseAt: null }] }, entry, NOW)).toBe('opened in 1 task · helped 7 · not relevant 3 · wrong 1');
    expect(gradeLine({ ...usage, entries: [{ ...row, lastConfirmedUseAt: '2026-09-29T11:59:40Z' }] }, entry, NOW)).toMatch(/ · last opened just now$/);
    expect(gradeLine({ ...usage, entries: [{ ...row, openedTasks: 0 }] }, entry, NOW)).toBe('not opened');
    expect(gradeLine(usage, { ...entry, version: 4 }, NOW)).toBe('not opened');
    expect(gradeLine(null, entry, NOW)).toBe('');
  });

  it('stops offering merged work once a run has learned from it', () => {
    const candidate: ContextMergedCandidateDto = { id: 'pr:1', repo: 'trinodb/trino', number: 18, title: 'Retry commits', mergedAt: '', taskTitle: null, taskResult: null,
      issue: null, review: null, checks: [] };
    const learned: ContextRunDto = { ...run(4, 'NO_NOTE', 0, '2026-09-29T11:00:00Z'), question: null, pullRequest: 18 };
    expect(openCandidates({ candidates: [candidate] }, [])).toHaveLength(1);
    expect(openCandidates({ candidates: [candidate] }, [learned])).toHaveLength(0);
  });
});
