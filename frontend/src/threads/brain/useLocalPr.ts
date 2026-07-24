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
import { useCallback, useEffect, useRef, useState } from 'react';
import type { LocalPRBundle } from '../../types/localPr';
import { makeIdCache } from './idCache';
import { usePR } from '../../pr/usePR';

/** A task's PR id never changes once assigned, so cache assigned ids forever.
 *  A missing id is not cached because the task can create its PR later. */
const prIdCache = makeIdCache<string>();

type LocalPrState = {
  /** The task's PR bundle, or null when it has none yet (the common case
   *  until Dev records its first commit) — the host then falls back to the
   *  remote PR view. Undefined only before the first fetch resolves. */
  bundle: LocalPRBundle | null | undefined;
  refresh: () => void;
  syncing: boolean;
  error: string | null;
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
  const [resolveToken, setResolveToken] = useState(0);
  const [resolveError, setResolveError] = useState<string | null>(null);
  const shownTaskIdRef = useRef(taskId);
  const requestGenerationRef = useRef(0);

  if (shownTaskIdRef.current !== taskId) {
    shownTaskIdRef.current = taskId;
    requestGenerationRef.current += 1;
    setPrId(prIdCache.get(taskId));
    setResolveError(null);
  }

  useEffect(() => {
    const generation = ++requestGenerationRef.current;
    const cached = prIdCache.get(taskId);
    if (cached !== undefined) {
      setPrId(cached);
      setResolveError(null);
      return () => { requestGenerationRef.current += 1; };
    }
    let cancelled = false;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getPrForTask) {
      return;
    }
    void bridge.getPrForTask(taskId)
      .then(pr => {
        if (cancelled || shownTaskIdRef.current !== taskId
            || requestGenerationRef.current !== generation) return;
        const resolved = pr?.id ?? null;
        if (resolved !== null) prIdCache.set(taskId, resolved);
        setPrId(resolved);
        setResolveError(null);
      })
      .catch((reason: unknown) => {
        if (cancelled || shownTaskIdRef.current !== taskId
            || requestGenerationRef.current !== generation) return;
        setResolveError(reason instanceof Error ? reason.message : 'Failed to resolve the task pull request');
      });
    return () => {
      cancelled = true;
      requestGenerationRef.current += 1;
    };
  }, [taskId, resolveToken]);

  const { bundle, refresh: refreshBundle, syncing, error: bundleError } = usePR(prId ?? null);
  const refresh = useCallback(() => {
    if (prId == null) setResolveToken(token => token + 1);
    else refreshBundle();
  }, [prId, refreshBundle]);
  return { bundle: prId === null ? null : bundle, refresh, syncing, error: resolveError ?? bundleError };
}
