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
import SettingCard from '../shared/SettingCard';
import WorkspaceMemoryProposalBanner from './WorkspaceMemoryProposalBanner';

/** v1 ships a single ambient workspace. When multi-workspace
 *  switching arrives, this constant becomes a useWorkspace() lookup. */
const DEFAULT_WORKSPACE_ID = 'ws-default';

/** Soft character target — matches the backend constant
 *  ({@code MEMORY_MD_TARGET_CHARS}). The editor renders past this
 *  but warns about budget overruns; the backend's hard cap is 32 000
 *  chars (PUT returns 413 past that). */
const TARGET_CHARS = 8_000;

function WorkspaceMemoryPage() {
  const [memory, setMemory] = useState('');
  const [original, setOriginal] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [distilling, setDistilling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState<string | null>(null);
  /** Bumped to nudge the banner into an immediate refresh after a
   *  manual Distill click — without it the user would wait up to 15s
   *  for the next poll to show the new proposal. */
  const [proposalRefreshKey, setProposalRefreshKey] = useState(0);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const { memoryMd } = await window.bridge.getWorkspaceMemory(DEFAULT_WORKSPACE_ID);
      setMemory(memoryMd);
      setOriginal(memoryMd);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const dirty = memory !== original;
  const overBudget = memory.length > TARGET_CHARS;

  const save = async () => {
    setSaving(true);
    setError(null);
    setStatusMsg(null);
    try {
      const updated = await window.bridge.setWorkspaceMemory(DEFAULT_WORKSPACE_ID, memory);
      setMemory(updated.memoryMd);
      setOriginal(updated.memoryMd);
      setStatusMsg('Saved.');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const discard = () => {
    setMemory(original);
    setStatusMsg(null);
    setError(null);
  };

  const distill = async () => {
    setDistilling(true);
    setError(null);
    setStatusMsg(null);
    try {
      const proposal = await window.bridge.distillWorkspaceMemory(DEFAULT_WORKSPACE_ID);
      if (proposal === null) {
        setStatusMsg('No proposal queued — there were no Thread Overalls to fold '
            + 'in, or the distilled body matched the current memory.');
      } else {
        setStatusMsg(`Proposed ${proposal.proposedMd.length.toLocaleString()} chars `
            + '— review the banner above and apply or discard.');
        setProposalRefreshKey(k => k + 1);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setDistilling(false);
    }
  };

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Workspace memory</h2>
          <div className="settings-shell-page__subtitle">
            The persistent project brain. Loaded into every thread's context —
            keep it tight. Distillation folds recent thread Overalls into this
            blob every 30 minutes; you can also write or rewrite it by hand.
          </div>
        </div>
      </div>

      <WorkspaceMemoryProposalBanner
        workspaceId={DEFAULT_WORKSPACE_ID}
        refreshKey={proposalRefreshKey}
        onApplied={() => { void load(); }}
      />

      <SettingCard
        title="WORKSPACE.md"
        hint={
          <>
            Target ~{TARGET_CHARS.toLocaleString()} characters. The editor stays
            usable above that, but the budget bar turns red so distillation has
            something obvious to work back down to.
          </>
        }
      >
        {loading ? (
          <div className="settings-shell-page__subtitle" style={{ padding: '20px 0' }}>
            Loading…
          </div>
        ) : (
          <>
            <textarea
              value={memory}
              onChange={e => { setMemory(e.target.value); }}
              spellCheck={false}
              style={textareaStyle}
              placeholder="# Architecture&#10;&#10;# Active work&#10;&#10;# Decisions&#10;&#10;# Blockers"
            />
            <div style={budgetRowStyle}>
              <BudgetBar used={memory.length} target={TARGET_CHARS} />
              <span style={budgetTextStyle}>
                {memory.length.toLocaleString()} / {TARGET_CHARS.toLocaleString()} chars
                {overBudget && <span style={budgetWarnStyle}> · over budget</span>}
              </span>
            </div>
            <div style={actionsRowStyle}>
              <button
                type="button"
                className="button button--primary"
                onClick={() => { void save(); }}
                disabled={!dirty || saving}
              >
                {saving ? 'Saving…' : 'Save'}
              </button>
              <button
                type="button"
                className="button"
                onClick={discard}
                disabled={!dirty || saving}
              >
                Discard changes
              </button>
              <div style={{ flex: 1 }} />
              <button
                type="button"
                className="button"
                onClick={() => { void distill(); }}
                disabled={distilling || saving}
                title="Ask Haiku to fold recent Thread Overalls into this blob"
              >
                {distilling ? 'Distilling…' : 'Distill from threads'}
              </button>
            </div>
            {(statusMsg !== null || error !== null) && (
              <div style={statusRowStyle}>
                {error !== null && <span style={errorStyle}>{error}</span>}
                {statusMsg !== null && error === null && (
                  <span style={statusOkStyle}>{statusMsg}</span>
                )}
              </div>
            )}
          </>
        )}
      </SettingCard>
    </>
  );
}

function BudgetBar({ used, target }: { used: number; target: number }) {
  const pct = Math.min(100, Math.max(0, Math.round((used / target) * 100)));
  const over = used > target;
  return (
    <div style={budgetBarStyle}>
      <div
        style={{
          ...budgetBarFillStyle,
          width: `${pct}%`,
          background: over ? '#dc2626' : '#16a34a',
        }}
      />
    </div>
  );
}

const textareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 320,
  padding: '10px 12px',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  lineHeight: 1.55,
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: 'var(--bg-elevated)',
  color: 'var(--text-1)',
  resize: 'vertical',
};

const budgetRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '8px 0 0',
};

const budgetBarStyle: React.CSSProperties = {
  flex: 1,
  height: 6,
  background: 'var(--bg-card, #f4f4f4)',
  borderRadius: 999,
  overflow: 'hidden',
};

const budgetBarFillStyle: React.CSSProperties = {
  height: '100%',
  transition: 'width 140ms ease',
};

const budgetTextStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  whiteSpace: 'nowrap',
};

const budgetWarnStyle: React.CSSProperties = {
  color: '#b91c1c',
  fontWeight: 600,
};

const actionsRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '12px 0 0',
};

const statusRowStyle: React.CSSProperties = {
  padding: '8px 0 0',
  fontSize: 12,
};

const errorStyle: React.CSSProperties = {
  color: '#b91c1c',
  fontStyle: 'italic',
};

const statusOkStyle: React.CSSProperties = {
  color: '#16a34a',
};

export default WorkspaceMemoryPage;
