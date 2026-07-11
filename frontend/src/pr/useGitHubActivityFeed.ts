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
import { useCallback, useEffect, useState } from 'react';
import type { ActivityItemDto, PullRequestDetailDto, ReviewThreadDto } from '../types';

/**
 * GitHub's own conversation feed for a pushed PR (task-origin once pushed,
 * or external) — `recentActivity`/`reviewThreads` off the same legacy
 * `/prs/detail` endpoint the remote PR diff already uses for the diff
 * view's inline threads (repo+number keyed, no unified PR id needed).
 * `PRTimeline` merges this alongside the local sync tables once the PR has
 * a `remotePrNumber` — see `unified-pr-view.md` U15's Conversation-tab spec.
 */
export function useGitHubActivityFeed(repo: string | null, number: number | null) {
  const [activity, setActivity] = useState<ActivityItemDto[]>([]);
  const [reviewThreads, setReviewThreads] = useState<ReviewThreadDto[]>([]);
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);

  const refresh = useCallback((force = false) => {
    if (repo === null || number === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    const fetcher = force ? bridge?.refreshPullRequestDetail : bridge?.fetchPullRequestDetail;
    if (fetcher === undefined) return;
    void fetcher(repo, number)
      .then(detail => {
        setActivity(detail.recentActivity ?? []);
        setReviewThreads(detail.reviewThreads ?? []);
        setDetail(detail);
      })
      .catch(() => { /* best-effort — the local feed still renders */ });
  }, [repo, number]);

  useEffect(() => { refresh(); }, [refresh]);

  return { activity, reviewThreads, detail, refresh };
}
