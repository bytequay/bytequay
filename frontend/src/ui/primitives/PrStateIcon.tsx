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

/** Which PR/task-state glyph to show before a task name. `closed` marks an
 *  errored/abandoned task, `progress` an in-flight one without a landed PR. */
export type PrGlyphState = 'merged' | 'open' | 'draft' | 'closed' | 'progress';

/** Stroke-drawn GitHub-style PR glyphs (24×24), from the Trunk Thread
 *  claude_design mockup — one consistent set across the sidebar task rows,
 *  the trunk task rail, and the task cards. */
const RAIL = 'M6 8.4v7.2';
const GLYPH: Record<PrGlyphState, { circles: [number, number][]; paths: string[]; dots?: [number, number][] }> = {
  merged: { circles: [[6, 6], [6, 18], [18, 13]], paths: [RAIL, 'M6.4 8.4C7.5 11.6 10.5 13 15.6 13'] },
  open: {
    circles: [[6, 6], [6, 18], [18, 18]],
    paths: [RAIL, 'M18 15.6V11a3.2 3.2 0 0 0-3.2-3.2h-2.3', 'm14.6 5.2-2.7 2.6 2.7 2.6'],
  },
  draft: { circles: [[6, 6], [6, 18], [18, 18]], paths: [RAIL], dots: [[18, 6], [18, 11.5]] },
  closed: { circles: [[6, 6], [6, 18], [18, 18]], paths: [RAIL, 'm15.6 5 4.8 4.8', 'm20.4 5-4.8 4.8'] },
  progress: {
    circles: [[6, 6], [6, 18], [18, 18]],
    paths: [RAIL, 'M18 15.6v-5a3.4 3.4 0 0 0-3.4-3.4h-1.8', 'm14.9 4.6-2.7 2.6 2.7 2.6'],
  },
};

const LABEL: Record<PrGlyphState, string> = {
  merged: 'Merged',
  open: 'Open pull request',
  draft: 'Draft pull request',
  closed: 'Errored',
  progress: 'In progress',
};

/**
 * The PR-state glyph shown before a task's name: purple git-merge once the
 * PR is merged, green pull-request while open, grey while a draft, red
 * closed-PR when the task errored, and a green incoming-arrow while the task
 * is still in progress. Colour is driven by the {@code pr-state-icon--<state>}
 * class so the same glyph reads correctly on the trunk task cards, the task
 * rail, and the left-nav task row.
 */
export function PrStateIcon({ state, title }: { state: PrGlyphState; title?: string }) {
  const label = title ?? LABEL[state];
  const g = GLYPH[state];
  return (
    <svg
      className={`pr-state-icon pr-state-icon--${state}`}
      viewBox="0 0 24 24"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.9"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={label}
    >
      <title>{label}</title>
      {g.circles.map(([cx, cy]) => <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r="2.4" />)}
      {g.paths.map(d => <path key={d} d={d} />)}
      {g.dots?.map(([cx, cy]) => <circle key={`d${cx}-${cy}`} cx={cx} cy={cy} r="1.3" fill="currentColor" stroke="none" />)}
    </svg>
  );
}
