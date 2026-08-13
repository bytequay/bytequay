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

/**
 * Whether a run belongs to the greenfield flow, from the id alone.
 *
 * The id carries its own domain, so a deep link into a run resolves before any
 * list has loaded. Runs started before the cutover keep the retired path and
 * are read, resumed and closed there — nothing translates between the two.
 */
export const FLOW_RUN_PREFIX = 'upstream-sync-run:';

export function isFlowRun(jobId: string): boolean {
  return jobId.startsWith(FLOW_RUN_PREFIX);
}

export const isLiveSync = (job: UpstreamCherryPickJobDto): boolean =>
  job.closedAt === null && (job.status === 'QUEUED' || job.status === 'RUNNING');

/** A closed run is terminal whatever its status said when the user closed it. */
export const isClosedSync = (job: UpstreamCherryPickJobDto): boolean =>
  job.closedAt !== null;

/** The label the run carries everywhere — sidebar, Today, and its own header. */
export function syncTitle(job: UpstreamCherryPickJobDto): string {
  return `Sync run — ${job.resultBranch}`;
}

export function syncPhase(job: UpstreamCherryPickJobDto): string {
  if (job.closedAt !== null) return 'CLOSED';
  if (job.status === 'FAILED') return 'FAILED';
  if (job.status === 'PAUSED_CONFLICT') return 'PARKED';
  if (job.status === 'COMPLETED') return 'COMPLETE';
  return job.pauseRequested ? 'PAUSING' : 'PICKING';
}

/**
 * The three phases a run moves through. Phase 2 has no data source until the
 * range is pushed and CI Autofix takes the pull request over, so a run that has
 * not pushed reports phase 1 and a pushed one reports at least phase 2.
 */
export type SyncPhase = 1 | 2 | 3;

export function syncPhaseNumber(job: UpstreamCherryPickJobDto): SyncPhase {
  if (job.prResult !== null) return 3;
  if (job.prNumber !== null) return 2;
  return 1;
}

/** The status chip on a run card: what it says, and in which tone. */
export type SyncChip = {
  label: string;
  tone: 'picking' | 'parked' | 'failed' | 'closed' | 'done';
  /** True while work is actually moving, so the dot pulses. */
  live: boolean;
};

export function syncChip(job: UpstreamCherryPickJobDto): SyncChip {
  if (job.closedAt !== null) {
    return { label: 'CLOSED', tone: 'closed', live: false };
  }
  if (job.status === 'FAILED') {
    return { label: 'STOPPED · NEEDS YOU', tone: 'failed', live: false };
  }
  if (job.status === 'PAUSED_CONFLICT') {
    return { label: 'PARKED FOR YOUR REVIEW', tone: 'parked', live: false };
  }
  if (job.status === 'COMPLETED') {
    // The range is done. Whether that means "yours to review" or "watching CI"
    // is phase 2's business, and phase 2 has no state to read yet — so this
    // says only what is certainly true.
    return job.prNumber === null
      ? { label: 'RANGE COMPLETE', tone: 'done', live: false }
      : { label: 'PARKED FOR YOUR REVIEW', tone: 'parked', live: false };
  }
  return {
    label: job.pauseRequested ? 'PHASE 1 · PAUSING' : 'PHASE 1 · PICKING',
    tone: 'picking',
    live: !job.pauseRequested,
  };
}

/** The phase label beside the progress bar on a run card. */
export function syncPhaseLabel(job: UpstreamCherryPickJobDto): string {
  switch (syncPhaseNumber(job)) {
    case 3: return 'Cleanup';
    // A run that reports its rounds names the one it is on; one that does not
    // says only "CI harness" rather than implying a first round.
    case 2: return job.roundCount === undefined || job.roundCount === 0
      ? 'CI harness' : `CI harness · round ${job.roundCount}`;
    default: return 'Local cherry-picks';
  }
}

/** The one-line detail under a run card's progress bar. */
export function syncDetailLine(job: UpstreamCherryPickJobDto): string {
  const queue = `${job.appliedCount - job.conflictedCount} clean · ${
    job.conflictedCount} carried · ${job.skippedCount} skipped`;
  if (job.status === 'FAILED' && job.errorMessage !== null) return job.errorMessage;
  if (job.status === 'PAUSED_CONFLICT' && job.errorMessage !== null) {
    return job.errorMessage;
  }
  // Once the range is pushed the picks are settled history, and what the card
  // is really reporting is how CI is going.
  if (job.roundCount !== undefined && job.roundCount > 0) {
    return `${job.appliedCount} picks settled · ${job.roundCount} fix round${
      job.roundCount === 1 ? '' : 's'}`;
  }
  return queue;
}

/** How a finished run ended, for the finished list's RESULT column. */
export type SyncResult = {
  label: string;
  tone: 'merged' | 'closed' | 'done' | 'failed';
};

export function syncResult(job: UpstreamCherryPickJobDto): SyncResult {
  if (job.prResult === 'merged') return { label: 'Merged', tone: 'merged' };
  if (job.prResult === 'closed') return { label: 'Closed', tone: 'closed' };
  if (job.status === 'FAILED') return { label: 'Stopped', tone: 'failed' };
  return { label: 'Closed', tone: 'closed' };
}

/**
 * The two lists the home page renders: what is live, and what is over.
 *
 * Running keeps the order it arrived in, which is newest-created first. Finished
 * is re-sorted by when it actually finished, because that is the column it shows
 * and the two orders genuinely differ — a long run started on Monday can finish
 * after a short one started on Thursday.
 */
