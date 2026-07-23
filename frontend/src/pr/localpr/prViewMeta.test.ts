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
import { actorRole, avatarLabel, displayName, workflowActorRole } from './prViewMeta';
import type { LocalPR } from '../../types/localPr';

const pr = { author: '@octocat' } as LocalPR;

describe('local PR actor presentation', () => {
  it('shows workflow roles instead of the persisted dev provider id', () => {
    expect(displayName('claude-code')).toBe('dev');
    expect(displayName('brain')).toBe('brain');
    expect(displayName('you')).toBe('You');
    expect(displayName('@octocat')).toBe('octocat');
    expect(avatarLabel('claude-code')).toBe('D');
  });

  it('presents the persisted stage agent as Dev', () => {
    expect(workflowActorRole('agent')).toBe('dev');
    expect(displayName('agent')).toBe('dev');
    expect(avatarLabel('agent')).toBe('D');
    expect(actorRole('agent', pr)).toBe('agent');
  });

  it('presents review-role and provider ids as Brain', () => {
    for (const actor of [
      'agent-reviewer', 'review-planner', 'independent-verifier', 'verifier',
      'claude-cli', 'codex-cli', 'openai',
    ]) {
      expect(workflowActorRole(actor)).toBe('brain');
      expect(displayName(actor)).toBe('brain');
      expect(avatarLabel(actor)).toBe('B');
      expect(actorRole(actor, pr)).toBe('agent');
    }
  });

  it('does not reinterpret remote GitHub handles as workflow roles', () => {
    expect(workflowActorRole('@codex')).toBeNull();
    expect(displayName('@codex')).toBe('codex');
    expect(actorRole('@octocat', pr)).toBe('author');
  });
});
