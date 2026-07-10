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
 * The square gradient repo/workspace logo — a branch-mark glyph on a
 * gradient tile. Used in front of every thread (its repo), in the
 * workspace list, and in the workspace header. The single logo
 * primitive; do not introduce a second avatar for repos.
 *
 * <p>{@code initials} no longer renders as visible text (a 2-letter
 * monogram read as "ugly English characters" next to the glyph-style
 * icons the rest of the shell uses) — it's kept as the accessible
 * name so screen readers still get the repo/workspace identity.
 */
export function Logo({ initials, color = 'purple', size = 'md', title }: {
  initials: string;
  color?: LogoColor;
  size?: LogoSize;
  title?: string;
}) {
  return (
    <span
      className={`v3-logo v3-logo--${size} v3-logo--${color}`}
      title={title}
      aria-label={title ?? initials}
    >
      <BranchGlyph />
    </span>
  );
}

/** Minimal three-node branch mark — reads as "repo" without spelling
 *  anything out, in the same spirit as GitHub Copilot's flat glyph
 *  badges. `currentColor` picks up `.v3-logo`'s white text color. */
function BranchGlyph() {
  return (
    <svg viewBox="0 0 16 16" width="58%" height="58%" aria-hidden="true">
      <circle cx="4" cy="3.2" r="1.5" fill="currentColor" />
      <circle cx="4" cy="12.8" r="1.5" fill="currentColor" />
      <circle cx="12" cy="7.8" r="1.5" fill="currentColor" />
      <path d="M4 4.7V11.3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" fill="none" />
      <path
        d="M4 8.2C4 9.1 4.7 9.6 6.1 9.6H10C11.2 9.6 12 9 12 7.8"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        fill="none"
      />
    </svg>
  );
}
