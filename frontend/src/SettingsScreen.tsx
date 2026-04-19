import React, { useEffect, useState } from 'react';
import CredentialsTab from './CredentialsTab';
import ReviewSkillsTab from './ReviewSkillsTab';
import { getTtlSeconds, setTtlSeconds } from './detailCache';
import { THEMES, applyTheme, loadTheme, type ThemeId } from './themes';

const THEME_DOT_COLORS: Record<ThemeId, React.CSSProperties> = {
  'warm': { background: '#c5a85a' },
  'github-light': { background: '#0969da' },
  'atom-one-dark': { background: '#61afef' },
};

type Tab = 'general' | 'credentials' | 'review-skills';

type Props = {
  onSaved: () => void;
  onClearPat?: () => void;
  /** If true, render just the Credentials tab (no tabs, no "General" content). Used for first-run. */
  firstRun?: boolean;
};

function GeneralTab() {
  const [ttl, setTtl] = useState<number>(getTtlSeconds());
  const [ttlError, setTtlError] = useState<string | null>(null);
  const [theme, setTheme] = useState<ThemeId>(loadTheme());
  const [syncInterval, setSyncInterval] = useState<number>(60);
  const [syncIntervalError, setSyncIntervalError] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState<string | null>(null);

  useEffect(() => {
    window.bridge.getSyncSettings()
      .then(s => setSyncInterval(s.intervalSeconds))
      .catch(() => { /* non-fatal */ });
  }, []);

  const handleTtlChange = (raw: string) => {
    const n = parseInt(raw, 10);
    setTtl(isNaN(n) ? 0 : n);
    if (!Number.isFinite(n) || n < 5 || n > 3600) {
      setTtlError('Must be between 5 and 3600 seconds.');
    } else {
      setTtlError(null);
      setTtlSeconds(n);
    }
  };

  const handleSyncIntervalChange = async (raw: string) => {
    const n = parseInt(raw, 10);
    setSyncInterval(isNaN(n) ? 0 : n);
    if (!Number.isFinite(n) || n < 10 || n > 3600) {
      setSyncIntervalError('Must be between 10 and 3600 seconds.');
      return;
    }
    setSyncIntervalError(null);
    try {
      await window.bridge.setSyncSettings({ intervalSeconds: n });
    } catch (e) {
      setSyncIntervalError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleTriggerSync = async () => {
    setSyncing(true);
    setSyncMessage(null);
    try {
      await window.bridge.triggerSync();
      setSyncMessage('Sync requested — data will refresh within 10 seconds.');
    } catch (e) {
      setSyncMessage(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncing(false);
    }
  };

  return (
    <>
      <h3>Background sync</h3>
      <p className="section-copy">
        ByteQuay polls GitHub in the background and caches your PR list locally.
        Adjust how often it syncs, or trigger an immediate refresh.
      </p>
      <div className="settings-field">
        <label className="settings-label" htmlFor="sync-interval">Sync interval (seconds)</label>
        <input
          id="sync-interval"
          className="settings-input-number"
          type="number"
          min={10}
          max={3600}
          value={syncInterval}
          onChange={(e) => handleSyncIntervalChange(e.target.value)}
        />
      </div>
      {syncIntervalError && <p className="error-text">{syncIntervalError}</p>}
      <div className="settings-actions">
        <button className="button button--secondary" onClick={handleTriggerSync} disabled={syncing}>
          {syncing ? 'Requesting…' : 'Sync now'}
        </button>
      </div>
      {syncMessage && <p className="section-copy">{syncMessage}</p>}

      <div className="settings-divider" />

      <h3>Detail cache</h3>
      <p className="section-copy">
        Fetched PR details are kept in memory and reused until they expire.
        This avoids redundant network calls when switching between PRs.
      </p>
      <div className="settings-field">
        <label className="settings-label" htmlFor="cache-ttl">Cache TTL (seconds)</label>
        <input
          id="cache-ttl"
          className="settings-input-number"
          type="number"
          min={5}
          max={3600}
          value={ttl}
          onChange={(e) => handleTtlChange(e.target.value)}
        />
      </div>
      {ttlError && <p className="error-text">{ttlError}</p>}

      <div className="settings-divider" />

      <h3>Appearance</h3>
      <p className="section-copy">Choose a color theme for the app.</p>
      <div className="theme-picker">
        {THEMES.map(t => (
          <button
            key={t.id}
            type="button"
            className={`theme-swatch${theme === t.id ? ' theme-swatch--active' : ''}`}
            onClick={() => { setTheme(t.id); applyTheme(t.id); }}
          >
            <span className="theme-swatch__dot" style={THEME_DOT_COLORS[t.id]} />
            {t.label}
          </button>
        ))}
      </div>
    </>
  );
}

function SettingsScreen({ onSaved, onClearPat, firstRun = false }: Props) {
  const [tab, setTab] = useState<Tab>(firstRun ? 'credentials' : 'general');

  if (firstRun) {
    return (
      <section className="panel">
        <p className="eyebrow">First-time setup</p>
        <h2 className="settings-title">Add your GitHub PAT</h2>
        <p className="section-copy">
          Add a GitHub Personal Access Token to get started. Required scopes (classic PAT):{' '}
          <code>repo</code>, <code>read:user</code>.
        </p>
        <CredentialsTab onFirstCredentialAdded={onSaved} />
      </section>
    );
  }

  return (
    <section className="panel">
      <p className="eyebrow">Settings</p>
      <div className="settings-tabs">
        <button
          className={`settings-tab${tab === 'general' ? ' settings-tab--active' : ''}`}
          onClick={() => setTab('general')}
          type="button"
        >
          General
        </button>
        <button
          className={`settings-tab${tab === 'credentials' ? ' settings-tab--active' : ''}`}
          onClick={() => setTab('credentials')}
          type="button"
        >
          Credentials
        </button>
        <button
          className={`settings-tab${tab === 'review-skills' ? ' settings-tab--active' : ''}`}
          onClick={() => setTab('review-skills')}
          type="button"
        >
          Review skills
        </button>
      </div>

      {tab === 'general' && <GeneralTab />}
      {tab === 'credentials' && <CredentialsTab />}
      {tab === 'review-skills' && <ReviewSkillsTab />}

      {onClearPat && tab === 'general' && (
        <>
          <div className="settings-divider" />
          <div className="settings-actions">
            <button className="button button--danger" onClick={onClearPat} type="button">
              Sign out (clear GitHub PAT)
            </button>
          </div>
        </>
      )}
    </section>
  );
}

export default SettingsScreen;
