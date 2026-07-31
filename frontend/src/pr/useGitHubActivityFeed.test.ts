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
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useGitHubActivityFeed } from './useGitHubActivityFeed';

function bridge(fetchPullRequestDetail: ReturnType<typeof vi.fn>) {
  (window as unknown as { bridge: unknown }).bridge = { fetchPullRequestDetail };
}

afterEach(() => {
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('useGitHubActivityFeed', () => {
  it('waits for an owner/name repo instead of fetching a 400', () => {
    const fetchDetail = vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] });
    bridge(fetchDetail);

    renderHook(() => useGitHubActivityFeed('', 52));

    expect(fetchDetail).not.toHaveBeenCalled();
  });

  it('fetches once the host supplies a real repo', async () => {
    const fetchDetail = vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] });
    bridge(fetchDetail);

    renderHook(() => useGitHubActivityFeed('acme/widget', 52));

    await waitFor(() => expect(fetchDetail).toHaveBeenCalledWith('acme/widget', 52));
  });
});
