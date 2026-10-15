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
import { MessageSquare } from 'lucide-react';

/** One line on the Summary timeline, like an event in a pull request's timeline: who did what, and when. */
export function TimelineEvent({ avatar, children, actions, id }: { avatar: ReactNode; children: ReactNode; actions?: ReactNode; id?: string }) {
  return <div className="agr-event" id={id}>
    <span className="agr-event__avatar">{avatar}</span>
    <div className="agr-event__text">{children}</div>
    {actions}
  </div>;
}

/** One entry in a Summary's outline: the intention or a commit, and how many messages discuss it. */
export function SummaryOutlineItem({ label, ordinal, messages, current, onSelect }: {
  label: string;
  ordinal?: number;
  messages: number;
  current: boolean;
  onSelect: () => void;
}) {
  return <button type="button" className="agr-summary-nav__item" aria-current={current} title={label} onClick={onSelect}>
    {ordinal !== undefined && <span className="agr-summary-nav__ordinal">{ordinal}</span>}
    <span className="agr-summary-nav__title">{label}</span>
    {messages > 0 && <span className="agr-summary-nav__count" title={`${messages} ${messages === 1 ? 'message' : 'messages'}`}>
      <MessageSquare size={12} aria-hidden="true" />{messages}</span>}
  </button>;
}
