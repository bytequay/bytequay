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
import type { KeyboardEvent, ReactNode } from 'react';

/**
 * The composer pinned to the bottom of every conversation column. One
 * mode pill (the model selector, passed in as `modePill`) and a send
 * button. Enter submits; Shift+Enter inserts a newline. While `busy`
 * the send button shows the spinner and submit is blocked.
 */
export function Composer({
  value, onChange, onSubmit, placeholder, modePill, busy = false, disabled = false, onAddContext,
}: {
  value: string;
  onChange: (next: string) => void;
  onSubmit: () => void;
  placeholder?: string;
  /** The model-selector mode pill (the relocated WORK MODEL card). */
  modePill?: ReactNode;
  busy?: boolean;
  disabled?: boolean;
  onAddContext?: () => void;
}) {
  const canSend = !busy && !disabled && value.trim().length > 0;

  const submit = () => {
    if (canSend) onSubmit();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  return (
    <div className="composer-wrap">
      <div className="composer">
        <textarea
          className="input"
          value={value}
          rows={1}
          placeholder={placeholder}
          disabled={disabled}
          onChange={e => onChange(e.target.value)}
          onKeyDown={onKeyDown}
        />
        <div className="footer">
          <button type="button" className="plus" aria-label="Add context" onClick={onAddContext}>+</button>
          {modePill}
          <span className="grow" />
          <button
            type="button"
            className={busy ? 'send spinning' : 'send'}
            aria-label="Send"
            disabled={!canSend}
            onClick={submit}
          >
            {busy ? '○' : '↑'}
          </button>
        </div>
      </div>
    </div>
  );
}
