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
export type ContextActivityDto = { at: string; text: string; tone: 'DONE' | 'FAILED' };
export type ContextBaselineDto = { branch: string; sha: string };
/** How the current check turn ended; SAVED means the turn saved its candidates or its reproduction result. */
export type ContextRunOutcome = 'RUNNING' | 'SAVED' | 'STOPPED' | 'FAILED';
/** The workspace's current run, a spec check turn, a memory harvest or a Goal's research: its task, whether it runs, and what its tools did. */
export type ContextBuildViewDto = {
  buildRequestSequence: number; canStart: boolean;
  build: null | {
    kind: 'CHECK' | 'HARVEST' | 'RESEARCH'; requestKey: string; taskId: string; checkId: string;
    task: null | { id: string; workspaceId: string; state: 'OPEN' | 'COMPLETED' | 'FAILED'; mutationClaim: string | null; worktreeRemovedAt: string | null };
    activeTurn: null | { cancellationRequestedAt: string | null };
    pendingApprovals: number; outcome: ContextRunOutcome; detail: string | null; activity: ContextActivityDto[];
  };
};

function malformed(field: string): never { throw new Error(`Malformed spec check: ${field}`); }
function object(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) malformed(field);
  return value as Record<string, unknown>;
}
function text(value: unknown, field: string): string {
  if (typeof value !== 'string') malformed(field);
  return value;
}
function optionalText(value: unknown, field: string): string | null { return value == null ? null : text(value, field); }
function count(value: unknown, field: string, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) malformed(field);
  return value;
}
function flag(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') malformed(field);
  return value;
}
function array<T>(value: unknown, field: string, parse: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) malformed(field);
  return value.map(parse);
}
function strings(value: unknown, field: string): string[] { return array(value, field, item => text(item, field)); }
function oneOf<T extends string>(value: unknown, allowed: readonly T[], field: string): T {
  const found = allowed.find(item => item === value);
  if (found === undefined) malformed(field);
  return found;
}
function request(value: unknown, fields: string[]): Record<string, unknown> {
  const item = object(value, 'request');
  for (const key of Object.keys(item)) if (!fields.includes(key)) malformed(`unsupported request field ${key}`);
  return item;
}
function nonblank(value: unknown, field: string): string {
  const result = text(value, field);
  if (result.trim() === '') malformed(field);
  return result;
}
function activity(value: unknown): ContextActivityDto {
  const item = object(value, 'run activity');
  return { at: text(item.at, 'activity time'), text: text(item.text, 'activity text'), tone: oneOf(item.tone, ['DONE', 'FAILED'], 'activity tone') };
}
export function parseContextBaseline(value: unknown): ContextBaselineDto | null {
  if (value == null) return null;
  const item = object(value, 'monitored baseline');
  const sha = text(item.sha, 'baseline commit');
  if (!/^[0-9a-f]{40}([0-9a-f]{24})?$/.test(sha)) malformed('baseline commit');
  return { branch: text(item.branch, 'baseline branch'), sha };
}
export function parseContextBuildView(value: unknown, workspaceId: string): ContextBuildViewDto {
  const item = object(value, 'build view');
  const result: ContextBuildViewDto = { buildRequestSequence: count(item.buildRequestSequence, 'build sequence'), canStart: flag(item.canStart, 'build availability'),
    build: null };
  if (item.build == null) return result;
  const build = object(item.build, 'build');
  const task = build.task == null ? null : object(build.task, 'build task');
  const turn = build.activeTurn == null ? null : object(build.activeTurn, 'build turn');
  result.build = { kind: oneOf(build.kind, ['CHECK', 'HARVEST', 'RESEARCH'], 'run kind'), requestKey: text(build.requestKey, 'build request key'), taskId: text(build.taskId, 'build task id'), checkId: text(build.checkId, 'build check'),
    task: task === null ? null : { id: text(task.id, 'task id'), workspaceId: text(task.workspaceId, 'task workspace'),
      state: oneOf(task.state, ['OPEN', 'COMPLETED', 'FAILED'], 'task state'), mutationClaim: optionalText(task.mutationClaim, 'task claim'),
      worktreeRemovedAt: optionalText(task.worktreeRemovedAt, 'task cleanup') },
    activeTurn: turn === null ? null : { cancellationRequestedAt: optionalText(turn.cancellationRequestedAt, 'turn cancellation') },
    pendingApprovals: count(build.pendingApprovals, 'pending approvals'), outcome: oneOf(build.outcome, ['RUNNING', 'SAVED', 'STOPPED', 'FAILED'], 'run outcome'),
    detail: optionalText(build.detail, 'run detail'), activity: array(build.activity, 'run activity', activity) };
  if (result.build.task !== null && (result.build.task.id !== result.build.taskId || result.build.task.workspaceId !== workspaceId)) malformed('build task ownership');
  return result;
}

