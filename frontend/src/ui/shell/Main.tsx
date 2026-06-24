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
import type { ReactNode } from 'react';

/**
 * The right column of the shell: a top bar above a flexible body. The
 * body is the surface's conversation column (+ optional right pane or
 * full-page content) and carries its own pinned composer, so `Main`
 * stays layout-only.
 */
export function Main({ topBar, children }: { topBar: ReactNode; children: ReactNode }) {
  return (
    <div className="main">
      {topBar}
      {children}
    </div>
  );
}
