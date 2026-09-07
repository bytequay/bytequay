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
import { applyMarkdownCommand, type MarkdownCommand } from '../PullChanges';

const MARKDOWN_ICONS: Record<MarkdownCommand, string[]> = {
  heading: ['M4 3v10', 'M12 3v10', 'M4 8h8'],
  bold: ['M5.5 3h3.2a2.4 2.4 0 0 1 0 4.8H5.5z', 'M5.5 7.8h3.8a2.6 2.6 0 0 1 0 5.2H5.5z'],
  italic: ['M6.5 3h4.5', 'M5 13h4.5', 'M9.5 3 7 13'],
  quote: ['M3.2 3.5v9', 'M6.5 5h6.3', 'M6.5 8h6.3', 'M6.5 11h4'],
  code: ['m5.8 4.8-3 3.2 3 3.2', 'm10.2 4.8 3 3.2-3 3.2'],
  link: ['M6.6 9.4 9.4 6.6', 'M8.6 5.4 10 4a2.6 2.6 0 0 1 3.7 3.7L12.3 9.1', 'M7.4 10.6 6 12a2.6 2.6 0 0 1-3.7-3.7L3.7 6.9'],
  bullet: ['M3 4.4h.01', 'M3 8h.01', 'M3 11.6h.01', 'M6.2 4.4h7', 'M6.2 8h7', 'M6.2 11.6h7'],
  number: ['M2.3 3.7 3.4 3v3.4', 'M2.2 10.2h1.9L2.2 12.6h2', 'M6.2 4.4h7', 'M6.2 8h7', 'M6.2 11.6h7'],
  task: ['m2 4.3 1.2 1.3L5.6 3', 'm2 10.8 1.2 1.3L5.6 9.5', 'M8 4.4h5.4', 'M8 10.9h5.4'],
};

const GROUPS: Array<Array<{ command: MarkdownCommand; label: string }>> = [
  [{ command: 'heading', label: 'Heading' }, { command: 'bold', label: 'Bold' }, { command: 'italic', label: 'Italic' }],
  [{ command: 'quote', label: 'Quote' }, { command: 'code', label: 'Code' }, { command: 'link', label: 'Link' }],
  [{ command: 'bullet', label: 'Bulleted list' }, { command: 'number', label: 'Numbered list' }, { command: 'task', label: 'Task list' }],
];

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
        {!preview && (
          <div className="agr-composer__toolbar">
            {GROUPS.map((group, groupIndex) => (
              <span className="agr-composer__group" key={groupIndex}>
                {groupIndex > 0 && <span className="agr-composer__divider" aria-hidden="true" />}
                {group.map(({ command, label }) => (
                  <button type="button" key={command} title={label} aria-label={label}
                    onMouseDown={event => event.preventDefault()}
                    onClick={() => runCommand(command)}>
                    <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor"
                      strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      {MARKDOWN_ICONS[command].map((path, index) => <path d={path} key={index} />)}
                    </svg>
                  </button>
                ))}
              </span>
            ))}
          </div>
        )}
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
