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
import { createRoot } from 'react-dom/client';
import { BlockView } from '../AgentConversation';
import type { ApprovalBlock } from '../transcript';
import '../../css/base.css';
import '../../css/agents.css';
import { assert, settle } from '../../fixtures/harness';

const QUESTIONS = {
  questions: [
    {
      question: 'Which of the two issues should I work on?',
      header: 'Issue',
      multiSelect: false,
      options: [
        { label: 'Both (#31252 + #31253)', description: 'Fix the checkpoint key and the OPTIMIZE remove entry together.' },
        { label: '#31252 checkpoint writer only', description: 'Key CheckpointBuilder add/remove entries by (path, deletionVector.uniqueId).' },
      ],
    },
    {
      question: 'Which checks should run before the pull request?',
      header: 'Checks',
      multiSelect: true,
      options: [{ label: 'Lint' }, { label: 'Tests' }],
    },
  ],
};

const block: ApprovalBlock = {
  kind: 'approval',
  id: 'block-1',
  approvalId: 'approval-1',
  title: 'Allow Claude AskUserQuestion',
  detail: JSON.stringify(QUESTIONS),
  approvalKind: 'TOOL',
  scope: ['AskUserQuestion'],
  agent: 'root agent',
  decisions: ['ALLOW_ONCE', 'DENY'],
  actionable: true,
  outcome: null,
  forTask: false,
  at: '2026-09-22T00:00:00Z',
};

const host = document.createElement('main');
host.style.padding = '24px';
document.body.append(host);
const root = createRoot(host);
let sent: { decision: string; answers?: Record<string, string> } | null = null;

function options(): HTMLButtonElement[] {
  return [...host.querySelectorAll<HTMLButtonElement>('.agt-ask__option')];
}

function send(): HTMLButtonElement {
  const button = host.querySelector<HTMLButtonElement>('.agt-decision.is-allow');
  assert(button !== null, 'The question card must offer a send button');
  return button;
}

export async function mount(): Promise<void> {
  sent = null;
  flushSync(() => root.render(
    <BlockView
      block={block}
      activityChoice={{}}
      agentLabels={{}}
      onToggleActivity={() => {}}
      onDecide={(_approvalId, decision, answers) => { sent = { decision, answers }; }} />));
  await settle();
  assert(options().length === 4, 'Every option of every question must be offered');
  assert(send().disabled, 'Nothing may be sent before the questions are answered');
}

/** Picks by visible label, the way the user does, and reports what the card would send. */
export async function answer(labels: string[]): Promise<{ picked: string[]; disabled: boolean }> {
  for (const label of labels) {
    const option = options().find(button => button.querySelector('.agt-ask__label')?.textContent === label);
    assert(option !== undefined, `Missing option: ${label}`);
    option.click();
  }
  await settle();
  return {
    picked: options().filter(option => option.classList.contains('is-picked'))
      .map(option => option.querySelector('.agt-ask__label')?.textContent ?? ''),
    disabled: send().disabled,
  };
}

export async function submit(): Promise<{ decision: string; answers?: Record<string, string> }> {
  send().click();
  await settle();
  assert(sent !== null, 'Sending an answer must resolve the approval');
  return sent;
}
