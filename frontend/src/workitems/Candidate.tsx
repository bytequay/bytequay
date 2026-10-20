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

import { useEffect, useState } from 'react';
import { SourceIcon } from '../cards/Card';
import type { GoalItemDto, WorkItemDto } from './contracts';
import { addable, GOAL_SOURCES } from './model';
import '../css/epic-view.css';

/** Stored pull requests, issues, tickets and tasks to add, searched as the user types. */
export function useCandidates(goal: { items: WorkItemDto[] }, search: (query: string) => Promise<GoalItemDto[]>, open: boolean): { query: string; setQuery: (query: string) => void; results: GoalItemDto[] } {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<GoalItemDto[]>([]);
  useEffect(() => {
    if (!open) return;
    let active = true;
    const timer = window.setTimeout(() => {
      void search(query).then((results) => { if (active) setFound(results); }, () => { if (active) setFound([]); });
    }, 150);
    return () => { active = false; window.clearTimeout(timer); };
  }, [open, query, search]);
  return { query, setQuery, results: addable(found, goal) };
}

export function Candidate({ item, onAdd, board, sub = board ? item.status : undefined, action = 'Add' }: {
  item: GoalItemDto;
  onAdd: (item: GoalItemDto) => Promise<unknown>;
  board?: boolean;
  /** Where the item stands, beside its title; a board shows its status. */
  sub?: string;
  action?: string;
}): React.JSX.Element {
  return (
    <div className="goal-result">
      <SourceIcon source={GOAL_SOURCES[item.kind]} tinted />
      <span className="card-key">{item.label}</span>
      <span className="goal-result__title">{item.title}</span>
      {sub !== undefined && <span className="goal-result__sub">{sub}</span>}
      <button type="button" className="goal-link" onClick={() => { void onAdd(item).catch(() => {}); }}>{action}</button>
    </div>
  );
}
