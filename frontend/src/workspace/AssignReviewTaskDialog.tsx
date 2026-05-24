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
import type { PullRequestDto, ReviewRosterEntryDto } from '../types';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from './dialogStyles';

type Props = {
  onClose: () => void;
  /** Fires after the review pass kicks off — parent owns navigation
   *  to the freshly-created review thread. */
  onStarted: (threadId: string) => void;
};

/**
 * Modal for spinning up a multi-agent review panel on a PR awaiting
 * the user's review. Pick a PR, pick the panel members (rendered as
 * chips), set debate-round + cost caps, and toggle "Independent
 * first (no anchoring)". The dialog calls bridge.startReview and
 * hands the new thread id to the parent.
 */
const ROUND_MIN = 0;
const ROUND_MAX = 5;
const COST_MIN_CENTS = 10; // 0.10 USD
const COST_MAX_CENTS = 200; // 2.00 USD
const COST_STEP_CENTS = 5;

function AssignReviewTaskDialog({ onClose, onStarted }: Props) {
  const [prs, setPrs] = useState<PullRequestDto[] | null>(null);
  const [roster, setRoster] = useState<ReviewRosterEntryDto[] | null>(null);
  const [selectedPr, setSelectedPr] = useState<PullRequestDto | null>(null);
  const [search, setSearch] = useState('');
  const [selectedProviders, setSelectedProviders] = useState<Set<string>>(new Set());
  // Debate rounds + cost budget caps. Defaults mirror ReviewPassService.StartOptions.DEFAULT.
  const [rounds, setRounds] = useState<number>(3);
  // Stored in milli-USD to match the backend, but rendered as $X.XX
  // and adjusted in 5-cent steps to feel like a money slider.
  const [costCapMilli, setCostCapMilli] = useState<number>(500);
  const [independentFirst, setIndependentFirst] = useState<boolean>(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const [prList, rosterList] = await Promise.all([
          window.bridge.fetchPrs(),
          window.bridge.listReviewRoster(),
        ]);
        if (cancelled) return;
        const awaiting = prList
            .filter(p => p.origin === 'REVIEW_REQUESTED')
            .filter(p => p.state !== 'closed' && p.state !== 'merged')
            .filter(p => p.snoozedUntil === null);
        setPrs(awaiting);
        setRoster(rosterList);
        // Default-seat every configured reviewer (mirrors the
        // registry's "all-configured" fallback so the chips show
        // the user what would happen if they pressed Start unchanged).
        const configured = new Set(rosterList.filter(r => r.configured).map(r => r.providerId));
        setSelectedProviders(configured);
      }
      catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err));
        }
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const filteredPrs = useMemo(() => {
    if (prs === null) return [];
    const q = search.trim().toLowerCase();
    if (q === '') return prs;
    const numMatch = q.match(/^#?(\d+)$/);
    return prs.filter(p => {
      if (numMatch !== null && String(p.number) === numMatch[1]) return true;
      const hay = `${p.title} ${p.author ?? ''} ${p.repo} #${p.number}`.toLowerCase();
      return hay.includes(q);
    });
  }, [prs, search]);

  const toggleProvider = (id: string, configured: boolean) => {
    if (!configured) return;
    setSelectedProviders(prev => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      }
      else {
        next.add(id);
      }
      return next;
    });
  };

  const selectedCount = selectedProviders.size;
  const submitDisabled = submitting
      || selectedPr === null
      || selectedCount === 0;

  const onSubmit = async (e?: React.FormEvent) => {
    if (e !== undefined) e.preventDefault();
    if (selectedPr === null || submitDisabled) return;
    setSubmitting(true);
    setError(null);
    try {
      const result = await window.bridge.startReview(
          selectedPr.repo,
          selectedPr.number,
          {
            panelProviderIds: Array.from(selectedProviders),
            roundCap: rounds,
            costCapMilli,
            independentFirst,
          });
      onStarted(result.pass.threadId);
    }
    catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setSubmitting(false);
    }
  };

  return (
    <div
      style={WS_DIALOG_OVERLAY}
      onClick={onClose}
      role="presentation"
    >
      <div
        style={dialogPanelStyle}
        role="dialog"
        aria-modal="true"
        aria-label="Assign review task"
        onClick={(e) => e.stopPropagation()}
      >
        <header style={dialogStyles.header}>
          <h2 style={dialogStyles.title}>
            <span style={iconStyle} aria-hidden>⎈</span>
            Assign review task
          </h2>
          <button type="button" onClick={onClose} style={dialogStyles.closeBtn} aria-label="Close">
            ✕
          </button>
        </header>
        <p style={subtitleStyle}>
          Spin up a multi-agent review panel on a PR awaiting your review
        </p>

        {error !== null && (
          <div style={errorStyle} role="alert">{error}</div>
        )}

        <form onSubmit={onSubmit}>
          <div style={sectionHeadStyle}>
            <span style={sectionLabelStyle}>Pull request</span>
            <span style={sectionMetaStyle}>
              awaiting my review · {prs === null ? '…' : prs.length}
            </span>
          </div>
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search PRs by title, author, or #number…"
            style={searchInputStyle}
          />
          <div style={prListStyle}>
            {prs === null ? (
              <div style={mutedRowStyle}>Loading PRs…</div>
            ) : filteredPrs.length === 0 ? (
              <div style={mutedRowStyle}>
                {prs.length === 0
                  ? 'No PRs awaiting your review right now.'
                  : 'No PRs match this search.'}
              </div>
            ) : (
              filteredPrs.map(pr => (
                <PrRow
                  key={pr.id}
                  pr={pr}
                  selected={selectedPr?.id === pr.id}
                  onSelect={() => setSelectedPr(pr)}
                />
              ))
            )}
          </div>

          <div style={sectionHeadStyle}>
            <span style={sectionLabelStyle}>Panel</span>
            <span style={sectionMetaStyle}>
              from credentials tagged "review"
            </span>
          </div>
          <div style={rosterGridStyle}>
            {roster === null ? (
              <div style={mutedRowStyle}>Loading roster…</div>
            ) : roster.length === 0 ? (
              <div style={mutedRowStyle}>
                No AI providers yet — add one in Settings → AI review.
              </div>
            ) : (
              roster.map(entry => (
                <RosterChip
                  key={entry.providerId}
                  entry={entry}
                  checked={selectedProviders.has(entry.providerId)}
                  onToggle={() => toggleProvider(entry.providerId, entry.configured)}
                />
              ))
            )}
          </div>

          <div style={sectionHeadStyle}>
            <span style={sectionLabelStyle}>Limits</span>
          </div>
          <div style={limitsGridStyle}>
            <LimitSlider
              label="Debate rounds"
              value={rounds}
              min={ROUND_MIN}
              max={ROUND_MAX}
              step={1}
              displayValue={String(rounds)}
              caption="stops early on convergence"
              onChange={setRounds}
            />
            <LimitSlider
              label="Cost budget"
              value={costCapMilli / 10}
              min={COST_MIN_CENTS}
              max={COST_MAX_CENTS}
              step={COST_STEP_CENTS}
              displayValue={formatUsd(costCapMilli)}
              caption="halts + summarises when hit"
              onChange={(centsValue) => setCostCapMilli(Math.round(centsValue) * 10)}
            />
          </div>

          <label style={toggleRowStyle}>
            <button
              type="button"
              role="switch"
              aria-checked={independentFirst}
              onClick={() => setIndependentFirst(v => !v)}
              style={togglePillStyle(independentFirst)}
            >
              <span style={toggleKnobStyle(independentFirst)} />
            </button>
            <div>
              <div style={toggleLabelStyle}>Independent first (no anchoring)</div>
              <div style={toggleHintStyle}>
                reviewers don't see each other until cross-review
              </div>
            </div>
          </label>

          <footer style={dialogStyles.footer}>
            <span style={dialogStyles.footerNote}>
              Est. ~{formatUsd(estimateMilli(selectedCount, rounds))} ·{' '}
              {selectedCount === 0 ? 'no reviewers' : `${selectedCount} reviewer${selectedCount === 1 ? '' : 's'}`} ·{' '}
              read-only · holds no worktree lease
            </span>
            <div style={dialogStyles.footerButtons}>
              <button type="button" onClick={onClose} style={dialogStyles.secondaryBtn}>
                Cancel
              </button>
              <button
                type="submit"
                disabled={submitDisabled}
                style={submitDisabled ? dialogStyles.primaryBtnDisabled : dialogStyles.primaryBtn}
              >
                {submitting ? 'Starting…' : '⌘ Start review'}
              </button>
            </div>
          </footer>
        </form>
      </div>
    </div>
  );
}

