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
 * The V3 outer shell: a 2-column grid of sidebar + main, used by every
 * surface. `collapsed` narrows the sidebar to the 48px toggle rail
 * (full-page views auto-collapse it); the sidebar markup is unchanged —
 * the collapse is driven entirely by this class.
 *
 * Children are the `<Sidebar>` and `<Main>` for the surface, in that
 * order. `.shell` is the single V3 styling root, so all V3 structural
 * CSS is scoped beneath it.
 */
export function Shell({ collapsed = false, fullWidth = false, children }: {
  collapsed?: boolean;
  /** When true the shell drops its own sidebar column and the main column
   *  spans the full width — used when the single global rail provides the
   *  navigation instead of a per-surface sidebar. */
  fullWidth?: boolean;
  children: ReactNode;
}) {
  const classes = ['shell'];
  if (fullWidth) classes.push('full-width');
  else if (collapsed) classes.push('sidebar-collapsed');
  return <div className={classes.join(' ')}>{children}</div>;
}
