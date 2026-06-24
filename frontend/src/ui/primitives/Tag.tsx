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

/** Tag colour. `accent` (purple) is the default; others tint per use. */
export type TagColor = 'accent' | 'green' | 'orange' | 'teal';

/**
 * Small chip for a label or tag — backlog tags, branch names, etc. The
 * `accent` variant is the bare base class; the rest add a colour modifier.
 */
export function Tag({ color = 'accent', children }: { color?: TagColor; children: ReactNode }) {
  const className = color === 'accent' ? 'v3-tag' : `v3-tag v3-tag--${color}`;
  return <span className={className}>{children}</span>;
}
