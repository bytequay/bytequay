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

import type { AgentProviderReadiness, AgentProviderStatus } from '../types';

/** Half-hour steps across the day, as minutes of day, for the quiet-hours pickers. */
export const MINUTE_OPTIONS = Array.from({ length: 48 }, (_, index) => index * 30);

export function formatMinuteOfDay(minute: number): string {
  const hour24 = Math.floor(minute / 60);
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  const minutes = String(minute % 60).padStart(2, '0');
  return `${hour12}:${minutes} ${hour24 < 12 ? 'AM' : 'PM'}`;
}

export type ProviderStatusTone = { label: string; tone: 'is-ok' | 'is-warn' | 'is-neutral' | 'is-danger' };

export function providerAvailable(readiness: AgentProviderReadiness): boolean {
  return readiness === 'READY' || readiness === 'DEGRADED';
}

/** The Codex lane is the only one CP7 accepts tasks on; the rest are shown but not offered. */
export const INTERACTIVE_LANE_ID = 'codex';

const LANE_LABELS: Record<string, string> = { codex: 'Codex', claude: 'Claude' };

export function laneLabel(laneId: string): string {
  return LANE_LABELS[laneId] ?? laneId;
}

/**
 * What the user can actually do about a provider that is not ready.
 *
 * ByteQuay never installs or signs in on the user's behalf: both are interactive and belong to
 * the user's own shell. So every state resolves to a command they run themselves plus a re-probe,
 * and `retry` is always offered because a probe can go stale the moment it finishes.
 */
export type ProviderRemedy = {
  kind: 'install' | 'login' | 'report' | 'retry';
  title: string;
  detail: string;
  /** The command to run, when the fix is one. */
  command: string | null;
};

export function providerRemedy(readiness: AgentProviderReadiness): ProviderRemedy {
  switch (readiness) {
    case 'CLI_MISSING':
      return {
        kind: 'install',
        title: 'Install the CLI',
        detail: 'ByteQuay runs the CLI you already manage; it does not install one for you.',
        command: 'npm install -g @openai/codex',
      };
    case 'AUTHENTICATION_REQUIRED':
      return {
        kind: 'login',
        title: 'Sign in',
        detail: 'Sign in in your own terminal, then check again.',
        command: 'codex login',
      };
    case 'UNSUPPORTED':
      return {
        kind: 'report',
        title: 'Report an issue',
        detail: 'This CLI reports a version ByteQuay cannot drive. Include the diagnostic below.',
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
export function providerStatusLabel(readiness: AgentProviderReadiness, checking: boolean): ProviderStatusTone {
  if (checking) return { label: 'Checking…', tone: 'is-neutral' };
  switch (readiness) {
    case 'READY':
      return { label: '✓ Available', tone: 'is-ok' };
    case 'AUTHENTICATION_REQUIRED':
      return { label: 'Sign in required', tone: 'is-danger' };
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
