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
import type { ReactNode } from 'react';
import type { PullRequestCreatedData } from '../../types/brainView';
import type { LocalPRTimelineEvent } from '../../types/localPr';
import { PullRequestCreatedEvent } from '../../ui/conv';
import {
  CheckIcon, ClockIcon, CloseIcon, CommitIcon,
} from '../../ui/TaskBrainDesignIcons';
import { agoLabel, displayName, isFailedCiPayload } from './prViewMeta';

function str(payload: Record<string, unknown> | null, key: string): string | null {
  const v = payload?.[key];
  return typeof v === 'string' ? v : null;
}

function num(payload: Record<string, unknown> | null, key: string): number | null {
  const v = payload?.[key];
  return typeof v === 'number' ? v : null;
}

function pullRequestData(payload: Record<string, unknown> | null): PullRequestCreatedData {
  return {
    phase: str(payload, 'phase'),
    branch: str(payload, 'branch'),
    baseBranch: str(payload, 'baseBranch'),
    number: num(payload, 'number'),
    url: str(payload, 'url'),
    additions: num(payload, 'additions'),
    deletions: num(payload, 'deletions'),
  };
}

/** Body copy for a compact one-line event, derived from its payload. Missing
 *  fields degrade gracefully — a malformed payload still renders a sensible
 *  line. Handles every event type except `review` (a person-event, see
 *  {@link TimelinePersonEvent}) and `comment` (rendered as a bubble directly
 *  from the comment it references, not from this timeline row). */
function eventBody(event: LocalPRTimelineEvent): ReactNode {
  const p = event.payload;
  const actor = <span className="who">{displayName(event.actor)}</span>;
  switch (event.eventType) {
    case 'commit': {
      const sha = str(p, 'sha');
      const message = str(p, 'message');
      return (
        <>
          {actor} committed
          {sha !== null && <><span className="sha">{sha.slice(0, 7)}</span> — {message ?? ''}</>}
        </>
      );
    }
    case 'ci': {
      const name = str(p, 'name') ?? 'Checks';
      const status = str(p, 'status') ?? '';
      const durationMs = num(p, 'durationMs');
      return <>{name} — {status}{durationMs !== null ? ` (${Math.round(durationMs / 1000)}s)` : ''}</>;
    }
    case 'status':
      if (str(p, 'gate') === 'push' && str(p, 'decision') === 'approved') {
        return <>Push approved automatically because auto-merge is enabled</>;
      }
      return <>{actor} <code>{str(p, 'from') ?? '?'}</code> → <code>{str(p, 'to') ?? '?'}</code></>;
    case 'amend':
      return <>{actor} amended{str(p, 'sha') !== null && <> <code>{str(p, 'sha')}</code></>}</>;
    case 'branch':
      return <>{actor} {str(p, 'message') ?? 'updated the branch'}</>;
    case 'follow-up':
      return <>{actor} flagged a follow-up</>;
    default:
      return actor;
  }
}

export function TimelineIconEvent({ event }: { event: LocalPRTimelineEvent }) {
  if (event.eventType === 'pull-request-progress' || event.eventType === 'pull-request-created') {
    return (
      <PullRequestCreatedEvent
        pullRequest={pullRequestData(event.payload)}
        timestamp={agoLabel(event.createdAt)}
        timeline
      />
    );
  }
  const failed = event.eventType === 'ci' && isFailedCiPayload(event.payload);
  const iconCls = event.eventType === 'ci' ? (failed ? 'fail' : 'green') : event.eventType === 'commit' ? 'commit' : '';
  const icon = event.eventType === 'ci'
    ? failed ? <CloseIcon size={12} strokeWidth={2.4} /> : <CheckIcon size={12} strokeWidth={2.4} />
    : event.eventType === 'commit' ? <CommitIcon /> : <ClockIcon />;
  return (
    <div className="pr-tl-icon-row">
      <span className={`tic ${iconCls}`}>{icon}</span>
      <div className="tb">
        {eventBody(event)}
        {event.isLocalOnly && <span className="lock-tag">🔒 local</span>}
      </div>
      <span className="ts">{agoLabel(event.createdAt)}</span>
    </div>
  );
}