function PrRow({
  pr, selected, onSelect,
}: {
  pr: PullRequestDto;
  selected: boolean;
  onSelect: () => void;
}) {
  const ci = pr.ciStatus === 'FAILING' ? 'CI ⨯' : 'CI ✓';
  const ciStyle = pr.ciStatus === 'FAILING' ? prRowCiBadStyle : prRowCiGoodStyle;
  return (
    <button
      type="button"
      onClick={onSelect}
      style={prRowStyle(selected)}
      aria-pressed={selected}
    >
      <span style={prRowRadioStyle(selected)}>
        {selected && <span style={prRowRadioDotStyle} />}
      </span>
      <span style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
        <span style={prRowTitleStyle}>
          <span style={prRowNumStyle}>#{pr.number}</span> {pr.title}
        </span>
        <span style={prRowMetaStyle}>
          {pr.author !== null && <>{pr.author}</>}
          <span style={prRowDiffPosStyle}>{` +${pr.additions}`}</span>
          <span style={prRowDiffNegStyle}>{` /-${pr.deletions}`}</span>
          {pr.commentCount > 0 && (
            <>{' · '}{pr.commentCount} {pr.commentCount === 1 ? 'comment' : 'comments'}</>
          )}
        </span>
      </span>
      <span style={ciStyle}>{ci}</span>
    </button>
  );
}

