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
import { Pin, PinOff } from 'lucide-react';
import { setPinned, usePinnedItems, type PinnedItem } from './pinned';

export default function PinButton({ item, className = '' }: { item: PinnedItem; className?: string }) {
  const pinned = usePinnedItems().some(candidate => candidate.key === item.key);
  const label = `${pinned ? 'Unpin' : 'Pin'} ${item.title}`;
  const Icon = pinned ? PinOff : Pin;
  return (
    <button type="button" className={`pin-button${pinned ? ' is-pinned' : ''}${className ? ` ${className}` : ''}`}
      aria-label={label} title={label} aria-pressed={pinned}
      onClick={event => { event.stopPropagation(); setPinned(item, !pinned); }}>
      <Icon size={14} aria-hidden="true" />
    </button>
  );
}
