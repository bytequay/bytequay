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
import type { ActorRole } from './prViewMeta';
import { agoLabel, avatarLabel, displayName } from './prViewMeta';

/** A GitHub-style speech-bubble comment (U15): 32/28px avatar sitting ON the
 *  rail, a card with a caret pointing at it, a tinted header (agent-purple /
 *  author-blue / neutral) carrying who + what + when. Used for the
 *  description (the first bubble) and every PR-level comment. */
export function TimelineBubble({
  actor, role, action, time, pending = false, children,
}: {
  actor: string;
  role: ActorRole;
  /** e.g. "drafted the description", "commented", "left a comment". */
  action: string;
  time: number;
  /** An unpublished/unresolved local draft — renders the amber Pending badge. */
  pending?: boolean;
  children: ReactNode;
}) {
  const avatarCls = role === 'other' ? '' : role;
  const bubbleCls = role === 'agent' ? 'hl-agent' : role === 'author' ? 'hl-author' : '';
  return (
    <div className="pr-bubble-row">
      <span className={`pr-avatar s28 ${avatarCls}`}>{avatarLabel(actor)}</span>
      <div className={`pr-bubble ${bubbleCls}`}>
        <div className="bh">
          <span><span className="who">{displayName(actor)}</span> {action} · {agoLabel(time)}</span>
          {role === 'agent' && <span className="pr-badge agent">Agent</span>}
          {role === 'author' && <span className="pr-badge author">Author</span>}
          {pending && <span className="pr-badge pending">Pending</span>}
          <span className="dots">···</span>
        </div>
        <div className="bb">{children}</div>
      </div>
    </div>
  );
}
