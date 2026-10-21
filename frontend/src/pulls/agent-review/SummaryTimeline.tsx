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
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Chevron } from '../../Chevron';

/** The Summary's account of the change: the intention and each commit in one row, and the item picked from it below. */
export function WhatChanged({ meta, actions, items, selected, onSelect, children }: {
  meta: ReactNode;
  actions?: ReactNode;
  items: Array<{ id: string; label: string; ordinal?: number }>;
  selected: string;
  onSelect: (id: string) => void;
  children?: ReactNode;
}) {
  // A native disclosure, so a link to one of its items reopens it; folded items stay mounted with their drafts.
  return <details className="agr-changed" aria-label="What changed" open>
    <summary className="agr-changed__head">
      <span className="agr-changed__toggle"><Chevron />What changed</span>
      <span className="agr-changed__meta">{meta}</span>
      {actions}
    </summary>
    <div className="agr-changed__body">
      {items.length > 0 && <nav className="agr-changed__items" aria-label="Summary outline">
        {items.map(item => <button key={item.id} type="button" aria-current={item.id === selected} title={item.label} onClick={() => onSelect(item.id)}>
          {item.ordinal !== undefined && <span>{item.ordinal}</span>}{item.label}
        </button>)}
      </nav>}
      {children}
    </div>
  </details>;
}

/** One item of What changed: its title, its text folded to three lines, and who wrote it. */
export function ChangedItem({ title, sha, note, onViewChanges, byline, badges, ask, children }: {
  title: ReactNode;
  sha?: string;
  /** Shown in full between the title and the folded text, such as a commit's own message. */
  note?: ReactNode;
  onViewChanges?: () => void;
  byline: string;
  badges?: ReactNode;
  ask?: ReactNode;
  children: ReactNode;
}) {
  const text = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [clipped, setClipped] = useState(false);
  useLayoutEffect(() => {
    const element = text.current;
    if (element === null || expanded) return;
    const measure = () => setClipped(element.scrollHeight > element.clientHeight + 1);
    measure();
    // A hidden item measures nothing until it is shown.
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [expanded, children]);
  return <article className="agr-changed__item">
    <div className="agr-changed__title">
      <strong>{title}</strong>
      {sha !== undefined && <code>{shortSha(sha)}</code>}
      <span className="agr-spacer" />
      {onViewChanges !== undefined && <button type="button" className="agr-link" title="Open this commit's diff in Changes" onClick={onViewChanges}>View changes</button>}
    </div>
    {note}
    <div ref={text} className={`agr-changed__text${expanded ? '' : ' is-clamped'}`}>{children}</div>
    <div className="agr-changed__foot">
      {(clipped || expanded) && <button type="button" className="agr-link" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
        {expanded ? 'Show less' : 'Show more'}</button>}
      <span>{byline}</span>
      {badges}
      <span className="agr-spacer" />
      {ask}
    </div>
  </article>;
}

/** A review round, by the head it read; rounds go oldest first. */
export type FindingsRound = { id: string; headSha: string };

/** The round a Findings tab names: 'all' is every round, 'last' follows the newest one, anything else is a round's id. */
export function shownRound<T extends FindingsRound>(rounds: T[], round: string): T | null {
  return round === 'all' ? null : round === 'last' ? rounds.at(-1) ?? null : rounds.find(item => item.id === round) ?? null;
}

/** What the findings below show, and the rounds to pick from: the last round, all of them, then older rounds. */
export function FindingsHead({ title, rounds, round, onRound, count, overall, children }: {
  title?: string;
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
  const tabs = rounds.length === 0 ? [] : [
    { id: 'last', label: 'Last round', round: rounds[rounds.length - 1] },
    { id: 'all', label: 'All rounds', round: null },
    ...rounds.slice(0, -1).map((item, position) => ({ id: item.id, label: `Round ${position + 1}`, round: item })).reverse(),
  ];
  const others = tabs.slice(3);
  const otherSelected = others.some(tab => tab.id === round);
  return <header className="agr-findings">
    {title !== undefined && <h2>{title}</h2>}
    <p className="agr-findings__meta">{shown === null ? `All rounds · ${overall}`
      : `Round ${index + 1} · head ${shortSha(shown.headSha)} · ${shownCount} ${index > 0 ? 'new' : shownCount === 1 ? 'finding' : 'findings'}`}</p>
    {children}
    {tabs.length > 0 && <div className="agr-findings__filters" aria-label="Review rounds">
      {tabs.slice(0, 3).map(tab => <button key={tab.id} type="button" aria-pressed={round === tab.id} onClick={() => onRound(tab.id)}>
        {tab.label}<span>{count(tab.round)}</span></button>)}
      {others.length > 0 && <select aria-label="Other review rounds" className={otherSelected ? 'is-selected' : undefined}
        value={otherSelected ? round : ''} onChange={event => onRound(event.target.value)}>
        <option value="" disabled>Others</option>
        {others.map(tab => <option key={tab.id} value={tab.id}>{tab.label} · {count(tab.round)}</option>)}
      </select>}
    </div>}
  </header>;
}
