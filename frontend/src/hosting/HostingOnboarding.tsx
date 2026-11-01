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
import { useCallback, useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import OnboardingScreen from '../OnboardingScreen';
import type { HostingConnectionDto } from '../types';
import GitLabAccounts from './GitLabAccounts';

type Provider = 'GITHUB' | 'GITLAB';
const PROVIDERS = [{ value: 'GITHUB', label: 'GitHub' }, { value: 'GITLAB', label: 'GitLab' }] as const;

export default function HostingOnboarding({ onSaved, embedded = false }: { onSaved: () => void; embedded?: boolean }) {
  const [provider, setProvider] = useState<Provider>('GITHUB');
  const [connected, setConnected] = useState<Provider[]>([]);
  // Both providers can be connected here: each connected one is checked, and the step moves on to one still missing.
  const refresh = useCallback(async () => {
    const [github, accounts] = await Promise.all([window.bridge.hasPat(), window.bridge.listHostingAccounts()]);
    const gitlab = accounts.some(account => account.connected && account.instance.provider === 'GITLAB');
    const now = PROVIDERS.map(({ value }) => value).filter(value => value === 'GITHUB' ? github : gitlab);
    setConnected(now);
    setProvider(current => now.includes(current) ? PROVIDERS.find(({ value }) => !now.includes(value))?.value ?? current : current);
  }, []);
  useEffect(() => { void refresh().catch(() => {}); }, [refresh]);
  const saved = () => { void refresh().catch(() => {}); onSaved(); };
  const [gitLabAccount, setGitLabAccount] = useState<number>();
  const chooseGitLab = useCallback((account: HostingConnectionDto) => {
    setGitLabAccount(account.account.id);
    void refresh().catch(() => {});
    onSaved();
  }, [refresh, onSaved]);
  return <div className="hosting-onboarding">
    <div className="hosting-tabs" aria-label="Code hosting provider">
      {PROVIDERS.map(({ value, label }) => <button key={value} type="button" aria-pressed={provider === value} onClick={() => setProvider(value)}>
        {label}{connected.includes(value) && <Check size={14} aria-label="connected" />}
      </button>)}
    </div>
    {provider === 'GITHUB' ? connected.includes('GITHUB') ? <section className="hosting-panel"><h2>GitHub is connected</h2></section>
      : <OnboardingScreen onSaved={saved} embedded={embedded} />
      : <section className="hosting-panel"><h2>{connected.includes('GITLAB') ? 'GitLab is connected' : 'Connect GitLab'}</h2><p>Use GitLab.com or your company’s GitLab server.</p>
        <GitLabAccounts selected={gitLabAccount} autoSelectFirst onSelect={chooseGitLab} /></section>}
  </div>;
}
