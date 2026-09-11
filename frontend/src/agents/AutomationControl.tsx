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
import { useEffect, useId, useRef } from 'react';
import type { DevelopmentAutomationDto, ModelProviderDto } from '../types';
import { AUTOMATION_OPTIONS, automationForSubmission, automationSummary } from './developmentAutomation';
import '../css/development-automation.css';

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
      <option value="">Choose CI repair agent</option>
      {value !== null && selected === undefined && <option value={value}>{value} · Unavailable</option>}
      {supported.map(provider => <option key={provider.id} value={provider.id}>
        {provider.label}{provider.readiness === 'READY' || provider.readiness === 'DEGRADED' ? '' : ' · Unavailable'}
      </option>)}
    </select>
    {selected === undefined && <small>{value === null ? 'Select a supported agent to enable CI repairs.' : 'This agent does not support CI Autofix.'}</small>}
    {selected !== undefined && selected.readiness !== 'READY' && selected.readiness !== 'DEGRADED' && <small>{selected.diagnostic}</small>}
  </div>;
}

export function AutomationControl({ value, providers, disabled, futureTurns, localOnly, onChange, onLocalOnlyChange }: {
  value: DevelopmentAutomationDto;
  providers: ModelProviderDto[];
  disabled?: boolean;
  futureTurns?: boolean;
  localOnly: boolean;
  onChange: (value: DevelopmentAutomationDto) => void;
  onLocalOnlyChange: (value: boolean) => void;
}) {
  const ref = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    // Capture phase, because the model picker stops mousedown from bubbling past its own trigger.
    const close = (event: MouseEvent): void => {
      if (ref.current?.open && !ref.current.contains(event.target as Node)) ref.current.open = false;
    };
    window.addEventListener('mousedown', close, true);
    return () => window.removeEventListener('mousedown', close, true);
  }, []);
  return <details ref={ref} className="agt-automation" onKeyDown={event => { if (event.key === 'Escape') event.currentTarget.open = false; }}>
    <summary className="agt-compose__chip" aria-label="Automation settings">
      Automation <span className="agt-picker__effort">{automationSummary(automationForSubmission(value, localOnly))}</span>
    </summary>
    <div className="agt-automation-options">
      <strong>Automation{futureTurns ? ' · For future turns' : ''}</strong>
      {AUTOMATION_OPTIONS.map(option => <label key={option.key} className="agt-automation-option">
        <input type="checkbox" checked={value[option.key]} disabled={disabled || localOnly}
          onChange={event => onChange({ ...value, [option.key]: event.target.checked })} />
        <span>{option.title}<small>{option.description}</small></span>
      </label>)}
      <CiProviderSelect value={value.ciLaneId} providers={providers} disabled={disabled || localOnly || !value.autoCiFix}
        onChange={ciLaneId => onChange({ ...value, ciLaneId })} />
      <label className="agt-automation-option is-local">
        <input type="checkbox" checked={localOnly} disabled={disabled} onChange={event => onLocalOnlyChange(event.target.checked)} />
        <span>Local only for this turn<small>Skip all three steps for this submission.</small></span>
      </label>
    </div>
  </details>;
}
