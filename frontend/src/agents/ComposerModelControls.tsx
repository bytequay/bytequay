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
import type { ReactNode } from 'react';
import type { ModelOptionDto, ModelProviderDto } from '../types';
import { ModelPicker } from './ModelPicker';
import { ProviderPicker } from './ProviderPicker';

export default function ComposerModelControls({
  models, model, effort, serviceTier, provider = '', providers, laneId, onSelectProvider, onOpenSettings,
  onSelectModel, disabled, modelLabel, automation, onAttach, attachEnabled, attachTitle,
}: {
  models: ModelOptionDto[];
  model: string | null;
  effort?: string | null;
  serviceTier?: string | null;
  provider?: string;
  providers?: ModelProviderDto[];
  laneId?: string;
  onSelectProvider?: (laneId: string) => void;
  onOpenSettings?: (laneId: string) => void;
  onSelectModel?: (model: string, effort: string | null, serviceTier: string | null) => void;
  disabled: boolean;
  modelLabel: string;
  automation?: ReactNode;
  onAttach?: () => void;
  attachEnabled: boolean;
  attachTitle: string;
}) {
  return <>
    <button type="button" className="agt-attach" aria-label="Attach an image" title={attachTitle}
      disabled={!attachEnabled || disabled || onAttach === undefined} onClick={onAttach}>+</button>
    {providers !== undefined && laneId !== undefined && onSelectProvider !== undefined && <ProviderPicker
      providers={providers} selectedId={laneId} onSelect={onSelectProvider} onOpenSettings={onOpenSettings} disabled={disabled} />}
    {automation}
    <ModelPicker ariaLabel={modelLabel} disabled={models.length === 0 || disabled || onSelectModel === undefined}
      models={models} provider={provider} selectedModel={model ?? ''} selectedEffort={effort ?? undefined} selectedServiceTier={serviceTier}
      onSelect={onSelectModel ?? (() => {})} />
  </>;
}
