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

import type { AgentProviderReadiness, AgentProviderStatus, ModelOptionDto, ModelProviderDto } from '../types';

/** Half-hour steps across the day, as minutes of day, for the quiet-hours pickers. */
export const MINUTE_OPTIONS = Array.from({ length: 48 }, (_, index) => index * 30);

export function formatMinuteOfDay(minute: number): string {
  const hour24 = Math.floor(minute / 60);
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  const minutes = String(minute % 60).padStart(2, '0');
  return `${hour12}:${minutes} ${hour24 < 12 ? 'AM' : 'PM'}`;
}

type ProviderStatusTone = { label: string; tone: 'is-ok' | 'is-warn' | 'is-neutral' | 'is-danger' };

export function providerAvailable(readiness: AgentProviderReadiness): boolean {
  return readiness === 'READY' || readiness === 'DEGRADED';
}

/** Initial lane when neither the workspace nor the catalog has supplied a selection yet. */
export const INTERACTIVE_LANE_ID = 'codex';

const LANE_LABELS: Record<string, string> = { codex: 'Codex', claude: 'Claude' };

export function isDeepSeekKeyLane(laneId: string): boolean {
  return laneId.startsWith('deepseek-key-');
}

export function isClaudeAccount(laneId: string): boolean {
  return laneId.startsWith('claude-account-');
}

export function isClaudeLane(laneId: string): boolean {
  return laneId === 'claude' || isClaudeAccount(laneId);
}

export function laneLabel(laneId: string): string {
  return isClaudeAccount(laneId) ? 'Claude' : isDeepSeekKeyLane(laneId) ? 'DeepSeek' : LANE_LABELS[laneId] ?? laneId;
}

/**
 * The short name Codex itself shows for its models: "GPT-5.6-Sol" reads as "5.6 Sol". Other
 * providers' names are already prose and pass through untouched.
 */
export function displayModelName(name: string): string {
  return name.startsWith('GPT-') ? name.slice(4).replace(/-/g, ' ') : name;
}

const EFFORT_LABELS: Record<string, string> = {
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  xhigh: 'Extra high',
  max: 'Max',
  ultra: 'Ultra',
};

/** The catalog's effort ids as words; an id the catalog invents later is shown capitalised. */
export function effortLabel(effort: string): string {
  return EFFORT_LABELS[effort] ?? effort.charAt(0).toUpperCase() + effort.slice(1);
}

/**
 * What the user can actually do about a provider that is not ready.
 *
 * ByteQuay never installs or signs in on the user's behalf: both are interactive and belong to
 * the user's own shell. So every state resolves to a command they run themselves plus a re-probe,
 * and `retry` is always offered because a probe can go stale the moment it finishes.
 */
type ProviderRemedy = {
  kind: 'install' | 'login' | 'report' | 'retry';
  title: string;
  detail: string;
  /** The command to run, when the fix is one. */
  command: string | null;
};

const INSTALL_COMMANDS: Record<string, string> = {
  codex: 'npm install -g @openai/codex',
  claude: 'npm install -g @anthropic-ai/claude-code',
};

const LOGIN_COMMANDS: Record<string, string> = {
  codex: 'codex login',
  claude: 'claude auth login',
};

/** As in the composer's picker: keep the preferred effort when the model offers it, else take the model's default. */
export function modelEffort(model: ModelOptionDto | undefined, preferred?: string | null): string {
  const efforts = model?.efforts ?? [];
  return [preferred, model?.defaultEffort].find(candidate => candidate != null && efforts.includes(candidate)) ?? efforts[0] ?? '';
}

/** What a lane would launch with: its configured model and effort, else the first it offers. */
export function laneDefaults(providers: ModelProviderDto[], laneId: string): { model: string; effort: string } {
  const provider = providers.find(candidate => candidate.id === laneId);
  const model = provider?.models.find(option => option.id === provider.selectedModel) ?? provider?.models[0];
  return { model: model?.id ?? '', effort: modelEffort(model, provider?.selectedEffort) };
}

export function laneModels(providers: ModelProviderDto[] | null, laneId: string): ModelOptionDto[] {
  return providers?.find(candidate => candidate.id === laneId)?.models ?? [];
}

export function providerRemedy(readiness: AgentProviderReadiness, laneId = INTERACTIVE_LANE_ID): ProviderRemedy {
  if (isDeepSeekKeyLane(laneId) && readiness === 'AUTHENTICATION_REQUIRED') {
    return {
      kind: 'retry',
      title: 'Check the DeepSeek key',
      detail: 'Review the saved key in Models settings, then check again.',
      command: null,
    };
  }
  switch (readiness) {
    case 'CLI_MISSING':
      return {
        kind: 'install',
        title: isDeepSeekKeyLane(laneId) ? 'Install Codex CLI' : 'Install the CLI',
        detail: 'ByteQuay runs the CLI you already manage; it does not install one for you.',
        command: isClaudeAccount(laneId) ? INSTALL_COMMANDS.claude : isDeepSeekKeyLane(laneId) ? INSTALL_COMMANDS.codex : INSTALL_COMMANDS[laneId] ?? null,
      };
    case 'AUTHENTICATION_REQUIRED':
      return {
        kind: 'login',
        title: 'Sign in',
        detail: 'Sign in in your own terminal, then check again.',
        command: LOGIN_COMMANDS[laneId] ?? null,
      };
    case 'UNSUPPORTED':
      return {
        kind: 'report',
        title: 'Report an issue',
        detail: 'A required provider protocol or capability failed. Include the detected version and diagnostic below.',
        command: null,
      };
    default:
      return {
        kind: 'retry',
        title: 'Check again',
        detail: 'Readiness is probed fresh on every check.',
        command: null,
      };
  }
}

/**
 * The command to show for a provider that is not ready.
 *
 * A lane that keeps its own provider home names its exact sign-in command, because the generic
 * one would sign the user's own home in instead.
 */
export function remedyCommand(
  provider: Pick<AgentProviderStatus, 'signInCommand'>,
  remedy: ProviderRemedy,
): string | null {
  if (remedy.kind === 'login' && provider.signInCommand) return provider.signInCommand;
  return remedy.command;
}

/** Maps backend provider readiness to the settings badge; checking wins over any probe result. */
export function providerStatusLabel(readiness: AgentProviderReadiness, checking: boolean, laneId = ''): ProviderStatusTone {
  if (checking) return { label: 'Checking…', tone: 'is-neutral' };
  switch (readiness) {
    case 'READY':
      return { label: 'Ready', tone: 'is-ok' };
    case 'AUTHENTICATION_REQUIRED':
      return { label: isDeepSeekKeyLane(laneId) ? 'Key needs attention' : 'Sign in required', tone: 'is-danger' };
    case 'CLI_MISSING':
      return { label: 'Not installed', tone: 'is-neutral' };
    case 'UNSUPPORTED':
      return { label: 'Unsupported', tone: 'is-danger' };
    case 'DEGRADED':
      return { label: 'Limited', tone: 'is-warn' };
    case 'TEMPORARILY_UNAVAILABLE':
      return { label: 'Unavailable', tone: 'is-neutral' };
  }
}
