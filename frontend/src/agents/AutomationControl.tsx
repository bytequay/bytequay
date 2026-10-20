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
import { GitCommitHorizontal, GitMerge, GitPullRequest, LoaderCircle, Lock, MessageSquareCheck, RotateCcw, Wrench, type LucideIcon } from 'lucide-react';
import { useId, useRef, useState, type CSSProperties } from 'react';
import '../css/development-automation.css';
import { Chevron } from '../Chevron';
import type { AutomationLevel, DevelopmentAutomationDto, ModelProviderDto } from '../types';
import { providerAvailable } from '../settings/model';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import { AUTOMATION_LEVELS } from './contracts';
import { AUTOMATION_STEPS, levelIndex, levelTitle, type StepStatus } from './developmentAutomation';

export function CiProviderSelect({ value, providers, disabled, onChange }: {
  value: string | null;
  providers: ModelProviderDto[];
  disabled?: boolean;
  onChange: (value: string | null) => void;
}) {
  const id = useId();
  const supported = providers.filter(provider => provider.capabilities.permissionPolicies.includes('CI_AUTOFIX'));
  const selected = supported.find(provider => provider.id === value);
  return <div className="agt-automation-ci">
    <label htmlFor={id}>CI repair agent</label>
    <select id={id} value={value ?? ''} disabled={disabled} onChange={event => onChange(event.target.value || null)}>
      <option value="">Same agent as development</option>
      {value !== null && selected === undefined && <option value={value}>{value} · Unavailable</option>}
      {supported.map(provider => <option key={provider.id} value={provider.id}>
        {provider.label}{providerAvailable(provider.readiness) ? '' : ' · Unavailable'}
      </option>)}
    </select>
    {value !== null && selected === undefined && <small>This agent does not support CI Autofix.</small>}
    {selected !== undefined && !providerAvailable(selected.readiness) && <small>{selected.diagnostic}</small>}
  </div>;
}

const ICONS: Record<AutomationLevel, LucideIcon> = {
  COMMIT: GitCommitHorizontal,
  DRAFT_PR: GitPullRequest,
  CI_FIX: Wrench,
  REVIEW_FIX: MessageSquareCheck,
  MERGE: GitMerge,
};

export function StepIcon({ level, size = 14 }: { level: AutomationLevel; size?: number }) {
  const Icon = ICONS[level];
  return <Icon size={size} strokeWidth={2} aria-hidden="true" />;
}

/**
 * The scale as one slider. A native range input lies over the drawn track, so dragging, clicking a stop
 * and the arrow keys all come from the platform; its thumb is as wide as the track's end insets, which
 * puts each value exactly under its drawn stop.
 */
export function AutomationSlider({ value, onChange }: {
  value: AutomationLevel;
  onChange?: (level: AutomationLevel) => void;
}) {
  const index = levelIndex(value);
  const last = AUTOMATION_LEVELS.length - 1;
  const locked = onChange === undefined;
  return <div className={`agt-auto-slider${locked ? ' is-locked' : ''}`}
    style={{ '--stop': index, '--stops': last, '--end': index === last ? 1 : 0 } as CSSProperties}>
    <span className="agt-auto-slider__fill" />
    {AUTOMATION_STEPS.map((step, stop) => stop !== index && <span key={step.level}
      className={`agt-auto-slider__dot${stop < index ? ' is-filled' : ''}`} style={{ '--i': stop } as CSSProperties} />)}
    <span className="agt-auto-slider__knob">{locked ? <Lock size={14} strokeWidth={2} aria-hidden="true" /> : <StepIcon level={value} />}</span>
    <input type="range" min={0} max={last} step={1} value={index} disabled={locked}
      aria-label="Automation" aria-valuetext={levelTitle(value)}
      onChange={event => onChange?.(AUTOMATION_LEVELS[Number(event.target.value)])} />
  </div>;
}

