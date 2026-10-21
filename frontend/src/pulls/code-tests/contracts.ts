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
/** Wire shapes for the Tears of time tab, parsed in main before they reach the renderer. */

import { count, object, oneOf, optionalCount, optionalText, text } from '../wireParse';

/** The managed working copy, independent of whether a pull request exists. */
export type CodeHostRef =
  | { kind: 'review'; reviewId: string }
  | { kind: 'task'; taskId: string; workspaceId: string };

/** One key per host, so a review and a task never share a terminal. */
export function codeHostKey(host: CodeHostRef): string {
  return host.kind === 'review' ? `review:${host.reviewId}` : `task:${host.taskId}`;
}

/** Main receives the host from the renderer, so it checks the shape before building a backend URL from it. */
export function parseCodeHost(value: unknown): CodeHostRef {
  const source = typeof value === 'object' && value !== null ? value as Record<string, unknown> : {};
  if (source.kind === 'review' && typeof source.reviewId === 'string') return { kind: 'review', reviewId: source.reviewId };
  if (source.kind === 'task' && typeof source.taskId === 'string' && typeof source.workspaceId === 'string') {
    return { kind: 'task', taskId: source.taskId, workspaceId: source.workspaceId };
  }
  throw new Error('Malformed Tears of time host');
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

export type ReviewCodeFilesDto = {
  source: ReviewCodeSource;
  revision: string;
  truncated: boolean;
  /** Hash of HEAD plus uncommitted and working-copy-only content; null for a reviewed revision. */
  sourceFingerprint: string | null;
  /** A failed Update review left the review's experiments in a stash that Discard would also drop. */
  savedExperiment: boolean;
  files: ReviewCodeFileEntryDto[];
};

export type ReviewCodeFileDto = ReturnType<typeof parseReviewCodeFile>;

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

export function parseReviewCodeFile(value: unknown) {
  const source = object(value, 'file');
  return {
    path: text(source.path, 'file path'),
    /** Null when the file cannot be shown; `unavailableReason` then says why. */
    content: optionalText(source.content),
    contentHash: optionalText(source.contentHash),
    editable: source.editable === true,
    readOnlyReason: optionalText(source.readOnlyReason),
    unavailableReason: optionalText(source.unavailableReason),
    /** This file's patch in the reviewed pull-request diff, when the PR changes it. */
    prPatch: optionalText(source.prPatch),
    /** How the shown content differs from the pull request's merge base. */
    lineChanges: lineChanges(source.lineChanges),
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
  | 'INTERRUPTED'
  | 'COMPLETED'
  | 'FAILED';

export type LocalRunMethodDto = {
  name: string;
  status: 'PASSED' | 'FAILED' | 'ERROR' | 'SKIPPED';
  durationMs: number | null;
  message: string | null;
  trace: string | null;
};

export type LocalRunClassDto = { className: string; durationMs: number | null; methods: LocalRunMethodDto[] };

export type LocalRunDto = ReturnType<typeof parseLocalRun>;

export type LocalRunOutputDto = ReturnType<typeof parseLocalRunOutput>;

const RUN_STATUSES: readonly LocalRunStatus[] = [
  'RUNNING', 'PASSED', 'FAILED_ASSERTION', 'BUILD_FAILED', 'NO_TESTS_RAN', 'STOPPED', 'TIMED_OUT', 'RESULTS_UNAVAILABLE', 'INTERRUPTED', 'COMPLETED', 'FAILED',
];



export function parseLocalRun(value: unknown) {
  const source = object(value, 'run');
  return {
    id: text(source.id, 'run id'),
    ordinal: count(source.ordinal, 'run ordinal'),
    initiator: oneOf(source.initiator, ['USER', 'AGENT'] as const, 'run initiator'),
    scope: oneOf(source.scope, ['CLASS', 'METHOD', 'COMMAND', 'TESTS', 'DEBUG', 'RUN'] as const, 'run scope'),
    /** Test source path the run was started from; null for commands. */
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
    /** The run's processes could not be shown stopped, so it still holds the working copy. */
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

export function parseLocalRunOutput(value: unknown) {
  const source = object(value, 'run output');
  return {
    text: text(source.text, 'run output text'),
    nextOffset: count(source.nextOffset, 'run output offset'),
    /** Bytes between the requested offset and the returned text that the bounded buffer no longer holds. */
    skippedBytes: optionalCount(source.skippedBytes) ?? 0,
    complete: source.complete === true,
  };
}

/** A start the backend refused before creating a record: the reason and the one action the panel offers. */

/** The file on disk no longer matches the draft's starting point; nothing was written. */

export type WorktreeChangeScope = 'ALL' | 'UNCOMMITTED' | 'COMMITTED' | 'COMMIT';
export type WorktreeChangesRequest = { scope: WorktreeChangeScope; commit?: string; base?: string; head?: string };
export type WorktreeChangesDto = ReturnType<typeof parseWorktreeChanges>;

export function parseWorktreeChanges(value: unknown) {
  const source = object(value, 'working copy changes');
  if (!Array.isArray(source.commits) || !Array.isArray(source.files)) throw new Error('Malformed working copy changes');
  const paths = (value: unknown): string[] => {
    if (!Array.isArray(value) || value.some(path => typeof path !== 'string')) throw new Error('Malformed working copy paths');
    return value as string[];
  };
  return {
    branch: text(source.branch, 'branch'),
    headSha: text(source.headSha, 'head revision'),
    baseSha: text(source.baseSha, 'base revision'),
    baseLabel: text(source.baseLabel, 'comparison base'),
    scope: oneOf(source.scope, ['ALL', 'UNCOMMITTED', 'COMMITTED', 'COMMIT'] as const, 'change scope'),
    selectedCommit: optionalText(source.selectedCommit),
    sourceFingerprint: optionalText(source.sourceFingerprint),
    commits: source.commits.map(value => {
      const commit = object(value, 'commit');
      return { sha: text(commit.sha, 'commit revision'), parentSha: optionalText(commit.parentSha),
        message: optionalText(commit.message), author: optionalText(commit.author), authoredAt: optionalText(commit.authoredAt) };
    }),
    files: source.files.map(value => {
      const file = object(value, 'changed file');
      return { filename: text(file.filename, 'file path'), status: text(file.status, 'file status'),
        additions: count(file.additions, 'added lines'), deletions: count(file.deletions, 'deleted lines'), patch: optionalText(file.patch) };
    }),
    staged: paths(source.staged), unstaged: paths(source.unstaged), untracked: paths(source.untracked),
    truncated: source.truncated === true,
  };
}