function RosterChip({
  entry, checked, onToggle,
}: {
  entry: ReviewRosterEntryDto;
  checked: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      disabled={!entry.configured}
      style={rosterChipStyle(checked, entry.configured)}
      aria-pressed={checked}
      title={entry.configured ? undefined : 'Add an API key in Settings → AI review'}
    >
      <span style={rosterChipAvatarStyle(entry)} aria-hidden>
        {entry.displayName.charAt(0).toUpperCase()}
      </span>
      <span style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
        <span style={rosterChipNameStyle}>{entry.displayName}</span>
        <span style={rosterChipMetaStyle}>
          {entry.configured ? entry.providerId : 'not configured'}
        </span>
      </span>
      <span style={rosterChipBoxStyle(checked, entry.configured)} aria-hidden>
        {checked && '✓'}
      </span>
    </button>
  );
}

function LimitSlider({
  label, value, min, max, step, displayValue, caption, onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  displayValue: string;
  caption: string;
  onChange: (next: number) => void;
}) {
  return (
    <div style={limitCellStyle}>
      <div style={limitTopRowStyle}>
        <span style={limitLabelStyle}>{label}</span>
        <span style={limitValueStyle}>{displayValue}</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        style={limitRangeStyle}
      />
      <div style={limitCaptionStyle}>{caption}</div>
    </div>
  );
}

function formatUsd(milli: number): string {
  return '$' + (milli / 1000).toFixed(2);
}

/** Very rough envelope used in the footer's "Est. ~" hint. ~$0.05 per
 *  reviewer per round, plus a small fixed kickoff cost — purely
 *  illustrative until the panel runtime records actual cost. */
function estimateMilli(reviewers: number, rounds: number): number {
  if (reviewers === 0) return 0;
  return Math.max(0, 50 + reviewers * 50 + reviewers * rounds * 40);
}

const dialogPanelStyle: React.CSSProperties = {
  ...WS_DIALOG_PANEL,
  width: 640,
};

const subtitleStyle: React.CSSProperties = {
  margin: '-6px 0 14px 0',
  fontSize: 12,
  color: 'var(--ws-text-3)',
};

const iconStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: 26,
  height: 26,
  borderRadius: 8,
  background: 'linear-gradient(135deg, #7c3aed, #6366f1)',
  color: '#fff',
  fontSize: 14,
  fontWeight: 700,
};

const errorStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 8,
  color: '#cf1322',
  fontSize: 12,
};

const sectionHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  marginTop: 16,
  marginBottom: 8,
};

const sectionLabelStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'var(--ws-text-3)',
};

const sectionMetaStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ws-text-4, #94a3b8)',
};

const searchInputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  fontSize: 12,
  border: '1px solid var(--ws-card-border)',
  borderRadius: 8,
  background: 'rgba(255, 255, 255, 0.9)',
  color: 'var(--ws-text-1)',
  boxSizing: 'border-box',
  outline: 'none',
};

const prListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  marginTop: 8,
  maxHeight: 220,
  overflowY: 'auto',
};

const mutedRowStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 12,
  color: 'var(--ws-text-3)',
  fontStyle: 'italic',
};

function prRowStyle(selected: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '10px 12px',
    border: selected
        ? '1.5px solid var(--ws-accent, #7c3aed)'
        : '1px solid var(--ws-card-border)',
    borderRadius: 10,
    background: selected
        ? 'linear-gradient(180deg, rgba(124,58,237,0.06), rgba(124,58,237,0.02))'
        : '#fff',
    cursor: 'pointer',
    transition: 'border-color 140ms ease, background 140ms ease',
    textAlign: 'left',
    width: '100%',
  };
}

function prRowRadioStyle(selected: boolean): React.CSSProperties {
  return {
    width: 16,
    height: 16,
    borderRadius: 999,
    border: selected
        ? '4px solid var(--ws-accent, #7c3aed)'
        : '1.5px solid rgba(0,0,0,0.20)',
    background: '#fff',
    flexShrink: 0,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
  };
}

