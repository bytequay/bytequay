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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { LinkedWorkRequest } from '../LinkedWorkspaceWork';
import { TurnView } from '../AgentConversation';
import { AgentTaskList } from '../AgentTasksView';
import type { LinkedWorkspaceWorkDto } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/home.css';
import '../../css/settings-window.css';

const base: LinkedWorkspaceWorkDto = {
  sourceWorkspaceId: 'repository', sourceRepository: 'owner/repository', sourceTaskId: 'source', sourceTitle: 'Investigate Iceberg upgrade', sourceTurnId: 'source-turn',
  targetTaskId: 'destination', targetWorkspaceId: 'iceberg', targetTitle: 'Check API behavior', repository: 'apache/iceberg',
  kind: 'DEPENDENCY', purpose: 'INVESTIGATE', question: 'Check whether v1.6.0 changes manifest caching for the Repository connector.',
  revision: 'v1.6.0', commit: 'a'.repeat(40), state: 'FINISHED', turnId: 'first-turn',
  result: '**Finding:** The cache now expires after the configured interval.\n\n### Commands and limitations\n\nCommands: `./gradlew :iceberg-core:test`\n\nOutcome: fixture passed.\n\nLimitations: no live catalog tested.',
  limitation: null, createdAt: '2026-01-01T00:00:00Z', archived: false,
};
export function mount() {
  const stops: unknown[][] = [];
  const links = [
    { ...base, state: 'NEEDS_YOU', result: null, createdAt: '1' },
    { ...base, state: 'UNAVAILABLE', targetTaskId: null, result: null, limitation: 'Managed checkout is unavailable.', createdAt: '2' },
    { ...base, state: 'RUNNING', result: null, createdAt: '3' },
    base,
    { ...base, state: 'STOPPED', result: null, createdAt: '4' },
  ];
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getLinkedWorkspaceWork: async () => links,
    getAgentRuntimeStopImpact: async (...args: unknown[]) => { stops.push(args); return { generation: 'expected-generation' }; },
    stopAgentRuntime: async (...args: unknown[]) => { stops.push(args); },
  } });
  const host = document.createElement('main');
  host.style.cssText = 'max-width:1080px;margin:20px auto';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskList workspaceId="repository" repository="owner/repository" tasks={[]} loading={false} error={null}
    laneId="codex" providers={[]} models={[]} selection={null} draft="" blocker={null} initialType="linked"
    onDraftChange={() => {}} onSelectProvider={() => {}} onSelectModel={() => {}} onCreate={() => {}} onOpenTask={() => {}} />));
  return { host, stops, showRequest: () => flushSync(() => root.render(<TurnView
    turn={{ turnId: 'first-turn', ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
      users: [{ id: 'request', text: 'Raw generated instructions', attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
    first={true} open={false} now={0} models={[]} durableTurns={{}} activityChoice={{}} previews={{}} agentLabels={{}}
    onToggle={() => {}} onToggleActivity={() => {}} turnRequest={<LinkedWorkRequest work={base} />} />)), close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

export async function run() {
  const view = mount();
  try {
    await settle();
    assert(view.host.textContent?.includes('Linked work'), 'Existing Tasks sub-tab');
    assert(![...view.host.querySelectorAll('button')].some(button => button.textContent?.includes('New task')), 'No task creation button for linked work');
    assert(view.host.querySelectorAll('.linked-work-card').length === 5, 'Every saved link shown');
    assert([...view.host.querySelectorAll('.linked-work-list h3')].map(heading => heading.textContent?.trim()).join(',') === 'Needs you 2,Running 1,Finished 1,Stopped 1', 'Prototype groups and counts');
    assert(view.host.querySelector('a[href="#/workspaces/repository/tasks/agent/source"]') !== null, 'Source conversation link');
    assert(view.host.querySelector('a[href="#/workspaces/iceberg/tasks/agent/destination"]') !== null, 'Destination conversation link');
    assert(view.host.querySelector('code[title="' + base.commit + '"]') !== null, 'Exact SHA available on hover');
    assert(view.host.querySelector('.linked-work-result strong')?.textContent === 'Finding:', 'Actual rendered Markdown result');
    assert(view.host.textContent?.includes('Limitations: no live catalog tested.'), 'Result limitations preserved');
    assert(view.host.textContent?.includes('Open workspace settings'), 'Unavailable recovery link');
    const stop = [...view.host.querySelectorAll('button')].find(button => button.textContent === 'Stop');
    assert(stop !== undefined, 'Stop action');
    stop.click(); await settle();
    assert(JSON.stringify(view.stops) === JSON.stringify([['destination', 'iceberg'], ['destination', 'iceberg', 'expected-generation']]), 'Reuse version-guarded destination Stop');
    view.showRequest(); await settle();
    assert(view.host.textContent?.includes('ByteQuay · Request from owner/repository'), 'Destination attribution');
    assert(!view.host.textContent?.includes('Raw generated instructions'), 'Shows focused request bubble');
    assert(view.host.querySelector('a[href="#/workspaces/repository/tasks/agent/source"]') !== null, 'Return to original conversation');
    return [{ name: 'linked-work groups, links, results and Stop' }];
  } finally { view.close(); }
}
