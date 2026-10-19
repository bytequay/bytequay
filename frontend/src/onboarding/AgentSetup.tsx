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
import { useEffect, useState } from 'react';
import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import ProviderSignInDialog from '../agents/ProviderSignInDialog';
import type { ModelSettingsDto } from '../types';
import ProviderRow from '../settings/ProviderRow';
import { isDeepSeekKeyLane } from '../settings/model';
import { SettingsCard } from '../settings/primitives';
import { setupCommand } from './setup';

export default function AgentSetup({ models, checking, busy, onCheck, onChange, onError, onBusy }: {
  models: ModelSettingsDto;
  checking: boolean;
  busy: boolean;
  onCheck: () => void;
  onChange: (value: ModelSettingsDto) => void;
  onError: (failure: unknown) => void;
  onBusy: (busy: boolean) => void;
}) {
  const [signingIn, setSigningIn] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [saving, setSaving] = useState(false);
  const [alias, setAlias] = useState('');
  const [secret, setSecret] = useState('');
  useEffect(() => { onBusy(saving || signingIn !== null); return () => onBusy(false); }, [saving, signingIn, onBusy]);
  const providers = models.providers.filter(provider => provider.workspaceId == null);
  const addKey = async () => {
    if (saving || !alias.trim() || !secret.trim()) return;
    setSaving(true);
    try {
      onChange(await window.bridge.addModelApiKey('DeepSeek', alias.trim(), secret.trim()));
      setSecret('');
      setAlias('');
      setAdding(false);
    }
    catch (failure) { onError(failure); }
    finally { setSaving(false); }
  };
  return <>
    <SettingsCard>
      {providers.map(provider => <div key={provider.id} className="setup-provider">
        <ProviderRow provider={provider} checking={checking || saving} onCheck={onCheck} onSignIn={isDeepSeekKeyLane(provider.id) ? undefined : setSigningIn}>
          {isDeepSeekKeyLane(provider.id) && <button type="button" className="setw-btn is-quiet" disabled={saving || checking} onClick={() => {
            const key = models.apiKeys.find(key => provider.label === `${key.provider} · ${key.alias}`);
            if (key === undefined) return;
            setSaving(true);
            window.bridge.removeModelApiKey(key.alias).then(onChange).catch(onError).finally(() => setSaving(false));
          }}>Remove key</button>}
        </ProviderRow>
        {provider.diagnostic && !['READY', 'DEGRADED', 'UNSUPPORTED'].includes(provider.readiness)
          && <p className="setup-provider-detail">{provider.diagnostic}</p>}
        {setupCommand(provider) !== null && <div className="onboarding__cmd">
          <code>{setupCommand(provider)}</code><CopyLinkButton value={setupCommand(provider) ?? ''} label="command" />
        </div>}
      </div>)}
      {providers.length === 0 && <div className="setup-provider-detail" role="status">{checking ? 'Checking installed agents…' : 'No agents found. Check again to detect your CLIs.'}</div>}
    </SettingsCard>
    <p className="setup-hint">Run install or upgrade commands in your terminal, then choose Check. Sign in connects the account used by ByteQuay.</p>
    <button type="button" className="setw-btn" disabled={busy || checking || saving} onClick={onCheck}>{checking ? 'Checking agents…' : 'Check agents again'}</button>
    {models.keyProviders.some(provider => provider.name === 'DeepSeek') && <section className="setup-key">
      <button type="button" className="setup-text-button" aria-expanded={adding} disabled={saving || checking} onClick={() => setAdding(value => !value)}>
        {adding ? 'Cancel API key setup' : 'Use a DeepSeek API key instead'}
      </button>
      {adding && <form onSubmit={event => { event.preventDefault(); void addKey(); }}>
        <p className="setup-hint">DeepSeek runs through Codex CLI and does not need a Codex account. Your key is stored encrypted on this Mac.</p>
        <label>Account label<input value={alias} onChange={event => setAlias(event.target.value)} autoComplete="off" placeholder="Personal" disabled={saving} /></label>
        <label>DeepSeek API key<input type="password" value={secret} onChange={event => setSecret(event.target.value)} autoComplete="off" spellCheck={false} disabled={saving} /></label>
        <button type="submit" className="setw-btn" disabled={saving || !alias.trim() || !secret.trim()}>{saving ? 'Validating key…' : 'Validate and save key'}</button>
      </form>}
    </section>}
    {signingIn !== null && <ProviderSignInDialog laneId={signingIn} providerName={providers.find(provider => provider.id === signingIn)?.label ?? signingIn}
      bridge={window.bridge} onClose={() => { setSigningIn(null); onCheck(); }} />}
  </>;
}
