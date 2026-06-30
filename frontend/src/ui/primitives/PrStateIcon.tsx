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

/** Which PR-state glyph to show before a task name. */
export type PrGlyphState = 'merged' | 'open' | 'draft';

/** GitHub's git-merge octicon (the merged-PR glyph). */
const MERGE_PATH = 'M5.45 5.154A4.25 4.25 0 0 0 9.25 7.5h1.378a2.251 2.251 0 1 1 0 1.5H9.25A5.734'
  + ' 5.734 0 0 1 5 7.123v3.505a2.25 2.25 0 1 1-1.5 0V5.372a2.25 2.25 0 1 1 1.95-.218ZM4.25'
  + ' 13.5a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm8.5-4.5a.75.75 0 1 0 0-1.5.75.75 0 0 0'
  + ' 0 1.5ZM5 3.25a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0Z';

/** GitHub's git-pull-request octicon (the open/draft-PR glyph). */
const PULL_REQUEST_PATH = 'M1.5 3.25a2.25 2.25 0 1 1 3 2.122v5.256a2.251 2.251 0 1 1-1.5 0V5.372A2.25'
  + ' 2.25 0 0 1 1.5 3.25Zm5.677-.177L9.573.677A.25.25 0 0 1 10 .854V2.5h1A2.5 2.5 0 0 1 13.5'
  + ' 5v5.628a2.251 2.251 0 1 1-1.5 0V5a1 1 0 0 0-1-1h-1v1.646a.25.25 0 0 1-.427.177L7.177'
  + ' 3.427a.25.25 0 0 1 0-.354ZM3.75 2.5a.75.75 0 1 0 0 1.5.75.75 0 0 0 0-1.5Zm0 9.5a.75.75'
  + ' 0 1 0 0 1.5.75.75 0 0 0 0-1.5Zm8.25.75a.75.75 0 1 0 1.5 0 .75.75 0 0 0-1.5 0Z';

const LABEL: Record<PrGlyphState, string> = {
  merged: 'Merged',
  open: 'Open pull request',
  draft: 'Draft pull request',
};

/**
 * The PR-state glyph shown before a task's name: GitHub's purple git-merge
 * mark once the PR is merged, and its pull-request mark (green when open,
 * grey while a draft) while the PR is still in flight. Colour is driven by
 * the {@code pr-state-icon--<state>} class so the same glyph reads correctly
 * on the trunk task cards and in the left-nav task row.
 */
export function PrStateIcon({ state, title }: { state: PrGlyphState; title?: string }) {
  const label = title ?? LABEL[state];
  return (
    <svg
      className={`pr-state-icon pr-state-icon--${state}`}
      viewBox="0 0 16 16"
      width="13"
      height="13"
      role="img"
      aria-label={label}
    >
      <title>{label}</title>
      <path fill="currentColor" d={state === 'merged' ? MERGE_PATH : PULL_REQUEST_PATH} />
    </svg>
  );
}
