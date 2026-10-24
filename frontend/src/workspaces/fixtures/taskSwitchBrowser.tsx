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
import { flushSync } from 'react-dom';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import { useTaskAttention } from '../../agents/attention';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, changeInput, runScenarios, until } from '../../fixtures/harness';
import type { AgentTurnDto, Bridge } from '../../types';
import WorkspaceShell from '../WorkspaceShell';
import { workspaceFixture } from './workspace';
import '../../css/base.css';
import '../../css/agents.css';

const WORKSPACE = workspaceFixture();

function TaskWorkspace({ taskId }: { taskId: string }) {
  const taskAttention = useTaskAttention([{ id: WORKSPACE.id, name: '', repository: `${WORKSPACE.monitoredOwner}/${WORKSPACE.monitoredRepo}` }]);
  return <WorkspaceShell workspace={WORKSPACE} taskAttention={taskAttention} tab="tasks" onSelectTab={() => {}}
    onWorkspaceChanged={() => {}} initialPullRequestNumber={null} initialIssueNumber={null} initialAgentTaskId={taskId}
    onSelectAgentTask={() => {}} onSelectPullRequest={() => {}} onSelectIssue={() => {}} />;
}

function fixture() {
  const preview = createPreviewBridge(WORKSPACE.id);
  const first = preview.events[0].taskId;
  const second = 'second-task';
  const readHistory = preview.readAgentHistory;
  let sending: PromiseWithResolvers<AgentTurnDto> | null = null;
  window.bridge = new Proxy({
    ...preview,
    readAgentHistory: (taskId, before, after) => taskId === second
      ? Promise.resolve({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 }) : readHistory(taskId, before, after),
    startAgentTurn: request => {
      preview.commands.push({ name: 'startAgentTurn', args: [request] });
      sending = Promise.withResolvers();
      return sending.promise;
    },
  } as Partial<Bridge> as Bridge, {
    get: (target, name: string) => Reflect.get(target, name) ?? (name.startsWith('on') ? () => () => {} : async (): Promise<never> => { throw new Error(`${name} is not in this fixture`); }),
  });
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height:700px;width:1000px';
  const show = (taskId: string): void => flushSync(() => root.render(<TaskWorkspace taskId={taskId} />));
  const visibleComposer = (): HTMLTextAreaElement | null => [...host.querySelectorAll<HTMLTextAreaElement>('.agt-compose__input')]
    .find(input => input.getClientRects().length > 0) ?? null;
  const reads = (name: string, taskId: string): number => preview.commands.filter(command => command.name === name && command.args[0] === taskId).length;
  return { preview, first, second, show, visibleComposer, reads, sending: () => sending, host, dispose };
}

async function keepsTheTaskShownBefore(): Promise<void> {
  const f = fixture();
  try {
    f.show(f.first);
    await until(() => f.visibleComposer() !== null, 'The first task must open');
    const composer = f.visibleComposer();
    assert(composer !== null, 'The first task needs a composer');
    changeInput(composer, 'Unsent note for the first task');
    await until(() => f.reads('subscribeAgentTask', f.first) === 1, 'The first task must follow its stream');
    const firstReads = f.reads('getAgentTask', f.first);
    f.show(f.second);
    await until(() => f.visibleComposer() !== null && f.visibleComposer() !== composer, 'The second task must open');
    assert(composer.isConnected && composer.getClientRects().length === 0, 'Switching away must keep the first task mounted but hidden');
    assert(f.reads('unsubscribeAgentTask', f.first) === 1, 'A hidden task must stop following its stream');
    f.show(f.first);
    assert(f.visibleComposer() === composer && composer.value === 'Unsent note for the first task',
      'Switching back must show the same page with its draft at once');
    await until(() => f.reads('getAgentTask', f.first) > firstReads && f.reads('subscribeAgentTask', f.first) === 2,
      'A task shown again must re-read itself and follow its stream again');
  }
  finally { f.dispose(); }
}

async function releasesASendThatFinishesAfterSwitching(): Promise<void> {
  const f = fixture();
  try {
    f.show(f.first);
    await until(() => f.visibleComposer() !== null, 'The first task must open');
    const composer = f.visibleComposer();
    assert(composer !== null, 'The first task needs a composer');
    changeInput(composer, 'Message sent before switching');
    await until(() => f.host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]:not(:disabled)') !== null, 'Send must be ready');
    f.host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.click();
    await until(() => f.sending() !== null && composer.value === '', 'The message must be in flight');
    f.show(f.second);
    await until(() => f.visibleComposer() !== composer, 'The second task must open');
    f.show(f.first);
    f.sending()?.reject(new Error('The agent is unavailable'));
    await until(() => composer.value === 'Message sent before switching', 'A send that failed after switching back must return its message');
    const send = f.host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]');
    assert(send !== null && !send.disabled, 'The composer must not stay busy with a send its page no longer owns');
  }
  finally { f.dispose(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  return runScenarios([
    ['keeps the task shown before mounted, then refreshes it', keepsTheTaskShownBefore],
    ['releases a send that finishes after switching away and back', releasesASendThatFinishesAfterSwitching],
  ]);
}
