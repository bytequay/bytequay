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
import type { WorkModelOptionsDto } from '../types';
import { choicesFrom, effortsFor, selectableChoice, splitChoice, withEffort } from './agentChoices';

const options: WorkModelOptionsDto = {
  cliAgents: [
    {
      id: 'claude-code', displayName: 'Claude Code', installed: true, authed: true,
      defaultModel: 'claude-opus-4-8',
      models: [{
        id: 'claude-opus-4-8', displayName: 'Opus', isDefault: true,
        defaultReasoningEffort: 'high',
        supportedReasoningEfforts: [{ id: 'low' }, { id: 'high' }, { id: 'xhigh' }, { id: 'max' }],
      }],
    },
    {
      id: 'codex', displayName: 'Codex', installed: true, authed: true,
      defaultModel: 'gpt-5',
      models: [{
        id: 'gpt-5', displayName: 'GPT-5', isDefault: true,
        defaultReasoningEffort: 'medium',
        supportedReasoningEfforts: [{ id: 'minimal' }, { id: 'low' }, { id: 'medium' }, { id: 'high' }],
      }],
    },
  ],
  apiProviders: [],
};

describe('engine choice ids', () => {
  it('reads effort out of the fourth segment and leaves older ids alone', () => {
    expect(splitChoice('cli:claude-code')).toEqual({ engine: 'cli:claude-code', effort: null });
    expect(splitChoice('cli:claude-code::xhigh'))
      .toEqual({ engine: 'cli:claude-code', effort: 'xhigh' });
    expect(splitChoice('api:openai:default api'))
      .toEqual({ engine: 'api:openai:default api', effort: null });
    expect(splitChoice('api:openai:default api:high'))
      .toEqual({ engine: 'api:openai:default api', effort: 'high' });
    expect(splitChoice('local')).toEqual({ engine: 'local', effort: null });
  });

  it('round-trips through withEffort, staying compact when no effort is set', () => {
    expect(withEffort('cli:codex', null)).toBe('cli:codex');
    expect(withEffort('cli:codex', 'minimal')).toBe('cli:codex::minimal');
    expect(withEffort('api:openai:default api', 'high')).toBe('api:openai:default api:high');
    expect(splitChoice(withEffort('cli:claude-code', 'max')).effort).toBe('max');
  });

  it('keeps a chosen effort when repairing an engine that is still available', () => {
    const choices = choicesFrom(options, 'RUNNING');
    expect(selectableChoice('cli:codex::minimal', choices)).toBe('cli:codex::minimal');
  });

  it('offers the ladder of the engine default model, which differs per engine', () => {
    // minimal is Codex-only; xhigh and max are Claude-only. One shared list
    // would offer values the CLI would reject.
    expect(effortsFor(options, 'cli:claude-code'))
      .toEqual({ ids: ['low', 'high', 'xhigh', 'max'], fallback: 'high' });
    expect(effortsFor(options, 'cli:codex'))
      .toEqual({ ids: ['minimal', 'low', 'medium', 'high'], fallback: 'medium' });
    expect(effortsFor(options, 'local')).toEqual({ ids: [], fallback: null });
    expect(effortsFor(null, 'cli:codex')).toEqual({ ids: [], fallback: null });
  });
});
