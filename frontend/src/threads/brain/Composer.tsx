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
import { useState, type KeyboardEvent } from 'react';

type Props = {
  /** Called with the trimmed message when the user sends. For M2 this is
   *  a stub — the brain agent backend doesn't exist yet. */
  onSubmit: (text: string) => void;
};

/**
 * Multi-line composer at the foot of the brain feed. ⌘+↵ (or Ctrl+↵)
 * sends; ⇧+↵ inserts a newline. A real `<textarea>` so the OS handles
 * caret, wrapping, and IME.
 */
export function Composer({ onSubmit }: Props) {
  const [value, setValue] = useState('');

  const send = () => {
    const text = value.trim();
    if (text === '') return;
    onSubmit(text);
    setValue('');
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      send();
    }
    // ⇧+↵ falls through to the textarea's default newline insertion.
  };

  return (
    <div className="composer">
      <div className="cbox">
        <span className="p" aria-hidden>›</span>
        <textarea
          className="t"
          rows={3}
          value={value}
          onChange={e => setValue(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Ask the brain agent about any stage, or steer the task…"
          aria-label="Message the brain agent"
        />
        <button
          type="button"
          className="send-icon"
          title="Send (⌘+↵)"
          aria-label="Send"
          disabled={value.trim() === ''}
          onClick={send}
        >
          ↑
        </button>
      </div>
      <div className="foot">
        <span className="kbd">⌘+↵</span> send
        <span className="kbd">⇧+↵</span> new line
        <span className="kbd">?</span> brain agent
        <span className="kbd">/</span> jump to stage
        <span className="hint">@StageName directive on the roadmap — not v1.</span>
      </div>
    </div>
  );
}
