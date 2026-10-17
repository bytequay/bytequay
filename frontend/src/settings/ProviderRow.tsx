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
import type { ReactNode } from 'react';
import { LogIn, RefreshCw } from 'lucide-react';
import type { ModelProviderDto } from '../types';
import { isDeepSeekKeyLane, providerStatusLabel } from './model';
import { SettingsRow, StatusDot, dotTone } from './primitives';

export default function ProviderRow({ provider, checking, account = false, onSignIn, onCheck, children }: {
  provider: ModelProviderDto;
  checking: boolean;
  account?: boolean;
  onSignIn?: (providerId: string) => void;
  onCheck?: () => void;
  children?: ReactNode;
}) {
  const status = providerStatusLabel(provider.readiness, checking, provider.id);
  const available = provider.readiness === 'READY' || provider.readiness === 'DEGRADED';
  const title = provider.id === 'claude' ? `Claude Code${account ? '' : ' CLI'} · Default account`
    : account || isDeepSeekKeyLane(provider.id) ? provider.label : `${provider.label} CLI`;
  return <SettingsRow title={title} subtitle={
    <span className="setw-status">
      <StatusDot tone={dotTone(status.tone)} />
      <span>{account && provider.readiness === 'AUTHENTICATION_REQUIRED' && provider.diagnostic ? provider.diagnostic : status.label}
        {(account || provider.id === 'claude') && available && provider.diagnostic ? ` · ${provider.diagnostic}` : ''}
        {provider.readiness === 'UNSUPPORTED' && provider.diagnostic ? ` · ${provider.diagnostic}` : ''}
        {provider.version ? ` · v${provider.version}` : ''}</span>
    </span>
  }>
    {onSignIn !== undefined && provider.readiness === 'AUTHENTICATION_REQUIRED' && (
      <button type="button" className="setw-btn" disabled={checking} onClick={() => onSignIn(provider.id)}>
        <LogIn size={12} strokeWidth={2.2} color="var(--text-3)" /><span>Sign in</span>
      </button>
    )}
    {onCheck !== undefined && <button type="button" className="setw-btn" disabled={checking} onClick={onCheck}>
      <RefreshCw size={12} strokeWidth={2.2} color="var(--text-3)" /><span>{checking ? 'Checking…' : 'Check'}</span>
    </button>}
    {children}
  </SettingsRow>;
}
