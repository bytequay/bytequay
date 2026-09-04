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
import { displayModelName, effortLabel } from '../settings/model';
import type { ModelOptionDto } from '../types';

export type PickerView = 'menu' | 'models' | 'effort';

/**
 * The composer's model control: the chip opens a floating card with two rows, Model and Effort,
 * each opening the list it names under a heading, with the current choice checked. Choosing
 * returns to the rows; the chip opens straight onto the model list while nothing is chosen.
 */
export function ModelPicker({ models, selectedModel, selectedEffort, provider = '', disabled = false, ariaLabel, onSelect }: {
  models: ModelOptionDto[];
  selectedModel: string;
  selectedEffort?: string;
  provider?: string;
  disabled?: boolean;
  ariaLabel: string;
  onSelect: (model: string, effort: string | null) => void;
}): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<PickerView>('menu');
  const current = models.find((model) => model.id === selectedModel);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const pickerRef = useRef<HTMLDivElement>(null);
  const cardId = useId();

  useEffect(() => {
    if (!open) return;
    const first = pickerRef.current?.querySelector<HTMLButtonElement>('.agt-picker__card [aria-selected="true"]')
      ?? pickerRef.current?.querySelector<HTMLButtonElement>('.agt-picker__card button:not(:disabled)');
    first?.focus();
    const close = (): void => setOpen(false);
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
    };
    window.addEventListener('mousedown', close);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('mousedown', close);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [open, view]);

  return (
    <div ref={pickerRef} className="agt-picker" onMouseDown={(event) => event.stopPropagation()}>
      <button
        ref={triggerRef}
        type="button"
        className="agt-picker__chip"
        aria-label={ariaLabel}
        aria-haspopup={current === undefined ? 'listbox' : 'menu'}
        aria-expanded={open}
        aria-controls={open ? cardId : undefined}
        disabled={disabled}
        onKeyDown={(event) => {
          if (event.key !== 'ArrowDown') return;
          event.preventDefault();
          setView(current === undefined ? 'models' : 'menu');
          setOpen(true);
        }}
        onClick={() => {
          setView(current === undefined ? 'models' : 'menu');
          setOpen((value) => !value);
        }}
      >
        <span className="agt-bolt" aria-hidden="true">⚡</span>
        {current === undefined ? 'Select model' : displayModelName(current.name)}
        {current !== undefined && selectedEffort !== undefined && (
          <span className="agt-picker__effort">{effortLabel(selectedEffort)}</span>
        )}
        <Chevron open size={11} />
      </button>
      {open && (
        <ModelPickerCard
          view={view}
          models={models}
          provider={provider}
          selectedModel={selectedModel}
          selectedEffort={selectedEffort}
          cardId={cardId}
          onSelect={onSelect}
          onView={setView}
        />
      )}
    </div>
  );
}

/** The floating card itself, one of three views; split out so each view can be rendered on its own. */
export function ModelPickerCard({ view, models, provider = '', selectedModel, selectedEffort, cardId, onSelect, onView }: {
  view: PickerView;
  models: ModelOptionDto[];
  provider?: string;
  selectedModel: string;
  selectedEffort?: string;
  cardId?: string;
  onSelect: (model: string, effort: string | null) => void;
  onView: (view: PickerView) => void;
}): React.JSX.Element {
  const current = models.find((model) => model.id === selectedModel);
  if (view === 'models' || current === undefined) {
    return (
      <div id={cardId} className="agt-picker__card" role="listbox" aria-label="Models">
        {provider !== '' && <div className="agt-picker__heading">{provider}</div>}
        {models.map((model) => (
          <button
            type="button"
            role="option"
            key={model.id}
            className={`agt-picker__row${model.id === selectedModel ? ' is-current' : ''}`}
            aria-selected={model.id === selectedModel}
            onClick={() => {
              // A change of model keeps the effort when the new model offers it, else takes its default.
              onSelect(
                model.id,
                selectedEffort !== undefined && model.efforts.includes(selectedEffort) ? selectedEffort : model.defaultEffort ?? null,
              );
              onView('menu');
            }}
          >
            {displayModelName(model.name)}
            {model.id === selectedModel && <span className="agt-picker__check" aria-hidden="true">✓</span>}
          </button>
        ))}
      </div>
    );
  }
  if (view === 'effort') {
    return (
      <div id={cardId} className="agt-picker__card" role="listbox" aria-label={`Effort for ${displayModelName(current.name)}`}>
        <div className="agt-picker__heading">Effort</div>
        {current.efforts.map((effort) => (
          <button
            type="button"
            role="option"
            key={effort}
            className={`agt-picker__row${effort === selectedEffort ? ' is-current' : ''}`}
            aria-selected={effort === selectedEffort}
            onClick={() => {
              onSelect(current.id, effort);
              onView('menu');
            }}
          >
            {effortLabel(effort)}
            {effort === selectedEffort && <span className="agt-picker__check" aria-hidden="true">✓</span>}
          </button>
        ))}
        {current.efforts.length === 0 && <div className="agt-picker__single">This model has no effort setting.</div>}
      </div>
    );
  }
  return (
    <div id={cardId} className="agt-picker__card" role="menu" aria-label="Model and effort">
      <button type="button" role="menuitem" className="agt-picker__item" onClick={() => onView('models')}>
        <span>Model</span>
        <span className="agt-picker__value">{displayModelName(current.name)}</span>
        <Chevron size={11} />
      </button>
      <button type="button" role="menuitem" className="agt-picker__item" disabled={current.efforts.length === 0} onClick={() => onView('effort')}>
        <span>Effort</span>
        <span className="agt-picker__value">{selectedEffort === undefined ? 'None' : effortLabel(selectedEffort)}</span>
        <Chevron size={11} />
      </button>
    </div>
  );
}
