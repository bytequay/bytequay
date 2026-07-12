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
import { useEffect, useLayoutEffect, useRef } from 'react';
import type { ClipboardEvent, KeyboardEvent, ReactNode } from 'react';
import { CloseIcon, PlusIcon, SendUpIcon } from '../TaskBrainDesignIcons';

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
}) {
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

  const submit = () => {
    if (canSend) onSubmit();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
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
    const imageItems = Array.from(e.clipboardData.items).filter(i => i.type.startsWith('image/'));
    if (imageItems.length === 0) return;
    e.preventDefault();
    for (const item of imageItems) {
      const file = item.getAsFile();
      if (file === null) continue;
      const reader = new FileReader();
      reader.onload = () => {
        if (typeof reader.result === 'string') {
          onImagesChange([...images, reader.result]);
        }
      };
      reader.readAsDataURL(file);
    }
  };

  if (closedNote !== undefined) {
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
    <div className="composer-wrap">
      <div className="composer">
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
          placeholder={placeholder}
          disabled={disabled}
          onChange={e => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
        />
        <div className="footer">
          <button type="button" className="plus" aria-label="Add context" onClick={onAddContext}>
            <PlusIcon />
          </button>
          {modePill}
          <span className="grow" />
          <button
            type="button"
            className={spinning ? 'send spinning' : 'send'}
            aria-label={busy && canSend ? 'Queue message' : 'Send'}
            title={busy && canSend ? 'Queue — sends when the agent is free' : 'Send'}
            disabled={!canSend}
            onClick={submit}
          >
            {spinning ? <span className="send-spin-dot" aria-hidden /> : <SendUpIcon />}
          </button>
        </div>
      </div>
    </div>
  );
}
