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
import type { WorkspaceCardDto } from '../types';

type State = {
  cards: WorkspaceCardDto[] | null;
  loading: boolean;
  error: string | null;
};

/** Read-side hook for the top-level Workspaces landing grid. Fires
 *  one fetch on mount and exposes a {@code reload()} the page can
 *  call after a workspace is created or deleted. The shape is
 *  deliberately small — the landing is the only consumer today, so
 *  this hook is not yet promoted to a shared cache. */
export default function useWorkspaces() {
  const [state, setState] = useState<State>({
    cards: null,
    loading: true,
    error: null,
  });

  const reload = useCallback(async () => {
    setState(s => ({ ...s, loading: true, error: null }));
    try {
      const cards = await window.bridge.listWorkspaces();
      setState({ cards, loading: false, error: null });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setState({ cards: null, loading: false, error: message });
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { ...state, reload } as const;
}
