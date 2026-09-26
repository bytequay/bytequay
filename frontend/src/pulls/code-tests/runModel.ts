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
import type { AgentTaskDto } from '../../types';
import type { AgentReviewDto } from '../agent-review/contracts';
import { latestRun } from '../agent-review/model';
import type { LocalRunDto, LocalRunMethodDto, LocalRunStatus, ReviewCodeFilesDto } from './contracts';

const STATUS_LABELS: Record<LocalRunStatus, string> = {
  RUNNING: 'Running',
  PASSED: 'Passed',
  FAILED_ASSERTION: 'Failed assertion',
  BUILD_FAILED: 'Build failed',
  NO_TESTS_RAN: 'No tests ran',
  STOPPED: 'Stopped',
  TIMED_OUT: 'Timed out after 60 min',
  RESULTS_UNAVAILABLE: 'Results unavailable',
  INTERRUPTED: 'Disconnected',
};

export function statusLabel(status: LocalRunStatus) {
  return STATUS_LABELS[status];
}

export type StatusTone = 'pass' | 'fail' | 'running' | 'neutral';

export function statusTone(status: LocalRunStatus): StatusTone {
  if (status === 'PASSED') return 'pass';
  if (status === 'FAILED_ASSERTION' || status === 'BUILD_FAILED') return 'fail';
  return status === 'RUNNING' ? 'running' : 'neutral';
}

export function simpleClassName(testClass: string | null) {
  return testClass === null ? null : testClass.slice(testClass.lastIndexOf('.') + 1);
}

// Maven options whose value is the next word, so it is not mistaken for a goal.
const MAVEN_VALUE_OPTIONS = new Set(['-pl', '--projects', '-f', '--file', '-s', '--settings', '-gs', '--global-settings', '-t', '--toolchains',
  '-T', '--threads', '-rf', '--resume-from', '-P', '--activate-profiles', '-b', '--builder', '-l', '--log-file']);

/**
 * A short name for a command run: its test selectors, or the module and goals of its Maven invocation. An agent's command is a whole
 * script (`cd`, redirections, `tail`), so only the Maven words count.
 */
