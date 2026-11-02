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
import { useRef, useState, type ReactNode, type Ref, type TextareaHTMLAttributes } from 'react';
import { X } from 'lucide-react';

export type ComposerProps = Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'children' | 'className' | 'onSubmit'> & {
  className?: string;
  inputRef?: Ref<HTMLTextAreaElement>;
  header?: ReactNode;
  notice?: ReactNode;
  children?: ReactNode;
  trailingActions?: ReactNode;
  busy?: boolean;
  onSubmit: () => void;
  submitEnabled: boolean;
  showSubmit?: boolean;
  submitLabel?: string;
  submitTitle?: string;
  submitContent?: ReactNode;
  submitShortcut?: 'enter' | 'mod-enter';
  /** Colors a host-owned reference without changing native textarea editing or layout. */
  inlineHighlight?: { start: number; end: number; className: string; link?: { href: string; title: string; onOpen: () => void } };
};

/** Shared message entry, keyboard submission and controls; hosts own drafts and delivery. */
export default function Composer({
  className = '', inputRef, header, notice, children, trailingActions, busy = false,
  onSubmit, submitEnabled, showSubmit = true, submitLabel = 'Send message', submitTitle = submitLabel,
  submitContent, submitShortcut = 'enter', inlineHighlight, onKeyDown, onCompositionStart, onCompositionEnd, onChange, onScroll, rows = 1, ...input
}: ComposerProps) {
  const composing = useRef(false);
  const highlights = useRef<HTMLDivElement>(null);
  const [draft, setDraft] = useState(input.defaultValue ?? '');
  const text = String(input.value ?? draft);
  const parts = text.split(/((?<![\p{L}\p{N}_@])@[\p{L}\p{N}_-]+)/gu);
  const highlighted = inlineHighlight !== undefined && inlineHighlight.start >= 0 && inlineHighlight.end <= text.length && inlineHighlight.end > inlineHighlight.start;
  const link = highlighted ? inlineHighlight.link : undefined;
  const keywords = (value: string) => value.split(/((?<![\p{L}\p{N}_@])@[\p{L}\p{N}_-]+)/gu)
    .map((part, index) => index % 2 === 1 ? <span className="agt-compose__keyword" key={index}>{part}</span> : part);
  const submit = () => { if (submitEnabled) onSubmit(); };
  return <div className={`agt-compose ${className}`.trim()} aria-busy={busy}>
    <span className="agt-compose__streamer" aria-hidden="true" />
    {header}
    <div className={`agt-compose__field${parts.length > 1 || highlighted ? ' has-keywords' : ''}`}>
      <div ref={highlights} className="agt-compose__highlights" aria-hidden={link === undefined ? true : undefined} style={input.style}>
        {highlighted ? <><span aria-hidden="true">{keywords(text.slice(0, inlineHighlight.start))}</span>
          {link === undefined ? <span className={inlineHighlight.className}>{text.slice(inlineHighlight.start, inlineHighlight.end)}</span>
            : <a className={inlineHighlight.className} href={link.href} title={link.title} aria-label={link.title}
              onClick={event => { event.preventDefault(); link.onOpen(); }}>{text.slice(inlineHighlight.start, inlineHighlight.end)}</a>}
          <span aria-hidden="true">{keywords(text.slice(inlineHighlight.end))}{'\n'}</span>
        </> : <>{keywords(text)}{'\n'}</>}
      </div>
      <textarea {...input} ref={inputRef} className="agt-compose__input" rows={rows}
        onChange={event => {
          if (input.value === undefined) setDraft(event.currentTarget.value);
          onChange?.(event);
        }}
        onScroll={event => {
          if (highlights.current) {
            highlights.current.scrollTop = event.currentTarget.scrollTop;
            highlights.current.scrollLeft = event.currentTarget.scrollLeft;
          }
          onScroll?.(event);
        }}
        onCompositionStart={event => { composing.current = true; onCompositionStart?.(event); }}
        onCompositionEnd={event => { composing.current = false; onCompositionEnd?.(event); }}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || composing.current || event.keyCode === 229) return;
          onKeyDown?.(event);
          if (event.defaultPrevented || event.key !== 'Enter' || event.shiftKey
            || (submitShortcut === 'mod-enter' && !event.metaKey && !event.ctrlKey)) return;
          event.preventDefault();
          submit();
        }} />
    </div>
    {notice}
    {(children || showSubmit || trailingActions) && <div className="agt-compose__row">
      {children}
      {showSubmit && <button type="button" className={`agt-send${submitContent === undefined ? '' : ' is-label'}`}
        aria-label={submitLabel} title={submitTitle} disabled={!submitEnabled} onClick={submit}>{submitContent ?? '↑'}</button>}
      {trailingActions}
    </div>}
  </div>;
}

export function ComposerAttachments({ items, disabled, onRemove }: {
  items: { id: string; name: string; preview: ReactNode }[];
  disabled: boolean;
  onRemove?: (id: string) => void;
}) {
  return items.length === 0 ? null : <div className="agt-drafts" aria-label="Images to send">
    {items.map(item => <span className="agt-draft" key={item.id}>
      {item.preview}
      <button type="button" className="agt-draft__remove" aria-label={`Remove image ${item.name}`}
        disabled={disabled || onRemove === undefined} onClick={() => onRemove?.(item.id)}><X aria-hidden="true" /></button>
    </span>)}
  </div>;
}
