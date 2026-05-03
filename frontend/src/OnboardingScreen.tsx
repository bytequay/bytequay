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
import { useState } from 'react';
import LogoOnboarding from './LogoOnboarding';

type Props = {
  /** Called once the PAT is successfully stored, so App can flip into
   *  the ready state and route to the home view. */
  onSaved: () => void;
};

/**
 * First-run onboarding. Mirrors the centred layout in
 * docs/mockups/loading/bytequay-loading-animations-demo.html — the
 * animated brand mark fades + draws in, "Welcome to ByteQuay" sits
 * underneath, then a single PAT field plus Connect button. No tabs,
 * no settings chrome — the user sees one thing to do and does it.
 */
export default function OnboardingScreen({ onSaved }: Props) {
  const [token, setToken] = useState('');
  const [state, setState] = useState<'idle' | 'saving' | 'error'>('idle');
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (state === 'saving') return;
    const trimmed = token.trim();
    if (!trimmed) {
      setError('Paste a personal access token to continue.');
      setState('error');
      return;
    }
    setState('saving');
    setError(null);
    try {
      const ok = await window.bridge.savePat(trimmed);
      if (!ok) {
        setError('Backend rejected the token. Check the scopes and try again.');
        setState('error');
        return;
      }
      onSaved();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setState('error');
    }
  };

  return (
    <div className="onboarding">
      <main className="onboarding__card" role="main" aria-labelledby="onboarding-title">
        <LogoOnboarding size={130} />
        <h1 id="onboarding-title" className="onboarding__title">Welcome to ByteQuay</h1>
        <p className="onboarding__lede">
          Connect your GitHub account to start. We'll surface mentions, reviews,
          and CI failures — never the noise.
        </p>
        <form className="onboarding__form" onSubmit={(e) => { void submit(e); }}>
          <label className="onboarding__label" htmlFor="onboarding-pat">
            GitHub personal access token
          </label>
          <input
            id="onboarding-pat"
            type="password"
            className="onboarding__input"
            placeholder="ghp_…"
            value={token}
            onChange={(e) => { setToken(e.target.value); if (state === 'error') setState('idle'); }}
            autoFocus
            autoComplete="off"
            spellCheck={false}
          />
          <p className="onboarding__hint">
            Required scopes (classic PAT): <code>repo</code>, <code>read:user</code>.
            Stored encrypted in your macOS Keychain.
          </p>
          {state === 'error' && error && (
            <div className="onboarding__error" role="alert">{error}</div>
          )}
          <button
            type="submit"
            className="onboarding__cta"
            disabled={state === 'saving' || token.trim().length === 0}
          >
            {state === 'saving' ? 'Connecting…' : 'Connect →'}
          </button>
        </form>
      </main>
    </div>
  );
}
