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
import { useRef, useState } from 'react';
import { LogIn, RefreshCw } from 'lucide-react';
import type { ModelProviderDto } from '../types';
import { isClaudeAccount, isClaudeLane, providerStatusLabel } from './model';
import { AddAction, Badge, Dialog, SettingsCard, SettingsRow, StatusDot, dotTone } from './primitives';

/** Repository account setup; model overrides select the login used by its new tasks. */
export default function ClaudeAccountsCard({ providers, checking, onCheck, onSignIn, onAdd, onRemove, repository = false, onRestrict }: {
  providers: ModelProviderDto[];
  checking: boolean;
  onCheck: () => void;
  onSignIn?: (providerId: string) => void;
  onAdd?: (label: string, email: string) => Promise<void>;
  onRemove?: (laneId: string) => Promise<void>;
  repository?: boolean;
  onRestrict?: (laneId: string) => Promise<void>;
}) {
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<ModelProviderDto | null>(null);
  const [restricting, setRestricting] = useState<ModelProviderDto | null>(null);
  const [restrictBusy, setRestrictBusy] = useState(false);
  const [restrictError, setRestrictError] = useState<string | null>(null);
  const accounts = providers.filter(provider => isClaudeLane(provider.id));
  if (accounts.length === 0 && onAdd === undefined) return null;
  return <>
    <div className="setw-section"><span className="setw-section-title">Claude accounts</span></div>
    <SettingsCard footer={onAdd === undefined ? undefined : <AddAction label="Add Claude account" onClick={() => setAdding(true)} />}>
      {accounts.map(provider => {
        const status = providerStatusLabel(provider.readiness, checking, provider.id);
        const available = provider.readiness === 'READY' || provider.readiness === 'DEGRADED';
        return <SettingsRow key={provider.id} title={provider.id === 'claude' ? 'Claude Code · Default account' : provider.label}
          subtitle={<span className="setw-status">
            <StatusDot tone={dotTone(status.tone)} />
            <span>{provider.readiness === 'AUTHENTICATION_REQUIRED' && provider.diagnostic ? provider.diagnostic : status.label}
              {available && provider.diagnostic ? ` · ${provider.diagnostic}` : ''}{provider.version ? ` · v${provider.version}` : ''}</span>
          </span>}>
          {onSignIn !== undefined && provider.readiness === 'AUTHENTICATION_REQUIRED' && (
            <button type="button" className="setw-btn" disabled={checking} onClick={() => onSignIn(provider.id)}>
              <LogIn size={12} strokeWidth={2.2} color="#6e6e73" /><span>Sign in</span>
            </button>
          )}
          <button type="button" className="setw-btn" disabled={checking} onClick={onCheck}>
            <RefreshCw size={12} strokeWidth={2.2} color="#6e6e73" /><span>{checking ? 'Checking…' : 'Check'}</span>
          </button>
          <Badge>{provider.workspaceId == null ? 'global' : 'this repository'}</Badge>
          {onRestrict !== undefined && isClaudeAccount(provider.id) && provider.workspaceId == null && (
            <button type="button" className="setw-btn" disabled={checking} onClick={() => { setRestrictError(null); setRestricting(provider); }}>Use only here</button>
          )}
          {onRemove !== undefined && isClaudeAccount(provider.id) && <button type="button" className="setw-btn"
            disabled={checking} onClick={() => setRemoving(provider)}>Remove</button>}
        </SettingsRow>;
      })}
    </SettingsCard>
    <div className="setw-footnote">
      {repository
        ? 'Adding an account selects it for Development and Review in this repository. Repository accounts are hidden and cannot run in other repositories. Global accounts remain available here.'
        : 'Accounts added here are global and available to every repository. Choose one above to use it by default.'}
    </div>
    {adding && onAdd !== undefined && <AddClaudeAccountDialog onAdd={onAdd} onCancel={() => setAdding(false)} />}
    {removing !== null && onRemove !== undefined && <RemoveClaudeAccountDialog provider={removing} onRemove={onRemove} onCancel={() => setRemoving(null)} />}
    {restricting !== null && onRestrict !== undefined && <Dialog title={`Use ${restricting.label} only here?`}
      confirmLabel={restrictBusy ? 'Saving…' : 'Use only here'} confirmDisabled={restrictBusy}
      onCancel={() => { if (!restrictBusy) setRestricting(null); }} onConfirm={() => {
        setRestrictBusy(true);
        void onRestrict(restricting.id).then(() => setRestricting(null))
          .catch(failure => setRestrictError(failure instanceof Error ? failure.message : 'Could not restrict this account'))
          .finally(() => setRestrictBusy(false));
      }}>
      <p>This selects the account for Development and Review here and hides it from other repositories. Existing tasks elsewhere cannot start another turn with it.</p>
      <p>Your saved Claude login stays signed in.</p>
      {restrictError !== null && <div className="setw-error" role="alert">{restrictError}</div>}
    </Dialog>}
  </>;
}

function AddClaudeAccountDialog({ onAdd, onCancel }: { onAdd: (label: string, email: string) => Promise<void>; onCancel: () => void }) {
  const [label, setLabel] = useState('');
  const [email, setEmail] = useState('');
  const emailInput = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const add = async () => {
    if (busy || label.trim() === '' || !emailInput.current?.reportValidity()) return;
    setBusy(true);
    setError(null);
    try {
      await onAdd(label.trim(), email.trim());
      onCancel();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not add the Claude account');
    } finally {
      setBusy(false);
    }
  };
  return <Dialog title="Add Claude account" confirmLabel={busy ? 'Adding…' : 'Add and sign in'}
    confirmDisabled={busy || label.trim() === '' || email.trim() === ''} onConfirm={() => void add()} onCancel={onCancel}>
    <label className="setw-field-label" htmlFor="claude-account-name">Name</label>
    <input id="claude-account-name" aria-label="Claude account name" placeholder="Work or Personal" maxLength={80} value={label}
      disabled={busy} onChange={event => setLabel(event.target.value)} />
    <label className="setw-field-label" htmlFor="claude-account-email">Email</label>
    <input id="claude-account-email" ref={emailInput} aria-label="Claude account email" type="email" required
      placeholder="you@example.com" autoComplete="email" maxLength={254} value={email}
      disabled={busy} onChange={event => setEmail(event.target.value)} />
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
  </Dialog>;
}

function RemoveClaudeAccountDialog({ provider, onRemove, onCancel }: {
  provider: ModelProviderDto;
  onRemove: (laneId: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const remove = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await onRemove(provider.id);
      onCancel();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not remove the Claude account');
    } finally {
      setBusy(false);
    }
  };
  return <Dialog title={`Remove ${provider.label}?`} confirmLabel={busy ? 'Removing…' : 'Remove account'}
    confirmDisabled={busy} onConfirm={() => void remove()} onCancel={() => { if (!busy) onCancel(); }}>
    <p>This removes the account from {provider.workspaceId == null ? 'ByteQuay for every repository' : 'this repository'}. Running turns can finish, but existing tasks cannot start another turn with it. Choose another account for new work.</p>
    <p>Saved Claude login and session files remain on this Mac. This does not sign out of Claude or delete its credentials.</p>
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
  </Dialog>;
}