export function splitSyncRuns(runs: UpstreamCherryPickJobDto[]): {
  running: UpstreamCherryPickJobDto[];
  finished: UpstreamCherryPickJobDto[];
} {
  return {
    running: runs.filter(job => job.closedAt === null),
    finished: runs.filter(job => job.closedAt !== null)
      .slice()
      .sort((left, right) => finishedAt(right) - finishedAt(left)),
  };
}

/** An unparseable timestamp sorts last rather than throwing the whole list. */
function finishedAt(job: UpstreamCherryPickJobDto): number {
  const parsed = Date.parse(job.closedAt ?? job.updatedAt);
  return Number.isFinite(parsed) ? parsed : 0;
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

/**
 * When the range itself finished, which is what phase 1's duration measures.
 * The run's `updatedAt` keeps moving for as long as anything touches the run, so
 * on a pushed run it reports the whole run rather than the picking.
 *
 * @return null when the range never completed — a run still picking, or one that
 *         stopped, has no phase-1 end yet.
 */
export function phaseOneEndedAt(
  events: UpstreamCherryPickEventDto[],
): string | null {
  return events.find(event => event.kind === 'done')?.at ?? null;
}

/**
 * The run's conversation, grouped the way it reads rather than the way it was
 * written. A flat log of a 151-commit range is a wall; what a reader wants is
 * the picking folded into one line, the agent's reasoning as prose, and the
 * program's steps behind a chip they can open.
 */
export type SyncFeedItem =
  /** Every pick, folded into one openable group. */
  | { key: string; kind: 'picks'; events: UpstreamCherryPickEventDto[] }
  /** Consecutive program steps — probes, notes — behind one chip. */
  | { key: string; kind: 'activity'; title: string; lines: UpstreamCherryPickEventDto[] }
  /** One agent turn's prose, with its transcript if the run kept one. */
  | { key: string; kind: 'agent'; event: UpstreamCherryPickEventDto;
      transcript: UpstreamCherryPickEventDto | null }
  /** Something the user said to the run. */
  | { key: string; kind: 'guidance'; event: UpstreamCherryPickEventDto }
  /** A moment worth its own line: pushed, opened, failed, finished, closed. */
  | { key: string; kind: 'moment'; event: UpstreamCherryPickEventDto };

/** Kinds that read as one program step rather than a moment of their own. */
const ACTIVITY_KINDS = new Set(['note', 'watch', 'command', 'skip']);

export function syncFeed(events: UpstreamCherryPickEventDto[]): SyncFeedItem[] {
  const picks = events.filter(event => event.pickIndex !== null);
  const runLevel = events.filter(event => event.pickIndex === null);
  const items: SyncFeedItem[] = [];
  if (picks.length > 0) {
    items.push({ key: 'picks', kind: 'picks', events: picks });
  }
  for (let index = 0; index < runLevel.length; index++) {
    const event = runLevel[index];
    // A transcript belongs to the agent turn above it, not to the stream.
    if (event.kind === 'agent_log') continue;
    if (event.kind === 'agent') {
      const next = runLevel[index + 1];
      items.push({
        key: event.id,
        kind: 'agent',
        event,
        transcript: next !== undefined && next.kind === 'agent_log' ? next : null,
      });
      continue;
    }
    if (event.kind === 'guidance') {
      items.push({ key: event.id, kind: 'guidance', event });
      continue;
    }
    if (!ACTIVITY_KINDS.has(event.kind)) {
      items.push({ key: event.id, kind: 'moment', event });
      continue;
    }
    const open = items.at(-1);
    if (open !== undefined && open.kind === 'activity') {
      open.lines.push(event);
      continue;
    }
    items.push({ key: event.id, kind: 'activity', title: event.title, lines: [event] });
  }
  return items;
}

/** What a run is asking of the reader, once it has stopped asking of itself. */
export type SyncDecision = {
  title: string;
  body: string;
  tone: 'parked' | 'failed' | 'done' | 'closed';
};

/** @return null while the run is still working — there is nothing to decide. */
export function syncDecision(job: UpstreamCherryPickJobDto): SyncDecision | null {
  if (job.closedAt !== null) {
    return {
      title: 'Run closed',
      body: 'The worktree is gone. The branch and this log are kept.',
      tone: 'closed',
    };
  }
  if (job.status === 'FAILED') {
    return {
      title: 'Stopped',
      body: job.errorMessage ?? 'The run stopped. Durable progress is kept.',
      tone: 'failed',
    };
  }
  if (job.status === 'PAUSED_CONFLICT') {
    return {
      title: 'Parked for your review',
      body: job.errorMessage
        ?? 'Nothing is pushed until you resume. Take over in the worktree, or reply below to steer.',
      tone: 'parked',
    };
  }
  if (job.status === 'COMPLETED') {
    return job.prNumber === null
      ? {
        title: `Range complete — ${job.appliedCount} picked`,
        body: `Everything landed on ${job.resultBranch}. Nothing was pushed.`,
        tone: 'done',
      }
      : {
        title: 'Range complete — parked for your review',
        body: `Draft PR #${job.prNumber} is open. Review it, or reply below to steer.`,
        tone: 'parked',
      };
  }
  return null;
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

/** What the run is doing right now, in words rather than a spinner. */
export function syncNowLine(
  job: UpstreamCherryPickJobDto,
  queue: SyncQueue,
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
    return job.prNumber === null
      ? `Range complete — ${job.appliedCount} picked on ${job.resultBranch}`
      : `Range complete — draft PR #${job.prNumber} parked for your review`;
  }
  if (job.pauseRequested) return 'Pausing — stopping after the pick in flight';
  if (queue.current === null) return 'Opening the pull request and finishing the run';
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