/* ── Spec checks ─────────────────────────────────────────────────────── */

/** A page the directory's code links, how often and at which sections, or another engine's source file, for comparison only. The check decides which pages are specs. */
export type ContextSpecSourceDto = { url: string; kind: 'PAGE' | 'REFERENCE_CODE'; links: number; sections: string[] };
/** A module a check can start from, with the page its own code cites in the most sections. */
export type ContextSpecModuleDto = { directory: string; page: string | null; sections: number; links: number };
export type ContextSpecCheckState = 'CHECKING' | 'REPRODUCING' | 'DONE' | 'STOPPED' | 'FAILED';
export type ContextSpecCandidateType = 'BUG' | 'FEATURE_REQUEST' | 'IMPROVEMENT';
export type ContextSpecCandidateStatus = 'PENDING' | 'REPRODUCING' | 'CONFIRMED' | 'REFUTED' | 'NOT_REPRODUCED' | 'ALREADY_REPORTED';
/** One gap a check found; only a confirmed one, whose test ByteQuay saw fail, is a finding with an issue draft. */
export type ContextSpecCandidateDto = {
  id: string; type: ContextSpecCandidateType; predicted: 'DIVERGES' | 'IGNORES' | 'REFUSES' | 'REFUSES_UNCLEARLY' | 'SKIPS_SHOULD';
  title: string; rule: string; specUrl: string; code: string[]; reasoning: string; severity: number;
  duplicate: null | { number: number; title: string; state: string; url: string };
  status: ContextSpecCandidateStatus;
  test: null | { path: string; command: string; exitCode: number; output: string; source: string };
  reason: string | null; draft: null | { title: string; body: string; labels: string[] }; dismissed: string | null;
};
/** The latest spec check of one directory: the pages it read to find specs, and the specs it checked against. */
export type ContextSpecCheckDto = {
  id: string; directory: string; baselineSha: string; requestKey: string;
  specs: string[]; checkedAgainst: string[]; labels: string[]; state: ContextSpecCheckState; candidates: ContextSpecCandidateDto[]; covered: string[]; next: string[];
  reproductions: number; maxReproductions: number; detail: string | null; startedAt: string; endedAt: string | null;
};
/** Starts one check of a directory: the pages to find its specs among, and the sections, are the check's to choose. */
export type StartContextSpecCheckRequest = { requestKey: string; expectedBuildSequence: number; directory: string; specs: string[]; maxReproductions: number };
export type ContextSpecCheckStartedDto = { check: ContextSpecCheckDto; build: ContextBuildViewDto };

