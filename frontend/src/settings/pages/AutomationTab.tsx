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
import SettingCard from '../shared/SettingCard';

/** Toggle for the backend's ScheduledReviewService — when enabled,
 *  the scheduler walks PRs awaiting the user's review once per hour
 *  and runs a panel-review against each. Off by default per
 *  CLAUDE.md ("auto-fix that pushes commits is opt-in"). */
function AutomationTab() {
  const [enabled, setEnabled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const settings = await window.bridge.getScheduledReviewSettings();
      setEnabled(settings.enabled);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const toggle = async (next: boolean) => {
    if (saving) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await window.bridge.setScheduledReviewSettings(next);
      setEnabled(updated.enabled);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  };

  return (
    <SettingCard
      title="Scheduled review panels"
      hint={
        <>
          When on, the app walks the "Review requested" queue once an hour
          and spins up a panel review against each PR. Each pass runs
          headlessly through INDEPENDENT → DEBATE, parks at the
          arbitration ballot when the panel can't agree, and pings the
          notifications screen. Read-only — no worktree lease, no PR
          comments posted automatically.
        </>
      }
    >
      {loading ? (
        <div style={loadingStyle}>Loading…</div>
      ) : (
        <>
          <label style={rowStyle}>
            <input
              type="checkbox"
              checked={enabled}
              onChange={e => { void toggle(e.target.checked); }}
              disabled={saving}
            />
            <span style={labelStyle}>
              {enabled ? 'Enabled' : 'Disabled'}
            </span>
            {saving && <span style={savingStyle}>saving…</span>}
          </label>
          {error !== null && (
            <div style={errorStyle} role="alert">{error}</div>
          )}
        </>
      )}
    </SettingCard>
  );
}

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  fontSize: 13,
};

const labelStyle: React.CSSProperties = {
  color: 'var(--text-1)',
};

const savingStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontStyle: 'italic',
};

const loadingStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--text-3)',
};

const errorStyle: React.CSSProperties = {
  marginTop: 10,
  padding: 8,
  fontSize: 13,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 6,
  color: '#cf1322',
};

export default AutomationTab;
