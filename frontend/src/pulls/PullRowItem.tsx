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
import type { PullRow } from './model';

export default function PullRowItem({ row, selected, onPick }: {
  row: PullRow;
  selected: boolean;
  onPick: () => void;
}) {
  return (
    <button
      type="button"
      className={`pull-row${selected ? ' is-selected' : ''}`}
      onClick={onPick}
    >
      <span className={`pull-row__state pull-row__state--${row.kind}`} aria-hidden="true" />
      <span className="pull-row__body">
        <span className="pull-row__title">{row.title} <small>#{row.num}</small></span>
        <span className="pull-row__meta">{row.repo} · {row.author} · {row.time}</span>
        {row.labels.length > 0 && (
          <span className="pull-row__labels">
            {row.labels.map(label => <span key={label}>{label}</span>)}
          </span>
        )}
      </span>
      <span className={`pull-row__ci${row.status === null ? '' : ` is-${row.status}`}`}>
        {row.status ?? 'No CI'}
      </span>
    </button>
  );
}
