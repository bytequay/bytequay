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
import { CheckIcon, ChevronIcon } from './WorkspaceSyncIcons';
import { clockLabel } from './syncRunModel';
import type { SyncRoundDto } from './workspaceApi';

/**
 * The CI fix rounds inside phase 2 of the run rail.
 *
 * Each row reads against the next one — "121 → 27 failing" is this round's
 * failing count and the count the round after it observed. The last row has no
 * successor, so it states its own count and stops rather than implying a
 * result nothing has measured yet.
 */
export default function WorkspaceSyncRounds({ rounds }: {
  rounds: SyncRoundDto[];
}) {
  return (
    <div className="st-rounds">
      {rounds.map((round, index) => (
        <div className="st-round" key={round.roundId}>
          <span className={`st-round__mark${
            round.failingCount === 0 ? ' is-green' : ''}`} aria-hidden>
            {round.failingCount === 0 ? <CheckIcon size={9} /> : <CrossIcon />}
          </span>
          <span className="st-round__label">Round {round.ordinal}</span>
          <code title={round.remoteHead}>{short(round.remoteHead)}</code>
          <span className="st-round__count">
            {countLine(round, rounds[index + 1])}
          </span>
        </div>
      ))}
    </div>
  );
}

/**
 * The same rounds in the run's conversation, one folded line each.
 *
 * The rail says where the run is; this says what happened, in the order it
 * happened, beside the picks and the agent's own prose.
 */
export function SyncFeedRounds({ rounds }: { rounds: SyncRoundDto[] }) {
  return (
    <>
      {rounds.map((round, index) => (
        <FeedRound key={round.roundId} round={round} next={rounds[index + 1]} />
      ))}
    </>
  );
}

function FeedRound({ round, next }: { round: SyncRoundDto; next?: SyncRoundDto }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" className="sf-group" aria-expanded={open}
        onClick={() => setOpen(current => !current)}>
        <span className={`sr-chevron${open ? ' is-open' : ''}`} aria-hidden>
          <ChevronIcon size={13} />
        </span>
        <span className="sf-pill">
          <span className="sf-pill__glyph is-agent" aria-hidden><RoundIcon /></span>
          Round {round.ordinal}
        </span>
        <span className="sf-group__meta">
          pushed {short(round.remoteHead)} · CI {countLine(round, next)}
        </span>
        <span className="sf-group__when">{clockLabel(round.createdAt)}</span>
      </button>
      {open && (
        <div className="sf-nested">
          <div className="sf-step">
            {/* The frozen required selection, which is the only thing this
                round observed — the provider's whole board is larger and is
                not a number this view has. */}
            <span>
              {round.observedCount} required check
              {round.observedCount === 1 ? '' : 's'} observed on{' '}
              {short(round.remoteHead)}
            </span>
            <em>{round.failingCount} failing · {roundState(round) ?? round.state}</em>
          </div>
        </div>
      )}
    </>
  );
}

/**
 * Counts cover the frozen required checks of that round, never the provider's
 * whole board — nothing observes the checks outside the required policy, so a
 * board total is not a number this view has.
 */
function countLine(round: SyncRoundDto, next?: SyncRoundDto): string {
  if (next !== undefined) {
    return `${round.failingCount} → ${next.failingCount} failing`;
  }
  const state = roundState(round);
  return state === null
    ? `${round.failingCount} failing`
    : `${round.failingCount} failing · ${state}`;
}

/** What the round is doing now, in the words the rail has room for. */
function roundState(round: SyncRoundDto): string | null {
  switch (round.state) {
    case 'COLLECTING': return 'collecting';
    case 'PARTIAL_RED_COMPILE': return 'compile red';
    case 'QUEUED': return 'queued';
    case 'ACTIVE': return 'repairing';
    case 'FIX_PREPARED': return 'fix ready';
    case 'GREEN': return 'green';
    case 'NEEDS_ATTENTION': return 'parked';
    default: return null;
  }
}

function short(sha: string): string {
  return sha.length <= 7 ? sha : sha.slice(0, 7);
}

/** The same spark the agent's own turns carry: this round is its work. */
function RoundIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
    </svg>
  );
}

function CrossIcon() {
  return (
    <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M18 6 6 18" /><path d="m6 6 12 12" />
    </svg>
  );
}
