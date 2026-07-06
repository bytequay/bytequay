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
import { useEffect, useState } from 'react';
import type { LocalPRBundle } from '../../types/localPr';
import { makeIdCache } from './idCache';
import { usePR } from '../../pr/usePR';

/** A task's PR id never changes once assigned, so resolving it once per
 *  task and caching forever is safe (unlike the bundle itself, which is
 *  re-polled by {@link usePR}). */
const prIdCache = makeIdCache<string | null>();

type LocalPrState = {
  /** The task's PR bundle, or null when it has none yet (the common case
   *  until Dev records its first commit) — the host then falls back to the
   *  remote PR view. Undefined only before the first fetch resolves. */
  bundle: LocalPRBundle | null | undefined;
  refresh: () => void;
  syncing: boolean;
};

/**
 * Resolves a task id to its PR id via {@code GET /api/tasks/{taskId}/pr}
 * (materialising the row on first read, same as the old task-scoped bundle
 * fetch did), then delegates to {@link usePR} for the actual data — task
 * surfaces stay keyed by task id, everything else routes through the one
 * PR-id-keyed hook every surface shares (unified-pr-view.md U5).
 */
export function useLocalPr(taskId: string): LocalPrState {
  const [prId, setPrId] = useState<string | null | undefined>(() => prIdCache.get(taskId));

  useEffect(() => {
    const cached = prIdCache.get(taskId);
    if (cached !== undefined) {
      setPrId(cached);
      return;
    }
    let cancelled = false;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getPrForTask) {
      return;
    }
    void bridge.getPrForTask(taskId)
      .then(pr => {
        if (cancelled) return;
        const resolved = pr?.id ?? null;
        prIdCache.set(taskId, resolved);
        setPrId(resolved);
      })
      .catch(() => { /* transient; effect re-runs on next mount/taskId change */ });
    return () => { cancelled = true; };
  }, [taskId]);

  const { bundle, refresh, syncing } = usePR(prId ?? null);
  return { bundle: prId === null ? null : bundle, refresh, syncing };
}
