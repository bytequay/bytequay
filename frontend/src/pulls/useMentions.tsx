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
import { useCallback, useEffect, useId, useState, type KeyboardEvent, type RefObject } from 'react';
import Avatar from '../Avatar';
import { once } from '../dataCache';
import type { RepoUserDto } from '../types';

export type MentionCandidate = { login: string; avatarUrl: string | null };
type ActiveMention = { start: number; query: string };

const MAX_MENTIONS = 8;
const NO_PEOPLE: MentionCandidate[] = [];
// ponytail: the people the reviewer and assignee pickers offer, read once per repository a session; restart to see newcomers.
const repositoryPeople = new Map<string, Promise<RepoUserDto[] | null>>();

export function activeMention(value: string, caret: number): ActiveMention | null {
  const match = value.slice(0, caret).match(/(^|[\s([{])@([a-z\d-]*)$/i);
  return match ? { start: caret - match[2].length - 1, query: match[2] } : null;
}

export function replaceMention(value: string, mention: ActiveMention, login: string) {
  const end = mention.start + mention.query.length + 1;
  const inserted = `@${login} `;
  return {
    value: value.slice(0, mention.start) + inserted + value.slice(end),
    caret: mention.start + inserted.length,
  };
}

/** The first few people whose login starts with `query`, in the order given and without repeats. */
export function matchingMentions(people: MentionCandidate[], query: string): MentionCandidate[] {
  const prefix = query.toLowerCase();
  const seen = new Set<string>();
  return people.filter(person => {
    const login = person.login.toLowerCase();
    if (!login.startsWith(prefix) || seen.has(login)) return false;
    seen.add(login);
    return true;
  }).slice(0, MAX_MENTIONS);
}

/**
 * Suggests `@login` completions while the caret follows an `@`: the given participants first, then
 * the repository's people. Without a repository nothing is suggested.
 */
export function useMentions({ repository, workspaceId, participants = NO_PEOPLE, value, onChange, textareaRef }: {
  repository?: string;
  workspaceId?: string;
  participants?: MentionCandidate[];
  value: string;
  onChange: (value: string) => void;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
}) {
  const [mention, setMention] = useState<ActiveMention | null>(null);
  const [selected, setSelected] = useState(0);
  const [loaded, setLoaded] = useState<{ repository: string; people: MentionCandidate[] } | null>(null);
  const listId = useId();
  const loading = mention !== null && repository !== undefined && loaded?.repository !== repository;
  const people = loaded !== null && loaded.repository === repository ? loaded.people : NO_PEOPLE;
  const candidates = mention === null ? NO_PEOPLE : matchingMentions([...participants, ...people], mention.query);

  useEffect(() => {
    if (!loading || repository === undefined) return;
    let live = true;
    void once(repositoryPeople, repository.toLowerCase(), () => window.bridge.fetchRepoUsers(repository, workspaceId))
      .then(users => {
        // A bot's `[bot]` login does not link as a mention.
        if (live) setLoaded({ repository, people: (users ?? []).filter(user => !user.bot) });
      });
    return () => { live = false; };
  }, [loading, repository, workspaceId]);

  const dismiss = useCallback(() => setMention(null), []);
  const choose = (candidate: MentionCandidate) => {
    if (mention === null) return;
    const replacement = replaceMention(value, mention, candidate.login);
    onChange(replacement.value);
    setMention(null);
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
      textareaRef.current?.setSelectionRange(replacement.caret, replacement.caret);
    });
  };
  const change = (textarea: HTMLTextAreaElement) => {
    setMention(repository === undefined ? null : activeMention(textarea.value, textarea.selectionStart));
    setSelected(0);
  };
  /** Returns whether the key went to the open list. */
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>): boolean => {
    if (mention === null || event.nativeEvent.isComposing) return false;
    if (event.key === 'Escape') {
      event.preventDefault();
      setMention(null);
      return true;
    }
    if (candidates.length === 0) return false;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setSelected(current => (current + step + candidates.length) % candidates.length);
      return true;
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault();
      choose(candidates[selected] ?? candidates[0]);
      return true;
    }
    return false;
  };

  return {
    change, keyDown, dismiss,
    open: mention !== null,
    inputProps: {
      'aria-autocomplete': 'list' as const,
      'aria-controls': mention === null ? undefined : listId,
      'aria-expanded': mention !== null,
      'aria-activedescendant': mention === null || candidates.length === 0 ? undefined : `${listId}-${selected}`,
    },
    list: mention !== null && (
      <div className="pr-mention-list" id={listId} role="listbox" aria-label="Mention someone">
        {candidates.length === 0
          ? <span className="pr-mention-list__empty">{loading ? 'Loading people…' : 'No matching people'}</span>
          : candidates.map((candidate, index) => (
            <button type="button" role="option" id={`${listId}-${index}`} aria-selected={index === selected}
              className={index === selected ? 'is-active' : ''} key={candidate.login}
              onMouseDown={event => { event.preventDefault(); choose(candidate); }}>
              <Avatar login={candidate.login} avatarUrl={candidate.avatarUrl} size={24} />
              <strong>{candidate.login}</strong>
            </button>
          ))}
      </div>
    ),
  };
}
