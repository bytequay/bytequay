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
import type {
  CiHarnessPhase,
  CiHarnessWatchSnapshotDto,
  UpstreamCherryPickCommitDto,
  UpstreamCherryPickEventDto,
  UpstreamCherryPickJobDto,
} from './workspaceApi';

/** Consecutive log lines that belong to the same pick, in run order. */
export type SyncLogGroup = {
  key: string;
  /** null for run-level lines (push, PR opened, parked, failed). */
  pickIndex: number | null;
  events: UpstreamCherryPickEventDto[];
};

export type SyncQueue = {
  done: UpstreamCherryPickCommitDto[];
  current: UpstreamCherryPickCommitDto | null;
  next: UpstreamCherryPickCommitDto[];
  /** Waiting commits beyond the visible window. */
  moreCount: number;
  last: UpstreamCherryPickCommitDto | null;
  cleanCount: number;
  carriedCount: number;
};

export const isLiveSync = (job: UpstreamCherryPickJobDto): boolean =>
  job.closedAt === null && (job.status === 'QUEUED' || job.status === 'RUNNING');

/** A closed run is terminal whatever its status said when the user closed it. */
export const isClosedSync = (job: UpstreamCherryPickJobDto): boolean =>
  job.closedAt !== null;

/**
 * Phase 2 keeps moving long after phase 1 says COMPLETED — the harness watch
 * is what runs it. Without this the cockpit stops polling the moment the last
 * pick lands and a run that is still working reads as finished.
 */
export const isWatchingSync = (job: UpstreamCherryPickJobDto): boolean =>
  job.closedAt === null && job.harnessWatchId !== null;

/** The label the run carries everywhere — sidebar, Today, and its own header. */
export function syncTitle(job: UpstreamCherryPickJobDto): string {
  return `Sync run — ${job.resultBranch}`;
}

/**
 * Phase 1 picks and pushes; phase 2 is the harness driving the pull request
 * green. The watch is what marks the boundary, so the pill follows it.
 */
export function syncPhase(job: UpstreamCherryPickJobDto): string {
  if (job.closedAt !== null) return 'CLOSED';
  if (job.status === 'FAILED') return 'FAILED';
  if (job.status === 'PAUSED_CONFLICT') return 'PHASE 1 · PARKED';
  if (job.harnessWatchId !== null) {
    return job.status === 'COMPLETED' ? 'PHASE 2 · CI HARNESS' : 'PHASE 1 · PICKING';
  }
  if (job.status === 'COMPLETED') return 'PHASE 1 · COMPLETE';
  return job.pauseRequested ? 'PHASE 1 · PAUSING' : 'PHASE 1 · PICKING';
}

export function syncProgress(job: UpstreamCherryPickJobDto): {
  done: number;
  total: number;
  percent: number;
} {
  const total = Math.max(job.requestedCount, 0);
  // A skipped commit is off the queue for good, so it counts as settled — the
  // bar, the label, and the DONE list all have to agree on that or the run
  // reads as further behind than it is.
  const done = Math.min(job.appliedCount + job.skippedCount, total);
  return {
    done,
    total,
    percent: total === 0 ? 0 : Math.round((done / total) * 100),
  };
}

/**
 * The queue the left column renders. "done" keeps its natural run order so a
 * reviewer reads the series the way git wrote it; "next" is windowed because a
 * range can be hundreds of commits long.
 */
export function syncQueue(
  commits: UpstreamCherryPickCommitDto[],
  nextWindow = 10,
): SyncQueue {
  const done = commits.filter(commit => commit.state === 'applied'
    || commit.state === 'conflicted' || commit.state === 'skipped');
  const waiting = commits.filter(commit => commit.state === 'waiting');
  return {
    done,
    current: commits.find(commit => commit.state === 'current') ?? null,
    next: waiting.slice(0, nextWindow),
    moreCount: Math.max(0, waiting.length - nextWindow),
    last: commits.at(-1) ?? null,
    cleanCount: commits.filter(commit => commit.state === 'applied').length,
    carriedCount: commits.filter(commit => commit.state === 'conflicted').length,
  };
}

/** Groups the log by the pick each line belongs to, preserving run order. */
export function syncLogGroups(events: UpstreamCherryPickEventDto[]): SyncLogGroup[] {
  const groups: SyncLogGroup[] = [];
  for (const event of events) {
    const pickIndex = event.pickIndex ?? null;
    const open = groups.at(-1);
    if (open !== undefined && open.pickIndex === pickIndex) {
      open.events.push(event);
      continue;
    }
    groups.push({ key: event.id, pickIndex, events: [event] });
  }
  return groups;
}

