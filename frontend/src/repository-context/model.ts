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
import { displayModelName, effortLabel, providerAvailable, providerRemedy } from '../settings/model';
import { ago, relativeTime } from '../relativeTime';
import type { ModelSettingsDto } from '../types';
import type {
  ContextActivityDto, ContextBaselineDto, ContextPlanQuestionDto, ContextWebLookupsDto, ContextBuildViewDto, ContextClaimDto, ContextDiscoveryReportDto, ContextEntryDto, ContextGradeDto,
  ContextLearningPlanDto, ContextLearningPlanViewDto, ContextMergedCandidatesDto, ContextPlanTopicDto, ContextQuestionTarget, ContextReportedNoteDto, ContextRunDto,
  ContextInvestigationOutcome, ContextScopeDto, ContextSettlementDto, ContextSourceEvidenceDto, ContextTimesDto, ContextUsageOverviewDto, RepositoryNotebookDto,
} from './contracts';

export const DOCUMENT_LINK_HOPS = 2;
/** A finished run older than this is summarized as earlier work rather than as the run that just ended. */
const RECENT_RUN_MS = 6 * 3_600_000;

export const kindLabel = (kind: ContextEntryDto['kind']) => kind === 'MEMORY' ? 'Lesson' : 'Knowledge';
export const shortSha = (sha: string) => sha.slice(0, 7);
export const plural = (count: number, one: string, many = one + 's') => `${count} ${count === 1 ? one : many}`;

