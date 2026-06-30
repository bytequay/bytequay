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
 * The client-side auto-approve allowlist — the set of command heads a user
 * has chosen "Always allow" for. A gated tool call whose command head is on
 * the list folds silently into the activity strip instead of prompting. This
 * is the same allowlist shared with the planning auto-approve toggle.
 *
 * Persisted in localStorage (there is no durable backend allowlist yet — only
 * session-scoped pre-approval budgets); applied client-side as an
 * auto-pre-approve. The command head is the first whitespace token
 * ("mvn verify -Dtest=X" → "mvn"), matching how a user thinks "always allow
 * mvn".
 */
const KEY = 'bq.toolAllowlist';

/** The command head used as the allowlist key: the first token, lowercased. */
export function commandHead(command: string): string {
  const trimmed = command.trim().replace(/^\$\s*/, '');
  return (trimmed.split(/\s+/)[0] ?? '').toLowerCase();
}

export function allowedCommands(): string[] {
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(KEY) : null;
    if (raw === null) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  }
  catch {
    return [];
  }
}

/** True when this command's head is allowlisted (so it auto-runs). */
export function isAllowed(command: string): boolean {
  const head = commandHead(command);
  return head.length > 0 && allowedCommands().includes(head);
}

/** Add a command's head to the allowlist. Returns the head added. */
export function addAllowed(command: string): string {
  const head = commandHead(command);
  if (head.length === 0) return head;
  const set = new Set(allowedCommands());
  set.add(head);
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(KEY, JSON.stringify([...set]));
  }
  catch {
    /* storage unavailable — in-memory only for this session */
  }
  return head;
}
