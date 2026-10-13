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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { assert, settle } from '../../../fixtures/harness';
import { NO_AGENT_CAPABILITIES } from '../../../agents/capabilities';
import type { ModelProviderDto } from '../../../types';
import ReviewActivity from '../ReviewActivity';
import type { ReviewModelSelection } from '../contracts';
import { runningReviewFixture } from './review';

export async function run(): Promise<{ name: string; error?: string }[]> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const selections: ReviewModelSelection[] = [];
  const providers: ModelProviderDto[] = [
    { id: 'claude', label: 'Claude Code', readiness: 'READY', diagnostic: '', capabilities: NO_AGENT_CAPABILITIES,
      selectedModel: 'opus', selectedEffort: 'Max', models: [{ id: 'opus', name: 'Opus', description: '', efforts: ['Max'], defaultEffort: 'Max' }] },
    { id: 'codex', label: 'Codex', readiness: 'READY', diagnostic: '', capabilities: NO_AGENT_CAPABILITIES,
      selectedModel: 'gpt-review', selectedEffort: 'high', models: [
        { id: 'gpt-review', name: 'GPT-Review', description: '', efforts: ['high', 'max'], defaultEffort: 'high' },
        { id: 'gpt-other', name: 'GPT-Other', description: '', efforts: ['max'], defaultEffort: 'max' },
      ] },
  ];
  const initial = runningReviewFixture();
  function Surface() {
    const [review, setReview] = useState({ ...initial, task: { ...initial.task, laneId: 'claude', laneLabel: 'Claude Code', selectionLaneId: 'claude', model: 'opus', effort: 'Max' } });
    return <ReviewActivity review={review} events={[]} turns={{}} models={providers.flatMap(provider => provider.models)} providers={providers}
      pendingApprovals={[]} now={Date.now()} composer="Keep my draft" busy={false}
      onCompose={() => {}} onSend={() => {}} onSteer={() => {}} onQueue={() => {}} onStop={() => {}} onDecide={() => {}} onRemoveQueued={() => {}}
      onSelectModel={selection => {
        selections.push(selection);
        setReview(current => ({ ...current, task: { ...current.task, selectionLaneId: selection.laneId, model: selection.model, effort: selection.effort ?? '' } }));
      }} />;
  }
  const button = (label: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label);
    assert(found !== undefined && !found.disabled, `Clickable ${label}`);
    return found;
  };
  const name = 'Provider and model changes preserve the running turn and draft';
  try {
    flushSync(() => root.render(<Surface />));
    await settle();
    button('Provider: Claude Code').click(); await settle();
    const codex = [...host.querySelectorAll<HTMLButtonElement>('.agt-provider-picker__row button')].find(item => item.querySelector('span')?.textContent === 'Codex');
    assert(codex !== undefined && !codex.disabled, 'Clickable Codex provider');
    codex.click(); await settle();
    assert(selections.length === 1 && selections[0].laneId === 'codex' && selections[0].model === 'gpt-review', 'Save the new provider with its model');
    button('Model and effort for the next message').click(); await settle();
    const modelMenu = host.querySelector<HTMLButtonElement>('[role="menuitem"]');
    assert(modelMenu !== null, 'Shared model menu is open');
    modelMenu.click(); await settle();
    const choice = [...host.querySelectorAll<HTMLButtonElement>('[role="option"]')].find(item => item.textContent?.trim() === 'Other');
    assert(choice !== undefined, 'The new provider models are offered');
    choice.click(); await settle();
    assert(selections.at(-1)?.model === 'gpt-other' && selections.at(-1)?.effort === 'max', 'Save the chosen model and supported effort');
    assert(host.querySelector<HTMLTextAreaElement>('textarea')?.value === 'Keep my draft', 'Changing selection preserves the draft');
    assert(host.querySelector('[aria-label="Stop this turn"]') !== null, 'The original turn is still running');
    return [{ name }];
  } catch (cause) {
    return [{ name, error: cause instanceof Error ? cause.message : String(cause) }];
  } finally {
    flushSync(() => root.unmount()); host.remove();
  }
}
