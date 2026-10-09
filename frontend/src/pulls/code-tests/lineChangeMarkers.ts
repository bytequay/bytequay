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
import type { LineChangeDto } from './contracts';

export function lineChangeMarkers(changes: readonly LineChangeDto[], lineCount: number) {
  return changes.flatMap(change => {
    const deleted = change.kind === 'DELETED';
    const start = deleted ? Math.max(1, change.line) : change.line;
    const end = deleted ? start : start + change.count - 1;
    if (start < 1 || end > lineCount) return [];
    const label = { ADDED: 'Added', MODIFIED: 'Updated', DELETED: 'Deleted' }[change.kind];
    return [{ start, end, className: `ct-line-change is-${change.kind.toLowerCase()}${deleted && change.line === 0 ? ' is-before' : ''}`,
      title: `${label} ${change.count} ${change.count === 1 ? 'line' : 'lines'}${deleted ? (change.line === 0 ? ' before line 1' : ` after line ${change.line}`) : ''} compared with the base` }];
  });
}
