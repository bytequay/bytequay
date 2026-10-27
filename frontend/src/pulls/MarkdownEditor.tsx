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
import { Fragment, type ReactNode, type RefObject, useId, useRef, useState } from 'react';
import { Lock } from 'lucide-react';
import MarkdownBody, { type SuggestionBase } from './MarkdownBody';
import { useMentions } from './useMentions';

export type MarkdownCommand =
  | 'heading' | 'bold' | 'italic' | 'quote' | 'code' | 'link' | 'bullet' | 'number' | 'task';

const MARKDOWN_PREFIXES: Partial<Record<MarkdownCommand, string>> = {
  heading: '### ',
  quote: '> ',
  bullet: '- ',
  number: '1. ',
  task: '- [ ] ',
};

const MARKDOWN_WRAPS: Partial<Record<MarkdownCommand, string>> = {
  bold: '**',
  italic: '_',
  code: '`',
};

/**
 * Applies a toolbar command to `value` over the [start, end) selection and
 * returns the text plus the selection to restore. Insert-only: clicking Bold
 * twice nests markers rather than toggling them off, which is the cheap
 * behaviour the textarea's own undo already covers.
 */
export function applyMarkdownCommand(value: string, start: number, end: number, command: MarkdownCommand):
  { value: string; start: number; end: number } {
  const prefix = MARKDOWN_PREFIXES[command];
  if (prefix !== undefined) {
    const lineStart = value.lastIndexOf('\n', start - 1) + 1;
    const lineBreak = value.indexOf('\n', end);
    const lineEnd = lineBreak === -1 ? value.length : lineBreak;
    const block = value.slice(lineStart, lineEnd).split('\n')
      .map((line, index) => (command === 'number' ? `${index + 1}. ` : prefix) + line)
      .join('\n');
    return {
      value: value.slice(0, lineStart) + block + value.slice(lineEnd),
      start: lineStart,
      end: lineStart + block.length,
    };
  }
  const selected = value.slice(start, end);
  if (command === 'link') {
    const urlStart = start + selected.length + 3;
    return {
      value: `${value.slice(0, start)}[${selected}](url)${value.slice(end)}`,
      start: urlStart,
      end: urlStart + 3,
    };
  }
  // A fenced block is the only thing that renders a multi-line code selection.
  const fenced = command === 'code' && selected.includes('\n');
  const open = fenced ? '```\n' : MARKDOWN_WRAPS[command] ?? '';
  const close = fenced ? '\n```' : open;
  return {
    value: value.slice(0, start) + open + selected + close + value.slice(end),
    start: start + open.length,
    end: end + open.length,
  };
}

/**
 * Replaces the [start, end) selection with a ```suggestion block holding `lines`, with the caret
 * at the end of the last suggested line. The fence outgrows any backtick run inside the lines.
 */
