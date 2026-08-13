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
import { useState, type ReactNode } from 'react';
import { CheckIcon, ChevronIcon, PauseIcon, TerminalIcon } from './WorkspaceSyncIcons';
import { SyncExcusedCheck, SyncFixupAttribution } from './WorkspaceSyncEvidence';
import { SyncFeedRounds } from './WorkspaceSyncRounds';
import WorkspaceSyncRunLog, { TranscriptTool } from './WorkspaceSyncRunLog';
import {
  clockLabel, durationLabel, elapsedLabel, parseTranscript, phaseOneEndedAt,
  syncDecision, syncFeed, syncPhaseNumber, syncQueue, type SyncFeedItem,
} from './syncRunModel';
import type {
  SyncCompileProofDto,
  SyncFixupDto,
  SyncRoundDto,
  UpstreamCherryPickCommitDto,
  UpstreamCherryPickEventDto,
  UpstreamCherryPickJobDto,
} from './workspaceApi';

/** Picks listed inside the folded phase-1 group before "View all". */
const PICK_ROWS = 3;

/**
 * The run's conversation: what it did, what the agent decided, and what it is
 * asking of you.
 *
 * The old view was one flat command log, which on a long range buried the two
 * sentences that explain a decision under a few hundred git invocations. This
 * reads as a conversation instead — the picking folded to a line, program steps
 * behind chips, the agent's reasoning as prose.
 */
