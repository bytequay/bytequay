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
import React, { useRef, useState } from 'react';

/**
 * The click-to-rename draft: its state, the double-commit guard, and the input's own props.
 *
 * Callers keep their own class name and their own non-editing presentation, which differ.
 */
export function useRenameDraft(current: string, onRename?: (title: string) => void) {
  const [draft, setDraft] = useState<string | null>(null);
  // Enter commits and then the input unmounts, which blurs it; the flag keeps that from committing twice.
  const committed = useRef(false);
  const commit = (): void => {
    if (committed.current || draft === null) return;
    committed.current = true;
    const next = draft.trim();
    setDraft(null);
    if (next !== '' && next !== current) onRename?.(next);
  };
  return {
    editing: draft !== null,
    start: (): void => {
      if (onRename === undefined) return;
      committed.current = false;
      setDraft(current);
    },
    inputProps: {
      type: 'text',
      'aria-label': 'Task name',
      value: draft ?? '',
      autoFocus: true,
      onChange: (event: React.ChangeEvent<HTMLInputElement>) => setDraft(event.target.value),
      onBlur: commit,
      onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => {
        if (event.key === 'Enter') commit();
        if (event.key === 'Escape') { committed.current = true; setDraft(null); }
      },
    },
  } as const;
}
