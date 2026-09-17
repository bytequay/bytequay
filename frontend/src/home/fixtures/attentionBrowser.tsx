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
import AttentionList from '../AttentionList';
import AttentionPreview from '../AttentionPreview';
import type { AttentionItem, Handled } from '../attention';

function assert(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

/** Real DOM clicks keep launching separate from source navigation and acknowledgement. */
export async function runAttentionActions(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const issue: AttentionItem = {
    key: 'octo/app#7', sourceKey: 'github.com:issue:9', type: 'issue', kind: 'assigned',
    repo: 'octo/app', number: 7, title: 'Restore uploads', author: 'alice', authorAvatarUrl: null,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z', labels: [], pr: null,
    body: 'Reproduce the timeout before changing the uploader.', commentCount: 1,
    workspaceId: 'workspace-fork', workspaceName: 'My app fork',
  };
  let started = 0;
  let opened = 0;
  let pullRequests = 0;
  let issues = 0;
  let handled: Handled = {};
  const start = (item: AttentionItem) => {
    assert(item.workspaceId === 'workspace-fork' && item.number === 7, 'Start must retain the issue’s explicit workspace');
    started++;
  };
  const render = () => flushSync(() => root.render(
    <AttentionList candidates={[issue]} handled={handled} onHandledChange={next => { handled = next; render(); }}
      onOpen={() => { opened++; }} onStartIssue={start} onSeeAll={() => { pullRequests++; }}
      issueNavigation={<button type="button" onClick={() => { issues++; }}>All issues</button>} />,
  ));
  const button = (label: string) => {
    const found = [...document.querySelectorAll<HTMLButtonElement>('button')]
      .find(candidate => candidate.getAttribute('aria-label') === label || candidate.textContent?.trim() === label);
    if (!found) throw new Error(`Missing button: ${label}`);
    return found;
  };
  try {
    render();
    assert(host.textContent?.includes('Assigned to you') === true, 'Issue must explain its attention reason');
    assert(host.textContent?.includes('My app fork') === true, 'Global row must show the destination workspace');
    button('Start development for #7').click();
    assert(started === 1 && opened === 0, 'Start must not also open the issue row');
    host.querySelector<HTMLElement>('.atn-row')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    assert(opened === 1 && started === 1, 'Keyboard open must not start development');
    button('All issues').click();
    button('All pull requests').click();
    assert(issues === 1 && pullRequests === 1, 'Overflow actions must keep source destinations separate');
    flushSync(() => button('Acknowledge #7').click());
    assert(handled[issue.key]?.kind === 'ack' && !host.querySelector('.atn-row'), 'Ack must hide the suggestion');
    assert(started === 1 && opened === 1, 'Ack must not launch or navigate');
    flushSync(() => root.render(
      <AttentionPreview item={issue} anchor={new DOMRect(20, 20, 500, 52)} onHold={() => {}} onRelease={() => {}}
        onOpen={() => { opened++; }} onStartIssue={() => start(issue)} onAck={() => {}} onSnooze={() => {}} />,
    ));
    button('Start development').click();
    assert(started === 2 && opened === 1, 'Preview must expose the same launch action');
    button('Open').click();
    assert(opened === 2 && started === 2, 'Preview Open must preserve source navigation');
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
  }
}