/** A repository-relative directory as the backend takes it: no leading slash, a trailing one, no empty, '.' or '..' parts. */
export function specCheckDirectory(value: string): string | null {
  const trimmed = value.trim().replace(/^\/+/, '').replace(/\/+$/, '');
  if (trimmed === '' || trimmed.includes('\\') || trimmed.startsWith('-') || trimmed.split('/').some(part => part === '' || part === '.' || part === '..')) return null;
  return `${trimmed}/`;
}
export function parseSpecCheckDirectory(value: unknown): string {
  const directory = specCheckDirectory(text(value, 'directory'));
  if (directory === null || directory !== value) malformed('directory');
  return directory;
}
export function parseContextSpecModules(value: unknown): ContextSpecModuleDto[] {
  return array(object(value, 'spec modules').modules, 'spec modules', raw => {
    const item = object(raw, 'spec module');
    return { directory: parseSpecCheckDirectory(item.directory), page: item.page == null ? null : text(item.page, 'module page'),
      sections: count(item.sections, 'module sections'), links: count(item.links, 'module links') };
  });
}
export function parseContextSpecSources(value: unknown): ContextSpecSourceDto[] {
  return array(object(value, 'spec sources').sources, 'spec sources', raw => {
    const item = object(raw, 'spec source');
    return { url: text(item.url, 'spec address'), kind: oneOf(item.kind, ['PAGE', 'REFERENCE_CODE'], 'spec kind'), links: count(item.links, 'spec links'),
      sections: strings(item.sections, 'spec sections') };
  });
}
function specCandidate(value: unknown): ContextSpecCandidateDto {
  const item = object(value, 'spec candidate');
  const duplicate = item.duplicate == null ? null : object(item.duplicate, 'spec duplicate');
  const test = item.test == null ? null : object(item.test, 'spec test');
  const draft = item.draft == null ? null : object(item.draft, 'issue draft');
  return { id: text(item.id, 'candidate id'), type: oneOf(item.type, ['BUG', 'FEATURE_REQUEST', 'IMPROVEMENT'], 'candidate type'),
    predicted: oneOf(item.predicted, ['DIVERGES', 'IGNORES', 'REFUSES', 'REFUSES_UNCLEARLY', 'SKIPS_SHOULD'], 'predicted outcome'),
    title: text(item.title, 'candidate title'), rule: text(item.rule, 'spec rule'), specUrl: text(item.specUrl, 'spec link'), code: strings(item.code, 'code locations'),
    reasoning: text(item.reasoning, 'candidate reasoning'), severity: count(item.severity, 'severity', 1),
    duplicate: duplicate === null ? null : { number: count(duplicate.number, 'report number', 1), title: text(duplicate.title, 'report title'),
      state: text(duplicate.state, 'report state'), url: text(duplicate.url, 'report url') },
    status: oneOf(item.status, ['PENDING', 'REPRODUCING', 'CONFIRMED', 'REFUTED', 'NOT_REPRODUCED', 'ALREADY_REPORTED'], 'candidate status'),
    test: test === null ? null : { path: text(test.path, 'test path'), command: text(test.command, 'test command'), exitCode: exitCode(test.exitCode),
      output: text(test.output, 'test output'), source: text(test.source, 'test source') },
    reason: optionalText(item.reason, 'result reason'),
    draft: draft === null ? null : { title: text(draft.title, 'issue title'), body: text(draft.body, 'issue body'), labels: strings(draft.labels, 'issue labels') },
    dismissed: optionalText(item.dismissed, 'dismissal') };
}
function exitCode(value: unknown): number {
  if (typeof value !== 'number' || !Number.isInteger(value)) malformed('test exit code');
  return value;
}
export function parseContextSpecCheck(value: unknown): ContextSpecCheckDto {
  const item = object(value, 'spec check');
  return { id: text(item.id, 'check id'), directory: text(item.directory, 'check directory'), baselineSha: text(item.baselineSha, 'check baseline'), requestKey: text(item.requestKey, 'check request key'), specs: strings(item.specs, 'check specs'),
    checkedAgainst: strings(item.checkedAgainst, 'checked specs'), labels: strings(item.labels, 'repository labels'), state: oneOf(item.state, ['CHECKING', 'REPRODUCING', 'DONE', 'STOPPED', 'FAILED'], 'check state'),
    candidates: array(item.candidates, 'spec candidates', specCandidate), covered: strings(item.covered, 'covered sections'), next: strings(item.next, 'next sections'),
    reproductions: count(item.reproductions, 'reproductions'), maxReproductions: count(item.maxReproductions, 'reproduction limit'),
    detail: optionalText(item.detail, 'check detail'), startedAt: text(item.startedAt, 'check start'),
    endedAt: optionalText(item.endedAt, 'check end') };
}
export function parseContextSpecChecks(value: unknown): ContextSpecCheckDto[] {
  return array(object(value, 'spec checks').checks, 'spec checks', parseContextSpecCheck);
}
export function parseContextSpecCheckStarted(value: unknown, workspaceId: string): ContextSpecCheckStartedDto {
  const item = object(value, 'started spec check');
  return { check: parseContextSpecCheck(item.check), build: parseContextBuildView(item.build, workspaceId) };
}
export function parseStartContextSpecCheckRequest(value: unknown): StartContextSpecCheckRequest {
  const item = request(value, ['requestKey', 'expectedBuildSequence', 'directory', 'specs', 'maxReproductions']);
  const result = { requestKey: nonblank(item.requestKey, 'spec check request key'), expectedBuildSequence: count(item.expectedBuildSequence, 'expected build sequence'),
    directory: parseSpecCheckDirectory(item.directory), specs: strings(item.specs, 'specs'), maxReproductions: count(item.maxReproductions, 'reproduction limit') };
  // With no page, the check proposes the specification the directory implements.
  if (result.specs.length > 30 || result.specs.some(spec => !spec.startsWith('https://'))) malformed('specs');
  if (result.maxReproductions < 1 || result.maxReproductions > 8) malformed('maxReproductions');
  return result;
}
/** Why a finding is dismissed, and the user's rule to remember for its file when they asked for that. */
export function parseDismissContextSpecFindingRequest(value: unknown): { reason: string; rule?: string } {
  const item = request(value, ['reason', 'rule']);
  const reason = nonblank(item.reason, 'dismissal reason');
  return item.rule === undefined ? { reason } : { reason, rule: nonblank(item.rule, 'remembered rule') };
}
