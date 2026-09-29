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
  ContextBuildViewDto, ContextDiscoveryReportDto, ContextEntryDto, ContextKnowledgeDraftDto, ContextLearningPlanDto, ContextMergedCandidateDto, ContextRunDto,
  RepositoryNotebookDto,
} from './contracts';
import { filterNotes, noteDiff, openCandidates, pendingCounts, questionRow, statusCard, type StatusInput } from './model';

const NOW = Date.parse('2026-09-29T12:00:00Z');
const entry: ContextEntryDto = { id: 'knowledge:iceberg', kind: 'KNOWLEDGE', scopeId: 'iceberg', title: 'Iceberg metadata refresh',
  summary: 'Refresh reads the latest snapshot.', body: 'Read the snapshot.\nCache per transaction.', claims: [{ text: 'Refresh is per query', basis: 'OBSERVED', sourceIds: [] }],
  sourceIds: ['s1'], origin: 'GENERATED', enabled: true, version: 3 };
const draft: ContextKnowledgeDraftDto = { id: 'draft:1', entry, replacesVersion: null, baselineSha: 'a'.repeat(40) };
const notebook: RepositoryNotebookDto = { schemaVersion: 1, revision: 8, useInReviews: true, useInDevelopment: true, sources: [],
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, discoveryEvidenceRef: null },
    { id: 'iceberg', title: 'Iceberg connector', match: { kind: 'SELECTED', pathPrefixes: ['plugin/trino-iceberg/'], terms: [] }, enabled: true,
      discoveryEvidenceRef: null }],
  entries: [], pendingDrafts: [], sourceEvidence: [] };
const report: ContextDiscoveryReportDto = { id: 'report', workspaceId: 'w',
  baselineSha: 'a'.repeat(40), topics: [{ id: 'topic:iceberg', title: 'Iceberg connector', questions: ['How is metadata refreshed?', 'How are commits retried?'],
    paths: ['plugin/trino-iceberg/'], sourceIds: [], evidenceReadIds: [], why: '' }], sources: [], evidence: [], remaining: [], partial: true,
  coverage: { areas: [{ path: 'plugin/', surveyed: 10, total: 40 }], unsurveyed: [], unsurveyedTruncated: false }, createdAt: '2026-09-29T09:00:00Z' };
const plan: ContextLearningPlanDto = { id: 'plan', workspaceId: 'w', reportId: 'report', baselineSha: report.baselineSha, topics: [{ id: 'topic:iceberg', title: 'Iceberg connector', scopeId: 'iceberg', paths: ['plugin/trino-iceberg/'],
    repositoryFiles: [], sourceIds: [], questions: [{ text: 'How is metadata refreshed?', status: 'COMPLETED', progress: 'Answered.' },
      { text: 'How are commits retried?', status: 'UNFINISHED', progress: 'Read the commit loop.' }] }],
  documentationSources: [], documentInventory: { items: [], suggestions: [], truncated: false } };
const run = (sequence: number, outcome: ContextRunDto['outcome'], index: number, at: string, noteIds: string[] = []): ContextRunDto => ({
  sequence, taskId: `task-${sequence}`, mode: 'LEARN', requestedAt: at, endedAt: outcome === 'RUNNING' ? null : at,
  question: { planId: 'plan', topicId: 'topic:iceberg', topicTitle: 'Iceberg connector', index, text: plan.topics[0].questions[index].text },
  pullRequest: null, outcome, sourceOperations: 40, documentOpens: 0, noteIds, activity: [], detail: null });
const build = (runs: ContextRunDto[]): ContextBuildViewDto => ({ buildRequestSequence: runs.length, notebookRevision: 8, canStart: runs.every(value => value.outcome !== 'RUNNING'),
  runs, build: null });
