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
/** Wire shapes for the Code & Tests tab, parsed in main before they reach the renderer. */

import { count, object, oneOf, optionalCount, optionalText, text } from '../wireParse';

/** The worktree Code & Tests reads: an Agent review's, or the development task's that owns the pull request. */
export type CodeHostRef =
  | { kind: 'review'; reviewId: string }
  | { kind: 'task'; taskId: string; workspaceId: string; repo: string; number: number };

/** One key per host, so a review and a task never share a terminal. */
export function codeHostKey(host: CodeHostRef): string {
  return host.kind === 'review' ? `review:${host.reviewId}` : `task:${host.taskId}`;
}

/** Main receives the host from the renderer, so it checks the shape before building a backend URL from it. */
export function parseCodeHost(value: unknown): CodeHostRef {
  const source = typeof value === 'object' && value !== null ? value as Record<string, unknown> : {};
  if (source.kind === 'review' && typeof source.reviewId === 'string') return { kind: 'review', reviewId: source.reviewId };
  if (source.kind === 'task' && typeof source.taskId === 'string' && typeof source.workspaceId === 'string' && typeof source.repo === 'string'
    && typeof source.number === 'number' && Number.isInteger(source.number)) {
    return { kind: 'task', taskId: source.taskId, workspaceId: source.workspaceId, repo: source.repo, number: source.number };
  }
  throw new Error('Malformed Code & Tests host');
}

export type ReviewCodeSource = 'WORKING_COPY' | 'REVISION';

export type ReviewCodeFileEntryDto = {
  path: string;
  changedInPr: boolean;
  /** The reviewed pull request adds this file. */
  addedInPr: boolean;
  onlyInWorkingCopy: boolean;
  uncommitted: boolean;
};

export type CodeGraphAvailability = 'READY' | 'OFF' | 'UNAVAILABLE';

export type ReviewCodeFilesDto = {
  source: ReviewCodeSource;
  revision: string;
  truncated: boolean;
  codeGraph: CodeGraphAvailability;
  /** Hash of HEAD plus uncommitted and working-copy-only content; null for a reviewed revision. */
  sourceFingerprint: string | null;
  /** A failed Update review left the review's experiments in a stash that Discard would also drop. */
  savedExperiment: boolean;
  files: ReviewCodeFileEntryDto[];
};

export type CodeSymbolStatus = 'READY' | 'BUSY' | 'UPDATING' | 'UNAVAILABLE';

export type ReviewCodeSymbolDto = {
  name: string;
  kind: string;
  qualifiedName: string | null;
  path: string;
  /** 1-based. */
  startLine: number;
  startColumn: number;
  endLine: number;
  signature: string | null;
  /** A JUnit test method; only outlines compute it. */
  test: boolean;
  /** Every line of the symbol is an added line in the pull request's patch; only outlines compute it. */
  added: boolean;
};

export type ReviewCodeSymbolsDto = { status: CodeSymbolStatus; symbols: ReviewCodeSymbolDto[] };

export type ReviewCodeFileDto = {
  path: string;
  /** Null when the file cannot be shown; `unavailableReason` then says why. */
  content: string | null;
  contentHash: string | null;
  editable: boolean;
  readOnlyReason: string | null;
  unavailableReason: string | null;
  /** This file's patch in the reviewed pull-request diff, when the PR changes it. */
  prPatch: string | null;
  /** How the shown content differs from the pull request's merge base. */
  lineChanges: LineChangeDto[];
};

/**
 * One contiguous change against the merge base: `line` and `count` are the added or modified lines, or for
 * DELETED the line the removed lines followed (0 for the top of the file) and how many were removed.
 */
export type LineChangeDto = { kind: 'ADDED' | 'MODIFIED' | 'DELETED'; line: number; count: number };

const LINE_CHANGE_KINDS = ['ADDED', 'MODIFIED', 'DELETED'] as const;