/** What automation is doing now, as one pill whose square at the end stops it. */
export function AutomationPill({ label, tone, stopLabel, stopTitle, stopDisabled, onStop }: {
  label: string;
  tone: 'blue' | 'amber';
  stopLabel: string;
  stopTitle: string;
  stopDisabled: boolean;
  onStop: () => void;
}) {
  return <span className={`agt-auto-pill is-${tone}`}>
    <span className="agt-auto-pill__label" role="status"><LoaderCircle className="agt-auto-pill__spin" size={12} aria-hidden="true" />{label}</span>
    <button type="button" className="agt-auto-pill__stop" aria-label={stopLabel} title={stopTitle} disabled={stopDisabled} onClick={onStop}>
      <span aria-hidden="true" />
    </button>
  </span>;
}

/** A step's icon on a tile coloured by its status; the hover text says the status in words. */
function StepTile({ level, status }: { level: AutomationLevel; status?: StepStatus }) {
  return <span className={`agt-auto-chip__step${status === undefined ? '' : ` is-${status.state}`}`}
    title={status === undefined ? undefined : `${levelTitle(level)} · ${status.note}`}>
    <StepIcon level={level} />
  </span>;
}

/**
 * The composer's automation chip: one icon per step that is on. New tasks open a card with the slider;
 * a started task opens the same card locked, because its automation is fixed when it starts, and its
 * icons carry each step's status.
 */
export function AutomationControl({ value, defaultValue, disabled = false, status, onChange }: {
  value: DevelopmentAutomationDto;
  /** The Settings default that reset returns to. */
  defaultValue?: DevelopmentAutomationDto;
  disabled?: boolean;
  status?: Partial<Record<AutomationLevel, StepStatus>>;
  onChange?: (value: DevelopmentAutomationDto) => void;
}) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const ref = usePopoverDismiss<HTMLDivElement>(event => {
    setOpen(false);
    if (event.type === 'keydown') triggerRef.current?.focus();
  }, false, open);
  const cardId = useId();
  const locked = onChange === undefined;
  const title = levelTitle(value.level);
  const steps = AUTOMATION_STEPS.slice(0, levelIndex(value.level) + 1);
  return <div ref={ref} className="agt-auto" onMouseDown={event => event.stopPropagation()}>
    <button ref={triggerRef} type="button" className={`agt-compose__chip agt-auto-chip${locked ? ' is-locked' : ''}`}
      aria-label={`Automation: ${title}`} title={title} aria-expanded={open} aria-controls={open ? cardId : undefined}
      disabled={disabled} onClick={() => setOpen(current => !current)}>
      {steps.map(step => <StepTile key={step.level} level={step.level} status={status?.[step.level]} />)}
      {!locked && <Chevron open size={10} />}
    </button>
    {open && <div id={cardId} className={`agt-auto-card${locked ? ' is-locked' : ''}`} role="group" aria-label="Automation">
      <div className="agt-auto-card__head">
        <span />
        <div className="agt-auto-card__name">
          <span>{title}</span>
          <span className={`agt-auto-card__trail${status === undefined ? '' : ' has-status'}`}>{steps.map(step => status === undefined
            ? <StepIcon key={step.level} level={step.level} />
            : <StepTile key={step.level} level={step.level} status={status[step.level]} />)}</span>
        </div>
        {locked
          ? <span className="agt-auto-card__corner" title="Automation is fixed when a task starts"><Lock size={14} aria-hidden="true" /></span>
          : <button type="button" className="agt-auto-card__corner" aria-label="Reset to default" title="Reset to default"
            disabled={defaultValue === undefined || defaultValue.level === value.level}
            onClick={() => { if (defaultValue !== undefined) onChange({ ...value, level: defaultValue.level }); }}>
            <RotateCcw size={16} aria-hidden="true" />
          </button>}
      </div>
      <AutomationSlider value={value.level} onChange={locked ? undefined : level => onChange({ ...value, level })} />
    </div>}
  </div>;
}
