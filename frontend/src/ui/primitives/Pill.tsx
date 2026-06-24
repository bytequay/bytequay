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

/** Which surface the pill labels: thread (teal), task (purple), stage (orange). */
export type PillKind = 'thread' | 'task' | 'stage';

/**
 * Bold surface-kind pill shown in the top bar (THREAD / TASK / STAGE).
 * Colour is driven entirely by `kind`; an optional leading glyph sits
 * before the label.
 */
export function Pill({ kind, icon, children }: {
  kind: PillKind;
  icon?: ReactNode;
  children: ReactNode;
}) {
  return (
    <span className={`v3-pill v3-pill--${kind}`}>
      {icon !== undefined && <span className="v3-pill__ic" aria-hidden>{icon}</span>}
      {children}
    </span>
  );
}
