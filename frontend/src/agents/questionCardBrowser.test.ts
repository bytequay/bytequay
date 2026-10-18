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

import { expect, it } from 'vitest';
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';

type Answered = {
  single: { picked: string[]; disabled: boolean };
  multiple: { picked: string[]; disabled: boolean };
  sent: { decision: string; answers?: Record<string, string> };
};

it('answers a question card by choosing options in Chromium', async () => {
  const results = await runBrowserFixture<Answered>({
    prefix: 'bytequay-question-card-',
    entry: 'src/agents/fixtures/questionCardBrowser.tsx',
    global: 'QuestionCardTests',
    styles: true,
    charset: true,
    width: 760,
    height: 900,
    body: `
      const run = code => window.webContents.executeJavaScript(code);
      await run('QuestionCardTests.mount()');
      const single = await run("QuestionCardTests.answer(['Both (#31252 + #31253)', '#31252 checkpoint writer only'])");
      const multiple = await run("QuestionCardTests.answer(['Lint', 'Tests'])");
      const sent = await run('QuestionCardTests.submit()');
      const results = { single, multiple, sent };
    `,
  });

  // One answer per question: the second choice replaces the first, and the send stays shut until both are answered.
  expect(results.single).toEqual({ picked: ['#31252 checkpoint writer only'], disabled: true });
  expect(results.multiple.picked).toEqual(['#31252 checkpoint writer only', 'Lint', 'Tests']);
  expect(results.multiple.disabled).toBe(false);
  // Claude reads the answers off the questions it asked; several choices arrive as one joined answer.
  expect(results.sent).toEqual({
    decision: 'ALLOW',
    answers: {
      'Which of the two issues should I work on?': '#31252 checkpoint writer only',
      'Which checks should run before the pull request?': 'Lint, Tests',
    },
  });
}, deadline(30_000));
