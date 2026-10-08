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
import { describe, expect, it } from 'vitest';
import { MINUTE_OPTIONS, formatMinuteOfDay, isDeepSeekKeyLane, laneLabel, providerAvailable, providerRemedy, providerStatusLabel, remedyCommand } from './model';

describe('settings model helpers', () => {
  it('formats minutes of day as 12-hour times', () => {
    expect(formatMinuteOfDay(0)).toBe('12:00 AM');
    expect(formatMinuteOfDay(60)).toBe('1:00 AM');
    expect(formatMinuteOfDay(450)).toBe('7:30 AM');
    expect(formatMinuteOfDay(720)).toBe('12:00 PM');
    expect(formatMinuteOfDay(23 * 60 + 30)).toBe('11:30 PM');
  });

  it('offers every half hour of the day', () => {
    expect(MINUTE_OPTIONS).toHaveLength(48);
    expect(MINUTE_OPTIONS[0]).toBe(0);
    expect(MINUTE_OPTIONS[47]).toBe(23 * 60 + 30);
  });

  it('maps provider readiness to badges and availability, with checking winning', () => {
    expect(providerStatusLabel('READY', true).label).toBe('Checking…');
    expect(providerStatusLabel('READY', false)).toEqual({ label: 'Ready', tone: 'is-ok' });
    expect(providerStatusLabel('AUTHENTICATION_REQUIRED', false).label).toBe('Sign in required');
    expect(providerStatusLabel('CLI_MISSING', false).label).toBe('Not installed');
    expect(providerStatusLabel('UNSUPPORTED', false).label).toBe('Unsupported');
    expect(providerStatusLabel('DEGRADED', false).tone).toBe('is-warn');
    expect(providerStatusLabel('TEMPORARILY_UNAVAILABLE', false).label).toBe('Unavailable');
    expect(providerAvailable('READY')).toBe(true);
    expect(providerAvailable('DEGRADED')).toBe(true);
    for (const readiness of ['AUTHENTICATION_REQUIRED', 'CLI_MISSING', 'UNSUPPORTED', 'TEMPORARILY_UNAVAILABLE'] as const) {
      expect(providerAvailable(readiness)).toBe(false);
    }
  });
});

describe('provider remedies', () => {
  it('offers installation commands and requires the provider profile for sign-in', () => {
    expect(providerRemedy('CLI_MISSING').kind).toBe('install');
    expect(providerRemedy('CLI_MISSING').command).toBe('npm install -g @openai/codex');
    expect(providerRemedy('CLI_MISSING', 'claude').command).toBe('npm install -g @anthropic-ai/claude-code');
    for (const lane of ['codex', 'claude', 'claude-account-work']) {
      const remedy = providerRemedy('AUTHENTICATION_REQUIRED', lane);
      expect(remedy.kind).toBe('login');
      expect(remedy.detail).toContain('ByteQuay profile');
      expect(remedyCommand({ signInCommand: null }, remedy)).toBeNull();
      const signInCommand = lane === 'codex' ? "CODEX_HOME='/bytequay/codex-home' codex login"
        : "CLAUDE_CONFIG_DIR='/bytequay/claude-home' claude auth login";
      expect(remedyCommand({ signInCommand }, remedy)).toBe(signInCommand);
    }
  });

  it('routes an undriveable CLI to a report and everything else to a re-probe', () => {
    expect(providerRemedy('UNSUPPORTED').kind).toBe('report');
    expect(providerRemedy('UNSUPPORTED').command).toBeNull();
    for (const readiness of ['READY', 'DEGRADED', 'TEMPORARILY_UNAVAILABLE'] as const) {
      expect(providerRemedy(readiness).kind).toBe('retry');
    }
  });

  it('routes DeepSeek key errors to Models settings without a CLI sign-in command', () => {
    expect(isDeepSeekKeyLane('deepseek-key-123')).toBe(true);
    expect(laneLabel('deepseek-key-123')).toBe('DeepSeek');
    expect(providerStatusLabel('AUTHENTICATION_REQUIRED', false, 'deepseek-key-123').label).toBe('Key needs attention');
    expect(providerRemedy('AUTHENTICATION_REQUIRED', 'deepseek-key-123')).toMatchObject({
      title: 'Check the DeepSeek key', command: null,
    });
  });
});
