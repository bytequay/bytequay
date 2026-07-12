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
import Avatar from '../../Avatar';
import { CheckIcon, EyeIcon } from '../../ui/TaskBrainDesignIcons';
import { agoLabel, displayName } from './prViewMeta';

const APPROVED_VERDICTS = new Set(['APPROVED', 'approved']);

/** A review, rendered as a person-event (U15): the avatar sits to the left,
 *  clear of the rail; the eye (or a green check for an approval) is the
 *  small icon that actually sits on the rail line, the same way `.tic` does
 *  for commit/CI rows — the avatar is *aligned with* it, not layered on it. */
export function TimelinePersonEvent({
  actor, verdict, time, scope, onViewChanges,
}: {
  actor: string;
  verdict: string | null;
  time: number;
  /** The review's target — `'plan'` for the plan self-review (R20), vs the
   *  default code lock-point review — swaps the verb so a plan pass doesn't
   *  read as "approved these changes" when there's no diff yet. */
  scope?: string | null;
  onViewChanges?: () => void;
}) {
  const approved = verdict !== null && APPROVED_VERDICTS.has(verdict);
  const isPlan = scope === 'plan';
  const verb = isPlan ? (approved ? 'approved the plan' : 'reviewed the plan') : (approved ? 'approved these changes' : 'reviewed');
  return (
    <div className="pr-person-event">
      <Avatar login={displayName(actor)} size={40} className={`pr-avatar s40 ${approved ? 'author' : ''}`} />
      <span className={`eye${approved ? ' approved' : ''}`} aria-hidden>
        {approved ? <CheckIcon size={12} strokeWidth={2.8} /> : <EyeIcon />}
      </span>
      <span className="tb">
        <span className="who">{displayName(actor)}</span> {verb}
        {' '}· {agoLabel(time)}
      </span>
      {!isPlan && onViewChanges !== undefined && (
        <button type="button" className="ts pr-link-btn" onClick={onViewChanges}>View reviewed changes</button>
      )}
    </div>
  );
}
