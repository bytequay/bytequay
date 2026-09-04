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

import React, { useEffect, useId, useRef, useState } from 'react';
import { Chevron } from '../Chevron';
import { laneLabel, providerAvailable, providerRemedy, providerStatusLabel } from '../settings/model';
import type { ModelProviderDto } from '../types';

/** The row that receives focus when the popover opens: current when usable, else first usable. */
export function providerFocusTarget(providers: ModelProviderDto[], selectedId: string): string | null {
  const selected = providers.find((provider) => provider.id === selectedId);
  if (selected !== undefined && providerAvailable(selected.readiness)) return selected.id;
  return providers.find((provider) => providerAvailable(provider.readiness))?.id ?? null;
}

/** The creation-only provider choice. A task's lane becomes immutable after the first send. */
export function ProviderPicker({ providers, selectedId, onSelect, onOpenSettings }: {
  providers: ModelProviderDto[];
  selectedId: string;
  onSelect: (laneId: string) => void;
  onOpenSettings?: () => void;
}): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const cardId = useId();
  const selected = providers.find((provider) => provider.id === selectedId);
  const focusTarget = providerFocusTarget(providers, selectedId);

  const close = (restoreFocus = false): void => {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  };

  useEffect(() => {
    if (!open) return;
    const first = cardRef.current?.querySelector<HTMLButtonElement>('[data-provider-focus="lane"]')
      ?? cardRef.current?.querySelector<HTMLButtonElement>('[data-provider-focus="remedy"]');
    first?.focus();
    const onMouseDown = (): void => close();
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      close(true);
    };
    window.addEventListener('mousedown', onMouseDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('mousedown', onMouseDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  return (
    <div className="agt-picker agt-provider-picker" onMouseDown={(event) => event.stopPropagation()}>
      <button
        ref={triggerRef}
        type="button"
        className="agt-compose__chip"
        aria-label={`Provider: ${selected?.label ?? laneLabel(selectedId)}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? cardId : undefined}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            setOpen(true);
          }
        }}
        onClick={() => setOpen((value) => !value)}
      >
        {selected?.label ?? laneLabel(selectedId)} <Chevron open size={11} />
      </button>
      {open && (
        <div
          ref={cardRef}
          id={cardId}
          className="agt-picker__card agt-provider-picker__card"
          role="dialog"
          aria-label="Choose an agent provider"
        >
          <div className="agt-picker__heading">Provider</div>
          {providers.map((provider) => {
            const available = providerAvailable(provider.readiness);
            const status = providerStatusLabel(provider.readiness, false);
            const remedy = providerRemedy(provider.readiness, provider.id);
            return (
              <div className={`agt-provider-picker__row${available ? '' : ' is-unavailable'}`} key={provider.id}>
                <button
                  type="button"
                  className={`agt-picker__row${provider.id === selectedId ? ' is-current' : ''}`}
                  aria-pressed={provider.id === selectedId}
                  disabled={!available}
                  data-provider-focus={provider.id === focusTarget ? 'lane' : undefined}
                  onClick={() => {
                    onSelect(provider.id);
                    close(true);
                  }}
                >
                  <span>{provider.label || laneLabel(provider.id)}</span>
                  <span className={`agt-provider-picker__status ${status.tone}`}>{status.label}</span>
                  {provider.id === selectedId && <span className="agt-picker__check" aria-hidden="true">✓</span>}
                </button>
                {!available && (
                  <div className="agt-provider-picker__remedy">
                    <span>{remedy.title}. {provider.diagnostic || remedy.detail}</span>
                    {onOpenSettings !== undefined && (
                      <button
                        type="button"
                        className="agt-action"
                        data-provider-focus="remedy"
                        onClick={() => {
                          close();
                          onOpenSettings();
                        }}
                      >
                        Open settings
                      </button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
