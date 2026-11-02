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
import type { ComposerSuggestionCommand, ComposerSuggestionDto } from '../types';

export type ComposerReference = {
  command: ComposerSuggestionCommand;
  item: ComposerSuggestionDto;
};

export function referenceLabel(item: Pick<ComposerSuggestionDto, 'title' | 'number'>): string {
  const title = [...item.title.replace(/\s+/g, ' ').trim()];
  return `${title.length > 40 ? title.slice(0, 39).join('') + '…' : title.join('')} #${item.number}`;
}

/** The canonical draft owns the reference; metadata only changes how that exact leading URL is shown. */
export function referenceDraft(draft: string, reference: ComposerReference | null | undefined): { text: string; start: number; end: number } | null {
  if (!reference) return null;
  const match = /^(\s*@(backport|fix|review)[ \t]+)(\S+)/i.exec(draft);
  if (match === null || match[2].toUpperCase() !== reference.command || match[3] !== reference.item.url) return null;
  const label = referenceLabel(reference.item);
  return { text: match[1] + label + draft.slice(match[0].length), start: match[1].length, end: match[1].length + label.length };
}

export function removeReferenceDraft(draft: string, reference: ComposerReference | null | undefined): string {
  const selected = referenceDraft(draft, reference);
  return selected === null ? draft : selected.text.slice(0, selected.start) + selected.text.slice(selected.end).replace(/^[ \t]/, '');
}

export function editReferenceDraft(text: string, reference: ComposerReference): { draft: string; reference: ComposerReference | null } {
  const command = /^(\s*@(backport|fix|review)[ \t]+)([\s\S]*)$/i.exec(text);
  const label = referenceLabel(reference.item);
  if (command !== null && command[2].toUpperCase() === reference.command && command[3].startsWith(label)
    && /^(?:\s|$)/.test(command[3].slice(label.length))) {
    return { draft: command[1] + reference.item.url + command[3].slice(label.length), reference };
  }
  return { draft: text, reference: referenceDraft(text, reference) === null ? null : reference };
}