export default function WorkspaceSyncFeed({
  job, commits, events, rounds = [], fixups = [], compileProof, onOpenPr,
}: {
  job: UpstreamCherryPickJobDto;
  commits: UpstreamCherryPickCommitDto[];
  events: UpstreamCherryPickEventDto[];
  /** The CI fix rounds, oldest first. */
  rounds?: SyncRoundDto[];
  /** The repairs, each attributed to the pick it belongs behind. */
  fixups?: SyncFixupDto[];
  /** The only thing that may excuse a red per-commit compile check. */
  compileProof?: SyncCompileProofDto | null;
  onOpenPr?: () => void;
}) {
  const items = syncFeed(events);
  const decision = syncDecision(job);
  // Phase 1 folds to a summary only once the run has left it. While it is still
  // picking, that summary is the whole page and says nothing a reader can use —
  // and it would hide the conflict repairs, which is what someone parked on a
  // conflict came here to read.
  const foldPicks = syncPhaseNumber(job) > 1;

  return (
    <div className="sf-feed">
      {items.length === 0 && (
        <p className="sf-empty">The run has not written anything yet.</p>
      )}
      {items.map(item => (
        <FeedItem key={item.key} item={item} job={job} commits={commits}
          events={events} foldPicks={foldPicks} />
      ))}
      <SyncFeedRounds rounds={rounds} />
      <SyncFixupAttribution fixups={fixups} />
      {compileProof !== undefined && compileProof !== null && (
        <SyncExcusedCheck proof={compileProof} />
      )}
      {decision !== null && (
        <div className={`sf-decision is-${decision.tone}`}>
          <div className="sf-decision__head">
            <span className="sf-decision__glyph" aria-hidden>
              {decision.tone === 'done' ? <CheckIcon size={9} /> : <PauseIcon size={9} />}
            </span>
            <strong>{decision.title}</strong>
            <span className="sf-decision__time">{elapsedLabel(job.updatedAt)} ago</span>
          </div>
          <p>{decision.body}</p>
          {job.prNumber !== null && onOpenPr !== undefined && (
            <div className="sf-decision__actions">
              <button type="button" onClick={onOpenPr}>Open PR #{job.prNumber}</button>
              <span>or reply below to steer</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function FeedItem({ item, job, commits, events, foldPicks }: {
  item: SyncFeedItem;
  job: UpstreamCherryPickJobDto;
  commits: UpstreamCherryPickCommitDto[];
  events: UpstreamCherryPickEventDto[];
  foldPicks: boolean;
}) {
  switch (item.kind) {
    case 'picks':
      return foldPicks
        ? <PickGroup job={job} commits={commits} events={events} />
        // The pick-by-pick conversation: every command with its exit status, the
        // notes between them, and each conflict's repair.
        : <WorkspaceSyncRunLog events={item.events} commits={commits} />;
    case 'activity':
      return <ActivityGroup title={item.title} lines={item.lines} />;
    case 'agent':
      return <AgentTurn event={item.event} transcript={item.transcript} />;
    case 'guidance':
      return (
        <div className="sf-guidance">
          <span>YOU</span>
          <p>{item.event.title}</p>
          <time>{clockLabel(item.event.at)}</time>
        </div>
      );
    default:
      return <Moment event={item.event} />;
  }
}

/** Phase 1, folded to one line. Opening it lists the picks themselves. */
function PickGroup({ job, commits, events }: {
  job: UpstreamCherryPickJobDto;
  commits: UpstreamCherryPickCommitDto[];
  events: UpstreamCherryPickEventDto[];
}) {
  const [open, setOpen] = useState(false);
  const queue = syncQueue(commits);
  const settled = queue.done.length;
  const ended = phaseOneEndedAt(events);
  return (
    <>
      <button type="button" className="sf-group" aria-expanded={open}
        onClick={() => setOpen(current => !current)}>
        <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
          <ChevronIcon size={13} />
        </span>
        <span className="sf-pill">
          <span className="sf-pill__glyph is-done" aria-hidden><CheckIcon size={12} /></span>
          Phase 1 · Local cherry-picks
        </span>
        <span className="sf-group__meta">
          {settled} settled{ended === null ? '' : ` in ${elapsedLabel(job.createdAt, ended)}`}
          {' · '}{queue.cleanCount} clean · {queue.carriedCount} carried
          {' · '}{job.skippedCount} skipped
        </span>
      </button>
      {open && (
        <div className="sf-nested">
          {queue.done.slice(-PICK_ROWS).map(commit => (
            <div className="sf-pick" key={commit.sha}>
              <span className="sf-pick__ordinal">PICK {commit.index + 1}</span>
              <code>{commit.shortSha}</code>
              <span className="sf-pick__subject" title={commit.subject}>
                {commit.subject}
              </span>
              <span className={`sf-pick__state is-${commit.state}`}>
                {pickStateLabel(commit)}
              </span>
            </div>
          ))}
          {commits.length > PICK_ROWS && (
            <span className="sf-nested__all">View all {commits.length} picks</span>
          )}
        </div>
      )}
    </>
  );
}

/** Consecutive program steps behind one chip, with their own times. */
function ActivityGroup({ title, lines }: {
  title: string;
  lines: UpstreamCherryPickEventDto[];
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" className="sf-group is-inline" aria-expanded={open}
        onClick={() => setOpen(current => !current)}>
        <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
          <ChevronIcon size={13} />
        </span>
        <span className="sf-pill">
          <span className="sf-pill__glyph" aria-hidden><TerminalIcon size={12} /></span>
          {title}
        </span>
        <span className="sf-group__meta">
          {lines.length} step{lines.length === 1 ? '' : 's'} · {clockLabel(lines[0].at)}
        </span>
      </button>
      {open && (
        <div className="sf-nested">
          {lines.map(line => (
            <div className="sf-step" key={line.id}>
              <span>{line.title}</span>
              {line.detail !== null && <em>{line.detail}</em>}
              <time>
                {line.durationMs === null
                  ? clockLabel(line.at) : durationLabel(line.durationMs)}
              </time>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

/** One agent turn: its prose, and its transcript one click away. */
function AgentTurn({ event, transcript }: {
  event: UpstreamCherryPickEventDto;
  transcript: UpstreamCherryPickEventDto | null;
}) {
  const [open, setOpen] = useState(false);
  const entries = transcript === null ? [] : parseTranscript(transcript.detail);
  const said = entries.filter(entry => entry.kind === 'say').length;
  const ran = entries.filter(entry => entry.kind === 'tool').length;
  const failed = event.exitCode !== null && event.exitCode !== 0;
  return (
    <div className="sf-agent">
      <div className="sf-agent__head">
        <span className="sf-pill is-agent">
          <span className="sf-pill__glyph is-agent" aria-hidden><SparkIcon /></span>
          Sync agent
        </span>
        <span className="sf-agent__when">updated · {clockLabel(event.at)}</span>
        {failed && <span className="sf-agent__failed">could not settle it</span>}
      </div>
      <p className="sf-agent__prose">{event.title}</p>
      {event.detail !== null && <p className="sf-agent__note">{event.detail}</p>}
      {entries.length > 0 && (
        <>
          <button type="button" className="sf-disclose" aria-expanded={open}
            onClick={() => setOpen(current => !current)}>
            <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
              <ChevronIcon size={13} />
            </span>
            <span className="sf-disclose__glyph" aria-hidden><TerminalIcon size={12} /></span>
            <span>Agent transcript</span>
            <span className="sf-disclose__count">
              {said} note{said === 1 ? '' : 's'} · {ran} command{ran === 1 ? '' : 's'}
            </span>
          </button>
          {open && (
            <div className="sf-transcript">
              {entries.map((entry, index) => {
                if (entry.kind === 'say') {
                  return <p key={index} className="sr-transcript__say">{entry.text}</p>;
                }
                if (entry.kind === 'tool') {
                  return <TranscriptTool key={index} entry={entry} />;
                }
                return (
                  <p key={index}
                    className={`sr-transcript__result${entry.failed ? ' is-failed' : ''}`}>
                    {entry.failed ? 'Turn failed' : 'Turn complete'} · {entry.turns} turns
                  </p>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** A moment worth its own line: pushed, opened, failed, finished, closed. */
function Moment({ event }: { event: UpstreamCherryPickEventDto }) {
  const tone = momentTone(event.kind);
  return (
    <div className={`sf-moment is-${tone}`}>
      <span className="sf-moment__glyph" aria-hidden>{momentGlyph(tone)}</span>
      <span className="sf-moment__copy">
        <b>{event.title}</b>
        {event.detail !== null && <span>{event.detail}</span>}
      </span>
      <time>{clockLabel(event.at)}</time>
    </div>
  );
}

function momentTone(kind: string): 'push' | 'pr' | 'bad' | 'good' | 'quiet' {
  if (kind === 'push') return 'push';
  if (kind === 'pr') return 'pr';
  if (kind === 'error' || kind === 'park') return 'bad';
  if (kind === 'done') return 'good';
  return 'quiet';
}

function momentGlyph(tone: string): ReactNode {
  if (tone === 'push') {
    return (
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M12 19V5" /><path d="m5 12 7-7 7 7" />
      </svg>
    );
  }
  if (tone === 'bad') {
    return (
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2.6" strokeLinecap="round" aria-hidden>
        <path d="M18 6 6 18M6 6l12 12" />
      </svg>
    );
  }
  if (tone === 'good') return <CheckIcon size={10} />;
  return (
    <svg width="9" height="9" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <circle cx="12" cy="12" r="5" />
    </svg>
  );
}

function SparkIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
    </svg>
  );
}

function pickStateLabel(commit: UpstreamCherryPickCommitDto): string {
  switch (commit.state) {
    case 'applied': return 'applied clean';
    case 'conflicted': return 'conflict · carried';
    case 'skipped': return 'skipped';
    case 'current': return 'in flight';
    default: return 'waiting';
  }
}
