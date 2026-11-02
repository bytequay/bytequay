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
import { CircleDot, GitBranch, ListChecks } from 'lucide-react';
import { removeReferenceDraft, type ComposerReference } from './composerReference';

const SHORTCUTS = [
  { name: 'backport', label: 'Backport', icon: GitBranch },
  { name: 'fix', label: 'Fix issues and failures', icon: CircleDot },
  { name: 'review', label: 'Review code and suggest changes', icon: ListChecks },
] as const;

function startTaskRequest(button: HTMLButtonElement, name: string, draft: string, onDraftChange: (draft: string) => void): void {
  const input = button.closest('.agt-page')?.querySelector<HTMLTextAreaElement>('.agt-compose__input');
  const mention = /^\s*@(\S*)/.exec(draft);
  const rest = mention !== null && SHORTCUTS.some(shortcut => shortcut.name.startsWith(mention[1].toLowerCase()))
    ? draft.slice(mention[0].length).trimStart() : draft;
  onDraftChange(`@${name} ${rest}`);
  requestAnimationFrame(() => {
    input?.focus();
    input?.setSelectionRange(name.length + 2, name.length + 2);
    // Reopening an unchanged shortcut must refresh suggestions even if focus and caret stayed put.
    input?.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
  });
}

export function TaskShortcuts({ draft, onDraftChange, disabled, backports, reference, onReferenceChange }: {
  draft: string;
  onDraftChange: (draft: string) => void;
  disabled: boolean;
  backports: boolean;
  reference?: ComposerReference | null;
  onReferenceChange?: (reference: ComposerReference | null) => void;
}) {
  return <>{SHORTCUTS.filter(shortcut => shortcut.name !== 'backport' || backports).map(shortcut =>
    <button key={shortcut.name} type="button" className="agt-start" disabled={disabled} title={`Add @${shortcut.name} to the composer`}
      onClick={event => {
        const text = removeReferenceDraft(draft, reference);
        onReferenceChange?.(null);
        startTaskRequest(event.currentTarget, shortcut.name, text, onDraftChange);
      }}>
      <shortcut.icon size={13} strokeWidth={1.9} aria-hidden="true" />{shortcut.label}
    </button>)}</>;
}