const prRowRadioDotStyle: React.CSSProperties = {
  width: 4,
  height: 4,
  borderRadius: 999,
  background: '#fff',
};

const prRowTitleStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--ws-text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const prRowNumStyle: React.CSSProperties = {
  color: 'var(--ws-accent, #7c3aed)',
  fontVariantNumeric: 'tabular-nums',
};

const prRowMetaStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
};

const prRowDiffPosStyle: React.CSSProperties = {
  color: '#15803d',
  fontVariantNumeric: 'tabular-nums',
};

const prRowDiffNegStyle: React.CSSProperties = {
  color: '#b91c1c',
  fontVariantNumeric: 'tabular-nums',
};

const prRowCiGoodStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '2px 8px',
  fontSize: 10,
  fontWeight: 600,
  borderRadius: 999,
  background: 'rgba(22, 163, 74, 0.12)',
  color: '#15803d',
};

const prRowCiBadStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '2px 8px',
  fontSize: 10,
  fontWeight: 600,
  borderRadius: 999,
  background: 'rgba(220, 38, 38, 0.12)',
  color: '#b91c1c',
};

const rosterGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 8,
};

function rosterChipStyle(checked: boolean, enabled: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '8px 10px',
    border: checked
        ? '1.5px solid var(--ws-accent, #7c3aed)'
        : '1px solid var(--ws-card-border)',
    borderRadius: 10,
    background: checked
        ? 'linear-gradient(180deg, rgba(124,58,237,0.06), rgba(124,58,237,0.02))'
        : '#fff',
    color: enabled ? 'var(--ws-text-1)' : 'var(--ws-text-3)',
    cursor: enabled ? 'pointer' : 'not-allowed',
    opacity: enabled ? 1 : 0.55,
    transition: 'border-color 140ms ease, background 140ms ease',
  };
}

function rosterChipAvatarStyle(entry: ReviewRosterEntryDto): React.CSSProperties {
  const colors: Record<string, string> = {
    'openai': '#10b981',
    'anthropic': '#d97706',
    'google': '#ec4899',
  };
  let bg = '#7c3aed';
  for (const prefix of Object.keys(colors)) {
    if (entry.providerId.toLowerCase().startsWith(prefix)) {
      bg = colors[prefix];
      break;
    }
  }
  return {
    width: 26,
    height: 26,
    borderRadius: 8,
    background: bg,
    color: '#fff',
    fontSize: 13,
    fontWeight: 700,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
  };
}

const rosterChipNameStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 13,
  fontWeight: 600,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const rosterChipMetaStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 10,
  color: 'var(--ws-text-3)',
  marginTop: 1,
};

function rosterChipBoxStyle(checked: boolean, enabled: boolean): React.CSSProperties {
  return {
    flexShrink: 0,
    width: 18,
    height: 18,
    borderRadius: 5,
    border: checked
        ? '1.5px solid var(--ws-accent, #7c3aed)'
        : '1.5px solid rgba(0,0,0,0.15)',
    background: checked ? 'var(--ws-accent, #7c3aed)' : '#fff',
    color: '#fff',
    fontSize: 12,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    opacity: enabled ? 1 : 0.55,
  };
}

const limitsGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 14,
};

const limitCellStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const limitTopRowStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
};

const limitLabelStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--ws-text-1)',
};

const limitValueStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 700,
  color: 'var(--ws-accent, #7c3aed)',
  fontVariantNumeric: 'tabular-nums',
};

const limitRangeStyle: React.CSSProperties = {
  width: '100%',
  accentColor: 'var(--ws-accent, #7c3aed)',
};

const limitCaptionStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ws-text-3)',
};

const toggleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  marginTop: 14,
  padding: '10px 12px',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 10,
  background: '#fff',
};

function togglePillStyle(on: boolean): React.CSSProperties {
  return {
    width: 36,
    height: 20,
    borderRadius: 999,
    background: on ? 'var(--ws-accent, #7c3aed)' : 'rgba(0,0,0,0.18)',
    border: 'none',
    position: 'relative',
    cursor: 'pointer',
    transition: 'background 140ms ease',
    padding: 0,
    flexShrink: 0,
  };
}

function toggleKnobStyle(on: boolean): React.CSSProperties {
  return {
    position: 'absolute',
    top: 2,
    left: on ? 18 : 2,
    width: 16,
    height: 16,
    background: '#fff',
    borderRadius: 999,
    transition: 'left 140ms ease',
    boxShadow: '0 1px 2px rgba(0,0,0,0.18)',
  };
}

const toggleLabelStyle: React.CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--ws-text-1)',
};

const toggleHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
};

export default AssignReviewTaskDialog;
