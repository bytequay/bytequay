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
import { createContext, useContext, useLayoutEffect } from 'react';

export const FocusBreakBackgroundContext = createContext<(() => void) | undefined>(undefined);

// Native panes can be replaced or closed without changing the app's route.
export function useFocusBreakSurface(key: string | null): (() => void) | undefined {
  const invalidate = useContext(FocusBreakBackgroundContext);
  useLayoutEffect(() => {
    if (key === null) return;
    invalidate?.();
    return invalidate;
  }, [invalidate, key]);
  return invalidate;
}
