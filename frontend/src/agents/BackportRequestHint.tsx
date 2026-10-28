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
import { GitBranch } from 'lucide-react';

function backportDraft(draft: string): string {
  const mention = /^\s*@(\S*)/.exec(draft);
  const rest = mention !== null && 'backport'.startsWith(mention[1].toLowerCase()) ? draft.slice(mention[0].length).trimStart() : draft;
  return `@backport ${rest}`;
}

export function startBackportRequest(button: HTMLButtonElement, draft: string, onDraftChange: (draft: string) => void): void {
  const input = button.closest('.agt-page')?.querySelector<HTMLTextAreaElement>('.agt-compose__input');
  onDraftChange(backportDraft(draft));
  requestAnimationFrame(() => { input?.focus(); input?.setSelectionRange('@backport '.length, '@backport '.length); });
}

export function BackportButton({ draft, onDraftChange, disabled }: {
  draft: string;
  onDraftChange: (draft: string) => void;
  disabled: boolean;
}) {
  return <button type="button" className="agt-start" disabled={disabled} title="Add @backport to the composer"
    onClick={event => startBackportRequest(event.currentTarget, draft, onDraftChange)}>
    <GitBranch size={13} strokeWidth={1.9} aria-hidden="true" />Backport
  </button>;
}
