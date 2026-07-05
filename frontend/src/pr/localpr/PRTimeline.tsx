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
import type { LocalPRTimelineEvent } from '../../types/localPr';
import type { PRViewMode } from '../../types/localPr';
import { agoLabel, isFailedCiPayload, timelineIconMeta } from './prViewMeta';

/** The unified PR timeline (decision #55). Every timeline event renders in one
 *  stream through {@link PRTimelineEvent}; a `local-only` event gets the 🔒
 *  marker (a CSS `::after` on the icon).
 *
 *  Pre-push (`mode="local"`) every event shows inline. Once the PR is promoted
 *  (`mode="remote"`), the private local-dev history collapses under one
 *  foldable `▸ Local development` group so the synced GitHub events read as
 *  current — the local segment stays one click away, never lost. */
export function PRTimeline({ events, mode }: { events: LocalPRTimelineEvent[]; mode: PRViewMode }) {
  const [localOpen, setLocalOpen] = useState(false);

  if (mode === 'local') {
    return (
      <div className="pr-timeline">
        {events.map(e => <PRTimelineEvent key={e.id} event={e} mode={mode} />)}
      </div>
    );
  }

  // Promoted: fold the local-only segment, show the GitHub segment inline.
  const local = events.filter(e => e.isLocalOnly);
  const remote = events.filter(e => !e.isLocalOnly);
  return (
    <div className="pr-timeline">
      {local.length > 0 && (
        <>
          <button
            type="button"
            className="pr-timeline-fold"
            onClick={() => setLocalOpen(o => !o)}
            aria-expanded={localOpen}
          >
            <span className="caret" aria-hidden="true">{localOpen ? '▾' : '▸'}</span>
            Local development
            <span className="count">{local.length} event{local.length === 1 ? '' : 's'}</span>
          </button>
          {localOpen && local.map(e => <PRTimelineEvent key={e.id} event={e} mode={mode} />)}
        </>
      )}
      {remote.map(e => <PRTimelineEvent key={e.id} event={e} mode={mode} />)}
    </div>
  );
}

function str(payload: Record<string, unknown> | null, key: string): string | null {
  const v = payload?.[key];
  return typeof v === 'string' ? v : null;
}

function num(payload: Record<string, unknown> | null, key: string): number | null {
  const v = payload?.[key];
  return typeof v === 'number' ? v : null;
}

/** Body copy per event type, derived from the event payload. Missing fields
 *  degrade gracefully — a malformed payload still renders a sensible line. */
function eventBody(event: LocalPRTimelineEvent): ReactNode {
  const p = event.payload;
  const actor = <span className="b">{event.actor}</span>;
  switch (event.eventType) {
    case 'commit': {
      const sha = str(p, 'sha');
      const message = str(p, 'message');
      const adds = num(p, 'additions');
      const dels = num(p, 'deletions');
      return (
        <>
          {actor} committed
          {sha !== null && (
            <div className="tl-nested-commits">
              <div className="nc-row">
                <span className="sha">{sha.slice(0, 7)}</span>
                <span className="msg">{message ?? ''}</span>
                <span className="delta">+{adds ?? 0} −{dels ?? 0}</span>
              </div>
            </div>
          )}
        </>
      );
    }
    case 'ci': {
      const name = str(p, 'name') ?? 'Checks';
      const status = str(p, 'status') ?? '';
      const durationMs = num(p, 'durationMs');
      return (
        <>
          {name} — {status}
          {durationMs !== null && <span className="ts">{Math.round(durationMs / 1000)}s</span>}
        </>
      );
    }
    case 'status':
      return (
        <>
          {actor} <code>{str(p, 'from') ?? '?'}</code> → <code>{str(p, 'to') ?? '?'}</code>
        </>
      );
    case 'amend':
      return (
        <>
          {actor} amended {str(p, 'sha') !== null && <code>{str(p, 'sha')}</code>}
        </>
      );
    case 'branch':
      return (
        <>
          {actor} {str(p, 'message') ?? 'updated the branch'}
        </>
      );
    case 'review': {
      if (event.actor !== 'brain') {
        return <>{actor} submitted a review</>;
      }
      // Brain adversarial review (plan-rail-runs.md R20-R24) — always
      // local-only (never posted to GitHub); scope names which lock point.
      const scope = str(p, 'scope');
      const verdict = str(p, 'verdict');
      const iteration = num(p, 'iteration');
      const scopeLabel = scope === 'plan' ? 'the plan' : scope === 'round' ? 'the round\'s fixes' : 'the diff';
      return (
        <>
          <span className="brain-badge">BRAIN</span>
          reviewed {scopeLabel}
          {iteration !== null && <span className="ts">iter {iteration}</span>}
          {verdict !== null && (
            <span className={`verdict-pill ${verdict === 'approved' ? 'ok' : 'chg'}`}>
              {verdict === 'approved' ? 'APPROVED' : 'CHANGES REQUESTED'}
            </span>
          )}
        </>
      );
    }
    case 'comment':
      return <>{actor} commented</>;
    case 'follow-up':
      return <>{actor} flagged a follow-up</>;
    default:
      return actor;
  }
}

function PRTimelineEvent({ event, mode }: { event: LocalPRTimelineEvent; mode: PRViewMode }) {
  const icon = timelineIconMeta(event.eventType);
  const iconCls = event.eventType === 'ci' && isFailedCiPayload(event.payload)
    ? `${icon.cls} fail`
    : icon.cls;
  // Remote mode dims the pre-push local-only history so the remote events read
  // as current (decision #49 timeline-rendering note).
  const dim = mode === 'remote' && event.isLocalOnly;
  return (
    <div
      className={event.isLocalOnly ? 'pr-timeline-event local-only' : 'pr-timeline-event'}
      style={dim ? { opacity: 0.55 } : undefined}
    >
      <span className={`tl-icon ${iconCls}`}>{icon.glyph}</span>
      <div className="tl-body">
        {eventBody(event)}
        <span className="ts">{agoLabel(event.createdAt)}</span>
      </div>
    </div>
  );
}
