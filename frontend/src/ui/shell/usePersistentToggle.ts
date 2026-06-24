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

/**
 * A boolean preference persisted to {@code localStorage} under {@code key}.
 * Used for per-user view toggles (hide file tree, hide CI panel) that
 * should survive navigation. Falls back to {@code fallback} when storage
 * is unavailable.
 */
export function usePersistentToggle(key: string, fallback = false): {
  value: boolean;
  toggle: () => void;
  setValue: (value: boolean) => void;
} {
  const [value, setValue] = useState<boolean>(() => {
    try {
      const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null;
      return stored === null ? fallback : stored === 'true';
    }
    catch {
      return fallback;
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(key, String(value));
    }
    catch {
      /* storage unavailable — in-memory only */
    }
  }, [key, value]);

  const toggle = useCallback(() => setValue(v => !v), []);

  return { value, toggle, setValue };
}
