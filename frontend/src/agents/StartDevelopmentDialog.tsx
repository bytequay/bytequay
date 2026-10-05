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
import { useEffect, useState } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import { developmentDefaults, displayModelName, effortLabel, isDeepSeekKeyLane, laneDefaults, laneLabel, laneModels, modelEffort, providerAvailable, providerStatusLabel } from '../settings/model';
import type { TaskLaunch } from './AgentTasksView';
import type { ModelProviderDto } from '../types';

export default function StartDevelopmentDialog({ workspaceId, issueNumber, onConfirm, onCancel }: {
  workspaceId: string;
  issueNumber: number;
  onConfirm: (launch: TaskLaunch) => void;
  onCancel: () => void;
}) {
  const [providers, setProviders] = useState<ModelProviderDto[] | null>(null);
  const [laneId, setLaneId] = useState('');
  const [model, setModel] = useState('');
  const [effort, setEffort] = useState('');

  useEffect(() => {
    let active = true;
    void window.bridge.getModelSettings(workspaceId)
      .then(settings => {
        if (!active) return;
        const providers = settings.providers.filter(provider => !isDeepSeekKeyLane(provider.id));
        const defaults = developmentDefaults({ ...settings, providers });
        setProviders(providers);
        setLaneId(defaults.laneId);
        setModel(defaults.model);
        setEffort(defaults.effort ?? '');
      })
      // An unreadable catalog offers nothing to choose, which the empty list already says.
      .catch(() => { if (active) setProviders([]); });
    return () => { active = false; };
  }, [workspaceId]);

  return <StartDevelopmentChoice
    issueNumber={issueNumber}
    providers={providers}
    laneId={laneId}
    model={model}
    effort={effort}
    onSelectLane={lane => {
      const defaults = laneDefaults(providers ?? [], lane);
      setLaneId(lane);
      setModel(defaults.model);
      setEffort(defaults.effort);
    }}
    onSelectModel={next => {
      setModel(next);
      setEffort(current => modelEffort(laneModels(providers, laneId).find(option => option.id === next), current));
    }}
    onSelectEffort={setEffort}
    onConfirm={() => onConfirm({ laneId, model, effort: effort === '' ? null : effort })}
    onCancel={onCancel}
  />;
}

export function StartDevelopmentChoice({ issueNumber, providers, laneId, model, effort, onSelectLane, onSelectModel, onSelectEffort, onConfirm, onCancel }: {
  issueNumber: number;
  providers: ModelProviderDto[] | null;
  laneId: string;
  model: string;
  effort: string;
  onSelectLane: (laneId: string) => void;
  onSelectModel: (model: string) => void;
  onSelectEffort: (effort: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const models = laneModels(providers, laneId);
  const efforts = models.find(option => option.id === model)?.efforts ?? [];
  return (
    <ConfirmDialog
      title={`Start development on #${issueNumber}?`}
      confirmLabel="Start"
      confirmDisabled={!providers?.some(provider => provider.id === laneId && providerAvailable(provider.readiness)) || !models.some(option => option.id === model)}
      onConfirm={onConfirm}
      onCancel={onCancel}
    >
      <p>Choose the agent that opens this issue&rsquo;s task. You can still change it before sending the first message.</p>
      {providers === null && <p>Loading providers…</p>}
      {providers !== null && providers.length === 0 && (
        <p role="alert">No agent provider is available. Check them in Workspace settings.</p>
      )}
      {providers !== null && providers.length > 0 && (
        <label className="bq-confirm-dialog__choice">
          <span>Agent provider</span>
          <select value={laneId} onChange={event => onSelectLane(event.target.value)}>
            {providers.map(provider => {
              const available = providerAvailable(provider.readiness);
              return (
                <option key={provider.id} value={provider.id} disabled={!available}>
                  {provider.label || laneLabel(provider.id)}
                  {available ? '' : ` — ${providerStatusLabel(provider.readiness, false).label}`}
                </option>
              );
            })}
          </select>
        </label>
      )}
      {models.length > 0 && (
        <label className="bq-confirm-dialog__choice">
          <span>Model</span>
          <select value={model} onChange={event => onSelectModel(event.target.value)}>
            {models.map(option => <option key={option.id} value={option.id}>{displayModelName(option.name)}</option>)}
          </select>
        </label>
      )}
      {efforts.length > 0 && (
        <label className="bq-confirm-dialog__choice">
          <span>Effort</span>
          <select value={effort} onChange={event => onSelectEffort(event.target.value)}>
            {efforts.map(option => <option key={option} value={option}>{effortLabel(option)}</option>)}
          </select>
        </label>
      )}
    </ConfirmDialog>
  );
}
