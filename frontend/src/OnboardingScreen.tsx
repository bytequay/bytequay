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
import LogoOnboarding from './LogoOnboarding';

type Props = {
  /** Called once auth (PAT or OAuth) successfully stores a token, so App
   *  can flip into the ready state and route to the home view. */
  onSaved: () => void;
};

type OauthStatus = 'idle' | 'launching' | 'awaiting' | 'error';

/** Only fine-grained tokens carry a repo-selection ceiling. Classic ones are
 *  `ghp_` or, pre-2021, bare 40-hex; gh's is `gho_` — none of those warrant
 *  the warning, so this prefix is the whole test. */
const FINE_GRAINED_PREFIX = 'github_pat_';

/**
 * First-run onboarding. OAuth-first when the backend is configured with
 * a GitHub OAuth App (GITHUB_CLIENT_ID/SECRET); falls back to the PAT
 * field underneath. The PAT path is also kept as an opt-in escape
 * hatch for users who would rather not run an OAuth dance.
 */
export default function OnboardingScreen({ onSaved }: Props) {
  const [oauthConfigured, setOauthConfigured] = useState<boolean | null>(null);
  const [oauthUrl, setOauthUrl] = useState<string | null>(null);
  const [oauthStatus, setOauthStatus] = useState<OauthStatus>('idle');
  const [oauthError, setOauthError] = useState<string | null>(null);
  const [showPat, setShowPat] = useState(false);

  const [token, setToken] = useState('');
  const [patState, setPatState] = useState<'idle' | 'saving' | 'error'>('idle');
  const [patError, setPatError] = useState<string | null>(null);

  // null until the probe answers — rendering the no-gh advice before then
  // would flash it at every user who does have gh.
  const [ghAvailable, setGhAvailable] = useState<boolean | null>(null);
  const [ghState, setGhState] = useState<'idle' | 'importing' | 'error'>('idle');
  const [ghError, setGhError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const res = await window.bridge.getGitHubOAuthAuthorizeUrl();
        if (cancelled) return;
        setOauthConfigured(res.configured);
        setOauthUrl(res.url ?? null);
        if (!res.configured) setShowPat(true);
      }
      catch {
        if (cancelled) return;
        setOauthConfigured(false);
        setShowPat(true);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      // A failed probe means "the sidecar hasn't answered yet", not "gh is
      // missing" — the two are indistinguishable from one call, and guessing
      // wrong pins the install-gh advice in front of users who already have
      // it, with no way to re-ask short of relaunching. Retry, and only
      // report absence when the backend actually says so.
      for (let attempt = 0; attempt < 5; attempt++) {
        if (cancelled) return;
        try {
          const res = await window.bridge.getGitHubCliAvailable();
          if (!cancelled) setGhAvailable(res.available);
          return;
        }
        catch {
          await new Promise((resolve) => { setTimeout(resolve, 1_000); });
        }
      }
      if (!cancelled) setGhAvailable(false);
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    const teardown = window.bridge.onGitHubOauthComplete((payload) => {
      if (payload.success) {
        setOauthStatus('idle');
        setOauthError(null);
        onSaved();
      }
      else {
        setOauthStatus('error');
        setOauthError(payload.error ?? 'GitHub sign-in failed');
      }
    });
    return teardown;
  }, [onSaved]);

  const startOauth = async () => {
    if (!oauthUrl) return;
    setOauthStatus('launching');
    setOauthError(null);
    try {
      // Re-fetch so a fresh state + verifier is minted on every click —
      // a single authorize URL is good for one round trip.
      const res = await window.bridge.getGitHubOAuthAuthorizeUrl();
      if (!res.configured || !res.url) {
        setOauthStatus('error');
        setOauthError('OAuth is no longer configured on the backend.');
        return;
      }
      await window.bridge.openExternal(res.url);
      setOauthStatus('awaiting');
    }
    catch (e) {
      setOauthStatus('error');
      setOauthError(e instanceof Error ? e.message : String(e));
    }
  };

  const importGhCli = async () => {
    if (ghState === 'importing') return;
    setGhState('importing');
    setGhError(null);
    try {
      await window.bridge.importGitHubCliToken();
      setGhState('idle');
      onSaved();
    }
    catch (e) {
      setGhState('error');
      setGhError(e instanceof Error ? e.message : String(e));
    }
  };

  const submitPat = async (e: React.FormEvent) => {
    e.preventDefault();
    if (patState === 'saving') return;
    const trimmed = token.trim();
    if (!trimmed) {
      setPatError('Paste a personal access token to continue.');
      setPatState('error');
      return;
    }
    setPatState('saving');
    setPatError(null);
    try {
      const ok = await window.bridge.savePat(trimmed);
      if (!ok) {
        setPatError('Backend rejected the token. Check the scopes and try again.');
        setPatState('error');
        return;
      }
      onSaved();
    }
    catch (err) {
      setPatError(err instanceof Error ? err.message : String(err));
      setPatState('error');
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

        {oauthConfigured && (
          <div className="onboarding__oauth">
            <button
              type="button"
              className="onboarding__cta"
              onClick={() => { void startOauth(); }}
              disabled={oauthStatus === 'launching' || oauthStatus === 'awaiting'}
            >
              {oauthStatus === 'launching' && 'Opening browser…'}
              {oauthStatus === 'awaiting' && 'Waiting for GitHub…'}
              {(oauthStatus === 'idle' || oauthStatus === 'error') && 'Sign in with GitHub'}
            </button>
            {oauthStatus === 'awaiting' && (
              <p className="onboarding__hint">
                A new tab opened in your browser — finish the consent flow there.
              </p>
            )}
            {oauthStatus === 'error' && oauthError && (
              <div className="onboarding__error" role="alert">{oauthError}</div>
            )}
            {!showPat && (
              <button
                type="button"
                className="onboarding__altlink"
                onClick={() => setShowPat(true)}
              >
                Use a personal access token instead
              </button>
            )}
          </div>
        )}

        {ghAvailable === true && (
          <div className="onboarding__oauth">
            <button
              type="button"
              className="onboarding__cta onboarding__cta--secondary"
              onClick={() => { void importGhCli(); }}
              disabled={ghState === 'importing'}
            >
              {ghState === 'importing' ? 'Reading gh credentials…' : 'Use my GitHub CLI login'}
            </button>
            <p className="onboarding__hint">
              Reuses the token <code>gh</code> already holds — handy when your org
              blocks personal access tokens but allows the GitHub CLI.
            </p>
            {ghState === 'error' && ghError && (
              <>
                <div className="onboarding__error" role="alert">{ghError}</div>
                {/* gh's own message says to run this, but it can't be run for
                    them: `gh auth login` is interactive and needs a terminal. */}
                <CommandToRun command="gh auth login" />
                <p className="onboarding__hint">
                  Run it in a terminal, then use the button above again.
                </p>
              </>
            )}
          </div>
        )}

        {ghAvailable === false && (
          <div className="onboarding__oauth">
            <p className="onboarding__hint">
              ByteQuay can reuse a GitHub CLI login instead of a token — install it
              with <code>brew install gh</code>, run <code>gh auth login</code>, then
              reopen this screen.
            </p>
            <CommandToRun command="brew install gh && gh auth login" />
            <p className="onboarding__hint">
              Prefer a token? Use a <strong>classic</strong> one below. Fine-grained
              tokens can't fork or open issues on repositories you don't own, which
              blocks connecting most repos.
            </p>
          </div>
        )}

        {showPat && (
          <form className="onboarding__form" onSubmit={(e) => { void submitPat(e); }}>
            <label className="onboarding__label" htmlFor="onboarding-pat">
              GitHub personal access token
            </label>
            <input
              id="onboarding-pat"
              type="password"
              className="onboarding__input"
              placeholder="ghp_…"
              value={token}
              onChange={(e) => { setToken(e.target.value); if (patState === 'error') setPatState('idle'); }}
              autoFocus={!oauthConfigured}
              autoComplete="off"
              spellCheck={false}
            />
            <p className="onboarding__hint">
              Required scopes (classic PAT): <code>repo</code>, <code>read:user</code>.
              Stored encrypted in your macOS Keychain.
            </p>
            {token.trim().startsWith(FINE_GRAINED_PREFIX) && (
              <p className="onboarding__hint onboarding__hint--warn">
                That's a fine-grained token. It can only reach the repositories it was
                issued for — connecting or filing issues on anyone else's repository
                will fail, even for public ones. A classic token with <code>repo</code>,
                or a GitHub CLI login, avoids that.
              </p>
            )}
            {patState === 'error' && patError && (
              <div className="onboarding__error" role="alert">{patError}</div>
            )}
            <button
              type="submit"
              className="onboarding__cta onboarding__cta--secondary"
              disabled={patState === 'saving' || token.trim().length === 0}
            >
              {patState === 'saving' ? 'Connecting…' : 'Connect with token →'}
            </button>
          </form>
        )}
      </main>
    </div>
  );
}

/** A shell command the user has to run themselves, with one-click copy. */
function CommandToRun({ command }: { command: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    await navigator.clipboard.writeText(command);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  };
  return (
    <div className="onboarding__cmd">
      <code>{command}</code>
      <button type="button" onClick={() => { void copy(); }}>
        {copied ? 'Copied' : 'Copy'}
      </button>
    </div>
  );
}
