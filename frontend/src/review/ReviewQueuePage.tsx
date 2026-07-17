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
import type { PullRequestDto } from '../types';
import PullRequestBoardList from '../workspace/PullRequestBoardList';

/**
 * Global Reviews deliberately uses the same board/list component as a
 * workspace, but remains GitHub-data-only. Repository chips disambiguate
 * rows and no clone, branch, test, memory, or Session action is rendered.
 */
export default function ReviewQueuePage({ onOpenPr }: {
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
  onOpenWorkspace: (workspaceId: string) => void;
}) {
  const [rows, setRows] = useState<PullRequestDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await window.bridge.fetchPrs());
      setError(null);
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
    finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  return (
    <PullRequestBoardList
      title="Reviews"
      rows={rows}
      loading={loading}
      error={error}
      showRepository
      remoteOnly
      onRefresh={() => { void load(); }}
      onOpen={pr => {
        const [owner, repo] = pr.repo.split('/');
        if (owner !== undefined && repo !== undefined) {
          onOpenPr(owner, repo, pr.number);
        }
      }}
    />
  );
}
