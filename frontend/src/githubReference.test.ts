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
import { describe, expect, it } from 'vitest';
import { parseGitHubReference, pullRequestFromRoute, pullRequestRoute } from './githubReference';

describe('GitHub references', () => {
  it('recognizes issue and pull request links, including query strings', () => {
    expect(parseGitHubReference('https://github.com/owner/repo/issues/42?notification_referrer_id=1')).toMatchObject({
      kind: 'issue', owner: 'owner', repo: 'repo', number: 42,
    });
    expect(parseGitHubReference('https://github.com/owner/repo/pull/7#discussion')).toMatchObject({
      kind: 'pull', owner: 'owner', repo: 'repo', number: 7,
    });
    expect(parseGitHubReference('https://example.com/owner/repo/issues/42')).toBeNull();
  });

  it('round-trips a pull request detail route', () => {
    const route = pullRequestRoute({ owner: 'owner', repo: 'repo', number: 7 });
    expect(route).toBe('#/pulls/owner/repo/7');
    expect(pullRequestFromRoute(route)).toEqual({ repo: 'owner/repo', number: 7 });
  });
});
