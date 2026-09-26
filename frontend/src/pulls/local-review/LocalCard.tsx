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
import { Bot, ChevronRight, CircleCheck, GitCommitHorizontal, Lock, Target } from 'lucide-react';
import { relativeTime } from '../../relativeTime';

/** A change's intention, one commit's design notes, or a review comment about code. */
export type LocalCardTone = 'purpose' | 'commit' | 'local';

/**
 * The one frame every private card draws: local review threads, agent findings and summary cards.
 * None of them ever reaches GitHub, so the chip says so wherever the card is placed. The GitHub
 * review thread card is a separate component and shares none of this markup.
 */
export default function LocalCard({ tone = 'local', title, badges, chip, resolved = false, outdated = false, open, code, children, controls, attributes }: {
  tone?: LocalCardTone;
  title: ReactNode;
  badges?: ReactNode;
  chip?: string;
  resolved?: boolean;
  outdated?: boolean;
  /** Resolved cards start folded, like resolved GitHub threads. */
  open?: boolean;
  code?: ReactNode;
  children: ReactNode;
  controls: ReactNode;
  attributes?: Record<`data-${string}`, string>;
}) {
  const Icon = tone === 'purpose' ? Target : tone === 'commit' ? GitCommitHorizontal : null;
  return <details className={`lrc is-${tone}`} open={open ?? !resolved} {...attributes}>
    <summary className="lrc__head">
      <ChevronRight className="lrc__chevron" size={14} strokeWidth={2} aria-hidden="true" />
      {Icon !== null && <Icon className="lrc__icon" size={16} strokeWidth={2} aria-hidden="true" />}
      {title}
      {outdated && <span className="lrc__badge is-warn">Outdated</span>}
      {badges}
      {resolved && <span className="lrc__resolved"><CircleCheck size={14} aria-hidden="true" />Resolved</span>}
      <span className="lrc__chip"><Lock size={11} strokeWidth={2} aria-hidden="true" />{chip ?? (tone === 'local' ? 'Local' : 'Private')}</span>
    </summary>
    {code}
    {children}
    {controls}
  </details>;
}

export function LocalCardMessage({ avatar, author, tag, timestamp, menu, children }: {
  avatar: ReactNode;
  author: string;
  tag?: ReactNode;
  timestamp: string | null;
  menu?: ReactNode;
  children: ReactNode;
}) {
  return <div className="lrc-message">
    <div className="lrc-message__head">
      {avatar}<strong>{author}</strong>{tag}<time>{relativeTime(timestamp)}</time>
      {menu && <span className="lrc-message__menu">{menu}</span>}
    </div>
    <div className="lrc-message__body">{children}</div>
  </div>;
}

/** A summary card names what it explains; a commit's own title is already on its timeline row. */
export function SummaryCardTitle({ sha, tooltip }: { sha?: string; tooltip?: string }) {
  return sha === undefined
    ? <span className="lrc__title" title={tooltip}>Change intention</span>
    : <span className="lrc__title" title={tooltip}>Design &amp; implementation <code>{sha.slice(0, 7)}</code></span>;
}

/** The review agent's mark, so its comments read as a bot's next to a person's. */
export function ReviewerAvatar() {
  return <span className="lrc-bot" aria-hidden="true"><Bot size={13} strokeWidth={2} /></span>;
}
