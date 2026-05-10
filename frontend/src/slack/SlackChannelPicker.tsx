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
import { useEffect, useMemo, useState } from 'react';
import type { SlackChannelRowDto } from '../types';

type Mode = 'first-run' | 'management';

type Props = {
  /** First-run mode pre-toggles smart-default rows + shows Skip/Continue;
   *  management mode shows current selections + Cancel/Save. */
  mode: Mode;
  /** Called when the user saves; the response carries the refreshed
   *  picker payload (smart-default flags drop after the first save). */
  onSaved: (rows: SlackChannelRowDto[]) => void;
  /** First-run only — caller routes to the inbox view. */
  onSkip?: () => void;
  /** Management only — caller routes back to the inbox view. */
  onCancel?: () => void;
};

/**
 * Renders the channel-selection screen sketched in
 * docs/mockups/design/slack/channel-selection.png. One panel covers
 * both the first-run and ongoing-management cases — the only
 * difference is the footer (Skip/Continue vs Cancel/Save) and the
 * "SMART DEFAULT" badge, which only appears on first-run rows.
 */
function SlackChannelPicker({ mode, onSaved, onSkip, onCancel }: Props) {
  const [rows, setRows] = useState<SlackChannelRowDto[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  // Selected ids: seeded from isFollowed (management) or isSmartDefault (first-run)
  // once rows arrive. Stored in state so toggles don't have to mutate the
  // server-supplied payload.
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [filter, setFilter] = useState('');

  useEffect(() => {
    let cancelled = false;
    void window.bridge.listSlackChannels()
      .then(loaded => {
        if (cancelled) return;
        setRows(loaded);
        setSelected(new Set(loaded
          .filter(r => mode === 'first-run' ? r.isSmartDefault : r.isFollowed)
          .map(r => r.channel.id)));
      })
      .catch(e => {
        if (!cancelled) setLoadError(e instanceof Error ? e.message : String(e));
      });
    return () => { cancelled = true; };
  }, [mode]);

  const toggle = (id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const visibleRows = useMemo(() => {
    if (rows == null) return null;
    const q = filter.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter(r => r.channel.name.toLowerCase().includes(q));
  }, [rows, filter]);

  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const updated = await window.bridge.replaceFollowedSlackChannels(Array.from(selected));
      onSaved(updated);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  if (loadError) {
    return <div className="slack-picker__error">Couldn't load channels: {loadError}</div>;
  }
  if (rows == null) {
    return <div className="slack-picker__loading">Loading channels…</div>;
  }

  return (
    <div className="slack-picker">
      <header className="slack-picker__head">
        {mode === 'first-run' && <div className="slack-picker__step">STEP 2 OF 2</div>}
        <h1 className="slack-picker__title">Pick channels to follow</h1>
        <p className="slack-picker__desc">
          Followed channels show their full feed in your Slack tab. ByteQuay
          {mode === 'first-run' ? ' has pre-toggled the ' : ' lets you adjust the set '}
          {mode === 'first-run' && (
            <strong>3 most active channels</strong>
          )}
          {mode === 'first-run' ? " you're in — adjust as you like, or skip and decide later." : 'anytime.'}
        </p>
      </header>

      <div className="slack-picker__filter-row">
        <input
          type="text"
          className="slack-picker__filter"
          placeholder="Filter channels…"
          value={filter}
          onChange={e => setFilter(e.target.value)}
        />
      </div>

      <div className="slack-picker__list">
        {visibleRows && visibleRows.length === 0 && (
          <div className="slack-picker__empty">No channels match.</div>
        )}
        {visibleRows && visibleRows.map(r => (
          <ChannelRow
            key={r.channel.id}
            row={r}
            selected={selected.has(r.channel.id)}
            onToggle={() => toggle(r.channel.id)}
          />
        ))}
      </div>

      <footer className="slack-picker__footer">
        <div className="slack-picker__count">
          <strong>{selected.size}</strong> of {rows.length} channels selected
        </div>
        {saveError && <div className="slack-picker__error slack-picker__error--inline">{saveError}</div>}
        <div className="slack-picker__actions">
          {mode === 'first-run' ? (
            <button
              type="button"
              className="slack-picker__btn slack-picker__btn--ghost"
              onClick={onSkip}
              disabled={saving}
            >
              Skip for now
            </button>
          ) : (
            <button
              type="button"
              className="slack-picker__btn slack-picker__btn--ghost"
              onClick={onCancel}
              disabled={saving}
            >
              Cancel
            </button>
          )}
          <button
            type="button"
            className="slack-picker__btn slack-picker__btn--primary"
            onClick={() => void save()}
            disabled={saving}
          >
            {saving ? 'Saving…' : mode === 'first-run' ? 'Continue' : 'Save'}
          </button>
        </div>
      </footer>
    </div>
  );
}

function ChannelRow({
  row,
  selected,
  onToggle,
}: {
  row: SlackChannelRowDto;
  selected: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      className={`slack-picker__row${selected ? ' slack-picker__row--selected' : ''}`}
      onClick={onToggle}
    >
      <span className="slack-picker__row-glyph" aria-hidden="true">
        {row.channel.isPrivate ? '🔒' : '#'}
      </span>
      <span className="slack-picker__row-meta">
        <span className="slack-picker__row-name">{row.channel.name}</span>
        <span className="slack-picker__row-sub">
          {row.channel.latestActivityAt && (
            <>last activity {formatRelative(row.channel.latestActivityAt)}</>
          )}
          {row.channel.memberCount != null && (
            <> · {row.channel.memberCount.toLocaleString()} members</>
          )}
        </span>
      </span>
      {row.isSmartDefault && <span className="slack-picker__badge">SMART DEFAULT</span>}
      <span className={`slack-picker__toggle${selected ? ' slack-picker__toggle--on' : ''}`} aria-hidden="true">
        <span className="slack-picker__toggle-knob" />
      </span>
    </button>
  );
}

function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

export default SlackChannelPicker;
