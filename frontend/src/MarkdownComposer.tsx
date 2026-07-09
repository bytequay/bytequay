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
import { useState } from 'react';
import { marked } from 'marked';
import { useAutoGrow } from './useAutoGrow';
import { useMentions } from './useMentions';

type Props = {
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  rows?: number;
  disabled?: boolean;
  /** Optional autoFocus on mount — reply composers want this. */
  autoFocus?: boolean;
  /** Class for the textarea itself. The wrapping div uses `.md-composer`. */
  textareaClassName?: string;
  /** Which tab to open on first render (default 'write'). */
  initialTab?: 'write' | 'preview';
  /** Logins offered as @mention autocomplete. Omit/empty to disable. */
  mentionCandidates?: string[];
  /** Optional Cmd/Ctrl+Enter handler for comment composers. */
  onSubmitShortcut?: () => void;
  /** Optional Esc handler for cancellable inline composers. */
  onCancelShortcut?: () => void;
};

/**
 * Tabbed Write / Preview wrapper around a markdown textarea. Same UX
 * github.com uses on its comment composers — type in Write, click
 * Preview to see the rendered version before posting. Used by every
 * comment surface (PR detail, inline diff, review-thread reply, etc.)
 * so the affordance is consistent.
 *
 * The component owns its tab state but not the text — the caller's
 * textarea value flows in via `value` so the existing PolishButtons
 * and submit handlers continue to operate on the same source of truth.
 */
function MarkdownComposer({
  value,
  onChange,
  placeholder,
  rows = 3,
  disabled,
  autoFocus,
  textareaClassName,
  initialTab = 'write',
  mentionCandidates,
  onSubmitShortcut,
  onCancelShortcut,
}: Props) {
  const [tab, setTab] = useState<'write' | 'preview'>(initialTab);
  const taRef = useAutoGrow(value);
  const mentions = useMentions({ value, onChange, candidates: mentionCandidates, textareaRef: taRef });
  const previewHtml = tab === 'preview'
    ? (marked(value.trim() || '_Nothing to preview._', { gfm: true, breaks: true }) as string)
    : '';

  return (
    <div className="md-composer">
      <div className="md-composer__tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'write'}
          className={`md-composer__tab${tab === 'write' ? ' md-composer__tab--active' : ''}`}
          onClick={() => setTab('write')}
        >
          Write
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'preview'}
          className={`md-composer__tab${tab === 'preview' ? ' md-composer__tab--active' : ''}`}
          onClick={() => setTab('preview')}
        >
          Preview
        </button>
      </div>
      {tab === 'write' ? (
        <div style={{ position: 'relative' }}>
          <textarea
            ref={taRef}
            className={textareaClassName ?? 'md-composer__textarea'}
            value={value}
            onChange={mentions.onChange}
            onClick={mentions.onClick}
            placeholder={placeholder}
            rows={rows}
            disabled={disabled}
            autoFocus={autoFocus}
            onKeyDown={(e) => {
              if (mentions.onKeyDown(e)) return;
              if ((e.metaKey || e.ctrlKey) && e.key === 'Enter' && onSubmitShortcut !== undefined) {
                e.preventDefault();
                onSubmitShortcut();
              }
              else if (e.key === 'Escape' && onCancelShortcut !== undefined) {
                e.preventDefault();
                onCancelShortcut();
              }
            }}
          />
          {mentions.dropdown}
        </div>
      ) : (
        <div
          className="md-body md-composer__preview"
          // Content originates from the user's own textarea, marked-rendered.
          // contextIsolation in Electron + the renderer being a separate
          // process means a malicious <script> wouldn't escape this surface
          // anyway, but we still trust marked's defaults.
          dangerouslySetInnerHTML={{ __html: previewHtml }}
        />
      )}
    </div>
  );
}

export default MarkdownComposer;
