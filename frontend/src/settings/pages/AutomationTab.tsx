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
  const [persona, setPersona] = useState('');
  const [loadedPersona, setLoadedPersona] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [savingPersona, setSavingPersona] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [settings, personaPayload] = await Promise.all([
        window.bridge.getScheduledReviewSettings(),
        window.bridge.getReviewPersona(),
      ]);
      setEnabled(settings.enabled);
      setPersona(personaPayload.persona);
      setLoadedPersona(personaPayload.persona);
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

  const savePersona = async () => {
    if (savingPersona || persona === loadedPersona) return;
    setSavingPersona(true);
    setError(null);
    try {
      const updated = await window.bridge.setReviewPersona(persona);
      setPersona(updated.persona);
      setLoadedPersona(updated.persona);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSavingPersona(false);
    }
  };

  return (
    <>
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
          </>
        )}
      </SettingCard>

      <SettingCard
        title="Reviewer persona"
        hint={
          <>
            A free-form nudge prepended to every panel reviewer's
            prompt. Use this to bias the panel toward a focus area —
            e.g. "lean security-first; flag any unbounded user input,
            implicit auth assumptions, or SQL composition", or
            "prefer terse comments and prioritise correctness over
            style". Empty disables the nudge. Composes with any
            repo-level review skill content; the persona lands first.
          </>
        }
      >
        {loading ? (
          <div style={loadingStyle}>Loading…</div>
        ) : (
          <>
            <textarea
              value={persona}
              onChange={e => setPersona(e.target.value)}
              placeholder="e.g. Focus on security; flag any input that flows into a shell or SQL string."
              rows={5}
              style={textareaStyle}
              disabled={savingPersona}
            />
            <div style={personaActionsStyle}>
              <span style={mutedHintStyle}>
                {persona === loadedPersona
                  ? 'Saved.'
                  : `Unsaved change · ${persona.length} chars`}
              </span>
              <button
                type="button"
                onClick={() => { void savePersona(); }}
                disabled={savingPersona || persona === loadedPersona}
                style={saveBtnStyle(persona !== loadedPersona && !savingPersona)}
              >
                {savingPersona ? 'Saving…' : 'Save persona'}
              </button>
            </div>
          </>
        )}
      </SettingCard>

      {error !== null && (
        <div style={errorStyle} role="alert">{error}</div>
      )}
    </>
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

const textareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 100,
  padding: 10,
  fontSize: 13,
  lineHeight: 1.5,
  border: '1px solid var(--border-1)',
  borderRadius: 6,
  fontFamily: 'inherit',
  resize: 'vertical',
  boxSizing: 'border-box',
};

const personaActionsStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  marginTop: 8,
};

const mutedHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

function saveBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '6px 14px',
    fontSize: 12,
    fontWeight: 600,
    border: 'none',
    borderRadius: 6,
    background: active ? '#7c3aed' : 'rgba(124, 58, 237, 0.35)',
    color: '#fff',
    cursor: active ? 'pointer' : 'not-allowed',
  };
}

export default AutomationTab;
