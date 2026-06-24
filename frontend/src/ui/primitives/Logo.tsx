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

/** Per-repo / per-workspace gradient hue. */
export type LogoColor = 'purple' | 'teal' | 'orange' | 'blue' | 'pink' | 'slate';
/** sm = inline thread row, md = default, lg = workspace row / header. */
export type LogoSize = 'sm' | 'md' | 'lg';

/**
 * The square gradient repo/workspace logo — a 2-letter monogram. Used in
 * front of every thread (its repo), in the workspace list, and in the
 * workspace header. The single logo primitive; do not introduce a second
 * avatar for repos.
 */
export function Logo({ initials, color = 'purple', size = 'md', title }: {
  initials: string;
  color?: LogoColor;
  size?: LogoSize;
  title?: string;
}) {
  return (
    <span className={`v3-logo v3-logo--${size} v3-logo--${color}`} title={title} aria-hidden={title === undefined}>
      {initials}
    </span>
  );
}
