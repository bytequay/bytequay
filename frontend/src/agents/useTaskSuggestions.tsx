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
import { useEffect, useId, useLayoutEffect, useRef, useState, type RefObject, type TextareaHTMLAttributes } from 'react';
import { CircleDot, FileText, GitBranch, GitPullRequest, ListChecks } from 'lucide-react';
import { remoteErrorMessage } from '../remoteError';
import type { ComposerSuggestionCommand, ComposerSuggestionDto } from '../types';
import { referenceLabel } from './composerReference';

const COMMANDS = [
  { name: 'backport', detail: 'Backport a PR through related workspaces', icon: GitBranch },
  { name: 'fix', detail: 'Fix an open issue', icon: CircleDot },
  { name: 'review', detail: 'Review someone else’s open PR', icon: ListChecks },
  { name: 'contribute', detail: 'Find and fix one small, worthwhile problem', icon: GitPullRequest },
  { name: 'artifacts', detail: 'Ask about this repository in Artifacts', icon: FileText },
];
const LIST_LABELS: Record<ComposerSuggestionCommand, string> = {
  BACKPORT: 'Merged pull requests', FIX: 'Open issues', REVIEW: 'Open pull requests from others',
};
type Cursor = { value: string; caret: number; end: number };
type Search = { command: ComposerSuggestionCommand; query: string; start: number; end: number };

