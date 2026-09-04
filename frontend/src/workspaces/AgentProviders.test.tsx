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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AgentProviderRow } from './WorkspaceSettings';
import { NO_AGENT_CAPABILITIES } from '../agents/capabilities';
import type { AgentProviderStatus, AgentProviderReadiness } from '../types';

function row(readiness: AgentProviderReadiness, over: Partial<AgentProviderStatus> = {}): string {
  const provider: AgentProviderStatus = {
    laneId: 'codex',
    readiness,
    version: '0.151.0',
    diagnostic: 'probe said so',
    capabilities: NO_AGENT_CAPABILITIES,
    ...over,
  };
  return renderToStaticMarkup(<AgentProviderRow provider={provider} checking={false} />);
}

describe('agent provider rows', () => {
  it('gives a missing CLI the install command and a blocked login the sign-in command', () => {
    const missing = row('CLI_MISSING');
    expect(missing).toContain('Not installed');
    expect(missing).toContain('npm install -g @openai/codex');
    // ByteQuay must not imply it installs or signs in for the user.
    expect(missing).toContain('does not install one for you');

    const login = row('AUTHENTICATION_REQUIRED');
    expect(login).toContain('Sign in required');
    expect(login).toContain('codex login');
    expect(login).toContain('your own terminal');

    // ByteQuay keeps its own Codex home, so the generic login would sign in the wrong one.
    const own = row('AUTHENTICATION_REQUIRED', {
      signInCommand: 'CODEX_HOME="/Users/me/Library/Application Support/ByteQuay/codex-home" codex login',
    });
    expect(own).toContain('CODEX_HOME=');
    expect(own).toContain('ByteQuay/codex-home');
  });

  it('offers a report only for a CLI it cannot drive', () => {
    expect(row('UNSUPPORTED')).toContain('Report an issue');
    expect(row('TEMPORARILY_UNAVAILABLE')).not.toContain('Report an issue');
    expect(row('READY')).not.toContain('Report an issue');
  });

  it('states a ready provider without inventing an action for it', () => {
    const ready = row('READY');
    expect(ready).toContain('Codex CLI');
    expect(ready).toContain('v0.151.0');
    expect(ready).not.toContain('Check again</');
    expect(ready).not.toContain('Install the CLI');
  });

  it('treats a ready Claude lane as a real provider and gives lane-correct remedies', () => {
    const claude = row('READY', { laneId: 'claude' });
    expect(claude).toContain('Claude CLI');
    expect(claude).not.toContain('Preview');
    expect(claude).not.toContain('tasks run on Codex');
    expect(row('CLI_MISSING', { laneId: 'claude' })).toContain('npm install -g @anthropic-ai/claude-code');
    expect(row('AUTHENTICATION_REQUIRED', { laneId: 'claude' })).toContain('claude auth login');
  });

  it('keeps the probe diagnostic visible so a report can quote it', () => {
    expect(row('UNSUPPORTED')).toContain('probe said so');
  });
});
