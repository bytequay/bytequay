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
import type { Ds4StateDto, WorkModelOptionsDto } from '../types';

/** One selectable engine: the two CLI agents, one row per configured API
 *  account, and the local ds4 server. `value` is the id persisted in
 *  workspace settings and in the account-level AI defaults. */
export type AgentChoice = {
  value: string;
  label: string;
  detail: string;
  disabled?: boolean;
};

/** Builds the picker's options from the probe result. Shared by the
 *  workspace Agents tab and Settings → AI so the two never drift on what
 *  counts as available. */
export function choicesFrom(
  options: WorkModelOptionsDto | null,
  localAiState: Ds4StateDto | null,
): AgentChoice[] {
  const choices: AgentChoice[] = [
    { value: 'cli:claude-code', label: 'Claude CLI', detail: cliDetail(options, 'claude-code'), disabled: cliDisabled(options, 'claude-code') },
    { value: 'cli:codex', label: 'Codex CLI', detail: cliDetail(options, 'codex'), disabled: cliDisabled(options, 'codex') },
  ];
  options?.apiProviders.forEach(provider => {
    provider.accounts.forEach(account => {
      choices.push({
        value: `api:${provider.id}:${account.name}`,
        label: 'API',
        detail: `${provider.displayName} · ${account.name}${account.isDefault ? ' · default' : ''}`,
      });
    });
  });
  choices.push({
    value: 'local',
    label: 'Local',
    detail: localAiState === 'RUNNING'
      ? 'available'
      : localAiState === 'DISABLED' ? 'not enabled' : localAiState === null ? 'checking…' : 'not running',
    disabled: localAiState !== 'RUNNING',
  });
  return choices;
}

export function choiceText(choice: AgentChoice): string {
  return `${choice.label}${choice.detail.length === 0 ? '' : ` · ${choice.detail}`}`;
}

/** Splits a stored id into the engine part the picker lists and the optional
 *  trailing reasoning effort. The grammar is `cli:<agent>[:<model>][:<effort>]`
 *  and `api:<provider>[:<account>][:<effort>]`, so effort is always the fourth
 *  segment and an empty segment means "unset". */
export function splitChoice(value: string): { engine: string; effort: string | null } {
  const parts = value.split(':');
  const effort = parts.length >= 4 && parts[3].length > 0 ? parts[3] : null;
  if (parts[0] === 'cli' && parts.length >= 2) return { engine: `cli:${parts[1]}`, effort };
  if (parts[0] === 'api' && parts.length >= 3) return { engine: `api:${parts[1]}:${parts[2]}`, effort };
  return { engine: value, effort: null };
}

/** Re-attaches an effort to an engine id, padding the unused model/account
 *  slot so effort stays the fourth segment. No effort keeps the compact id
 *  every older reader already understands. */
export function withEffort(engine: string, effort: string | null): string {
  if (effort === null || effort.length === 0) return engine;
  const parts = engine.split(':');
  if (parts[0] === 'cli') return `${parts[0]}:${parts[1] ?? ''}::${effort}`;
  if (parts[0] === 'api') return `${parts[0]}:${parts[1] ?? ''}:${parts[2] ?? ''}:${effort}`;
  return engine;
}

/** The reasoning-effort ladder an engine's default model accepts. Claude and
 *  Codex do not share one — `minimal` is Codex-only, `xhigh`/`max` are
 *  Claude-only — and it narrows again by model, so this always reads the live
 *  catalog rather than assuming a fixed list. */
export function effortsFor(
  options: WorkModelOptionsDto | null,
  engine: string,
): { ids: string[]; fallback: string | null } {
  const none: { ids: string[]; fallback: string | null } = { ids: [], fallback: null };
  if (options === null) return none;
  const parts = engine.split(':');
  const row = parts[0] === 'cli'
    ? options.cliAgents.find(agent => agent.id === parts[1])
    : parts[0] === 'api'
      ? options.apiProviders.find(provider => provider.id === parts[1])
      : undefined;
  if (row === undefined) return none;
  const model = row.models.find(entry => entry.id === row.defaultModel) ?? row.models[0];
  if (model === undefined) return none;
  return {
    ids: (model.supportedReasoningEfforts ?? []).map(effort => effort.id),
    fallback: model.defaultReasoningEffort ?? null,
  };
}

/** Keeps a stored value usable: falls back to the first enabled choice
 *  when the saved engine has been uninstalled or signed out. A chosen effort
 *  survives that repair — it is a separate control, not part of the engine. */
export function selectableChoice(value: string, choices: AgentChoice[]): string {
  const { engine, effort } = splitChoice(value);
  const normalized = choices.some(choice => choice.value === engine) ? engine : normalizeChoice(engine);
  const current = choices.find(choice => choice.value === normalized);
  if (current !== undefined && !current.disabled) return withEffort(normalized, effort);
  return choices.find(choice => !choice.disabled)?.value ?? withEffort(normalized, effort);
}

/** Maps a pre-id value (old settings stored free-text model names) onto
 *  the current choice ids. */
export function normalizeChoice(value: string): string {
  if (value.startsWith('cli:') || value.startsWith('api:') || value === 'local') return value;
  const lower = value.toLowerCase();
  if (lower.includes('codex') || lower.includes('gpt')) return 'cli:codex';
  if (lower.includes('claude')) return 'cli:claude-code';
  return 'local';
}

export function choiceClass(value: string): string {
  if (value.startsWith('cli:claude')) return 'claude';
  if (value.startsWith('cli:codex') || value.startsWith('api:openai')) return 'gpt';
  if (value.startsWith('api:')) return 'api';
  return 'local';
}

export function choiceGlyph(value: string): string {
  if (value.startsWith('cli:claude')) return 'C';
  if (value.startsWith('cli:codex')) return 'X';
  if (value.startsWith('api:')) return 'A';
  return 'L';
}

function cliDisabled(options: WorkModelOptionsDto | null, id: string): boolean {
  const agent = options?.cliAgents.find(row => row.id === id);
  return agent === undefined ? true : !agent.installed;
}

function cliDetail(options: WorkModelOptionsDto | null, id: string): string {
  const agent = options?.cliAgents.find(row => row.id === id);
  if (agent === undefined) return 'checking…';
  if (!agent.installed) return 'not installed';
  return agent.authed ? 'available' : 'installed';
}