/**
 * The fixup each repaired pick produced, by pick index. A pick whose repair
 * was a no-op has none, and the oldest of a very long run age out of the
 * event window — both read as "no fixup to name" rather than a wrong one.
 */
export function fixupsByPick(
  events: UpstreamCherryPickEventDto[],
): Map<number, string> {
  const byPick = new Map<number, string>();
  for (const event of events) {
    if (event.kind === 'fixup' && event.pickIndex !== null) {
      byPick.set(event.pickIndex, event.title);
    }
  }
  return byPick;
}

/** What phase 2 is doing, for a reader who only wants to know if it is alive. */
export type HarnessLine = {
  label: string;
  detail: string | null;
  tone: 'live' | 'wait' | 'green' | 'attention';
  /** When the harness last finished looking at CI, epoch ms. */
  checkedAtMs: number | null;
};

const PHASE_WORK: Record<CiHarnessPhase, string> = {
  probe: 'Reading the latest CI checks',
  parse: 'Reading the failed job logs',
  classify: 'Sorting what is worth fixing',
  fix: 'Agent fixing the failures',
  verify: 'Verifying the fix',
  commit: 'Pushing the round',
  rebase: 'Rebasing onto the target branch',
  done: 'Finishing the round',
};

export function harnessLine(snapshot: CiHarnessWatchSnapshotDto): HarnessLine {
  const newest = snapshot.cycles.at(0) ?? null;
  const base = {
    detail: snapshot.runStatusTail,
    checkedAtMs: newest === null ? null : newest.finishedAtMs ?? newest.startedAtMs,
  };
  switch (snapshot.status) {
    case 'bootstrap':
      return { ...base, label: 'Setting up — reading how this repo runs CI', tone: 'wait' };
    case 'running':
      return {
        ...base,
        label: PHASE_WORK[snapshot.activeCycle?.phase ?? 'probe'],
        tone: 'live',
      };
    case 'watching':
      return { ...base, label: 'Waiting for CI to finish', tone: 'wait' };
    case 'handoff':
      return { ...base, label: 'Waiting for the fix to reach the remote', tone: 'wait' };
    case 'needs_attention':
      // `reason` is the machine code ("needs_attention"); `detail` is the
      // sentence saying what actually stopped it, which is the whole point.
      return {
        ...base,
        label: 'Stopped — nothing runs until you restart it',
        detail: snapshot.handoff?.detail ?? snapshot.runStatusTail,
        tone: 'attention',
      };
    case 'green':
      return { ...base, label: 'All checks green — yours to merge', tone: 'green' };
    default:
      return { ...base, label: 'Watch stopped', tone: 'attention' };
  }
}

/** What the run is doing right now, in words rather than a spinner. */
export function syncNowLine(
  job: UpstreamCherryPickJobDto,
  queue: SyncQueue,
  harness?: CiHarnessWatchSnapshotDto | null,
): string {
  if (job.closedAt !== null) {
    return 'Run closed — the worktree was removed; the branch and this log are kept';
  }
  if (job.status === 'FAILED') return job.errorMessage ?? 'Run failed';
  if (job.status === 'PAUSED_CONFLICT') {
    return job.pauseRequested || job.conflictPaths.length === 0
      ? 'Parked — nothing is pushed until you resume'
      : `Parked on a conflict git cannot finish — ${job.conflictPaths.length} file${
        job.conflictPaths.length === 1 ? '' : 's'}`;
  }
  if (job.status === 'COMPLETED') {
    // The range being picked is not the run being over — phase 2 takes it from
    // here, and saying "parked for your review" while it works is a lie.
    if (harness !== undefined && harness !== null) {
      return `Phase 2 — ${harnessLine(harness).label.toLowerCase()}`;
    }
    return job.prNumber === null
      ? `Range complete — ${job.appliedCount} picked on ${job.resultBranch}`
      : `Range complete — draft PR #${job.prNumber} parked for your review`;
  }
  if (job.pauseRequested) return 'Pausing — stopping after the pick in flight';
  if (queue.current === null) return 'Opening the pull request and starting the watch';
  return `Picking ${queue.current.subject} — pick ${queue.current.index + 1} of ${
    job.requestedCount}`;
}

