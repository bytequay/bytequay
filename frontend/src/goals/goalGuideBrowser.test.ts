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
import { runBrowserFixture } from '../fixtures/browserFixture';

it('clarifies a goal from free text and a suggested answer to an accepted definition', async () => {
  expect(await runBrowserFixture<boolean>({
    prefix: 'bytequay-goal-guide-', entry: 'src/goals/fixtures/goalGuideBrowser.tsx', global: 'GoalGuideTests', styles: true, charset: true, width: 1200, height: 900,
  })).toBe(true);
}, 30_000);