function itemSearch(cursor: Cursor, backport: boolean): Search | null {
  const prefix = cursor.value.slice(0, cursor.caret);
  const match = /^\s*@(backport|fix|review)[ \t]+(\S*)$/i.exec(prefix);
  if (match === null || match[0] !== prefix || (!backport && match[1].toLowerCase() === 'backport')) return null;
  const suffix = /\S$/.test(match[2]) ? cursor.value.slice(cursor.caret).match(/^\S*/)?.[0] ?? '' : '';
  const query = (match[2] + suffix).trim();
  if (query.length > 500 || /^https?:\/\//i.test(query)) return null;
  return { command: match[1].toUpperCase() as ComposerSuggestionCommand, query, start: prefix.length - match[2].length, end: cursor.caret + suffix.length };
}

/** Command completion and repository suggestions share one caret and one keyboard selection. */
export function useTaskSuggestions({ draft, onDraftChange, workspaceId, inputRef, disabled, backport, artifacts, referenceSelected = false, onChooseReference }: {
  draft: string;
  onDraftChange: (draft: string) => void;
  workspaceId?: string;
  inputRef: RefObject<HTMLTextAreaElement | null>;
  disabled: boolean;
  backport: boolean;
  artifacts: boolean;
  referenceSelected?: boolean;
  onChooseReference?: (command: ComposerSuggestionCommand, item: ComposerSuggestionDto) => void;
}) {
  const listId = useId();
  const composing = useRef(false);
  const nextCaret = useRef<number | null>(null);
  const dismissed = useRef<Cursor | null>(null);
  const [cursor, setCursor] = useState<Cursor | null>(null);
  const [chosen, setChosen] = useState(0);
  const [loaded, setLoaded] = useState<{ key: string; items: ComposerSuggestionDto[]; error: string | null } | null>(null);
  const offered = COMMANDS.filter(entry => entry.name === 'artifacts' ? artifacts : entry.name !== 'backport' || backport);
  const current = !disabled && cursor?.value === draft && cursor.caret === cursor.end ? cursor : null;
  const match = current === null ? null : /^\s*@([^\s@]*)$/.exec(draft.slice(0, current.caret));
  const token = match === null || current === null ? null : (match[1] + (draft.slice(current.caret).match(/^\S*/)?.[0] ?? '')).toLowerCase();
  const commands = token === null ? [] : offered.filter(entry => entry.name.startsWith(token));
  const search = current === null || !workspaceId || referenceSelected ? null : itemSearch(current, backport);
  const command = search?.command;
  const query = search?.query;
  const key = command === undefined ? null : JSON.stringify([workspaceId, command, query]);
  const result = loaded?.key === key ? loaded : null;
  const items = result?.items ?? [];
  const open = commands.length > 0 || search !== null;
  const count = search === null ? commands.length : items.length;
  const active = chosen < count ? chosen : 0;
  const optionId = (index: number) => `${listId}-${search === null ? commands[index].name : items[index].number}`;
  const activeId = count > 0 ? optionId(active) : undefined;

  useEffect(() => {
    if (key === null || command === undefined || query === undefined || !workspaceId) return;
    let live = true;
    const timer = window.setTimeout(() => {
      void window.bridge.getComposerSuggestions(workspaceId, command, query).then(
        items => { if (live) setLoaded({ key, items: items.slice(0, 10), error: null }); },
        failure => { if (live) setLoaded({ key, items: [], error: remoteErrorMessage(failure) }); },
      );
    }, query === '' ? 0 : 180);
    return () => { live = false; window.clearTimeout(timer); };
  }, [command, key, query, workspaceId]);

  const update = (input: HTMLTextAreaElement) => {
    const position = { value: input.value, caret: input.selectionStart, end: input.selectionEnd };
    if (composing.current || (dismissed.current?.value === position.value && dismissed.current.caret === position.caret)) return;
    dismissed.current = null;
    setCursor(position);
    if (position.value !== cursor?.value || position.caret !== cursor.caret) setChosen(0);
  };
  useLayoutEffect(() => {
    if (nextCaret.current === null || inputRef.current === null) return;
    inputRef.current.focus();
    inputRef.current.setSelectionRange(nextCaret.current, nextCaret.current);
    nextCaret.current = null;
    update(inputRef.current);
  });
  useEffect(() => {
    if (activeId !== undefined) document.getElementById(activeId)?.scrollIntoView({ block: 'nearest' });
  }, [activeId]);
  const dismiss = () => {
    dismissed.current = cursor;
    setCursor(null);
  };
  const choose = (index: number) => {
    if (search === null) {
      const name = commands[index].name;
      nextCaret.current = name.length + 2;
      onDraftChange(`@${name} ${draft.replace(/^\s*@\S*\s*/, '')}`);
    }
    else {
      const prefix = draft.slice(0, search.start) + items[index].url + ' ';
      onChooseReference?.(search.command, items[index]);
      nextCaret.current = onChooseReference === undefined ? prefix.length : search.start + referenceLabel(items[index]).length + 1;
      onDraftChange(prefix + draft.slice(search.end).trimStart());
    }
    setCursor(null);
    setChosen(0);
  };
  const inputProps: TextareaHTMLAttributes<HTMLTextAreaElement> = {
    'aria-autocomplete': 'list',
    'aria-expanded': open,
    'aria-controls': open ? listId : undefined,
    'aria-activedescendant': activeId,
    onChange: event => { onDraftChange(event.currentTarget.value); update(event.currentTarget); },
    onFocus: event => { dismissed.current = null; update(event.currentTarget); },
    onSelect: event => update(event.currentTarget),
    onBlur: () => setCursor(null),
    onCompositionStart: () => { composing.current = true; setCursor(null); },
    onCompositionEnd: event => { composing.current = false; update(event.currentTarget); },
    onKeyDown: event => {
      if (!open || event.nativeEvent.isComposing || composing.current) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        dismiss();
      }
      else if ((event.key === 'Enter' || event.key === 'Tab') && !event.shiftKey) {
        // While searching, Enter must never turn an unselected query into a task.
        if (event.key === 'Enter' || count > 0) event.preventDefault();
        if (count > 0) choose(active);
      }
      else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        if (count > 0) setChosen((active + (event.key === 'ArrowDown' ? 1 : count - 1)) % count);
      }
    },
  };
  return {
    inputProps,
    list: open && <div className={`agt-mentions${search === null ? '' : ' is-items'}`} id={listId} role="listbox"
      aria-label={search === null ? 'Task suggestions' : LIST_LABELS[search.command]} aria-busy={search !== null && result === null}>
      {search === null ? commands.map((entry, index) => <button key={entry.name} type="button" id={optionId(index)} role="option" aria-selected={index === active}
        tabIndex={-1} onMouseDown={event => event.preventDefault()} onClick={() => choose(index)}>
        <entry.icon size={15} strokeWidth={1.8} aria-hidden="true" />@{entry.name}<span>{entry.detail}</span>
      </button>) : <>
        <div className="agt-mentions__heading">{LIST_LABELS[search.command]}<span>Search by title or number</span></div>
        {items.map((item, index) => <button key={item.number} type="button" title={item.title} id={optionId(index)} role="option" aria-selected={index === active}
          tabIndex={-1} onMouseDown={event => event.preventDefault()} onClick={() => choose(index)}>
          {search.command === 'FIX' ? <CircleDot size={15} aria-hidden="true" /> : <GitPullRequest size={15} aria-hidden="true" />}
          <span className="agt-mentions__item"><strong>{item.title}</strong><span>#{item.number} · {item.author}</span></span>
        </button>)}
        {items.length === 0 && <div className="agt-mentions__status" role="status">
          {result === null ? 'Loading suggestions…' : result.error !== null ? `Could not load suggestions: ${result.error}` : 'No matching results'}
        </div>}
      </>}
    </div>,
  };
}
