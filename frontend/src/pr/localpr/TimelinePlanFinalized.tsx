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
import { Fragment } from 'react';
import type { LocalPRTimelineEvent } from '../../types/localPr';
import { agoLabel } from './prViewMeta';

function str(payload: Record<string, unknown> | null, key: string): string | null {
  const v = payload?.[key];
  return typeof v === 'string' ? v : null;
}

/** The user's plan approval (R20's finalize gate), rendered as a one-line
 *  icon event plus a link card back to the Plan node — the plan itself lives
 *  on the Live Plan ladder, not in this timeline, so the card is the only
 *  way to jump from "the plan is finalized" back to what was approved. */
export function TimelinePlanFinalized({
  event, actor, onOpenStage,
}: {
  event: LocalPRTimelineEvent;
  actor?: string;
  onOpenStage?: (stageId: string) => void;
}) {
  const stageId = str(event.payload, 'planStageId');
  return (
    <Fragment>
      <div className="pr-tl-icon-row">
        <span className="tic green">✓</span>
        <div className="tb">
          <span className="who">{actor ?? (event.actor === 'you' ? 'You' : event.actor)}</span> approved the plan
          {event.isLocalOnly && <span className="lock-tag">🔒 local</span>}
        </div>
        <span className="ts">{agoLabel(event.createdAt)}</span>
      </div>
      {stageId !== null && onOpenStage !== undefined && (
        <button type="button" className="pr-tl-plan-link" onClick={() => onOpenStage(stageId)}>
          <span aria-hidden>◆</span> View the plan
        </button>
      )}
    </Fragment>
  );
}
