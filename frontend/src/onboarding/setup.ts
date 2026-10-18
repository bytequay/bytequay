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
import type { ModelProviderDto, SetupStep } from '../types';
import { providerRemedy } from '../settings/model';

export const SETUP_CHANGED = 'bytequay:setup-changed';
export const OPEN_SETUP = 'bytequay:open-setup';
export const SETUP_STEPS: { id: Exclude<SetupStep, 'COMPLETE'>; label: string; title: string; description: string }[] = [
  { id: 'APPEARANCE', label: 'Appearance', title: 'Make yourself at home', description: 'Choose the appearance you like. You can change everything here later in Settings.' },
  { id: 'GITHUB', label: 'GitHub', title: 'Bring your GitHub work together', description: 'Connect your account to follow repositories, read pull requests, and review changes.' },
  { id: 'AGENT', label: 'Connect an agent', title: 'Choose who you work with', description: 'Connect Codex, Claude Code, or a supported API key. One usable agent is enough to get started.' },
  { id: 'DEFAULTS', label: 'Default agent', title: 'Your everyday agent', description: 'Choose the account and model for new development tasks, Today, and Assistant.' },
  { id: 'AUTOMATION', label: 'Automation', title: 'How should your agent work?', description: 'Choose what happens automatically. These defaults apply to new tasks and use your model allowance.' },
  { id: 'FEATURES', label: 'Your features', title: 'A little help with your day', description: 'Choose the features you want. Each uses your default Development agent.' },
];

/** Version diagnostics carry the release's configured minimum; do not maintain another version here. */
export function setupCommand(provider: ModelProviderDto): string | null {
  if (provider.readiness === 'UNSUPPORTED') {
    return provider.diagnostic.match(/npm install --global @openai\/codex@[\w.+-]+/)?.[0]
      ?? (provider.diagnostic.includes('claude update') ? 'claude update' : null);
  }
  return providerRemedy(provider.readiness, provider.id).command;
}
