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
import type { LocalPR } from '../../types/localPr';

/** Who an actor is, relative to this PR — drives bubble tinting + badges
 *  (U15): `agent` (the persisted dev / brain actor ids) gets the purple tint,
 *  `author` (matches `pr.author` on an external PR) gets the blue tint +
 *  Author badge, `you` is the local user (no tint), anyone else is a
 *  third-party reviewer/commenter (no tint, no badge — we don't have their
 *  real GitHub role association synced). */
export type ActorRole = 'agent' | 'author' | 'you' | 'other';

export type WorkflowActorRole = 'dev' | 'brain';

export const QUICK_REVIEW_AUTHOR = 'ai-reviewer';

const DEV_ACTORS = new Set(['claude-code', 'agent']);
const BRAIN_ACTORS = new Set([
  'brain',
  'ai reviewer',
  QUICK_REVIEW_AUTHOR,
  'agent-reviewer',
  'review-planner',
  'independent-verifier',
  'verifier',
  'claude',
  'claude-cli',
  'codex',
  'codex-cli',
  'openai',
  'anthropic',
  'deepseek',
]);

/** Canonical workflow role for an internal actor id. GitHub actors are
 * prefixed with `@`, so a remote login that resembles a provider id is left
 * alone. */
export function workflowActorRole(actor: string): WorkflowActorRole | null {
  if (actor.startsWith('@')) return null;
  const normalized = actor.trim().toLowerCase();
  if (DEV_ACTORS.has(normalized)) return 'dev';
  if (BRAIN_ACTORS.has(normalized)) return 'brain';
  return null;
}

function sameActor(a: string, b: string): boolean {
  const normalise = (value: string) => value.startsWith('@') ? value.slice(1).toLowerCase() : value.toLowerCase();
  return normalise(a) === normalise(b);
}

export function actorRole(actor: string, pr: LocalPR): ActorRole {
  if (workflowActorRole(actor) !== null) return 'agent';
  if (actor === 'you') return 'you';
  if (pr.author !== null && sameActor(actor, pr.author)) return 'author';
  return 'other';
}

/** Short avatar-glyph label for an actor (2 letters max, matching the
 *  mockup's circular avatar chips). */
export function avatarLabel(actor: string): string {
  const role = workflowActorRole(actor);
  if (role === 'dev') return 'D';
  if (role === 'brain') return 'B';
  if (actor === 'you') return 'Y';
  const handle = actor.startsWith('@') ? actor.slice(1) : actor;
  return handle.slice(0, 2).toUpperCase();
}

/** Display name for an actor row. Persisted task actors are implementation
 *  ids, so present their workflow role instead of leaking the CLI provider. */
export function displayName(actor: string): string {
  const role = workflowActorRole(actor);
  if (role !== null) return role;
  if (actor === 'you') return 'You';
  return actor.startsWith('@') ? actor.slice(1) : actor;
}

/** A `ci` event whose payload status is a failure paints the icon red. */
export function isFailedCiPayload(payload: Record<string, unknown> | null): boolean {
  return payload !== null && payload['status'] === 'failed';
}

/** Compact relative label from an epoch-ms timestamp (the timeline `.ts`) —
 *  matches github.com's own convention: relative for the first week, then
 *  the exact date (so an 84-day-old row reads "Apr 17", not "84d ago"). */
export function agoLabel(ms: number, now: number = Date.now()): string {
  const deltaSec = Math.round((now - ms) / 1000);
  if (deltaSec < 60) return 'just now';
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m ago`;
  if (deltaSec < 86400) return `${Math.round(deltaSec / 3600)}h ago`;
  const days = Math.round(deltaSec / 86400);
  if (days < 7) return days === 1 ? '1 day ago' : `${days} days ago`;
  const date = new Date(ms);
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return date.toLocaleDateString('en-US', sameYear
    ? { month: 'short', day: 'numeric' }
    : { month: 'short', day: 'numeric', year: 'numeric' });
}
