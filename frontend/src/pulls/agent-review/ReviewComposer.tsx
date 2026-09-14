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
import { useRef, useState } from 'react';
import MarkdownBody, { type SuggestionBase } from '../MarkdownBody';
import { applyMarkdownCommand, MarkdownToolbar, type MarkdownCommand } from '../PullChanges';

/**
 * The Write/Preview composer, in the PR page's own shape.
 *
 * Its footer says where the text is going, because the same control writes a private question in
 * one place and an outgoing GitHub comment in another, and only the caption tells them apart.
 */
export default function ReviewComposer({
  repository,
  value,
  placeholder,
  ariaLabel,
  primaryLabel,
  outgoing = null,
  suggestion,
  busy = false,
  autoFocus = false,
  onChange,
  onSubmit,
  onCancel,
}: {
  repository: string;
  value: string;
  placeholder: string;
  ariaLabel: string;
  primaryLabel: string;
  /** Where the text will be posted, when it leaves ByteQuay; private when absent. */
  outgoing?: string | null;
  suggestion?: SuggestionBase;
  busy?: boolean;
  autoFocus?: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const [preview, setPreview] = useState(false);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const ready = value.trim() !== '' && !busy;

  const runCommand = (command: MarkdownCommand) => {
    const textarea = bodyRef.current;
    if (textarea === null) return;
    const next = applyMarkdownCommand(textarea.value, textarea.selectionStart, textarea.selectionEnd, command);
    onChange(next.value);
    requestAnimationFrame(() => {
      textarea.focus();
      textarea.setSelectionRange(next.start, next.end);
    });
  };

  return (
    <div className="pull-diff-review agr-composer">
      <div className="pull-diff-review__bar">
        <div className="pull-diff-review__tabs" role="tablist">
          <button type="button" role="tab" aria-selected={!preview} className={preview ? '' : 'is-active'}
            onClick={() => {
              setPreview(false);
              requestAnimationFrame(() => bodyRef.current?.focus());
            }}>Write</button>
          <button type="button" role="tab" aria-selected={preview} className={preview ? 'is-active' : ''}
            onClick={() => setPreview(true)}>Preview</button>
        </div>
        {!preview && <MarkdownToolbar onCommand={runCommand} disabled={busy} />}
      </div>
      {preview && <div className="pull-diff-review__preview">
        {value.trim()
          ? <MarkdownBody body={value} repository={repository} suggestion={suggestion} />
          : <p className="pull-diff-review__nothing">Nothing to preview</p>}
      </div>}
      <textarea
        ref={bodyRef}
        value={value}
        rows={4}
        hidden={preview}
        disabled={busy}
        autoFocus={autoFocus}
        aria-label={ariaLabel}
        placeholder={placeholder}
        onChange={event => onChange(event.target.value)}
        onKeyDown={event => {
          if (event.key === 'Escape') {
            event.preventDefault();
            onCancel();
          }
          if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
            event.preventDefault();
            if (ready) onSubmit();
          }
        }} />
      <p className={`pull-diff-review__nothing${outgoing === null ? '' : ' agr-composer__outgoing'}`}>
        {outgoing ?? 'Private — only you and the agent'}
      </p>
      <footer>
        <button type="button" disabled={busy} onClick={onCancel}>Cancel</button>
        <button type="button" className="is-primary" disabled={!ready} onClick={onSubmit}>
          {primaryLabel}
        </button>
      </footer>
    </div>
  );
}
