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

import React, { useEffect, useState } from 'react';
import { Chevron } from '../Chevron';
import { displayModelName, effortLabel } from '../settings/model';
import type { ModelOptionDto } from '../types';

export type PickerView = 'models' | 'effort';

/**
 * The composer's model control, as Codex draws its own: the chip opens a floating card that
 * lists the models by their short names, and once one is chosen the card becomes that model's
 * effort slider. The chip opens straight onto the slider when a model is already chosen; the
 * slider's heading goes back to the list.
 */
export function ModelPicker({ models, selectedModel, selectedEffort, disabled = false, ariaLabel, onSelect }: {
  models: ModelOptionDto[];
  selectedModel: string;
  selectedEffort?: string;
  disabled?: boolean;
  ariaLabel: string;
  onSelect: (model: string, effort: string | null) => void;
}): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<PickerView>('models');
  const current = models.find((model) => model.id === selectedModel);

  useEffect(() => {
    if (!open) return;
    const close = (): void => setOpen(false);
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [open]);

  return (
    <div className="agt-picker" onMouseDown={(event) => event.stopPropagation()}>
      <button
        type="button"
        className="agt-picker__chip"
        aria-label={ariaLabel}
        aria-expanded={open}
        disabled={disabled}
        onClick={() => {
          setView(current === undefined ? 'models' : 'effort');
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
          selectedModel={selectedModel}
          selectedEffort={selectedEffort}
          onSelect={onSelect}
          onView={setView}
        />
      )}
    </div>
  );
}

/** The floating card itself, one of two views; split out so each view can be rendered on its own. */
export function ModelPickerCard({ view, models, selectedModel, selectedEffort, onSelect, onView }: {
  view: PickerView;
  models: ModelOptionDto[];
  selectedModel: string;
  selectedEffort?: string;
  onSelect: (model: string, effort: string | null) => void;
  onView: (view: PickerView) => void;
}): React.JSX.Element {
  const current = models.find((model) => model.id === selectedModel);
  if (view === 'models' || current === undefined) {
    return (
      <div className="agt-picker__card" role="listbox" aria-label="Models">
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
              onView('effort');
            }}
          >
            {displayModelName(model.name)}
            {model.id === selectedModel && <span className="agt-picker__check" aria-hidden="true">✓</span>}
          </button>
        ))}
      </div>
    );
  }
  const efforts = current.efforts;
  const index = Math.max(0, efforts.indexOf(selectedEffort ?? ''));
  const last = efforts.length - 1;
  const fill = last <= 0 ? 100 : (index / last) * 100;
  return (
    <div className="agt-picker__card is-effort">
      <button type="button" className="agt-picker__head" onClick={() => onView('models')} title="Choose another model">
        <span className="agt-bolt" aria-hidden="true">⚡</span>
        <span className="agt-picker__model">{displayModelName(current.name)}</span>
        {efforts.length > 0 && <span className="agt-picker__level">{effortLabel(efforts[index])}</span>}
        <Chevron size={12} />
      </button>
      {efforts.length > 1
        ? (
          <div className={`agt-picker__slider${index === last ? ' is-max' : ''}`} style={{ '--fill': `${fill}%` } as React.CSSProperties}>
            <input
              type="range"
              className="agt-picker__range"
              min={0}
              max={last}
              step={1}
              value={index}
              aria-label={`Effort for ${displayModelName(current.name)}`}
              aria-valuetext={effortLabel(efforts[index])}
              onChange={(event) => onSelect(current.id, efforts[Number(event.target.value)])}
            />
            <span className="agt-picker__stops" aria-hidden="true">
              {/* The stop under the thumb stays hidden: the thumb is the mark for that level. */}
              {efforts.map((effort, stop) => <span className={`agt-picker__stop${stop === index ? ' is-under-thumb' : ''}`} key={effort} />)}
            </span>
          </div>
        )
        : <div className="agt-picker__single">{efforts.length === 1 ? `${effortLabel(efforts[0])} is this model's only effort level.` : 'This model has no effort setting.'}</div>}
    </div>
  );
}