export function insertSuggestion(value: string, start: number, end: number, lines: string[]):
  { value: string; start: number; end: number } {
  const before = value.slice(0, start);
  const after = value.slice(end);
  const longestRun = Math.max(0, ...(lines.join('\n').match(/`+/g) ?? []).map(run => run.length));
  const fence = '`'.repeat(Math.max(3, longestRun + 1));
  const opening = `${before && !before.endsWith('\n') ? '\n' : ''}${fence}suggestion\n${lines.join('\n')}`;
  const caret = before.length + opening.length;
  return {
    value: `${before}${opening}\n${fence}${after && !after.startsWith('\n') ? '\n' : ''}${after}`,
    start: caret,
    end: caret,
  };
}

const SUGGESTION_ICON = [
  'M9.5 2H4.2A1.2 1.2 0 0 0 3 3.2v9.6A1.2 1.2 0 0 0 4.2 14h7.6a1.2 1.2 0 0 0 1.2-1.2V5.5z',
  'M9.5 2v3.5H13',
  'M8 6v3',
  'M6.5 7.5h3',
  'M6.5 11.3h3',
];

function ToolbarIcon({ paths }: { paths: string[] }) {
  return (
    <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor"
      strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths.map((path, index) => <path d={path} key={index} />)}
    </svg>
  );
}

const MARKDOWN_ICONS: Record<MarkdownCommand, string[]> = {
  heading: ['M4 3v10', 'M12 3v10', 'M4 8h8'],
  bold: ['M5.5 3h3.2a2.4 2.4 0 0 1 0 4.8H5.5z', 'M5.5 7.8h3.8a2.6 2.6 0 0 1 0 5.2H5.5z'],
  italic: ['M6.5 3h4.5', 'M5 13h4.5', 'M9.5 3 7 13'],
  quote: ['M3.2 3.5v9', 'M6.5 5h6.3', 'M6.5 8h6.3', 'M6.5 11h4'],
  code: ['m5.8 4.8-3 3.2 3 3.2', 'm10.2 4.8 3 3.2-3 3.2'],
  link: [
    'M6.6 9.4 9.4 6.6',
    'M8.6 5.4 10 4a2.6 2.6 0 0 1 3.7 3.7L12.3 9.1',
    'M7.4 10.6 6 12a2.6 2.6 0 0 1-3.7-3.7L3.7 6.9',
  ],
  bullet: ['M3 4.4h.01', 'M3 8h.01', 'M3 11.6h.01', 'M6.2 4.4h7', 'M6.2 8h7', 'M6.2 11.6h7'],
  number: ['M2.3 3.7 3.4 3v3.4', 'M2.2 10.2h1.9L2.2 12.6h2', 'M6.2 4.4h7', 'M6.2 8h7', 'M6.2 11.6h7'],
  task: ['m2 4.3 1.2 1.3L5.6 3', 'm2 10.8 1.2 1.3L5.6 9.5', 'M8 4.4h5.4', 'M8 10.9h5.4'],
};

const MARKDOWN_GROUPS: Array<Array<{ command: MarkdownCommand; label: string }>> = [
  [
    { command: 'heading', label: 'Heading' },
    { command: 'bold', label: 'Bold' },
    { command: 'italic', label: 'Italic' },
  ],
  [
    { command: 'quote', label: 'Quote' },
    { command: 'code', label: 'Code' },
    { command: 'link', label: 'Link' },
  ],
  [
    { command: 'bullet', label: 'Bulleted list' },
    { command: 'number', label: 'Numbered list' },
    { command: 'task', label: 'Task list' },
  ],
];

export function MarkdownToolbar({ onCommand, onSuggest, disabled = false }: {
  onCommand: (command: MarkdownCommand) => void;
  onSuggest?: () => void;
  disabled?: boolean;
}) {
  return (
    <div className="pull-diff-review__toolbar">
      {onSuggest && <>
        <button type="button" title="Add a suggestion" aria-label="Add a suggestion"
          disabled={disabled}
          onMouseDown={event => event.preventDefault()}
          onClick={onSuggest}>
          <ToolbarIcon paths={SUGGESTION_ICON} />
        </button>
        <span className="pull-diff-review__divider" aria-hidden="true" />
      </>}
      {MARKDOWN_GROUPS.map((group, groupIndex) => (
        <Fragment key={groupIndex}>
          {groupIndex > 0 && <span className="pull-diff-review__divider" aria-hidden="true" />}
          {group.map(({ command, label }) => (
            <button type="button" key={command} title={label} aria-label={label}
              disabled={disabled}
              // The textarea keeps its selection because the button never takes focus.
              onMouseDown={event => event.preventDefault()}
              onClick={() => onCommand(command)}>
              <ToolbarIcon paths={MARKDOWN_ICONS[command]} />
            </button>
          ))}
        </Fragment>
      ))}
    </div>
  );
}

/**
 * The Write/Preview Markdown editor behind line comments and review questions. Hosts own the header,
 * the footer actions and when a keyboard submit or cancel may run; a disabled action carries its reason as a title
 * and as `statusMessage`.
 */
export default function MarkdownEditor({
  className,
  value,
  onChange,
  ariaLabel,
  placeholder,
  repository,
  suggestion,
  canSuggest = false,
  preview: controlledPreview,
  onPreviewChange,
  textareaRef,
  header,
  isPrivate = false,
  note,
  statusMessage = null,
  error = null,
  busy = false,
  autoFocus = false,
  onSubmit,
  onCancel,
  children,
}: {
  className?: string;
  value: string;
  onChange: (value: string) => void;
  ariaLabel: string;
  placeholder?: string;
  repository?: string;
  /** The base a suggestion previews against. */
  suggestion?: SuggestionBase;
  /** Offers the suggestion button when the base lines are known. */
  canSuggest?: boolean;
  /** Controlled by a host that outlives this editor's DOM. */
  preview?: boolean;
  onPreviewChange?: (preview: boolean) => void;
  textareaRef?: RefObject<HTMLTextAreaElement | null>;
  header?: ReactNode;
  /** Every action in the box keeps the text between the user and the agent. */
  isPrivate?: boolean;
  /** Destination or other help text below the action buttons. */
  note?: ReactNode;
  /** Explains disabled actions; the live region stays mounted so a changed reason is announced. */
  statusMessage?: string | null;
  error?: string | null;
  busy?: boolean;
  autoFocus?: boolean;
  /** Cmd/Ctrl+Enter. */
  onSubmit?: () => void;
  /** Escape. */
  onCancel?: () => void;
  children: ReactNode;
}) {
  const [ownPreview, setOwnPreview] = useState(false);
  const preview = controlledPreview ?? ownPreview;
  const setPreview = (next: boolean) => {
    setOwnPreview(next);
    onPreviewChange?.(next);
  };
  const ownRef = useRef<HTMLTextAreaElement>(null);
  const bodyRef = textareaRef ?? ownRef;
  const suggestionLines = canSuggest ? suggestion?.lines ?? null : null;
  const statusId = useId();
  // A private note never reaches GitHub, so nobody there could be mentioned.
  const mentions = useMentions({ repository: isPrivate ? undefined : repository, value, onChange, textareaRef: bodyRef });

  const edit = (change: (value: string, start: number, end: number) => { value: string; start: number; end: number }) => {
    const textarea = bodyRef.current;
    if (busy || textarea === null) return;
    const next = change(textarea.value, textarea.selectionStart, textarea.selectionEnd);
    onChange(next.value);
    requestAnimationFrame(() => {
      textarea.focus();
      textarea.setSelectionRange(next.start, next.end);
    });
  };

  return (
    <div className={className === undefined ? 'pull-diff-review' : `pull-diff-review ${className}`}>
      {header}
      <div className={`pull-diff-review__bar${isPrivate ? ' pull-diff-review__bar--private' : ''}`}>
        <div className="pull-diff-review__tabs" role="tablist">
          <button type="button" role="tab" aria-selected={!preview} className={preview ? '' : 'is-active'}
            onClick={() => {
              setPreview(false);
              requestAnimationFrame(() => bodyRef.current?.focus());
            }}>Write</button>
          <button type="button" role="tab" aria-selected={preview} className={preview ? 'is-active' : ''}
            onClick={() => setPreview(true)}>Preview</button>
        </div>
        {isPrivate && <button type="button" className="pull-diff-review__private" disabled>
          <Lock size={12} strokeWidth={2} aria-hidden="true" />Private
        </button>}
        {!preview && <MarkdownToolbar
          disabled={busy}
          onCommand={command => edit((text, start, end) => applyMarkdownCommand(text, start, end, command))}
          onSuggest={suggestionLines === null
            ? undefined
            : () => edit((text, start, end) => insertSuggestion(text, start, end, suggestionLines))} />}
      </div>
      {preview && <div className="pull-diff-review__preview">
        {value.trim()
          ? <MarkdownBody body={value} repository={repository} suggestion={suggestion} />
          : <p className="pull-diff-review__nothing">Nothing to preview</p>}
      </div>}
      <textarea
        ref={bodyRef}
        value={value}
        hidden={preview}
        disabled={busy}
        autoFocus={autoFocus}
        aria-label={ariaLabel}
        aria-describedby={statusId}
        placeholder={placeholder}
        {...(mentions.open ? mentions.inputProps : {})}
        onBlur={mentions.dismiss}
        onChange={event => {
          onChange(event.target.value);
          mentions.change(event.target);
        }}
        onKeyDown={event => {
          if (busy) return;
          // Ahead of the mention list, whose plain Enter would otherwise take Cmd+Enter as a pick.
          if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && onSubmit !== undefined) {
            event.preventDefault();
            onSubmit();
            return;
          }
          if (mentions.keyDown(event)) return;
          if (event.key === 'Escape' && onCancel !== undefined) {
            event.preventDefault();
            onCancel();
          }
        }} />
      {mentions.list}
      <p id={statusId} className={statusMessage ? 'pull-diff-review__status' : 'sr-only'} role="status">{statusMessage}</p>
      {error && <span className="pull-diff-review__error" role="alert">{error}</span>}
      <footer>{children}</footer>
      {note}
    </div>
  );
}
