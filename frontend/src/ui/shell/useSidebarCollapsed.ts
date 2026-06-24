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

/** Single per-user key (not per-surface) for the sidebar preference. */
const KEY = 'v3.sidebar.collapsed';

function readPref(): boolean {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem(KEY) === 'true';
  }
  catch {
    return false;
  }
}

/**
 * The user's persisted sidebar-collapsed preference. One preference
 * applies everywhere (per-user, not per-surface). Full-page views
 * (Changes / CI Status) force the collapsed look on entry by OR-ing
 * their own flag with `collapsed` at the surface level, and the
 * preference is restored automatically when navigating back.
 */
export function useSidebarCollapsed(): {
  collapsed: boolean;
  toggle: () => void;
  setCollapsed: (value: boolean) => void;
} {
  const [collapsed, setCollapsed] = useState<boolean>(readPref);

  useEffect(() => {
    try {
      localStorage.setItem(KEY, String(collapsed));
    }
    catch {
      /* storage unavailable (private mode / SSR) — preference is in-memory only */
    }
  }, [collapsed]);

  const toggle = useCallback(() => setCollapsed(c => !c), []);

  return { collapsed, toggle, setCollapsed };
}
