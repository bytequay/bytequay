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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { Bridge, RepoUserDto, UserProfileDto } from '../types';
import { PullRequestWorkspaceProvider, readPullRequestViewer } from './PullRequestWorkspace';
import ReviewThread from './agent-review/ReviewThread';
import { reviewFixture } from './agent-review/fixtures/review';

const profile: UserProfileDto = { login: 'global-user', avatarUrl: 'https://example.com/global.png', name: null, htmlUrl: '',
  publicRepos: 0, followers: 0, following: 0, bio: null, location: null, company: null, email: null };
const cachedAvatar = 'http://127.0.0.1:9527/api/avatars/saved-user';
const scope = { workspaceId: 'workspace', repository: 'owner/repository' };
function bridge() {
  return {
    getUserProfile: vi.fn(async () => profile),
    getWorkspaceHostingViewer: vi.fn(async (): Promise<string | null> => 'Alice'),
    fetchRepoUsers: vi.fn(async (): Promise<RepoUserDto[]> => [
      { login: 'someone-else', avatarUrl: 'https://example.com/other.png', bot: false },
      { login: 'alice', avatarUrl: cachedAvatar, bot: false },
    ]),
  } satisfies Pick<Bridge, 'getUserProfile' | 'getWorkspaceHostingViewer' | 'fetchRepoUsers'>;
}

describe('repository viewer avatars', () => {
  it.each(['GITHUB', 'GITLAB'] as const)('uses the saved %s account avatar in private review messages', async provider => {
    const api = bridge();
    const server = { provider, webBaseUrl: 'https://hosting.example.com', apiBaseUrl: 'https://hosting.example.com/api' };
    const viewer = await readPullRequestViewer(api, { ...scope, server });
    expect(viewer).toEqual({ login: 'Alice', avatarUrl: cachedAvatar });
    expect(api.getWorkspaceHostingViewer).toHaveBeenCalledWith(scope.workspaceId);
    expect(api.fetchRepoUsers).toHaveBeenCalledWith(scope.repository, scope.workspaceId);
    expect(api.getUserProfile).not.toHaveBeenCalled();
    const review = reviewFixture();
    const html = renderToStaticMarkup(<PullRequestWorkspaceProvider {...scope} server={server}>
      <ReviewThread review={review} viewerLogin={viewer?.login ?? ''} viewerAvatarUrl={viewer?.avatarUrl ?? null}
        messages={[{ id: 'question', author: 'USER', body: 'Explain this finding.', findingId: null,
          targetRevision: 1, turnId: null, queuedInputId: null, createdAt: review.runs[0].createdAt }]} />
    </PullRequestWorkspaceProvider>);
    expect(html).toContain(`src="${cachedAvatar}"`);
    expect(html).toContain('alt="Alice"');
    expect(html).not.toContain('avatar--fallback');
    expect(html).not.toContain('github.com');
  });

  it.each(['missing', 'failed'] as const)('keeps the selected account when its saved avatar is %s', async state => {
    const api = bridge();
    if (state === 'missing') api.fetchRepoUsers.mockResolvedValue([{ login: 'other', avatarUrl: cachedAvatar, bot: false }]);
    else api.fetchRepoUsers.mockRejectedValue(new Error('Cache unavailable'));
    await expect(readPullRequestViewer(api, scope)).resolves.toEqual({ login: 'Alice', avatarUrl: null });
    expect(api.getUserProfile).not.toHaveBeenCalled();
  });

  it('does not substitute the global account for an unconnected workspace', async () => {
    const api = bridge();
    api.getWorkspaceHostingViewer.mockResolvedValue(null);
    await expect(readPullRequestViewer(api, scope)).resolves.toBeNull();
    expect(api.fetchRepoUsers).not.toHaveBeenCalled();
    expect(api.getUserProfile).not.toHaveBeenCalled();
  });

  it('retains the profile avatar outside a repository workspace', async () => {
    const api = bridge();
    await expect(readPullRequestViewer(api, {})).resolves.toBe(profile);
    expect(api.getWorkspaceHostingViewer).not.toHaveBeenCalled();
    expect(api.fetchRepoUsers).not.toHaveBeenCalled();
  });
});
