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
import { displayModelName, effortLabel, providerAvailable, providerRemedy } from '../settings/model';
import type { ModelSettingsDto } from '../types';
import type { ContextSpecCheckDto } from './contracts';

export const shortSha = (sha: string) => sha.slice(0, 7);
export const plural = (count: number, one: string, many = one + 's') => `${count} ${count === 1 ? one : many}`;

export function shortDate(value: string | null | undefined, now = Date.now()): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const today = new Date(now);
  if (date.toDateString() === today.toDateString()) return 'Today';
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', ...(date.getFullYear() === today.getFullYear() ? {} : { year: 'numeric' }) });
}

/** "Codex · 5.6 Sol Medium", and why a check cannot start when the Review model is unavailable. */
export function modelLine(models: ModelSettingsDto | null): { label: string; blocker: string | null } {
  const agent = models?.agents.find(item => item.type === 'REVIEW');
  if (models === null || agent === undefined) return { label: 'Review model', blocker: models === null ? null : 'No Review model is configured.' };
  const effort = agent.effort ? ' ' + effortLabel(agent.effort) : '';
  const label = `${agent.label} · ${displayModelName(agent.modelLabel)}${effort}`;
  const provider = models.providers.find(item => item.id === agent.configId);
  if (provider === undefined) return { label, blocker: 'The Review model provider is unavailable.' };
  if (!providerAvailable(provider.readiness)) return { label, blocker: providerRemedy(provider.readiness, provider.id).title };
  if (!provider.models.some(model => model.id === agent.model)) return { label, blocker: 'The configured Review model is unavailable.' };
  return { label, blocker: null };
}

/** How many candidates the user lets a check reproduce, most severe first: each reproduction is a build. The first is the default. */
export const REPRODUCTION_LIMITS: number[] = [3, 8];

export function checkActive(check: ContextSpecCheckDto): boolean {
  return check.state === 'CHECKING' || check.state === 'REPRODUCING';
}

/** The check that holds the workspace's one check task, if one runs. */
export function activeCheck(checks: ContextSpecCheckDto[]): ContextSpecCheckDto | null {
  return checks.find(checkActive) ?? null;
}

/** What an active check is doing. */
export function checkProgress(check: ContextSpecCheckDto): string {
  if (check.state === 'CHECKING') return check.candidates.length === 0 ? 'Reading the specs and the code' : `${plural(check.candidates.length, 'candidate')} saved so far`;
  const waiting = check.candidates.filter(candidate => candidate.status === 'PENDING').length;
  return `Reproduction ${check.reproductions} of up to ${Math.min(check.maxReproductions, check.reproductions + waiting)}`;
}
