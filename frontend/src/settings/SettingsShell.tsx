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

export default function SettingsShell() {
  const [configured, setConfigured] = useState(false);
  const [pat, setPat] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [version, setVersion] = useState('');

  useEffect(() => {
    void window.bridge.hasPat().then(setConfigured);
    void window.bridge.getAppVersion().then(value => setVersion(value.version));
  }, []);

  const save = async () => {
    if (!pat.trim()) return;
    setSaving(true);
    setMessage(null);
    try {
      await window.bridge.savePat(pat.trim());
      setConfigured(true);
      setPat('');
      setMessage('GitHub credential saved.');
    }
    catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  };

  return (
    <main className="settings-clean">
      <header>
        <h1>Settings</h1>
        <p>Only settings required by the Home and pull-request surfaces are retained.</p>
      </header>
      <section>
        <h2>GitHub account</h2>
        <p className={configured ? 'settings-clean__ok' : ''}>
          {configured ? 'Connected' : 'Not connected'}
        </p>
        <label htmlFor="github-pat">Replace personal access token</label>
        <input
          id="github-pat"
          type="password"
          value={pat}
          placeholder="github_pat_…"
          onChange={event => setPat(event.target.value)}
        />
        <div className="settings-clean__actions">
          <button type="button" disabled={saving || !pat.trim()} onClick={() => { void save(); }}>
            {saving ? 'Saving…' : 'Save token'}
          </button>
          <button type="button" onClick={() => { void window.bridge.syncPrs().then(() => setMessage('Sync requested.')); }}>
            Sync pull requests now
          </button>
        </div>
        {message && <p>{message}</p>}
      </section>
      <footer>ByteQuay {version}</footer>
    </main>
  );
}
