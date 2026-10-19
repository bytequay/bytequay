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
import { shortSha } from '@bytequay/ui/displayText';
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

/** A review round, by the head it read; rounds go oldest first. */
export type FindingsRound = { id: string; headSha: string };

/** The round a Findings tab names: 'all' is every round, 'last' follows the newest one, anything else is a round's id. */
export function shownRound<T extends FindingsRound>(rounds: T[], round: string): T | null {
  return round === 'all' ? null : round === 'last' ? rounds.at(-1) ?? null : rounds.find(item => item.id === round) ?? null;
}

/** The Summary's Findings heading: recent rounds, an older-round picker, and the selected round's details. */
export function FindingsHead({ rounds, round, onRound, count, overall, children }: {
  rounds: FindingsRound[];
  round: string;
  onRound: (round: string) => void;
  /** How many findings a round holds; null counts every round. */
  count: (round: FindingsRound | null) => number;
  /** What All rounds says about the findings, such as how many are open. */
  overall: string;
  children?: ReactNode;
}) {
  const shown = shownRound(rounds, round);
  const index = shown === null ? -1 : rounds.indexOf(shown);
  const shownCount = count(shown);
  const tabs = [{ id: 'all', label: 'All rounds', round: null }, ...rounds.map((item, position) => position === rounds.length - 1
    ? { id: 'last', label: 'Last round', round: item } : { id: item.id, label: `Round ${position + 1}`, round: item }).reverse()];
  const others = tabs.slice(3);
  const otherSelected = others.some(tab => tab.id === round);
  return <header className="agr-findings">
    <div className="agr-findings__head">
      <h2>Findings</h2>
      {children}
      {rounds.length > 0 && <div className="agr-findings__filters" aria-label="Review rounds">
        {tabs.slice(0, 3).map(tab => <button key={tab.id} type="button" aria-pressed={round === tab.id} onClick={() => onRound(tab.id)}>
          {tab.label}<span>{count(tab.round)}</span></button>)}
        {others.length > 0 && <select aria-label="Other review rounds" className={otherSelected ? 'is-selected' : undefined}
          value={otherSelected ? round : ''} onChange={event => onRound(event.target.value)}>
          <option value="" disabled>Others</option>
          {others.map(tab => <option key={tab.id} value={tab.id}>{tab.label} · {count(tab.round)}</option>)}
        </select>}
      </div>}
    </div>
    <p className="agr-findings__meta">{shown === null ? `All rounds · ${overall}`
      : `Round ${index + 1} · head ${shortSha(shown.headSha)} · ${shownCount} ${index > 0 ? 'new' : shownCount === 1 ? 'finding' : 'findings'}`}</p>
  </header>;
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
