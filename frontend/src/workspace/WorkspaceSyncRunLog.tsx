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
import { useState } from 'react';
import { CheckIcon, ChevronIcon, TerminalIcon } from './WorkspaceSyncIcons';
import {
  clockLabel, durationLabel, money, parseTranscript, syncLogGroups,
  type SyncLogGroup, type TranscriptEntry,
} from './syncRunModel';
import type {
  UpstreamCherryPickCommitDto,
  UpstreamCherryPickEventDto,
} from './workspaceApi';

/**
 * The run's centre column: every command it executed with its exit status and
 * output, filed under the pick that produced it, plus the notes that explain
 * what the program did between commands.
 */
export default function WorkspaceSyncRunLog({ events, commits }: {
  events: UpstreamCherryPickEventDto[];
  commits: UpstreamCherryPickCommitDto[];
}) {
  const groups = syncLogGroups(events);
  const firstPick = groups.find(group => group.pickIndex !== null)?.pickIndex ?? 0;
  const earlier = commits.slice(0, firstPick)
    .filter(commit => commit.state !== 'waiting' && commit.state !== 'current');

  return (
    <div className="sr-log">
      {earlier.length > 0 && (
        <div className="sr-log__earlier">
          <span className="sr-chevron" aria-hidden><ChevronIcon size={11} /></span>
          <span className="sr-log__earlier-label">EARLIER</span>
          <span>
            picks 1–{firstPick} · {earlier.filter(c => c.state === 'applied').length} clean
            {' · '}{earlier.filter(c => c.state === 'conflicted').length} carried
            {' · '}{earlier.filter(c => c.state === 'skipped').length} skipped
          </span>
        </div>
      )}
      {groups.map(group => (
        <LogGroup key={group.key} group={group}
          commit={group.pickIndex === null ? undefined : commits[group.pickIndex]} />
      ))}
      {groups.length === 0 && (
        <p className="sr-log__empty">The run has not written anything yet.</p>
      )}
    </div>
  );
}

function LogGroup({ group, commit }: {
  group: SyncLogGroup;
  commit?: UpstreamCherryPickCommitDto;
}) {
  // A pick's conversation runs to a screenful; folding it is how a reader gets
  // past the ones they have already read to the one they came for.
  const [open, setOpen] = useState(true);
  const tone = commit === undefined ? 'run' : commitTone(commit);
  const foldable = commit !== undefined && group.pickIndex !== null;
  return (
    <section className={`sr-pick is-${tone}`}>
      {foldable && (
        <button type="button" className="sr-pick__head" aria-expanded={open}
          onClick={() => setOpen(current => !current)}>
          <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
            <ChevronIcon size={10} />
          </span>
          <span className="sr-pick__mark" aria-hidden>
            {commit.state === 'current' ? null : <CheckIcon size={9} />}
          </span>
          <span className="sr-pick__ordinal">PICK {(group.pickIndex ?? 0) + 1}</span>
          <code>{commit.shortSha}</code>
          <strong title={commit.subject}>{commit.subject}</strong>
          <span className="sr-pick__state">{pickStateLabel(commit)}</span>
          <time>{clockLabel(group.events[0].at)}</time>
        </button>
      )}
      {(open || !foldable) && (
        <div className="sr-pick__body">
          {group.events.map(event => <LogRow key={event.id} event={event} />)}
        </div>
      )}
    </section>
  );
}

/** One tool call: the readable line, click for everything it actually ran. */
export function TranscriptTool({ entry }: {
  entry: Extract<TranscriptEntry, { kind: 'tool' }>;
}) {
  const [open, setOpen] = useState(false);
  const expandable = entry.full.trim() !== entry.summary.trim();
  return (
    <div className="sr-transcript__tool-wrap">
      <button type="button" className="sr-transcript__tool" disabled={!expandable}
        aria-expanded={expandable ? open : undefined}
        onClick={() => setOpen(current => !current)}>
        <b>{entry.name}</b>
        {entry.summary.length > 0 && <code>{entry.summary}</code>}
        {expandable && (
          <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
            <ChevronIcon size={9} />
          </span>
        )}
      </button>
      {open && <pre className="sr-transcript__full">{entry.full}</pre>}
    </div>
  );
}

/** One tool response: readable immediately, exact output behind a click. */
export function TranscriptToolResult({ entry }: {
  entry: Extract<TranscriptEntry, { kind: 'tool_result' }>;
}) {
  const [open, setOpen] = useState(false);
  const summary = entry.summary.length === 0 ? '(no output)' : entry.summary;
  const expandable = entry.full.trim() !== summary.trim();
  return (
    <div className={`sr-transcript__tool-wrap is-result${entry.failed ? ' is-failed' : ''}`}>
      <button type="button" className="sr-transcript__tool" disabled={!expandable}
        aria-expanded={expandable ? open : undefined}
        onClick={() => setOpen(current => !current)}>
        <b>{entry.failed ? 'Error' : 'Result'}</b>
        <code>{summary}</code>
        {expandable && (
          <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
            <ChevronIcon size={9} />
          </span>
        )}
      </button>
      {open && <pre className="sr-transcript__full">{entry.full}</pre>}
    </div>
  );
}

