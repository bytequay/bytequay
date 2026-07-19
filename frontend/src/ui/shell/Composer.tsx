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
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ClipboardEvent, KeyboardEvent, ReactNode } from 'react';
import { CloseIcon, PlusIcon, SendUpIcon } from '../TaskBrainDesignIcons';
import { useSlashCommands, type SlashCommand } from '../../useSlashCommands';
import { OPEN_WORK_MODEL_EVENT } from '../../workspace/WorkModelPill';
import { pasteClipboardImages } from './pasteClipboardImages';

/** Grow the textarea to fit its content, up to this many px (then scroll). */
const MAX_INPUT_HEIGHT = 160;

/**
 * The composer pinned to the bottom of every conversation column. One
 * mode pill (the model selector, passed in as `modePill`) and a send
 * button. Enter submits; Shift+Enter inserts a newline. While `busy`
 * the send button shows the spinner and submit is blocked.
 */
export function Composer({
  value, onChange, onSubmit, placeholder, modePill, busy = false, disabled = false,
  queueWhenBusy = false, onAddContext, images = [], onImagesChange, closedNote,
  variant = 'default', toolbar, meta, effortLabel = 'Medium', usage,
}: {
  value: string;
  onChange: (next: string) => void;
  onSubmit: () => void;
  placeholder?: string;
  /** The model-selector mode pill (the relocated WORK MODEL card). */
  modePill?: ReactNode;
  busy?: boolean;
  disabled?: boolean;
  /** Allow submitting while the agent is busy — the host queues the message
   *  (it sends when the agent goes idle) instead of blocking the send. */
  queueWhenBusy?: boolean;
  onAddContext?: () => void;
  /** Pending pasted-screenshot data URLs (e.g. `data:image/png;base64,...`),
   *  shown as removable thumbnail chips above the input — controlled, like
   *  `value`. Omit (with `onImagesChange`) to disable image paste on this
   *  composer instance. */
  images?: string[];
  onImagesChange?: (next: string[]) => void;
  /** Replaces the whole composer with a muted "closed" box (terminal
   *  tasks/stages take no more input). */
  closedNote?: string;
  /** Locked workspace/task composer chrome. Input, paste, queue, and model
   *  behavior remain shared with every other conversation surface. */
  variant?: 'default' | 'workspace-v2';
  /** Task-only pills above the input; trunk pages intentionally omit it. */
  toolbar?: ReactNode;
  /** Right-aligned task/thread metrics above the input. */
  meta?: ReactNode;
  /** The design's effort picker label (there is no backend override yet). */
  effortLabel?: string;
  /** Usage-ring values. Omit only when the host deliberately hides usage. */
  usage?: { planPercent: number; sessionLabel: string };
}) {
  const workspaceVariant = variant === 'workspace-v2';
  const [usageOpen, setUsageOpen] = useState(false);
  const [effortOpen, setEffortOpen] = useState(false);
  const [effort, setEffort] = useState(effortLabel);
  const canSend = !disabled && (value.trim().length > 0 || images.length > 0) && (!busy || queueWhenBusy);
  // Spinner only when we're blocked (busy with nothing queueable); when a
  // message can be queued mid-run the button stays active.
  const spinning = busy && !canSend;
  const taRef = useRef<HTMLTextAreaElement>(null);

  // Auto-grow to fit the text (up to MAX_INPUT_HEIGHT, then scroll) so a
  // multi-line message isn't squeezed into one clipped row. Runs before
  // paint on every value change — including the reset to '' after send.
  useLayoutEffect(() => {
    const el = taRef.current;
    if (el === null) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, MAX_INPUT_HEIGHT)}px`;
  }, [value]);
  // Re-fit once mounted (initial content / fonts settled).
  useEffect(() => {
    const el = taRef.current;
    if (el !== null) el.style.height = `${Math.min(el.scrollHeight, MAX_INPUT_HEIGHT)}px`;
  }, []);
  useEffect(() => { setEffort(effortLabel); }, [effortLabel]);

  const submit = () => {
    if (canSend) onSubmit();
  };

  // Slash commands. "/model" opens the work-model pill already rendered in
  // this composer's footer (it listens for the window event). Only shown
  // when a modePill is present, since that's the pill the event drives.
  const commands: SlashCommand[] = modePill === undefined ? [] : [
    { name: 'model', desc: 'Switch AI model', run: () => window.dispatchEvent(new Event(OPEN_WORK_MODEL_EVENT)) },
  ];
  const slash = useSlashCommands({ value, onChange, commands, textareaRef: taRef });

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (slash.onKeyDown(e)) return;
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  // Cmd/Ctrl+V a screenshot straight into the composer — the common "paste
  // a bug repro" workflow. Reads each pasted image item as a data URL and
  // appends it to the pending-attachments list; the caller sends them
  // alongside the text on submit. No-ops (falls through to normal text
  // paste) when the host didn't wire onImagesChange.
  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    if (onImagesChange === undefined) return;
    pasteClipboardImages(e, images, onImagesChange);
  };

  if (closedNote !== undefined && !workspaceVariant) {
    return (
      <div className="composer-wrap">
        <div className="composer composer--closed">
          <span className="composer-closed-note">{closedNote}</span>
          <span className="composer-closed-plus" aria-hidden><PlusIcon /></span>
        </div>
      </div>
    );
  }

  return (
    <div className={workspaceVariant ? 'composer-wrap composer-wrap--workspace-v2' : 'composer-wrap'}>
      {workspaceVariant && (toolbar !== undefined || meta !== undefined) && (
        <div className="workspace-composer-meta">
          {toolbar}
          {meta !== undefined && <span>{meta}</span>}
        </div>
      )}
      <div className={workspaceVariant ? 'composer composer--workspace-v2' : 'composer'} style={{ position: 'relative' }}>
        {slash.dropdown}
        {images.length > 0 && (
          <div className="composer-images">
            {images.map(src => (
              <div className="composer-image-chip" key={src}>
                <img src={src} alt="Pasted attachment" />
                <button
                  type="button"
                  className="rm"
                  aria-label="Remove image"
                  onClick={() => onImagesChange?.(images.filter(i => i !== src))}
                ><CloseIcon size={10} strokeWidth={2.4} /></button>
              </div>
            ))}
          </div>
        )}
        <textarea
          ref={taRef}
          className="input"
          value={value}
          rows={1}
          placeholder={closedNote ?? placeholder}
          disabled={disabled}
          onChange={slash.onChange}
          onKeyDown={onKeyDown}
          onClick={slash.onClick}
          onPaste={onPaste}
        />
        <div className="footer">
          <button type="button" className="plus" aria-label="Add context" onClick={onAddContext}>
            {workspaceVariant ? <PlusIcon size={15} strokeWidth={2} /> : <PlusIcon />}
          </button>
          {modePill}
          {workspaceVariant && (
            <span className="workspace-composer-effort-wrap">
              <button type="button" className="workspace-composer-effort" title="Reasoning effort"
                aria-haspopup="listbox" aria-expanded={effortOpen}
                onClick={() => setEffortOpen(open => !open)}>
                {effort}
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#8b949e"
                  strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <path d="m6 9 6 6 6-6" />
                </svg>
              </button>
              {effortOpen && (
                <span className="workspace-composer-effort-menu" role="listbox" aria-label="Reasoning effort">
                  {['Low', 'Medium', 'High'].map(option => (
                    <button type="button" role="option" aria-selected={effort === option} key={option}
                      onClick={() => { setEffort(option); setEffortOpen(false); }}>{option}</button>
                  ))}
                </span>
              )}
            </span>
          )}
          <span className="grow" />
          {workspaceVariant && usage !== undefined && (
            <span className="workspace-composer-usage">
              <button type="button" aria-label="Usage" title="Usage"
                aria-expanded={usageOpen} onClick={() => setUsageOpen(open => !open)}>
                <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden>
                  <circle cx="10" cy="10" r="7.5" stroke="#e1e5e9" strokeWidth="2.5" />
                  <circle cx="10" cy="10" r="7.5" stroke="#2da44e" strokeWidth="2.5"
                    strokeLinecap="round" strokeDasharray={`${Math.max(0, Math.min(100, usage.planPercent)) * 0.471} ${47.1 - Math.max(0, Math.min(100, usage.planPercent)) * 0.471}`}
                    transform="rotate(-90 10 10)" />
                </svg>
              </button>
              {usageOpen && (
                <div className="workspace-composer-usage__popover" role="dialog" aria-label="Usage details">
                  <div>
                    <span>Plan</span>
                    <i><i style={{ width: `${Math.max(0, Math.min(100, usage.planPercent))}%` }} /></i>
                    <strong>{usage.planPercent}% used</strong>
                  </div>
                  <hr />
                  <div><span>Session</span><strong>{usage.sessionLabel}</strong></div>
                </div>
              )}
            </span>
          )}
          {workspaceVariant && (
            <button type="button" className="workspace-composer-mic" aria-label="Voice input" title="Voice input">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                <rect x="9" y="2" width="6" height="12" rx="3" />
                <path d="M5 10v1a7 7 0 0 0 14 0v-1" />
                <path d="M12 18v4" />
              </svg>
            </button>
          )}
          <button
            type="button"
            className={spinning ? 'send spinning' : 'send'}
            aria-label={busy && canSend ? 'Queue message' : 'Send'}
            title={busy && canSend ? 'Queue — sends when the agent is free' : 'Send'}
            disabled={!canSend}
            onClick={submit}
          >
            {spinning
              ? <span className="send-spin-dot" aria-hidden />
              : workspaceVariant ? <SendUpIcon size={14} strokeWidth={2.4} /> : <SendUpIcon />}
          </button>
        </div>
      </div>
    </div>
  );
}
