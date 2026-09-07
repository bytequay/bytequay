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
import MarkdownBody from '../MarkdownBody';
import { applyMarkdownCommand, MarkdownToolbar, type MarkdownCommand } from '../PullChanges';

function LockIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.3" stroke="currentColor" strokeWidth="1.4" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  );
}

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
    <div className={`agr-composer${outgoing === null ? '' : ' is-outgoing'}`}>
      <div className="agr-composer__bar">
        <div className="agr-composer__tabs" role="tablist">
          <button type="button" role="tab" aria-selected={!preview} className={preview ? '' : 'is-active'}
            onClick={() => {
              setPreview(false);
              requestAnimationFrame(() => bodyRef.current?.focus());
            }}>Write</button>
          <button type="button" role="tab" aria-selected={preview} className={preview ? 'is-active' : ''}
            onClick={() => setPreview(true)}>Preview</button>
        </div>
        {!preview && <MarkdownToolbar onCommand={runCommand} />}
      </div>
      {preview
        ? <div className="agr-composer__preview">
          {value.trim()
            ? <MarkdownBody body={value} repository={repository} />
            : <p className="agr-composer__nothing">Nothing to preview</p>}
        </div>
        : <textarea
          ref={bodyRef}
          className="agr-composer__input"
          value={value}
          rows={4}
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
          }} />}
      <footer className="agr-composer__footer">
        {outgoing === null
          ? <span className="agr-composer__note"><LockIcon />Private — only you and the agent</span>
          : <span className="agr-composer__note is-outgoing">{outgoing}</span>}
        <button type="button" onClick={onCancel}>Cancel</button>
        <button type="button" className={outgoing === null ? '' : 'is-primary'} disabled={!ready} onClick={onSubmit}>
          {primaryLabel}
        </button>
      </footer>
    </div>
  );
}