export function commandLabel(command: string) {
  const segments = command.split(/&&|\|\||[;|\n]/);
  const words: string[] = (segments.map((segment): string[] => segment.match(/'[^']*'|"[^"]*"|\S+/g) ?? [])
    .find(tokens => tokens.some(token => /(^|\/)mvnw?$/.test(token))) ?? [])
    .map(token => token.replace(/^(['"])(.*)\1$/, '$2'));
  const maven = words.findIndex(word => /(^|\/)mvnw?$/.test(word));
  if (maven < 0) return command.trim().split(/\s+/)[0] ?? 'command';
  const selectors: string[] = [];
  const modules: string[] = [];
  const goals: string[] = [];
  for (let index = maven + 1; index < words.length; index++) {
    const word = words[index];
    if (/^\d*[<>]/.test(word)) break;
    if (word.startsWith('-Dtest=')) {
      selectors.push(...word.slice('-Dtest='.length).split(',').filter(Boolean).map(selector => selector.replace(/^[\w$.]*\.(?=[^.]*$)/, '')));
    }
    else if (MAVEN_VALUE_OPTIONS.has(word)) {
      if (word === '-pl' || word === '--projects') modules.push(...(words[index + 1] ?? '').split(',').filter(Boolean).map(module => module.slice(module.lastIndexOf('/') + 1)));
      index++;
    }
    else if (!word.startsWith('-')) {
      goals.push(word);
    }
  }
  const shownGoals = goals.length === 1 && goals[0] === 'test' ? [] : goals;
  const subject = selectors.length > 0 ? selectors.join(', ') : modules.join(', ');
  return [subject, shownGoals.join(' ')].filter(Boolean).join(' · ') || 'mvnw';
}

export function runLabel(run: LocalRunDto) {
  const name = run.testMethod ?? simpleClassName(run.testClass) ?? commandLabel(run.command);
  return `${name} #${run.ordinal}`;
}

export function scopeText(run: LocalRunDto) {
  if (run.scope === 'COMMAND') return 'command';
  return run.scope === 'METHOD' ? `method ${run.testClass}#${run.testMethod}` : `class ${run.testClass}`;
}

export function methodRows(run: LocalRunDto): LocalRunMethodDto[] {
  return run.results.flatMap(result => result.methods);
}

export function failedRows(run: LocalRunDto) {
  return methodRows(run).filter(isFailedRow);
}

function isFailedRow(row: LocalRunMethodDto) {
  return row.status === 'FAILED' || row.status === 'ERROR';
}

/** The result tree the run panel draws: every class, or only failed and errored methods under the classes that have them. */
export function shownResults(run: LocalRunDto, failedOnly: boolean): LocalRunDto['results'] {
  if (!failedOnly) return run.results;
  return run.results.map(result => ({ ...result, methods: result.methods.filter(isFailedRow) })).filter(result => result.methods.length > 0);
}

/** The one-line result a tab or toolbar shows; an unfinished or unverified run never reads as a success. */
export function runSummary(run: LocalRunDto, now: number): string {
  if (run.status === 'RUNNING') {
    const name = run.testMethod === null ? simpleClassName(run.testClass) ?? 'command' : `${run.testMethod}()`;
    return `Running ${name} · ${elapsedSeconds(run.startedAt, now)}s`;
  }
  if (run.scope === 'COMMAND' && run.results.length === 0) return 'Command result';
  const failed = failedRows(run).length;
  if (failed > 0) return `${failed} failed`;
  if (run.status === 'PASSED') return `${methodRows(run).filter(row => row.status === 'PASSED').length} passed`;
  return statusLabel(run.status);
}

export function runMeta(run: LocalRunDto, now: number): string {
  const by = run.initiator === 'USER' ? 'You' : 'Review agent';
  const started = new Date(run.startedAt).toLocaleTimeString();
  const duration = run.endedAt === null ? `${elapsedSeconds(run.startedAt, now)}s` : formatDuration(Date.parse(run.endedAt) - Date.parse(run.startedAt));
  const rows = methodRows(run).length;
  return rows > 0
    ? `${rows} ${rows === 1 ? 'test' : 'tests'} · ${duration} · ${by} · ${started}`
    : `exit ${run.exitCode ?? '—'} · ${duration} · ${by} · ${started}`;
}

export function elapsedSeconds(startedAt: string, now: number) {
  return Math.max(0, Math.floor((now - Date.parse(startedAt)) / 1000));
}

export function formatDuration(milliseconds: number | null) {
  if (milliseconds === null || !Number.isFinite(milliseconds)) return '—';
  if (milliseconds < 1000) return `${(milliseconds / 1000).toFixed(2)}s`;
  if (milliseconds < 60_000) return `${(milliseconds / 1000).toFixed(1)}s`;
  const minutes = Math.floor(milliseconds / 60_000);
  return `${minutes}m ${Math.round((milliseconds % 60_000) / 1000)}s`;
}

/** Whether the working copy changed since the run took its snapshot; only comparable while the working copy exists. */
export function runIsStale(run: LocalRunDto, files: ReviewCodeFilesDto | null) {
  return files !== null && files.sourceFingerprint !== null && run.sourceFingerprint !== null && files.sourceFingerprint !== run.sourceFingerprint;
}

/** `hidden` removes run controls (no working copy or an ended review); `blocked` disables them with a reason. */
export type RunGate = { hidden: boolean; blocked: string | null };

/**
 * Whether a run can start now, following the availability table: the backend refuses the same cases, so this only
 * explains a disabled run control before the click.
 */
export function runGate(review: AgentReviewDto, files: ReviewCodeFilesDto | null, runs: readonly LocalRunDto[]): RunGate {
  if (review.state !== 'OPEN' || files === null || files.source !== 'WORKING_COPY') return { hidden: true, blocked: null };
  const claim = review.task.mutationClaim;
  const turnRunning = review.activeTurn !== null && (review.activeTurn.state === 'RUNNING' || review.activeTurn.state === 'ACCEPTED');
  if (claim === 'LOCAL_RUN' || runs.some(run => run.status === 'RUNNING' || run.treeUnverified)) {
    return { hidden: false, blocked: 'A run is using this working copy' };
  }
  if (claim === 'RECOVERY' || (claim === 'TURN' && !turnRunning)) {
    return { hidden: false, blocked: 'The working copy needs recovery — see Summary' };
  }
  if (claim === 'COMPLETION') return { hidden: false, blocked: 'The working copy is being updated' };
  const reviewRun = latestRun(review);
  if (review.activeTurn !== null || (reviewRun?.state === 'RUNNING' && !review.followUpPaused)) {
    return { hidden: false, blocked: 'The review agent is working' };
  }
  if (review.queuedInputIds.length > 0) return { hidden: false, blocked: 'Remove or resume the queued question' };
  return { hidden: false, blocked: null };
}

/** The same gate for a development task's working copy, which only exists while the task keeps its worktree. */
export function taskRunGate(task: AgentTaskDto, files: ReviewCodeFilesDto | null, runs: readonly LocalRunDto[]): RunGate {
  if (task.interactive === false || task.state !== 'OPEN' || task.checkoutRemovedAt !== null || files === null) return { hidden: true, blocked: null };
  const claim = task.mutationClaim?.kind ?? null;
  if (claim === 'LOCAL_RUN' || runs.some(run => run.status === 'RUNNING' || run.treeUnverified)) {
    return { hidden: false, blocked: 'A run is using this working copy' };
  }
  if (claim === 'TURN') return { hidden: false, blocked: 'The task\'s agent is working' };
  if (claim === 'RECOVERY') return { hidden: false, blocked: 'The task needs recovery — see the task window' };
  if (claim === 'COMPLETION') return { hidden: false, blocked: 'The working copy is being updated' };
  if (task.developmentContinuationUnfinished) return { hidden: false, blocked: 'Development automation is working' };
  return { hidden: false, blocked: null };
}

export type LogTone = 'info' | 'error' | 'warning' | 'frame' | 'fail' | 'pass' | 'note' | 'plain';

export function logTone(line: string): LogTone {
  if (line.includes('[ERROR]')) return 'error';
  if (line.includes('[WARNING]')) return 'warning';
  if (/\b(FAILED|FAILURE|AssertionFailedError)\b/.test(line) || /^\s*(expected|but was)/.test(line)) return 'fail';
  if (/\b(PASSED|BUILD SUCCESS)\b/.test(line)) return 'pass';
  if (/^\s+at /.test(line)) return 'frame';
  if (line.includes('[INFO]')) return 'info';
  return /truncated|omitted/i.test(line) ? 'note' : 'plain';
}

/** The newest outcome for a test class, or one of its methods, among runs that reported rows. */
export function lastOutcome(runs: readonly LocalRunDto[], className: string, method: string | null): { tone: 'pass' | 'fail'; ordinal: number } | null {
  for (const run of runs) {
    const rows = run.results.filter(result => simpleClassName(result.className) === className).flatMap(result => result.methods);
    const relevant = method === null ? rows : rows.filter(row => row.name === method);
    if (relevant.length === 0) continue;
    const failed = relevant.some(row => row.status === 'FAILED' || row.status === 'ERROR');
    return { tone: failed ? 'fail' : 'pass', ordinal: run.ordinal };
  }
  return null;
}

/** Whether the live run is this class (method null) or this method of the test source at `path`. */
export function isRunning(runs: readonly LocalRunDto[], path: string, method: string | null) {
  return runs.some(run => run.status === 'RUNNING' && run.path === path
    && (method === null ? run.scope === 'CLASS' : run.scope === 'METHOD' && run.testMethod === method));
}