function LogRow({ event }: { event: UpstreamCherryPickEventDto }) {
  const [open, setOpen] = useState(false);
  if (event.kind === 'command') {
    const failed = event.exitCode !== null && event.exitCode !== 0;
    return (
      <>
        <button type="button" className="sr-cmd" aria-expanded={open}
          disabled={event.detail === null}
          onClick={() => setOpen(current => !current)}>
          <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
            {event.detail === null ? null : <ChevronIcon size={10} />}
          </span>
          <span className="sr-cmd__glyph" aria-hidden><TerminalIcon size={11} /></span>
          <code>{event.title}</code>
          <span className={`sr-exit${failed ? ' is-failed' : ''}`}>
            exit {event.exitCode ?? 0}
          </span>
          <time>{durationLabel(event.durationMs)}</time>
        </button>
        {open && event.detail !== null && <pre className="sr-output">{event.detail}</pre>}
      </>
    );
  }
  if (event.kind === 'agent_log') {
    // The turn as a conversation: what it said, what it ran, how it ended.
    // The stored detail is the CLI's raw JSONL, where one tool call can carry a
    // whole pom.xml — rendering that verbatim buries the two sentences that
    // explain the decision.
    const entries = parseTranscript(event.detail);
    const said = entries.filter(entry => entry.kind === 'say').length;
    const ran = entries.filter(entry => entry.kind === 'tool').length;
    return (
      <>
        <button type="button" className="sr-cmd" aria-expanded={open}
          disabled={entries.length === 0}
          onClick={() => setOpen(current => !current)}>
          <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
            {entries.length === 0 ? null : <ChevronIcon size={10} />}
          </span>
          <span className="sr-cmd__glyph" aria-hidden><TerminalIcon size={11} /></span>
          <code>{event.title}</code>
          <span className="sr-transcript__count">
            {said} note{said === 1 ? '' : 's'} · {ran} command{ran === 1 ? '' : 's'}
          </span>
          <time>{clockLabel(event.at)}</time>
        </button>
        {open && (
          <div className="sr-transcript">
            {entries.map((entry, index) => {
              if (entry.kind === 'say') {
                return <p key={index} className="sr-transcript__say">{entry.text}</p>;
              }
              if (entry.kind === 'tool') {
                return <TranscriptTool key={index} entry={entry} />;
              }
              if (entry.kind === 'tool_result') {
                return <TranscriptToolResult key={index} entry={entry} />;
              }
              return (
                <p key={index}
                  className={`sr-transcript__result${entry.failed ? ' is-failed' : ''}`}>
                  {entry.failed ? 'Turn failed' : 'Turn complete'} · {entry.turns} turns ·{' '}
                  {money(entry.costUsdMilli)}
                </p>
              );
            })}
          </div>
        )}
      </>
    );
  }
  if (event.kind === 'guidance' || event.kind === 'agent') {
    // The agent proposes; the program applies. The block reads as reasoning,
    // never as "the agent changed your files".
    return (
      <div className={`sr-guidance${event.kind === 'agent' ? ' is-agent' : ''}`}>
        <span className="sr-guidance__label">
          {event.kind === 'agent' ? 'AGENT' : 'GUIDANCE'}
        </span>
        <time>{clockLabel(event.at)}{event.detail === null ? '' : ` · ${event.detail}`}</time>
        <p>{event.title}</p>
      </div>
    );
  }
  if (event.kind === 'fixup') {
    return (
      <div className="sr-fixup">
        <span className="sr-fixup__glyph" aria-hidden><FixupIcon /></span>
        <code>{event.title}</code>
        <span>{event.detail}</span>
      </div>
    );
  }
  return (
    <div className={`sr-note is-${event.kind}`}>
      <span className="sr-note__copy">
        <b>{event.title}</b>
        {event.detail !== null && <em>{event.detail}</em>}
      </span>
      <time>{clockLabel(event.at)}</time>
    </div>
  );
}

function FixupIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="3" /><path d="M12 3v6M12 15v6" />
    </svg>
  );
}

function commitTone(commit: UpstreamCherryPickCommitDto): string {
  if (commit.state === 'conflicted') return 'carried';
  if (commit.state === 'current') return 'live';
  if (commit.state === 'skipped') return 'skipped';
  return 'clean';
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
