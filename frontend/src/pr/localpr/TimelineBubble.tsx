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
import Avatar from '../../Avatar';
import type { ActorRole } from './prViewMeta';
import { agoLabel, displayName } from './prViewMeta';

/** A GitHub-style comment card (U15): avatar beside the timeline, with a
 *  tinted header (agent-purple / author-blue / neutral) carrying who + what +
 *  when. Used for the description and every PR-level comment. */
export function TimelineBubble({
  actor, role, action, time, local = false, pending = false, children,
}: {
  actor: string;
  role: ActorRole;
  /** e.g. "drafted the description", "commented", "left a comment". */
  action: string;
  time: number;
  /** The comment exists only in ByteQuay and has not been posted to GitHub. */
  local?: boolean;
  /** An unpublished/unresolved local draft — renders the amber Pending badge. */
  pending?: boolean;
  children: ReactNode;
}) {
  const avatarCls = role === 'other' ? '' : role;
  const bubbleCls = role === 'agent' ? 'hl-agent' : role === 'author' ? 'hl-author' : '';
  return (
    <div className="pr-bubble-row">
      <Avatar login={displayName(actor)} size={40} className={`pr-avatar s40 ${avatarCls}`} />
      <div className={`pr-bubble ${bubbleCls}`}>
        <div className="bh">
          <span><span className="who">{displayName(actor)}</span> {action} · {agoLabel(time)}</span>
          {role === 'agent' && <span className="pr-badge agent">{displayName(actor) === 'dev' ? 'Dev' : 'Brain'}</span>}
          {role === 'author' && <span className="pr-badge author">Author</span>}
          {local && <span className="pr-badge local">Local</span>}
          {pending && <span className="pr-badge pending">Pending</span>}
          <span className="dots">···</span>
        </div>
        <div className="bb">{children}</div>
      </div>
    </div>
  );
}