export function clock(elapsedMs: number): string {
  const seconds = Math.max(0, Math.floor(elapsedMs / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

export function shortDate(value: string | null | undefined, now = Date.now()): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const today = new Date(now);
  if (date.toDateString() === today.toDateString()) return 'Today';
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', ...(date.getFullYear() === today.getFullYear() ? {} : { year: 'numeric' }) });
}

export function timeOfDay(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
}

/** "Codex · 5.6 Sol Medium", and why a run cannot start when the Review model is unavailable. */
export function modelLine(models: ModelSettingsDto | null): { label: string; blocker: string | null } {
  const agent = models?.agents.find(item => item.type === 'REVIEW');
  if (models === null || agent === undefined) return { label: 'Review model', blocker: models === null ? null : 'No Review model is configured.' };
  const effort = agent.effort ? ' ' + effortLabel(agent.effort) : '';
  const label = `${agent.label} · ${displayModelName(agent.modelLabel)}${effort}`;
  const provider = models.providers.find(item => item.id === agent.configId);
  if (provider === undefined) return { label, blocker: 'The Review model provider is unavailable.' };
  if (!providerAvailable(provider.readiness)) return { label, blocker: providerRemedy(provider.readiness, provider.id).title };
  if (!provider.models.some(model => model.id === agent.model)) return { label, blocker: 'The configured Review model is unavailable.' };
  return { label, blocker: null };
}

/* ── Areas ──────────────────────────────────────────────────────────── */

export type ContextArea = { id: string; label: string; hint: string; paths: string[] };

/** A note's area is its scope; the repository-wide scope reads as Project-wide. */
export function area(scope: ContextScopeDto | undefined, scopeId: string): ContextArea {
  if (scope === undefined) return { id: scopeId, label: scopeId, hint: '', paths: [] };
  if (scope.match.kind === 'REPOSITORY') return { id: scope.id, label: 'Project-wide', hint: '/', paths: ['/'] };
  return { id: scope.id, label: scope.title, hint: scope.match.pathPrefixes[0] ?? '', paths: scope.match.pathPrefixes };
}

export function noteArea(notebook: RepositoryNotebookDto, scopeId: string): ContextArea {
  return area(notebook.scopes.find(scope => scope.id === scopeId), scopeId);
}

/** Readable source paths never imply applicability; reuse only an explicitly proposed note target. */
export function scopeForTopic(notebook: RepositoryNotebookDto, paths: string[] | null): ContextScopeDto | undefined {
  if (paths === null) return undefined;
  if (paths.length === 0) return notebook.scopes.find(scope => scope.id === 'project');
  const matches = notebook.scopes.filter(scope => scope.match.kind === 'SELECTED' && scope.match.terms.length === 0
    && scope.match.pathPrefixes.length === paths.length && scope.match.pathPrefixes.every(path => paths.includes(path)));
  return matches.find(scope => scope.enabled) ?? matches[0];
}

export function needsTopicReselection(plan: ContextLearningPlanDto | null): boolean {
  return plan?.topics.some(topic => topic.notePaths === null) ?? false;
}

/* ── Runs ───────────────────────────────────────────────────────────── */

export function currentRun(build: ContextBuildViewDto | null): ContextRunDto | null {
  return build?.runs.find(run => run.outcome === 'RUNNING') ?? null;
}

export function latestRun(runs: ContextRunDto[], filter: (run: ContextRunDto) => boolean): ContextRunDto | null {
  return runs.filter(filter).reduce<ContextRunDto | null>((latest, run) => latest === null || run.sequence > latest.sequence ? run : latest, null);
}

export function runOrigin(run: ContextRunDto): string {
  if (run.question !== null) return `Learning run ${run.sequence} · ${run.question.topicTitle}`;
  if (run.investigation !== null) return `Investigation ${run.sequence} · ${run.investigation.title}`;
  if (run.pullRequest !== null) return `Learning from merged PR #${run.pullRequest}`;
  return run.mode === 'DISCOVER' ? `Discovery run ${run.sequence}` : `Learning run ${run.sequence}`;
}

export const OUTCOME: Record<ContextRunDto['outcome'], { label: string; tone: Tone }> = {
  RUNNING: { label: 'Running', tone: 'blue' },
  SUBMITTED: { label: 'Suggestions saved', tone: 'green' },
  SETTLED: { label: 'Settled', tone: 'green' },
  ANSWERED: { label: 'Answered', tone: 'green' },
  CORRECTED: { label: 'Question corrected', tone: 'amber' },
  NO_NOTE: { label: 'No reusable note', tone: 'gray' },
  UNFINISHED: { label: 'Progress saved', tone: 'amber' },
  LIMIT: { label: 'Source limit reached', tone: 'amber' },
  STOPPED: { label: 'Stopped by you', tone: 'gray' },
  FAILED: { label: 'Failed', tone: 'red' },
};

export type Tone = 'amber' | 'green' | 'gray' | 'blue' | 'red' | 'purple' | 'orange';

/* ── Plan questions ─────────────────────────────────────────────────── */

export type QuestionRow = {
  topicId: string; index: number; text: string;
  state: 'PENDING' | 'RUNNING' | 'UNFINISHED' | 'ANSWERED' | 'CORRECTED' | 'DROPPED';
  meta: string; metaTone: 'muted' | 'blue' | 'amber' | 'plain';
  noteId: string | null;
  /** Where the question came from, when not discovery, and why it exists or ended. */
  origin: string | null; note: string;
};

const ORIGIN: Record<ContextPlanQuestionDto['origin'], string | null> = {
  DISCOVERY: null, LEARNING: 'from a learning run', TASK: 'from a task', REVIEW: 'from a review', USER: 'added by you',
};

/** Questions a batch still runs. */
export const isOpen = (question: ContextPlanQuestionDto): boolean => question.status === 'PENDING' || question.status === 'UNFINISHED';

/** Questions that count toward a topic: corrected ones were replaced and dropped ones are no longer asked. */
const counted = (question: ContextPlanQuestionDto): boolean => question.status !== 'CORRECTED' && question.status !== 'DROPPED';

/** Answered questions link to a note their run saved. */
export function questionRow(topic: ContextPlanTopicDto, index: number, runs: ContextRunDto[], running: ContextRunDto | null,
  notebook: RepositoryNotebookDto): QuestionRow {
  const question = topic.questions[index];
  const base: Pick<QuestionRow, 'topicId' | 'index' | 'text' | 'noteId' | 'origin' | 'note'> = { topicId: topic.id, index, text: question.text, noteId: null,
    origin: ORIGIN[question.origin], note: question.note };
  if (running?.question?.topicId === topic.id && running.question.index === index) {
    return { ...base, state: 'RUNNING', meta: 'Running now', metaTone: 'blue' };
  }
  if (question.status === 'CORRECTED') return { ...base, state: 'CORRECTED', meta: 'Corrected', metaTone: 'muted' };
  if (question.status === 'DROPPED') return { ...base, state: 'DROPPED', meta: 'Dropped', metaTone: 'muted' };
  if (question.status === 'UNFINISHED') return { ...base, state: 'UNFINISHED', meta: 'Unfinished · progress saved', metaTone: 'amber' };
  if (question.status === 'PENDING') return { ...base, state: 'PENDING', meta: 'Not started', metaTone: 'muted' };
  const answered = latestRun(runs, run => run.question?.topicId === topic.id && run.question.index === index
    && (run.outcome === 'ANSWERED' || run.outcome === 'NO_NOTE'));
  if (answered === null) return { ...base, state: 'ANSWERED', meta: 'Answered', metaTone: 'plain' };
  const entry = notebook.entries.find(value => answered.noteIds.includes(value.id));
  return { ...base, state: 'ANSWERED', meta: answered.outcome === 'NO_NOTE' ? 'Answered · no reusable note' : 'Answered', metaTone: 'plain', noteId: entry?.id ?? null };
}

export type TopicSummary = { topic: ContextPlanTopicDto; rows: QuestionRow[]; answered: number; unfinished: number; running: boolean; meta: string };

export function topicSummary(topic: ContextPlanTopicDto, runs: ContextRunDto[], running: ContextRunDto | null, notebook: RepositoryNotebookDto): TopicSummary {
  const rows = topic.questions.map((_, index) => questionRow(topic, index, runs, running, notebook));
  const answered = topic.questions.filter(question => question.status === 'COMPLETED').length;
  const total = topic.questions.filter(counted).length;
  const unfinished = rows.filter(row => row.state === 'UNFINISHED').length;
  const noteIds = new Set(runs.filter(run => run.question?.topicId === topic.id).flatMap(run => run.noteIds));
  const notes = notebook.entries.filter(entry => noteIds.has(entry.id)).length;
  const meta = `${answered} of ${plural(total, 'question')} answered`
    + (unfinished ? ` · ${unfinished} unfinished` : '') + (notes ? ` · ${plural(notes, 'note')} saved` : '');
  return { topic, rows, answered, unfinished, running: rows.some(row => row.state === 'RUNNING'), meta };
}

/** One click runs each open question in turn, at most this many. */
export const BATCH_RUNS = 30;

/** How many runs Continue learning or Learn selected starts: one per open or unfinished question, capped. */
export function batchRuns(plan: ContextLearningPlanDto | null, adding = 0): number {
  const open = plan?.topics.flatMap(topic => topic.questions).filter(isOpen).length ?? 0;
  return Math.min(BATCH_RUNS, open + adding);
}

/** The run line of a batch-starting action. */
export function batchLine(runs: number, first: string | null, model: string): string {
  return `${plural(runs, 'run')}, one per open question${first === null ? '' : `, starting with ${first}`} · ${model} · Stop ends the batch`;
}

/**
 * The question a batch starts next while the run that just finished is cleaned up, or null when no batch goes on: the
 * backend ends a batch when its run stops or fails, or when no queued question is still open.
 */
export function batchNext(plan: ContextLearningPlanDto | null, build: ContextBuildViewDto): { question: string; topic: string; run: number; total: number } | null {
  const batch = plan?.batch ?? null;
  const current = build.build;
  if (batch === null || current === null || currentRun(build) !== null || (current.requestKey !== batch.key && !current.requestKey.startsWith(`${batch.key}#`))) return null;
  const last = build.runs.find(run => run.taskId === current.taskId);
  if (last === undefined || last.outcome === 'STOPPED' || last.outcome === 'FAILED') return null;
  for (const target of batch.queue) {
    const topic = plan.topics.find(value => value.id === target.topicId);
    const question = topic?.questions[target.question];
    if (question !== undefined && isOpen(question)) return { question: question.text, topic: topic.title, run: batch.started + 1, total: batch.total };
  }
  return null;
}

export function planCounts(plan: ContextLearningPlanDto): { answered: number; total: number } {
  const questions = plan.topics.flatMap(topic => topic.questions).filter(counted);
  return { answered: questions.filter(question => question.status === 'COMPLETED').length, total: questions.length };
}

export function nextQuestion(view: ContextLearningPlanViewDto | undefined): null | { target: ContextQuestionTarget; topic: ContextPlanTopicDto; text: string; resumes: boolean } {
  const plan = view?.plan ?? null;
  const target = view?.next ?? null;
  if (plan === null || target === null) return null;
  const topic = plan.topics.find(value => value.id === target.topicId);
  const question = topic?.questions[target.question];
  if (topic === undefined || question === undefined) return null;
  return { target, topic, text: question.text, resumes: question.status === 'UNFINISHED' };
}

/* ── Saved notes and what needs the user ────────────────────────────── */

export type TaskTitles = Record<string, string>;

export function entryOrigin(entry: ContextEntryDto, times: ContextTimesDto, runs: ContextRunDto[], titles: TaskTitles): string {
  const taskId = times.entries[entryTimeKey(entry)]?.taskId ?? null;
  const run = taskId === null ? undefined : runs.find(value => value.taskId === taskId);
  if (run !== undefined) return runOrigin(run);
  if (taskId !== null && titles[taskId] !== undefined) return `Task “${titles[taskId]}”`;
  return entry.origin === 'USER' ? 'Saved by you' : 'Earlier learning';
}

export const entryTimeKey = (entry: Pick<ContextEntryDto, 'id' | 'version'>) => `${entry.version}:${entry.id}`;

/** Notes whose current version the task saved; a later change by anyone else makes the note no longer the task's. */
export function notesSavedBy(notebook: RepositoryNotebookDto, times: ContextTimesDto, taskId: string): ContextEntryDto[] {
  return notebook.entries.filter(entry => times.entries[entryTimeKey(entry)]?.taskId === taskId);
}

/** The version a note's last text change replaced; Restore makes it current again. */
export function previousVersion(notebook: RepositoryNotebookDto, entryId: string): ContextEntryDto | undefined {
  return notebook.replaced.find(entry => entry.id === entryId);
}

/** What Disable notes from this run turns off: enabled notes the run added that are still at the version it saved. */
export function runNotesToDisable(notebook: RepositoryNotebookDto, times: ContextTimesDto, run: ContextRunDto): ContextEntryDto[] {
  return notesSavedBy(notebook, times, run.taskId).filter(entry => entry.enabled && previousVersion(notebook, entry.id) === undefined);
}

export type ReviewItem = { url: string; origin: string; at: string };

/** Needs you: sources learning runs want to read outside their approved boundary. */
export function reviewItems(plan: ContextLearningPlanDto | null, runs: ContextRunDto[]): ReviewItem[] {
  return (plan?.documentInventory.suggestions ?? []).map(suggestion => {
    const run = runs.find(value => value.taskId === suggestion.taskId);
    return { url: suggestion.url, origin: run === undefined ? 'found by an earlier learning run' : `found in Learning run ${run.sequence}`, at: suggestion.foundAt };
  });
}

/** Reports still open against the notebook as last read: a newer version of the note, or disabling it, closes them. */
export function openReports(notebook: RepositoryNotebookDto, reports: ContextReportedNoteDto[]): ContextReportedNoteDto[] {
  return reports.filter(report => notebook.entries.some(entry => entry.id === report.entryId && entry.version === report.entryVersion && entry.enabled));
}

/** Needs you: notes agents contest, and open reports on notes the user wrote, which agents cannot change. */
export function reportsNeedingYou(notebook: RepositoryNotebookDto, reports: ContextReportedNoteDto[]): ContextReportedNoteDto[] {
  return openReports(notebook, reports).filter(report => report.userWritten || report.contested !== null);
}

/** The Wrong and Holds grades on each side of a dispute, newest first. */
export const disputeSides = (report: ContextReportedNoteDto): ContextGradeDto[] => report.grades.filter(grade => grade.grade === 'WRONG' || grade.grade === 'HOLDS');

/** How a settlement reads on its note: an agent's root cause, or the user's choice. */
export function settledLine(settlement: ContextSettlementDto): string {
  const by = settlement.by === 'INVESTIGATION' ? 'An investigation' : settlement.by === 'TASK' ? 'A task' : 'You';
  return settlement.rootCause === null ? `${by} kept v${settlement.version} when agents disagreed` : `${by} settled it in v${settlement.version}: ${settlement.rootCause}`;
}

/** The newest open Wrong report on a note; grades come newest first. */
export const latestReport = (report: ContextReportedNoteDto): ContextGradeDto | undefined => report.grades.find(grade => grade.grade === 'WRONG');

/** Who graded: the user, a task or review named by its title, or one whose title isn't known. */
export function grader(grade: ContextGradeDto, titles: TaskTitles): string {
  if (grade.grader === 'USER') return 'you';
  const kind = grade.taskKind === 'PR_REVIEW' ? 'review' : 'task';
  return titles[grade.taskId] ? `${kind} “${titles[grade.taskId]}”` : `a ${kind}`;
}

/** Web lookups per development task with notes on and off; a task whose turns ran with both counts in neither. */
export function webLookupLine(web: ContextWebLookupsDto): string {
  if (web.notesOn.tasks + web.notesOff.tasks === 0) return 'No finished development task in this period recorded its web lookups.';
  const side = (lookups: ContextWebLookupsDto['notesOn'], setting: string) => lookups.tasks === 0 ? `no task with notes ${setting}`
    : `${(lookups.lookups / lookups.tasks).toFixed(1)} per task with notes ${setting} (${plural(lookups.tasks, 'task')})`;
  return `${side(web.notesOn, 'on')} · ${side(web.notesOff, 'off')}`;
}

/** A note's reach and grades in the usage window; the grades are what grading tasks said, not a verdict. Empty until usage is read. */
export function gradeLine(usage: ContextUsageOverviewDto | null, entry: Pick<ContextEntryDto, 'id' | 'version'>, now: number): string {
  if (usage === null) return '';
  const row = usage.entries.find(value => value.entryId === entry.id && value.currentVersion === entry.version);
  if (row === undefined || row.openedTasks === 0) return 'not opened';
  return `opened in ${plural(row.openedTasks, 'task')} · helped ${row.helpedTasks} · not relevant ${row.notRelevantTasks} · wrong ${row.wrongTasks}`
    + (row.lastConfirmedUseAt === null ? '' : ` · last opened ${ago(row.lastConfirmedUseAt, now)}`);
}

/** What the sidebar count, Home and the page header show: source proposals, contested notes and reports on notes the user wrote. */
export function pendingCounts(plan: ContextLearningPlanDto | null, reports: ContextReportedNoteDto[] = []): { sources: number; reports: number; total: number } {
  const sources = plan?.documentInventory.suggestions.length ?? 0;
  return { sources, reports: reports.length, total: sources + reports.length };
}

/* ── Status card ────────────────────────────────────────────────────── */

export type StatusAction = 'discover' | 'discover-rest' | 'continue' | 'plan' | 'disc-activity' | 'plan-activity' | 'run-activity' | 'coverage' | 'sources'
  | 'disable-notes';

/** What an investigation did to settle its dispute. */
export const SETTLED_BY: Record<ContextInvestigationOutcome, string> = {
  REWRITE: 'rewrote the statement with its conditions', KEEP_CURRENT: 'kept the current text', KEEP_PREVIOUS: 'restored the previous text',
  NARROW: 'narrowed the note to its scope', QUESTION: 'turned the statement into an open question',
};
export type StatusCard = {
  icon: 'search' | 'check' | 'doc' | 'pause' | 'review' | 'alert' | 'spin';
  tone: 'gray' | 'green' | 'blue' | 'amber' | 'red';
  headline: string; elapsedSince: string | null; question: string | null; body: string | null;
  steps: boolean; how: boolean;
  primary: null | { label: string; bolt: boolean; action: StatusAction };
  secondary: null | { label: string; bolt: boolean; action: StatusAction };
  details: null | { label: string; action: StatusAction };
  stop: boolean;
  runLine: string | null;
  feed: ContextActivityDto[]; live: string | null;
  foot: null | { text: string; link: null | { label: string; action: StatusAction } };
  sourceApproval: string | null;
  /** The finished run whose notes Disable notes from this run turns off, and how many. */
  disable: null | { taskId: string; sequence: number; notes: number };
};

export type StatusInput = {
  repository: string; baseline: ContextBaselineDto | null; model: string;
  notebook: RepositoryNotebookDto; times: ContextTimesDto; build: ContextBuildViewDto; report: ContextDiscoveryReportDto | null;
  planView: ContextLearningPlanViewDto; now: number;
};

const blank: StatusCard = { icon: 'doc', tone: 'gray', headline: '', elapsedSince: null, question: null, body: null, steps: false, how: false,
  primary: null, secondary: null, details: null, stop: false, runLine: null, feed: [], live: null, foot: null, sourceApproval: null, disable: null };

export function coverageFoot(report: ContextDiscoveryReportDto): string {
  const total = report.coverage.areas.reduce((sum, area) => sum + area.total, 0);
  const surveyed = report.coverage.areas.reduce((sum, area) => sum + area.surveyed, 0);
  if (total > 0 && surveyed < total) return `Partial survey: ${total - surveyed} of ${total} module directories weren’t surveyed, so this isn’t a complete map.`;
  if (report.partial) return 'Partial survey: some areas weren’t surveyed, so this isn’t a complete map.';
  return total > 0 ? `All ${total} module directories were surveyed.` : 'Discovery finished its survey of the repository layout.';
}

function lastRunSentence(run: ContextRunDto, question: string): string {
  switch (run.outcome) {
    case 'LIMIT': return `reached its source limit before finishing “${question}”. Progress is saved.`;
    case 'ANSWERED': return `answered “${question}”.`;
    case 'CORRECTED': return `corrected “${question}” and added the questions that replace it.`;
    case 'NO_NOTE': return `answered “${question}” without a reusable note.`;
    case 'STOPPED': return `was stopped during “${question}”. Progress is saved.`;
    case 'UNFINISHED': return `saved progress on “${question}”.`;
    case 'FAILED': return `failed during “${question}”${run.detail ? `: ${run.detail}` : '.'}`;
    default: return `worked on “${question}”.`;
  }
}

/** One status card: what is happening now and the one next action. Only lightning-marked actions start a run. */
export function statusCard(input: StatusInput): StatusCard {
  const { notebook, build, report, planView, model, repository, baseline } = input;
  const plan = planView.plan;
  const running = currentRun(build);
  const available = notebook.entries.filter(entry => entry.enabled).length;
  const revision = baseline === null ? '' : `${baseline.branch}@${shortSha(report?.baselineSha ?? plan?.baselineSha ?? baseline.sha)}`;
  if (running !== null) {
    const last = running.activity.at(-1);
    const card: StatusCard = { ...blank, icon: 'spin', tone: 'blue', elapsedSince: running.requestedAt, stop: true,
      feed: running.activity.slice(-4), live: last === undefined ? 'Waiting for the first activity…'
        : `Run in progress · last recorded activity ${relativeTime(last.at, { now: input.now })}`,
      runLine: `Started by you · ${model}` };
    if (running.mode === 'DISCOVER') {
      const continuing = build.build?.continuation === true;
      const unsurveyed = report?.coverage.unsurveyed.length ?? 0;
      return { ...card, headline: continuing ? 'Discovering remaining areas' : 'Discovering topics',
        body: continuing ? `Surveying the ${plural(unsurveyed, 'directory', 'directories')} the last discovery skipped. New suggestions appear when the run finishes.`
          : `Surveying ${repository}${revision ? ` at ${revision}` : ''}. Suggestions appear when the run finishes.`,
        details: { label: 'View activity', action: 'disc-activity' }, runLine: `Started by you · ${model} · read-only`,
        foot: { text: available ? `Your ${plural(available, 'note')} stay available — discovery only adds suggestions.`
          : 'Discovery saves suggestions only. It never writes notes or changes files.', link: null } };
    }
    if (running.question !== null) {
      const topic = plan?.topics.find(value => value.id === running.question?.topicId);
      const total = topic?.questions.length ?? running.question.index + 1;
      const resumed = topic?.questions[running.question.index]?.progress ? ' · resumed from saved progress' : '';
      const batch = plan?.batch ?? null;
      return { ...card, headline: `Learning · ${running.question.topicTitle}`, question: running.question.text,
        body: `Run ${running.sequence} · question ${running.question.index + 1} of ${total} in this topic${resumed}`
          + (batch === null ? '' : ` · run ${batch.started} of ${batch.total} in this batch`),
        details: { label: 'View details', action: 'plan-activity' },
        foot: { text: 'Notes are saved as they finish. Stopping keeps the plan and every saved note.', link: null } };
    }
    if (running.investigation !== null) {
      return { ...card, headline: `Investigating · ${running.investigation.title}`, question: running.investigation.statement,
        body: `Run ${running.sequence} reads the code at the monitored branch head to find which side holds and why agents disagreed. It rewrites, keeps or narrows the note, with a root cause.`,
        details: { label: 'View activity', action: 'run-activity' },
        foot: { text: 'Stopping leaves the note as it is, still marked as disputed.', link: null } };
    }
    return { ...card, headline: `Learning from merged PR #${running.pullRequest ?? ''}`, question: 'What’s reusable from this change?',
      body: 'Reads the PR bundle and the current code. It may save a note, an update, or nothing.',
      details: { label: 'View details', action: 'plan-activity' } };
  }
  const upcoming = batchNext(plan, build);
  if (upcoming !== null) {
    return { ...blank, icon: 'spin', tone: 'blue', headline: 'Starting the next question', question: upcoming.question,
      body: `${upcoming.topic} · run ${upcoming.run} of ${upcoming.total} in this batch. It starts once the last run’s worktree is cleaned up.`,
      runLine: `Started by you · ${model} · Stop is available once it starts` };
  }
  if (report === null && plan === null && available === 0) {
    return { ...blank, icon: 'search', headline: `Find what’s worth learning in ${repository}`,
      body: `An agent surveys the repository${revision ? ` at ${revision}` : ''} — layout, READMEs, build files and docs — and suggests topics. You choose what to learn; agents can use each note as soon as it’s saved.`,
      steps: true, how: true, primary: { label: 'Start discovery', bolt: true, action: 'discover' },
      runLine: `Starts 1 discovery run · ${model} · read-only · you can stop it` };
  }
  if (report === null && plan === null) {
    return { ...blank, icon: 'check', tone: 'green', headline: `${plural(available, 'note')} available to agents`,
      body: 'Development and review agents look notes up when they seem relevant — the agent decides what applies. Missing notes never block work.',
      secondary: { label: 'Find topics to learn', bolt: true, action: 'discover' },
      runLine: `Starts 1 discovery run · ${model} · your ${plural(available, 'note')} stay as they are` };
  }
  if (needsTopicReselection(plan)) {
    return { ...blank, icon: 'alert', tone: 'amber', headline: 'Reselect topics before continuing',
      body: 'This earlier plan does not say where its notes apply. Discard the remaining plan, discover again, and select topics with explicit note targets. Saved notes are kept.',
      primary: { label: 'Review learning plan', bolt: false, action: 'plan' } };
  }
  if (plan === null && report?.topics.some(topic => topic.notePaths === null)) {
    return { ...blank, icon: 'alert', tone: 'amber', headline: 'Update suggestions before learning',
      body: 'Earlier suggestions do not say where their notes apply. Discover again to set note targets. Saved notes are kept.',
      primary: { label: 'Discover again', bolt: true, action: 'discover' }, runLine: `Starts 1 discovery run · ${model} · read-only` };
  }
  if (plan === null && report !== null) {
    const topics = report.topics.length;
    const card: StatusCard = { ...blank, icon: 'doc', tone: 'blue',
      headline: topics ? `${plural(topics, 'topic')} suggested` : 'No topics suggested',
      body: topics ? 'Select the topics worth learning. Inspect where notes will apply and which sources the agent can read before you start.'
        : 'Discovery didn’t find a topic worth learning in the areas it surveyed.',
      foot: { text: coverageFoot(report), link: { label: 'See coverage', action: 'coverage' } } };
    return report.partial ? { ...card, secondary: { label: 'Discover remaining areas', bolt: true, action: 'discover-rest' },
      runLine: `Discover remaining areas starts 1 discovery run · ${model}` } : card;
  }
  const active = plan;
  const sources = pendingCounts(active).sources;
  const next = nextQuestion(planView);
  const left = planCounts(active).total - planCounts(active).answered;
  const last = latestRun(build.runs, run => run.question?.planId === active.id);
  let card: StatusCard;
  const lastQuestion = last?.question?.text ?? '';
  const recent = last !== null && input.now - Date.parse(last.endedAt ?? last.requestedAt) < RECENT_RUN_MS;
  if (last !== null && !recent) {
    card = { ...blank, icon: 'review', tone: 'amber',
      headline: sources ? `${plural(sources, 'source proposal')} ${sources === 1 ? 'needs' : 'need'} your review` : 'Learning is paused',
      body: `${plural(left, 'question')} left in the plan.`,
      foot: { text: `Last run · ${shortDate(last.endedAt ?? last.requestedAt, input.now)} · ${lastRunSentence(last, lastQuestion)}`, link: null } };
  }
  else if (last?.outcome === 'STOPPED' || last?.outcome === 'UNFINISHED' || last?.outcome === 'LIMIT') {
    const headline = last.outcome === 'STOPPED' ? 'Run stopped · progress saved'
      : last.outcome === 'LIMIT' ? `Run ${last.sequence} reached its source limit` : `Run ${last.sequence} finished · progress saved`;
    card = { ...blank, icon: 'pause', headline, body: `“${lastQuestion}” is unfinished; Continue picks it up.` };
  }
  else if (last?.outcome === 'CORRECTED') {
    const topic = active.topics.find(value => value.id === last.question?.topicId);
    card = { ...blank, icon: 'review', tone: 'amber', headline: `Run ${last.sequence} corrected its question`,
      body: topic?.questions[last.question?.index ?? -1]?.note ?? `“${lastQuestion}” did not hold as asked.` };
  }
  else if (last?.outcome === 'FAILED') {
    card = { ...blank, icon: 'alert', tone: 'red', headline: `Run ${last.sequence} failed`,
      body: `${last.detail ? last.detail + ' ' : ''}Progress and saved notes are kept.` };
  }
  else if (last !== null) {
    const topic = active.topics.find(value => value.id === last.question?.topicId);
    const open = topic?.questions.filter(isOpen).length ?? 0;
    card = { ...blank, icon: 'check', tone: 'green', headline: `Run ${last.sequence} finished · question answered`,
      body: topic ? `${topic.title} ${open ? `still has ${plural(open, 'open question')}` : 'has no open questions'}; ${left} left in the plan.` : null };
  }
  else {
    card = { ...blank, icon: 'doc', headline: 'Learning plan saved', body: `${plural(left, 'question')} left in the plan.` };
  }
  if (next !== null) {
    card.primary = { label: 'Continue learning', bolt: true, action: 'continue' };
    card.runLine = batchLine(batchRuns(active), `${next.topic.title} · “${next.text}”${next.resumes ? ' (resumes saved progress)' : ''}`, model);
  }
  else {
    card = { ...card, icon: 'check', tone: 'green', headline: 'All planned questions answered',
      body: 'Saved notes are available to agents. Choose more topics under Learning opportunities.' };
  }
  if (last !== null && last.noteIds.length > 0) {
    const disable = runNotesToDisable(notebook, input.times, last).length;
    const saved = `Run ${last.sequence} saved ${plural(last.noteIds.length, 'note')}.`;
    card.foot = { text: card.foot === null ? saved : `${card.foot.text} ${saved}`,
      link: disable === 0 ? null : { label: 'Disable notes from this run', action: 'disable-notes' } };
    if (disable > 0) card.disable = { taskId: last.taskId, sequence: last.sequence, notes: disable };
  }
  card.details = { label: 'Plan details', action: 'plan' };
  if (sources) card.sourceApproval = active.documentInventory.suggestions[0].url;
  return card;
}

/* ── Notes ──────────────────────────────────────────────────────────── */

export type NoteFilter = { kind: 'all' | 'KNOWLEDGE' | 'MEMORY'; area: string; query: string };

/** Literal, case-insensitive filtering over what the list shows; it never ranks notes. */
export function filterNotes(notebook: RepositoryNotebookDto, filter: NoteFilter): ContextEntryDto[] {
  const query = filter.query.trim().toLowerCase();
  return notebook.entries.filter(entry => filter.kind === 'all' || entry.kind === filter.kind)
    .filter(entry => filter.area === 'all' || entry.scopeId === filter.area)
    .filter(entry => query === '' || [entry.title, entry.summary, ...noteArea(notebook, entry.scopeId).paths].join(' ').toLowerCase().includes(query));
}

export function noteGroups(notebook: RepositoryNotebookDto, entries: ContextEntryDto[]): Array<{ area: ContextArea; entries: ContextEntryDto[] }> {
  const order = [...notebook.scopes.map(scope => scope.id), ...entries.map(entry => entry.scopeId)];
  return [...new Set(order)].map(id => ({ area: noteArea(notebook, id), entries: entries.filter(entry => entry.scopeId === id) }))
    .filter(group => group.entries.length > 0);
}

/* ── Note content ───────────────────────────────────────────────────── */

const DOCUMENT_PATH = /\.(md|mdx|rst|adoc|txt)$|(^|\/)(docs?|documentation)\//i;

/** The latest time a learning run captured different text from a page the note cites, or null when none changed. */
export function pageChangedAt(entry: ContextEntryDto, changedPages: Record<string, string>): string | null {
  const seen = entry.sourceIds.flatMap(id => changedPages[id] === undefined ? [] : [changedPages[id]]).sort();
  return seen.length === 0 ? null : seen[seen.length - 1];
}

export function sourceView(source: ContextSourceEvidenceDto | undefined, id: string): { target: string; kind: string; detail: string; capture: string; agent: boolean } {
  if (source === undefined) return { target: id, kind: 'Source', detail: 'no longer retained', capture: '', agent: false };
  const reference = source.reference;
  const lines = /lines[= ](\d+)-(\d+)/.exec(source.provenance);
  const capture = source.capture === 'AGENT_REPORTED' ? 'Agent-reported · not captured'
    : reference.kind === 'PR' || reference.kind === 'ISSUE' || reference.kind === 'TASK' || reference.kind === 'REVIEW' ? 'From work bundle' : 'Read by ByteQuay';
  const agent = source.capture === 'AGENT_REPORTED';
  switch (reference.kind) {
    case 'REPOSITORY_FILE': {
      const documented = DOCUMENT_PATH.test(reference.path);
      return { target: reference.path, kind: documented ? 'Repository page' : 'Code',
        detail: `${lines ? `lines ${lines[1]}–${lines[2]} · ` : ''}at ${shortSha(reference.commit)}`, capture, agent };
    }
    case 'DOCUMENT': return { target: reference.url, kind: 'Web page', detail: source.retrievedAt ? `captured ${shortDate(source.retrievedAt)}` : 'captured', capture, agent };
    case 'PR': return { target: `PR #${reference.number}`, kind: 'Pull request', detail: `${reference.repository.owner}/${reference.repository.repo}`, capture, agent };
    case 'ISSUE': return { target: `Issue #${reference.number}`, kind: 'Issue', detail: `${reference.repository.owner}/${reference.repository.repo}`, capture, agent };
    case 'TASK': return { target: 'Task', kind: 'Task', detail: source.provenance, capture, agent };
    case 'REVIEW': return { target: 'Review', kind: 'Review', detail: source.provenance, capture, agent };
  }
}

export const CLAIM_TAG: Record<ContextClaimDto['basis'], { label: string; tone: 'muted' | 'amber' | 'purple' | 'blue' }> = {
  DOCUMENTED: { label: 'Documented', tone: 'muted' },
  OBSERVED: { label: 'Observed in code', tone: 'muted' },
  RECORDED_DECISION: { label: 'Recorded decision', tone: 'muted' },
  INFERENCE: { label: 'Inference', tone: 'amber' },
  QUESTION: { label: 'Open question', tone: 'purple' },
  USER_LESSON: { label: 'Your lesson', tone: 'blue' },
};

export type DiffLine = { sign: '+' | '−' | ' '; text: string };

/** Compares a note's previous version with its current one, claim by claim and line by line. */
export function noteDiff(previous: ContextEntryDto, current: ContextEntryDto): { lines: DiffLine[]; summary: string } {
  // Lines compare as the reader sees them: without Markdown heading and list markers.
  const items = (entry: ContextEntryDto) => [entry.summary,
    ...entry.body.split('\n').map(line => line.trim().replace(/^#{1,6}\s+/, '').replace(/^[-*+]\s+/, '')).filter(Boolean), ...entry.claims.map(claim => claim.text)];
  const before = items(previous);
  const after = items(current);
  const table = before.map(() => new Array<number>(after.length + 1).fill(0));
  table.push(new Array<number>(after.length + 1).fill(0));
  for (let i = before.length - 1; i >= 0; i--) {
    for (let j = after.length - 1; j >= 0; j--) {
      table[i][j] = before[i] === after[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }
  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < before.length || j < after.length) {
    if (i < before.length && j < after.length && before[i] === after[j]) { lines.push({ sign: ' ', text: after[j] }); i++; j++; }
    else if (j < after.length && (i === before.length || table[i][j + 1] >= table[i + 1][j])) { lines.push({ sign: '+', text: after[j] }); j++; }
    else { lines.push({ sign: '−', text: before[i] }); i++; }
  }
  const added = lines.filter(line => line.sign === '+').length;
  const removed = lines.filter(line => line.sign === '−').length;
  const sources = current.sourceIds.filter(id => !previous.sourceIds.includes(id)).length;
  const summary = [`${plural(added, 'line')} added or changed`, `${removed} removed`, ...(sources ? [`${plural(sources, 'source')} added`] : [])].join(' · ');
  return { lines, summary };
}

/* ── Opportunities ──────────────────────────────────────────────────── */

/** Merged work already learned from is not offered again while its run is retained. */
export function openCandidates(candidates: ContextMergedCandidatesDto | null, runs: ContextRunDto[]): ContextMergedCandidatesDto['candidates'] {
  const learned = new Set(runs.filter(run => run.pullRequest !== null && (run.outcome === 'ANSWERED' || run.outcome === 'NO_NOTE')).map(run => run.pullRequest));
  return (candidates?.candidates ?? []).filter(candidate => !learned.has(candidate.number));
}

export function topicsOutsidePlan(report: ContextDiscoveryReportDto | null, plan: ContextLearningPlanDto | null): ContextDiscoveryReportDto['topics'] {
  if (report === null) return [];
  const planned = plan === null || plan.reportId !== report.id ? new Set<string>() : new Set(plan.topics.map(topic => topic.id));
  return report.topics.filter(topic => !planned.has(topic.id));
}
