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
import { useRef, type ReactNode, type Ref, type TextareaHTMLAttributes } from 'react';
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
};

/** Shared message entry, keyboard submission and controls; hosts own drafts and delivery. */
export default function Composer({
  className = '', inputRef, header, notice, children, trailingActions, busy = false,
  onSubmit, submitEnabled, showSubmit = true, submitLabel = 'Send message', submitTitle = submitLabel,
  submitContent, submitShortcut = 'enter', onKeyDown, onCompositionStart, onCompositionEnd, rows = 1, ...input
}: ComposerProps) {
  const composing = useRef(false);
  const submit = () => { if (submitEnabled) onSubmit(); };
  return <div className={`agt-compose ${className}`.trim()} aria-busy={busy}>
    <span className="agt-compose__streamer" aria-hidden="true" />
    {header}
    <textarea {...input} ref={inputRef} className="agt-compose__input" rows={rows}
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
