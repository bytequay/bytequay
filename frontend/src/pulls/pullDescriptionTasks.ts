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

const TASK_MARKER = /^(\s*(?:[-+*]|\d+[.)])\s+\[)[ xX](\])/gm;

export function enableTaskCheckboxes(html: string): string {
  return html.replaceAll(' disabled=""', '');
}

export function toggleTaskCheckbox(markdown: string, index: number, checked: boolean): string | null {
  let current = 0;
  let found = false;
  const next = markdown.replace(TASK_MARKER, (marker: string, prefix: string, suffix: string) => {
    if (current++ !== index) return marker;
    found = true;
    return `${prefix}${checked ? 'x' : ' '}${suffix}`;
  });
  return found ? next : null;
}
