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
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { normalizeWebAddress } from './webAddress';

type Props = {
  url?: string;
  onNavigate: (url: string) => Promise<void>;
};

export default function WebAddressForm({ url = '', onNavigate }: Props) {
  const input = useRef<HTMLInputElement>(null);
  const errorId = useId();
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);

  useEffect(() => {
    // Redirects update the address, but must not replace text being edited.
    if (document.activeElement !== input.current) setDraft(null);
  }, [url]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (opening) return;
    setError(null);
    try {
      const target = normalizeWebAddress(draft ?? url);
      setDraft(target);
      input.current?.blur();
      setOpening(true);
      await onNavigate(target);
      setDraft(current => current === target ? null : current);
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to open this web page.');
    }
    finally {
      setOpening(false);
    }
  }

  return (
    <div className="web-address">
      <form className="web-address__form" onSubmit={event => { void submit(event); }} aria-label="Open web page">
        <input
          ref={input}
          className="bq-input web-address__input"
          aria-label="Web address"
          aria-invalid={error !== null}
          aria-describedby={error ? errorId : undefined}
          type="text"
          inputMode="url"
          placeholder="Enter a web address"
          autoComplete="off"
          spellCheck={false}
          value={draft ?? url}
          onFocus={event => event.currentTarget.select()}
          onChange={event => { setDraft(event.target.value); setError(null); }}
          onKeyDown={event => {
            if (event.key !== 'Escape') return;
            event.preventDefault();
            event.stopPropagation();
            setDraft(null);
            setError(null);
            event.currentTarget.blur();
          }}
        />
      </form>
      {error && <div className="web-address__error" id={errorId} role="alert">{error}</div>}
    </div>
  );
}
