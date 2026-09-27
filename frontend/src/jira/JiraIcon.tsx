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

import type { SVGProps } from 'react';

/** A compact Jira source mark, distinct from GitHub's issue-state glyph. */
export default function JiraIcon({ size = 18, ...props }: SVGProps<SVGSVGElement> & { size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>
    <path d="M13 3h8v8h-4V7h-4V3Z" />
    <path d="M8 8h8v8h-4v-4H8V8Z" />
    <path d="M3 13h8v8H7v-4H3v-4Z" />
  </svg>;
}
