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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DashboardPR } from '../types/dashboardPr';
import PullDetailPane from './PullDetailPane';
import { toRow } from './model';

afterEach(() => {
  cleanup();
  delete (globalThis as { bridge?: unknown }).bridge;
});

describe('PullDetailPane', () => {
  it('opens the pull request on GitHub from the tab-row icon', () => {
    const url = 'https://github.com/trinodb/trino/pull/1';
    const openInAppBrowser = vi.fn().mockResolvedValue(undefined);
    window.bridge = {
      getLocalPrBundle: vi.fn().mockResolvedValue(null),
      openInAppBrowser,
    } as unknown as typeof window.bridge;
    const dto: DashboardPR = {
      id: 'pr-github-link', repo: 'trinodb/trino', number: 1, title: 'A change', author: 'octocat', htmlUrl: url,
      createdAt: null, updatedAt: null, origin: 'AUTHORED', labels: [], labelColors: null, draft: false,
      viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null,
      additions: 1, deletions: 1, commentCount: 0, attentionReason: null, state: 'open', closedAt: null,
      mergedAt: null, mergeable: null, mergeableState: null, headPushedAt: null, reviewerVerdicts: null,
      snoozedUntil: null, snoozeWakeReason: null,
    };

    render(<PullDetailPane row={toRow(dto)} />);
    fireEvent.click(screen.getByRole('button', { name: 'Open pull request on GitHub' }));

    expect(openInAppBrowser).toHaveBeenCalledWith(url);
  });
});
