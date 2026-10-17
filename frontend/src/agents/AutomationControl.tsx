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
import { useId, useState } from 'react';
import '../css/development-automation.css';
import type { DevelopmentAutomationDto, ModelProviderDto } from '../types';
import { providerAvailable } from '../settings/model';
import { usePopoverDismiss } from '../usePopoverDismiss';
import { AUTOMATION_OPTIONS, automationForSubmission, automationSummary } from './developmentAutomation';

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

/** A started task renders this disabled and without handlers: its automation cannot change. */
export function AutomationControl({ value, providers, disabled, localOnly = false, onChange, onLocalOnlyChange }: {
  value: DevelopmentAutomationDto;
  providers: ModelProviderDto[];
  disabled?: boolean;
  localOnly?: boolean;
  onChange?: (value: DevelopmentAutomationDto) => void;
  onLocalOnlyChange?: (value: boolean) => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const ref = usePopoverDismiss<HTMLDetailsElement>(event => {
    if (ref.current) ref.current.open = false;
    if (event.type === 'keydown') ref.current?.querySelector<HTMLElement>('summary')?.focus();
  }, false, pickerOpen);
  return <details onToggle={event => setPickerOpen(event.currentTarget.open)} ref={ref} className="agt-automation">
    <summary className="agt-compose__chip" aria-label="Automation settings">
      Automation <span className="agt-picker__effort">{automationSummary(automationForSubmission(value, localOnly))}</span>
    </summary>
    <div className="agt-automation-options">
      <strong>Automation</strong>
      <p>Finished development work is committed automatically. Review experiments stay uncommitted.</p>
      {AUTOMATION_OPTIONS.map(option => <label key={option.key} className="agt-automation-option">
        <input type="checkbox" checked={value[option.key]} disabled={disabled || (localOnly && option.key !== 'reviewSummary')}
          onChange={event => onChange?.({ ...value, [option.key]: event.target.checked })} />
        {option.title}
      </label>)}
      <CiProviderSelect value={value.ciLaneId} providers={providers} disabled={disabled || localOnly || !value.autoCiFix}
        onChange={ciLaneId => onChange?.({ ...value, ciLaneId })} />
      <label className="agt-automation-option is-local">
        <input type="checkbox" checked={localOnly} disabled={disabled} onChange={event => onLocalOnlyChange?.(event.target.checked)} />
        Keep commits local for this turn
      </label>
    </div>
  </details>;
}
