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
import type { LocalPRBundle } from '../types/localPr';
import { makeIdCache } from '../threads/brain/idCache';
import { usePR } from './usePR';

/** A (repo, number) resolves to the same PR id for the app's lifetime,
 *  same reasoning as the task→PR-id cache in {@code useLocalPr}. */
const prIdCache = makeIdCache<string>();

type ExternalPrState = {
  bundle: LocalPRBundle | null | undefined;
  refresh: () => void;
  syncing: boolean;
};

/**
 * Resolves a GitHub (owner, repo, number) to its unified PR id via
 * {@code GET /api/repos/{owner}/{repo}/prs/{number}} (materialising the
 * external-origin row on first read), then delegates to {@link usePR} —
 * the standalone details page stays keyed by the same PR id every other
 * surface uses (unified-pr-view.md U5/U10).
 */
export function useExternalPr(owner: string, repo: string, number: number): ExternalPrState {
  const cacheKey = `${owner}/${repo}#${number}`;
  const [prId, setPrId] = useState<string | undefined>(() => prIdCache.get(cacheKey));

  useEffect(() => {
    const cached = prIdCache.get(cacheKey);
    if (cached !== undefined) {
      setPrId(cached);
      return;
    }
    let cancelled = false;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getPrForRepoPull) {
      return;
    }
    void bridge.getPrForRepoPull(owner, repo, number)
      .then(pr => {
        if (cancelled) return;
        prIdCache.set(cacheKey, pr.id);
        setPrId(pr.id);
      })
      .catch(() => { /* transient; effect re-runs on next mount/prop change */ });
    return () => { cancelled = true; };
  }, [cacheKey, owner, repo, number]);

  const { bundle, refresh, syncing } = usePR(prId ?? null);
  return { bundle, refresh, syncing };
}