export function elapsedLabel(fromIso: string, toIso?: string): string {
  const from = Date.parse(fromIso);
  const to = toIso === undefined ? Date.now() : Date.parse(toIso);
  if (!Number.isFinite(from) || !Number.isFinite(to)) return '';
  const seconds = Math.max(0, Math.round((to - from) / 1000));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m`;
  return `${seconds}s`;
}

/** Command durations read like a terminal: sub-minute in seconds. */
export function durationLabel(milliseconds: number | null): string {
  if (milliseconds === null || !Number.isFinite(milliseconds)) return '';
  if (milliseconds < 10_000) return `${(milliseconds / 1000).toFixed(1)}s`;
  if (milliseconds < 60_000) return `${Math.round(milliseconds / 1000)}s`;
  const minutes = Math.floor(milliseconds / 60_000);
  const seconds = Math.round((milliseconds % 60_000) / 1000);
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}

export function clockLabel(iso: string): string {
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) return '';
  return new Date(parsed).toLocaleTimeString(undefined, {
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
}

export function money(milliUsd: number): string {
  return `$${(milliUsd / 1000).toFixed(2)}`;
}

/**
 * One line of an agent turn as a reader wants it: what the agent said, what it
 * ran, and how the turn ended. The stored transcript is the CLI's raw JSONL,
 * where a single tool call can carry an entire pom.xml — rendering that
 * verbatim buries the two sentences that actually explain the decision.
 */
export type TranscriptEntry =
  | { kind: 'say'; text: string }
  /** `summary` is the readable one-liner; `full` is everything, for the expand. */
  | { kind: 'tool'; name: string; summary: string; full: string }
  | { kind: 'result'; failed: boolean; costUsdMilli: number; turns: number };

/** Tool arguments are unbounded; a command's first line is the readable part. */
const MAX_TOOL_SUMMARY = 200;

export function parseTranscript(raw: string | null): TranscriptEntry[] {
  if (raw === null || raw.trim().length === 0) return [];
  const entries: TranscriptEntry[] = [];
  for (const line of raw.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('{')) continue;
    try {
      entries.push(...transcriptEntries(JSON.parse(trimmed)));
    }
    catch {
      // A truncated tail is expected — the transcript is capped at 64KB.
      continue;
    }
  }
  return entries;
}

/**
 * The same reading, for one already-parsed event. The live stream delivers
 * events one at a time; the stored transcript is the same events as text.
 */
export function transcriptEntries(value: unknown): TranscriptEntry[] {
  const entries: TranscriptEntry[] = [];
  if (value === null || typeof value !== 'object') return entries;
  {
    const event = value as Record<string, unknown>;
    if (event.type === 'assistant') {
      const message = event.message as { content?: unknown[] } | undefined;
      for (const part of message?.content ?? []) {
        const block = part as { type?: string; text?: string; name?: string; input?: unknown };
        if (block.type === 'text' && (block.text ?? '').trim().length > 0) {
          entries.push({ kind: 'say', text: (block.text ?? '').trim() });
        }
        if (block.type === 'tool_use') {
          const full = toolFull(block.input);
          entries.push({
            kind: 'tool',
            name: block.name ?? 'tool',
            summary: toolSummary(full),
            full,
          });
        }
      }
    }
    if (event.type === 'result') {
      entries.push({
        kind: 'result',
        failed: event.is_error === true,
        costUsdMilli: Math.round(Number(event.total_cost_usd ?? 0) * 1000),
        turns: Number(event.num_turns ?? 0),
      });
    }
  }
  return entries;
}

function toolFull(input: unknown): string {
  if (input === null || typeof input !== 'object') return '';
  const fields = input as Record<string, unknown>;
  // A command, a path, or whatever single field reads as the subject.
  const subject = fields.command ?? fields.file_path ?? fields.pattern ?? fields.path;
  return typeof subject === 'string' ? subject : JSON.stringify(fields, null, 2);
}

/**
 * Every command the agent runs in a worktree starts by cd-ing into it, and the
 * worktree path is ~120 characters of app data directory. Left in, the prefix
 * is the only thing that fits on the line and every row looks identical.
 */
const WORKTREE_CD = /^cd\s+(?:"[^"]*"|'[^']*'|\S+)\s*&&\s*/;

function toolSummary(full: string): string {
  const firstLine = full.split('\n').find(part => part.trim().length > 0) ?? '';
  const withoutCd = firstLine.replace(WORKTREE_CD, '');
  return withoutCd.length <= MAX_TOOL_SUMMARY
    ? withoutCd
    : `${withoutCd.slice(0, MAX_TOOL_SUMMARY)}…`;
}

/**
 * Where Claude Code keeps this session's own transcript. It escapes the working
 * directory into the file name, so the path is derivable — which is the only
 * way to watch the run from a terminal, since `--resume` continues a
 * conversation rather than attaching to a running one.
 */
export function sessionTranscriptPath(
  worktreePath: string | null,
  sessionId: string | null,
): string | null {
  if (worktreePath === null || sessionId === null) return null;
  const escaped = worktreePath.replace(/[/.]/g, '-');
  return `~/.claude/projects/${escaped}/${sessionId}.jsonl`;
}