// Gutter decoration only, so an entry this renderer cannot place is dropped instead of failing the whole read.
function lineChanges(value: unknown): LineChangeDto[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item: unknown) => {
    if (typeof item !== 'object' || item === null) return [];
    const { kind, line, count } = item as Record<string, unknown>;
    return LINE_CHANGE_KINDS.some(known => known === kind) && Number.isInteger(line) && Number.isInteger(count)
      && (line as number) >= 0 && (count as number) > 0
      ? [{ kind: kind as LineChangeDto['kind'], line: line as number, count: count as number }]
      : [];
  });
}




export function parseReviewCodeFiles(value: unknown): ReviewCodeFilesDto {
  const source = object(value, 'file list');
  if (source.source !== 'WORKING_COPY' && source.source !== 'REVISION') {
    throw new Error('Malformed file list source from the review code service');
  }
  if (!Array.isArray(source.files)) throw new Error('Malformed file list from the review code service');
  return {
    source: source.source,
    revision: text(source.revision, 'file list revision'),
    truncated: source.truncated === true,
    codeGraph: source.codeGraph === 'READY' || source.codeGraph === 'OFF' ? source.codeGraph : 'UNAVAILABLE',
    sourceFingerprint: optionalText(source.sourceFingerprint),
    savedExperiment: source.savedExperiment === true,
    files: source.files.map(item => {
      const entry = object(item, 'file entry');
      return {
        path: text(entry.path, 'file path'),
        changedInPr: entry.changedInPr === true,
        addedInPr: entry.addedInPr === true,
        onlyInWorkingCopy: entry.onlyInWorkingCopy === true,
        uncommitted: entry.uncommitted === true,
      };
    }),
  };
}

export function parseReviewCodeFile(value: unknown): ReviewCodeFileDto {
  const source = object(value, 'file');
  return {
    path: text(source.path, 'file path'),
    content: optionalText(source.content),
    contentHash: optionalText(source.contentHash),
    editable: source.editable === true,
    readOnlyReason: optionalText(source.readOnlyReason),
    unavailableReason: optionalText(source.unavailableReason),
    prPatch: optionalText(source.prPatch),
    lineChanges: lineChanges(source.lineChanges),
  };
}


const SYMBOL_STATUSES: readonly CodeSymbolStatus[] = ['READY', 'BUSY', 'UPDATING', 'UNAVAILABLE'];

export function parseReviewCodeSymbols(value: unknown): ReviewCodeSymbolsDto {
  const source = object(value, 'symbols');
  const status = SYMBOL_STATUSES.find(candidate => candidate === source.status);
  if (status === undefined) throw new Error('Malformed symbol status from the review code service');
  if (!Array.isArray(source.symbols)) throw new Error('Malformed symbols from the review code service');
  return {
    status,
    symbols: source.symbols.map(item => {
      const symbol = object(item, 'symbol');
      return {
        name: text(symbol.name, 'symbol name'),
        kind: text(symbol.kind, 'symbol kind'),
        qualifiedName: optionalText(symbol.qualifiedName),
        path: text(symbol.path, 'symbol path'),
        startLine: count(symbol.startLine, 'symbol line'),
        startColumn: typeof symbol.startColumn === 'number' ? symbol.startColumn : 0,
        endLine: count(symbol.endLine, 'symbol end line'),
        signature: optionalText(symbol.signature),
        test: symbol.test === true,
        added: symbol.added === true,
      };
    }),
  };
}

export type LocalRunStatus =
  | 'RUNNING'
  | 'PASSED'
  | 'FAILED_ASSERTION'
  | 'BUILD_FAILED'
  | 'NO_TESTS_RAN'
  | 'STOPPED'
  | 'TIMED_OUT'
  | 'RESULTS_UNAVAILABLE'
  | 'INTERRUPTED';

export type LocalRunMethodDto = {
  name: string;
  status: 'PASSED' | 'FAILED' | 'ERROR' | 'SKIPPED';
  durationMs: number | null;
  message: string | null;
  trace: string | null;
};

export type LocalRunClassDto = { className: string; durationMs: number | null; methods: LocalRunMethodDto[] };

