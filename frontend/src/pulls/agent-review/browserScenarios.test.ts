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
import { testBrowserScenarios } from '../../fixtures/browserScenarios';

testBrowserScenarios([
  {
    name: 'changes the next review provider and model in the shared conversation composer',
    expected: 1, timeout: 120_000,
    fixture: {
      prefix: 'bytequay-review-model-', entry: 'src/pulls/agent-review/fixtures/reviewModelBrowser.tsx', global: 'ReviewModelTests', timeoutMs: 60_000,
    },
  },
  {
    name: 'edits and removes whole-PR findings from the durable review summary in Chromium',
    expected: 1, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-review-summary-',
      entry: 'src/pulls/agent-review/fixtures/reviewSummaryBrowser.tsx',
      global: 'ReviewSummaryTests',
      charset: true,
    },
  },
  {
    name: 'recovers stale review controls without losing or resending the draft in Chromium',
    expected: 15, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-review-turn-recovery-',
      entry: 'src/pulls/agent-review/fixtures/reviewTurnRecoveryBrowser.tsx',
      global: 'ReviewTurnRecoveryTests',
    },
  },
  {
    name: 'keeps the compact Summary interactive and readable at narrow widths',
    expected: 1, timeout: 30_000,
    fixture: { prefix: 'bytequay-summary-layout-',
      entry: 'src/pulls/agent-review/fixtures/summaryLayoutBrowser.tsx', global: 'SummaryLayout', styles: true, charset: true, width: 1200, height: 1000 },
  },
  {
    name: 'refreshes explanations independently and preserves the prior text until success in Chromium',
    expected: 1, timeout: 30_000,
    fixture: { prefix: 'bytequay-summary-refresh-',
      entry: 'src/pulls/agent-review/fixtures/summaryRefreshBrowser.tsx', global: 'SummaryRefresh', styles: true, charset: true },
  },
  {
    name: 'reports review update results from both controls in Chromium',
    expected: 7, timeout: 120_000,
    fixture: {
      prefix: 'bytequay-update-review-',
      entry: 'src/pulls/agent-review/fixtures/updateReviewBrowser.tsx',
      global: 'UpdateReviewTests',
      timeoutMs: 60_000,
    },
  },
  {
    name: 'reviews local work, routes questions and fixes, and inspects changing worktree history in Chromium',
    expected: 7, timeout: 30_000,
    fixture: { prefix: 'bytequay-workbench-',
      entry: 'src/pulls/agent-review/fixtures/workbenchBrowser.tsx', global: 'WorkbenchTests', styles: true, charset: true, width: 700, height: 800 },
  },
]);
