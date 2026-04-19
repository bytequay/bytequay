import { useState } from 'react';
import { marked } from 'marked';

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
}: Props) {
  const [tab, setTab] = useState<'write' | 'preview'>('write');
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
        <textarea
          className={textareaClassName ?? 'md-composer__textarea'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          rows={rows}
          disabled={disabled}
          autoFocus={autoFocus}
        />
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
