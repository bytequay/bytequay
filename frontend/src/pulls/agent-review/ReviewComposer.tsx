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
import { useRef, useState, type ReactNode } from 'react';
import type { SuggestionBase } from '../MarkdownBody';
import MarkdownEditor from '../MarkdownEditor';

/** The same editor writes private questions and outgoing comments, so its destination stays visible. */
export default function ReviewComposer({
  repository,
  value,
  placeholder,
  ariaLabel,
  primaryLabel,
  sendingLabel = 'Sending…',
  outgoing = null,
  suggestion,
  busy = false,
  disabledReason = null,
  autoFocus = false,
  allowEmpty = false,
  secondary,
  extra,
  onChange,
  onSubmit,
  onCancel,
}: {
  repository: string;
  value: string;
  placeholder: string;
  ariaLabel: string;
  primaryLabel: string;
  sendingLabel?: string;
  /** Where the text will be posted, when it leaves ByteQuay; private when absent. */
  outgoing?: string | null;
  suggestion?: SuggestionBase;
  busy?: boolean;
  /** Blocks only the primary action; editing and cancelling stay available. */
  disabledReason?: string | null;
  autoFocus?: boolean;
  allowEmpty?: boolean;
  /** Takes the Cancel button's place; Escape still cancels. */
  secondary?: ReactNode;
  /** Sits before the buttons, such as Resolve's Remember checkbox. */
  extra?: ReactNode;
  onChange: (value: string) => void;
  /** The host closes or clears on success and presents errors; keep its draft until this settles. */
  onSubmit: () => void | Promise<void>;
  onCancel: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const inFlight = useRef(false);
  const ready = (allowEmpty || value.trim() !== '') && !busy && disabledReason === null;
  const submit = async () => {
    if (!ready || inFlight.current) return;
    inFlight.current = true;
    try {
      const pending = onSubmit();
      if (pending !== undefined) {
        setSubmitting(true);
        await pending;
      }
    }
    finally { inFlight.current = false; setSubmitting(false); }
  };

  if (submitting) return <p className="pr-local-review__note" role="status">{sendingLabel}</p>;

  return (
    <MarkdownEditor
      className="agr-composer"
      value={value}
      onChange={onChange}
      ariaLabel={ariaLabel}
      placeholder={placeholder}
      repository={repository}
      suggestion={suggestion}
      isPrivate={outgoing === null}
      note={outgoing !== null && <p className="pull-diff-review__nothing agr-composer__outgoing">{outgoing}</p>}
      statusMessage={disabledReason}
      busy={busy}
      autoFocus={autoFocus}
      onSubmit={() => { void submit(); }}
      onCancel={() => { if (!inFlight.current) onCancel(); }}>
      {extra}
      {secondary ?? <button type="button" disabled={busy} onClick={onCancel}>Cancel</button>}
      <button type="button" className="is-primary" disabled={!ready} title={disabledReason ?? undefined} onClick={() => { void submit(); }}>
        {primaryLabel}
      </button>
    </MarkdownEditor>
  );
}
