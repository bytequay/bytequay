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
import { afterEach, describe, expect, it, vi } from 'vitest';
import ArtifactsPage, { openArtifact } from './ArtifactsPage';

describe('a conversation started from Home', () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  it('opens in its own workspace’s Artifacts', () => {
    const location = { hash: '' };
    vi.stubGlobal('window', { location, innerWidth: 1280, dispatchEvent: () => true });
    openArtifact('w1', 'a1', {
      artifact: { id: 'a1', title: 'What is a checkpoint?', working: true, updatedAt: '2026-01-01T00:00:00Z' },
      offers: [], pages: [], questions: [], pendingApprovals: [],
    });
    expect(location.hash).toBe('#/workspaces/w1/artifacts');
    expect(renderToStaticMarkup(<ArtifactsPage workspaceId="w2" />)).toContain('aria-label="Artifacts"');
    expect(renderToStaticMarkup(<ArtifactsPage workspaceId="w1" />)).toContain('aria-label="What is a checkpoint?"');
  });
});
