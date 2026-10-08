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
import CodeTestsTab, { type CodeTestsBridge } from '../CodeTestsTab';
import type { AgentReviewDto } from '../../agent-review/contracts';
import type { PullRequestDetailDto } from '../../../types';
import { reviewFixture } from '../../agent-review/fixtures/review';
import { assert, settle } from '../../../fixtures/harness';
import '../../../css/base.css';

export async function runExploration(): Promise<string> {
  const prepared = reviewFixture({ runs: [], activeTurn: null, followUpPaused: true });
  const detail = { repo: prepared.repo, number: prepared.number } as PullRequestDetailDto;
  let attempts = 0;
  let opened = 0;
  let finish: (review: AgentReviewDto) => void = () => {};
  const oldIde = window.ide;
  window.ide = {
    open: async host => {
      assert(host.kind === 'review' && host.reviewId === prepared.id, 'Explore must open the prepared checkout');
      opened++; return 'explorer';
    },
    present: async () => {}, update: async () => {}, reply: () => {}, close: async () => {},
    onRequest: () => () => {}, onEvent: () => () => {},
  };
  const unused = async (): Promise<never> => { throw new Error('Unexpected bridge call'); };
  const bridge: CodeTestsBridge = {
    listCodeFiles: async () => ({ source: 'WORKING_COPY', revision: 'a'.repeat(40), sourceFingerprint: 'fingerprint', truncated: false,
      savedExperiment: false, files: [] }),
    readCodeFile: unused, listCodeRuns: unused, stopCodeRun: unused, readCodeRunOutput: unused,
    openCodeTerminal: unused, writeReviewTerminal: unused, resizeReviewTerminal: unused, onReviewTerminalEvent: () => () => {},
    listAgentReviews: async () => [], getAgentReview: unused, subscribeAgentReview: unused, getAgentTask: unused,
    prepareReviewWorktree: async (workspaceId, repo, number) => {
      assert(workspaceId === prepared.workspaceId && repo === prepared.repo && number === prepared.number, 'Preparation must target the selected watched PR');
      attempts++;
      if (attempts === 1) throw new Error('Cannot fetch PR branch');
      return new Promise(resolve => { finish = resolve; });
    },
  };
  const host = document.createElement('div'); host.style.cssText = 'height:820px;width:1050px'; document.body.append(host);
  const root = createRoot(host);
  function Harness() {
    const [review, setReview] = useState<AgentReviewDto | null>(null);
    return <CodeTestsTab detail={detail} workspaceId={prepared.workspaceId} review={review} onReviewChange={setReview}
      active ownReviewStream={false} bridge={bridge} />;
  }
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    const button = host.querySelector<HTMLButtonElement>('.agr-action.is-primary');
    assert(button?.textContent === 'Create worktree to explore' && host.textContent?.includes('additional memory'), 'Explain creation and memory use before launching');
    assert(opened === 0 && attempts === 0, 'Opening the tab must not create a worktree or code server');
    button.click(); await settle();
    assert(host.querySelector('[role="alert"]')?.textContent === 'Cannot fetch PR branch' && !button.disabled, 'Failed creation must show a retryable error');
    button.click(); await settle();
    assert(button.disabled && button.textContent === 'Creating worktree…', 'Pending preparation must disable duplicate clicks');
    button.click(); await settle();
    assert(Number(attempts) === 2 && Number(opened) === 0, 'Wait for preparation before opening the editor');
    finish(prepared); await settle(); await settle();
    assert(Number(opened) === 1 && host.querySelector('.ct-ide-surface') !== null, 'The prepared working copy must open without an agent run');
    return 'Exploration waits for explicit creation, retries failures, and opens the prepared checkout';
  } finally { flushSync(() => root.unmount()); host.remove(); window.ide = oldIde; }
}
