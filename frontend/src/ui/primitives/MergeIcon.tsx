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

/**
 * The purple git-merge glyph, shown before a finished task's name to mark
 * it as merged / done (mockup `design/copilot-redesign/img_3.png`).
 */
export function MergeIcon({ title = 'Merged' }: { title?: string }) {
  return (
    <svg className="merge-icon" viewBox="0 0 16 16" width="13" height="13" role="img" aria-label={title}>
      <title>{title}</title>
      <path
        fill="currentColor"
        d="M5.45 5.154A4.25 4.25 0 0 0 9.25 7.5h1.378a2.251 2.251 0 1 1 0 1.5H9.25A5.734
           5.734 0 0 1 5 7.123v3.505a2.25 2.25 0 1 1-1.5 0V5.372a2.25 2.25 0 1 1 1.95-.218ZM4.25
           13.5a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm8.5-4.5a.75.75 0 1 0 0-1.5.75.75 0 0 0
           0 1.5ZM5 3.25a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0Z"
      />
    </svg>
  );
}
