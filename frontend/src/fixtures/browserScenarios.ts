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
import { deadline, runBrowserFixture, type BrowserFixture, type ScenarioResult } from './browserFixture';

/** Each named test still builds and runs its own Electron process and browser profile. */
export function testBrowserScenarios(scenarios: {
  name: string;
  expected: number;
  timeout: number;
  fixture: BrowserFixture;
}[]): void {
  for (const { name, expected, timeout, fixture } of scenarios) {
    it(name, async () => {
      const results = await runBrowserFixture<ScenarioResult[]>(fixture);
      expect(results).toHaveLength(expected);
      expect(results.filter(result => result.error !== undefined)).toEqual([]);
    }, deadline(timeout));
  }
}
