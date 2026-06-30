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
import { afterEach, describe, expect, it } from 'vitest';
import { addAllowed, allowedCommands, commandHead, isAllowed } from './toolAllowlist';

afterEach(() => localStorage.clear());

describe('toolAllowlist', () => {
  it('reduces a command to its lowercased head, stripping a $ prefix', () => {
    expect(commandHead('mvn verify -Dtest=X')).toBe('mvn');
    expect(commandHead('$ git diff --stat')).toBe('git');
    expect(commandHead('  MVN clean ')).toBe('mvn');
    expect(commandHead('')).toBe('');
  });

  it('add then isAllowed matches any command with the same head', () => {
    expect(isAllowed('mvn verify')).toBe(false);
    expect(addAllowed('mvn verify -Dtest=PullRequestRefTest')).toBe('mvn');
    expect(isAllowed('mvn clean install')).toBe(true);
    expect(isAllowed('git status')).toBe(false);
    expect(allowedCommands()).toEqual(['mvn']);
  });

  it('de-duplicates heads', () => {
    addAllowed('mvn verify');
    addAllowed('mvn test');
    expect(allowedCommands()).toEqual(['mvn']);
  });
});
