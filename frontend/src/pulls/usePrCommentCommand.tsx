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
import { useEffect, useId, useRef, useState, type ChangeEvent, type KeyboardEvent, type RefObject } from 'react';

const COMMAND = '/test-with-secrets';
type CommandMatch = { start: number; end: number; query: string };

/** Commands occupy their own Markdown line, outside quotes and fenced code. */
export function activePrCommentCommand(value: string, caret: number): CommandMatch | null {
  const before = value.slice(0, caret);
  const match = before.match(/(?:^|\n) {0,3}(\/[a-z-]*)[ \t]*$/);
  if (!match || !COMMAND.startsWith(match[1]) || !/^[ \t]*(?:\n|$)/.test(value.slice(caret))) return null;
  let fence: string | null = null;
  for (const line of before.slice(0, match.index).split('\n')) {
    const marker = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (!marker) continue;
    if (fence === null) fence = marker[1];
    else if (marker[1][0] === fence[0] && marker[1].length >= fence.length && !marker[2].trim()) fence = null;
  }
  if (fence !== null) return null;
  const start = before.lastIndexOf(match[1]);
  return { start, end: start + match[1].length, query: match[1] };
}

export function usePrCommentCommand({ repository, number, value, onChange, textareaRef }: {
  repository: string;
  number?: number;
  value: string;
  onChange: (value: string) => void;
  textareaRef: RefObject<HTMLTextAreaElement>;
}) {
  const [active, setActive] = useState<CommandMatch | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const request = useRef(0);
  const target = `${repository}#${number}`;
  const currentTarget = useRef(target);
  currentTarget.current = target;
  const listId = useId();

  useEffect(() => {
    request.current += 1;
    setActive(null);
    setLoading(false);
    setError(null);
    return () => { request.current += 1; };
  }, [target]);

  const dismiss = () => {
    request.current++;
    setActive(null);
    setLoading(false);
    setError(null);
  };
  const complete = async (text: string, match: CommandMatch) => {
    if (number === undefined) return;
    const version = ++request.current;
    setLoading(true);
    setError(null);
    const stillCurrent = () => version === request.current && target === currentTarget.current && textareaRef.current?.value === text;
    try {
      // Read the complete PR history, independently of any commit selected in Changes.
      const commits = await window.bridge.fetchPrDiffCommits(repository, number);
      if (!stillCurrent()) return;
      const sha = commits.at(-1)?.sha;
      if (!sha || !/^[a-f0-9]{40}$/i.test(sha)) throw new Error('Missing full commit SHA');
      const inserted = `${COMMAND} sha=${sha}`;
      const next = text.slice(0, match.start) + inserted + text.slice(match.end);
      const caret = (textareaRef.current?.selectionStart ?? match.end) + inserted.length - (match.end - match.start);
      onChange(next);
      setActive(null);
      requestAnimationFrame(() => {
        const textarea = textareaRef.current;
        if (version !== request.current || textarea?.value !== next) return;
        textarea.focus();
        textarea.setSelectionRange(caret, caret);
      });
    }
    catch {
      if (stillCurrent()) setError('Could not load the latest commit. Refresh the pull request and try again.');
    }
    finally {
      if (version === request.current) setLoading(false);
    }
  };
  const change = (event: ChangeEvent<HTMLTextAreaElement>) => {
    const textarea = event.target;
    const text = textarea.value;
    dismiss();
    onChange(text);
    const match = number !== undefined && textarea.selectionStart === textarea.selectionEnd
      ? activePrCommentCommand(text, textarea.selectionStart) : null;
    setActive(match);
    const input = event.nativeEvent as InputEvent;
    const inserting = input.inputType ? input.inputType.startsWith('insert') : text.length > value.length;
    if (match?.query === COMMAND && inserting && !input.isComposing) void complete(text, match);
  };
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>): boolean => {
    if (active === null || event.nativeEvent.isComposing || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return false;
    const textarea = event.currentTarget;
    const match = activePrCommentCommand(textarea.value, textarea.selectionStart);
    if (textarea.selectionStart !== textarea.selectionEnd || match?.start !== active.start || match?.end !== active.end) {
      dismiss();
      return false;
    }
    if (event.key === 'Escape') { event.preventDefault(); dismiss(); return true; }
    if (!['Enter', 'Tab', 'ArrowDown', 'ArrowUp'].includes(event.key)) return false;
    event.preventDefault();
    if (!loading && (event.key === 'Enter' || event.key === 'Tab')) void complete(value, active);
    return true;
  };

  return {
    change, keyDown, dismiss, loading,
    inputProps: {
      onClick: dismiss,
      'aria-autocomplete': 'list' as const,
      'aria-controls': active === null ? undefined : listId,
      'aria-expanded': active !== null,
      'aria-activedescendant': active === null ? undefined : `${listId}-option`,
    },
    suggestion: active !== null && <div className="pr-command-suggestion">
      <div id={listId} role="listbox" aria-label="Pull request commands">
        <button id={`${listId}-option`} type="button" role="option" aria-selected="true" tabIndex={-1} disabled={loading}
          onMouseDown={event => event.preventDefault()} onClick={() => { void complete(value, active); }}>
          <strong>{COMMAND}</strong><span role="status">{loading ? 'Loading latest commit…' : 'Add the latest commit’s full SHA · Enter or Tab'}</span>
        </button>
      </div>
      {error && <span role="alert">{error}</span>}
    </div>,
  };
}