export type LocalRunDto = {
  id: string;
  ordinal: number;
  initiator: 'USER' | 'AGENT';
  scope: 'CLASS' | 'METHOD' | 'COMMAND';
  /** Test source path the run was started from; null for commands. */
  path: string | null;
  testClass: string | null;
  testMethod: string | null;
  module: string | null;
  command: string;
  sourceHeadSha: string | null;
  localPaths: string[];
  sourceFingerprint: string | null;
  status: LocalRunStatus;
  startedAt: string;
  endedAt: string | null;
  exitCode: number | null;
  tests: number | null;
  failures: number | null;
  errors: number | null;
  skipped: number | null;
  outputTruncated: boolean;
  omittedBytes: number | null;
  /** The run's processes could not be shown stopped, so it still holds the working copy. */
  treeUnverified: boolean;
  results: LocalRunClassDto[];
};

export type LocalRunOutputDto = {
  text: string;
  nextOffset: number;
  /** Bytes between the requested offset and the returned text that the bounded buffer no longer holds. */
  skippedBytes: number;
  complete: boolean;
};

const RUN_STATUSES: readonly LocalRunStatus[] = [
  'RUNNING', 'PASSED', 'FAILED_ASSERTION', 'BUILD_FAILED', 'NO_TESTS_RAN', 'STOPPED', 'TIMED_OUT', 'RESULTS_UNAVAILABLE', 'INTERRUPTED',
];



export function parseLocalRun(value: unknown): LocalRunDto {
  const source = object(value, 'run');
  return {
    id: text(source.id, 'run id'),
    ordinal: count(source.ordinal, 'run ordinal'),
    initiator: oneOf(source.initiator, ['USER', 'AGENT'] as const, 'run initiator'),
    scope: oneOf(source.scope, ['CLASS', 'METHOD', 'COMMAND'] as const, 'run scope'),
    path: optionalText(source.path),
    testClass: optionalText(source.testClass),
    testMethod: optionalText(source.testMethod),
    module: optionalText(source.module),
    command: text(source.command, 'run command'),
    sourceHeadSha: optionalText(source.sourceHeadSha),
    localPaths: Array.isArray(source.localPaths) ? source.localPaths.filter((item): item is string => typeof item === 'string') : [],
    sourceFingerprint: optionalText(source.sourceFingerprint),
    status: oneOf(source.status, RUN_STATUSES, 'run status'),
    startedAt: text(source.startedAt, 'run start'),
    endedAt: optionalText(source.endedAt),
    exitCode: optionalCount(source.exitCode),
    tests: optionalCount(source.tests),
    failures: optionalCount(source.failures),
    errors: optionalCount(source.errors),
    skipped: optionalCount(source.skipped),
    outputTruncated: source.outputTruncated === true,
    omittedBytes: optionalCount(source.omittedBytes),
    treeUnverified: source.treeUnverified === true,
    results: (Array.isArray(source.results) ? source.results : []).map(item => {
      const result = object(item, 'run class');
      return {
        className: text(result.className, 'run class name'),
        durationMs: optionalCount(result.durationMs),
        methods: (Array.isArray(result.methods) ? result.methods : []).map(entry => {
          const method = object(entry, 'run method');
          return {
            name: text(method.name, 'run method name'),
            status: oneOf(method.status, ['PASSED', 'FAILED', 'ERROR', 'SKIPPED'] as const, 'run method status'),
            durationMs: optionalCount(method.durationMs),
            message: optionalText(method.message),
            trace: optionalText(method.trace),
          };
        }),
      };
    }),
  };
}

export function parseLocalRuns(value: unknown): LocalRunDto[] {
  const source = object(value, 'runs');
  if (!Array.isArray(source.runs)) throw new Error('Malformed runs from the review code service');
  return source.runs.map(parseLocalRun);
}

export function parseLocalRunOutput(value: unknown): LocalRunOutputDto {
  const source = object(value, 'run output');
  return {
    text: text(source.text, 'run output text'),
    nextOffset: count(source.nextOffset, 'run output offset'),
    skippedBytes: optionalCount(source.skippedBytes) ?? 0,
    complete: source.complete === true,
  };
}

/** A start the backend refused before creating a record: the reason and the one action the panel offers. */
export type RunRefusalDto = { reason: string; action: 'OPEN_TERMINAL' | null };

/** The file on disk no longer matches the draft's starting point; nothing was written. */
export type SaveConflictDto = { currentHash: string | null; currentContent: string | null };