const input = (patch: Partial<StatusInput>): StatusInput => ({ repository: 'trinodb/trino', baseline: { branch: 'master', sha: 'a'.repeat(40) }, model: 'Codex · 5.6',
  notebook, build: build([]), report: null, planView: { plan: null, next: null }, now: NOW, ...patch });

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

  it('keeps a finished run distinct from a finished topic and resumes the unfinished question', () => {
    const limited = run(2, 'LIMIT', 1, '2026-09-29T11:00:00Z');
    const card = statusCard(input({ report, build: build([limited]), planView: { plan, next: { topicId: 'topic:iceberg', question: 1 } } }));
    expect(card.headline).toBe('Run 2 reached its source limit');
    expect(card.primary).toEqual({ label: 'Continue learning', bolt: true, action: 'continue' });
    expect(card.runLine).toContain('resumes saved progress');
  });

  it('puts review before continuation and never marks review as a run', () => {
    const draftNotebook = { ...notebook, pendingDrafts: [draft] };
    const card = statusCard(input({ notebook: draftNotebook, report, build: build([run(1, 'ANSWERED', 0, '2026-09-29T11:00:00Z', [entry.id])]),
      planView: { plan, next: { topicId: 'topic:iceberg', question: 1 } } }));
    expect(card.primary).toEqual({ label: 'Review draft', bolt: false, action: 'review' });
    expect(card.secondary?.bolt).toBe(true);
    expect(pendingCounts(draftNotebook, plan).total).toBe(1);
  });

  it('summarizes old work as waiting review instead of the run that just ended', () => {
    const card = statusCard(input({ report, build: build([run(1, 'ANSWERED', 0, '2026-09-20T11:00:00Z')]), planView: { plan, next: { topicId: 'topic:iceberg', question: 1 } } }));
    expect(card.headline).toBe('Learning is paused');
    expect(card.foot?.text).toMatch(/^Last run · Sep 20 · answered/);
  });

  it('reports a running learning question with its stop control', () => {
    const card = statusCard(input({ report, build: build([run(3, 'RUNNING', 1, '2026-09-29T11:59:00Z')]), planView: { plan, next: null } }));
    expect(card).toMatchObject({ headline: 'Learning · Iceberg connector', question: 'How are commits retried?', stop: true });
    expect(card.body).toContain('resumed from saved progress');
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
  it('links an answered question to its waiting draft or approved note, and says when a draft was dismissed', () => {
    const answered = [run(1, 'ANSWERED', 0, '2026-09-29T11:00:00Z', [entry.id])];
    expect(questionRow(plan.topics[0], 0, answered, null, { ...notebook, pendingDrafts: [draft] }).link).toEqual({ label: 'Review draft', kind: 'draft', id: 'draft:1' });
    expect(questionRow(plan.topics[0], 0, answered, null, { ...notebook, entries: [entry] }).link).toEqual({ label: 'Open note', kind: 'entry', id: entry.id });
    expect(questionRow(plan.topics[0], 0, answered, null, notebook).meta).toBe('Answered · draft dismissed');
    expect(questionRow(plan.topics[0], 1, answered, null, notebook).meta).toBe('Unfinished · progress saved');
  });

  it('diffs an update against the approved version line by line', () => {
    const proposed = { ...entry, body: 'Read the snapshot.\nCache per query.', sourceIds: ['s1', 's2'] };
    const diff = noteDiff(entry, proposed);
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

  it('stops offering merged work once a run has learned from it', () => {
    const candidate: ContextMergedCandidateDto = { id: 'pr:1', repo: 'trinodb/trino', number: 18, title: 'Retry commits', mergedAt: '', taskTitle: null, taskResult: null,
      issue: null, review: null, checks: [] };
    const learned: ContextRunDto = { ...run(4, 'NO_NOTE', 0, '2026-09-29T11:00:00Z'), question: null, pullRequest: 18 };
    expect(openCandidates({ candidates: [candidate] }, [])).toHaveLength(1);
    expect(openCandidates({ candidates: [candidate] }, [learned])).toHaveLength(0);
  });
});
