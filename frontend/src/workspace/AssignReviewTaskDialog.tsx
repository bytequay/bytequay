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
import type {
  SkillDto, PullRequestDto, ReviewRosterEntryDto } from '../types';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from './dialogStyles';

type Props = {
  /** Workspace the dialog opened from — its first repo is the default
   *  a bare typed PR number resolves against. */
  workspaceId: string;
  onClose: () => void;
  /** Fires after the review pass kicks off — parent owns navigation
   *  to the freshly-created review thread. */
  onStarted: (threadId: string) => void;
  /** When opened against a specific PR (e.g. from the diff page), the PR
   *  is fixed: it's pre-selected and the search/picker is hidden in favour
   *  of a compact header. Omit for the workspace-home "pick a PR" flow. */
  initialPr?: PullRequestDto;
};

/** State of the on-demand PR lookup — what happens when the user types
 *  a PR reference (a number against the workspace's default repo, an
 *  owner/repo#number, or a pasted github.com URL) that isn't already in
 *  the awaiting-review list. */
type LookupState =
  | { status: 'idle' }
  | { status: 'loading'; ref: PrRef }
  | { status: 'found'; ref: PrRef; pr: PullRequestDto }
  | { status: 'notfound'; ref: PrRef }
  | { status: 'error'; ref: PrRef; message: string };

type PrRef = { repo: string; number: number };

/** A reviewer row in the panel builder: a model ({@code providerId})
 *  paired with a "role" — {@code 'none'} (raw model), {@code 'custom'}
 *  (a typed prompt in {@code customPrompt}), or {@code 'skill:<id>'} (a
 *  review-skill voice). {@code key} is a stable React id that also
 *  identifies the lead seat. */
type SeatDraft = {
  key: string;
  providerId: string;
  role: 'none' | 'custom' | string;
  customPrompt: string;
};

let seatKeyCounter = 0;
function newSeatKey(): string {
  seatKeyCounter += 1;
  return `seat-${seatKeyCounter}`;
}

/** CLI agents (claude-cli / codex-cli) can review but can't lead — the
 *  lead coordinates the panel via structured tools the CLI path can't run.
 *  Provider ids for CLI seats end in "-cli". */
function isCliProvider(providerId: string): boolean {
  return providerId.endsWith('-cli');
}

/** Parses the search box into a concrete repo + number when it looks
 *  like a PR reference. A bare number (or {@code #123}) resolves
 *  against {@code defaultRepo}; an {@code owner/repo#123} (or
 *  {@code .../123}) or a pasted github.com PR URL carry their own repo.
 *  Returns null when the text isn't a PR reference (plain search). */
function parsePrRef(input: string, defaultRepo: string | null): PrRef | null {
  const s = input.trim();
  if (s === '') return null;
  const urlMatch = s.match(/github\.com\/([^/\s]+\/[^/\s]+)\/pull\/(\d+)/i);
  if (urlMatch !== null) return { repo: urlMatch[1], number: Number(urlMatch[2]) };
  const refMatch = s.match(/^([\w.-]+\/[\w.-]+)(?:#|\/|\s+)(\d+)$/);
  if (refMatch !== null) return { repo: refMatch[1], number: Number(refMatch[2]) };
  const numMatch = s.match(/^#?(\d+)$/);
  if (numMatch !== null && defaultRepo !== null) {
    return { repo: defaultRepo, number: Number(numMatch[1]) };
  }
  return null;
}

/**
 * Modal for spinning up a multi-agent review panel on a PR awaiting
 * the user's review. Pick a PR, pick the panel members (rendered as
 * chips), set debate-round + cost caps, and toggle "Independent
 * first (no anchoring)". The dialog calls bridge.startReview and
 * hands the new thread id to the parent.
 */
const COST_MIN_CENTS = 10; // 0.10 USD
const COST_MAX_CENTS = 1000; // 10.00 USD — matches the in-review raise ceiling
const COST_STEP_CENTS = 5;

function AssignReviewTaskDialog({ workspaceId, onClose, onStarted, initialPr }: Props) {
  const [prs, setPrs] = useState<PullRequestDto[] | null>(null);
  const [roster, setRoster] = useState<ReviewRosterEntryDto[] | null>(null);
  // Pre-selected when the dialog is scoped to a specific PR (diff page).
  const [selectedPr, setSelectedPr] = useState<PullRequestDto | null>(initialPr ?? null);
  const [search, setSearch] = useState('');
  // The workspace's default repo — a bare typed number resolves here.
  const [defaultRepo, setDefaultRepo] = useState<string | null>(null);
  // On-demand lookup for a PR that isn't in the awaiting-review list.
  const [lookup, setLookup] = useState<LookupState>({ status: 'idle' });
  // Review-surface rows from the skills vault — the user's authored
  // reviewing voices (the named @mention identities). Offered in each
  // seat's Role dropdown; development skills stay with the build agents
  // and thread-scoped rows with their thread.
  const [roleSkills, setRoleSkills] = useState<SkillDto[]>([]);
  // Composed panel — one row per reviewer seat, each a model paired with
  // a review-skill voice or a typed prompt. leadKey identifies
  // the seat that runs consensus + moderates. Seeded with one row once
  // the roster loads.
  const [seats, setSeats] = useState<SeatDraft[]>([]);
  const [leadKey, setLeadKey] = useState<string | null>(null);
  // Cost budget cap. Default mirrors ReviewPassService.StartOptions.DEFAULT.
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
        const [prList, rosterList, repoList, skillList] = await Promise.all([
          window.bridge.fetchPrs(),
          window.bridge.listReviewRoster(),
          window.bridge.listWorkspaceRepos(workspaceId),
          window.bridge.listSkills().catch(() => [] as SkillDto[]),
        ]);
        if (cancelled) return;
        const awaiting = prList
            .filter(p => p.origin === 'REVIEW_REQUESTED')
            .filter(p => p.state !== 'closed' && p.state !== 'merged')
            .filter(p => p.snoozedUntil === null);
        setPrs(awaiting);
        setRoster(rosterList);
        setRoleSkills(skillList.filter(s =>
            s.enabled && s.usage === 'review' && s.scope !== 'thread'));
        // First repo (oldest by addedAt) is the workspace's main repo —
        // the default a bare typed PR number resolves against.
        setDefaultRepo(repoList[0]?.repoFullName ?? null);
        // Seed one reviewer row on the first configured model, marked
        // lead, so the panel is valid the moment the dialog opens. The lead
        // can't be a CLI agent, so prefer a configured API model for it.
        const firstLeadable = rosterList.find(r => r.configured && !isCliProvider(r.providerId))
            ?? rosterList.find(r => r.configured);
        if (firstLeadable !== undefined) {
          const key = newSeatKey();
          setSeats([{ key, providerId: firstLeadable.providerId, role: 'none', customPrompt: '' }]);
          setLeadKey(key);
        }
      }
      catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err));
        }
      }
    })();
    return () => { cancelled = true; };
  }, [workspaceId]);

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

  // On-demand lookup: when the search box is a PR reference that isn't
  // already in the awaiting list, fetch it straight from GitHub (debounced)
  // so the user can assign a review to any PR they can see — including
  // ones GitHub drops from review-requested:@me or that live in another
  // repo. A bare number resolves against the workspace's default repo.
  useEffect(() => {
    const ref = parsePrRef(search, defaultRepo);
    if (ref === null) {
      setLookup({ status: 'idle' });
      return;
    }
    const inList = (prs ?? []).some(p =>
        p.number === ref.number && p.repo.toLowerCase() === ref.repo.toLowerCase());
    if (inList) {
      setLookup({ status: 'idle' });
      return;
    }
    let cancelled = false;
    setLookup({ status: 'loading', ref });
    const handle = window.setTimeout(() => {
      void (async () => {
        try {
          const pr = await window.bridge.lookupPr(ref.repo, ref.number);
          if (!cancelled) setLookup({ status: 'found', ref, pr });
        }
        catch (err) {
          if (cancelled) return;
          const msg = err instanceof Error ? err.message : String(err);
          setLookup(/\b404\b/.test(msg)
              ? { status: 'notfound', ref }
              : { status: 'error', ref, message: msg });
        }
      })();
    }, 400);
    return () => { cancelled = true; window.clearTimeout(handle); };
  }, [search, defaultRepo, prs]);

  const configuredProviders = useMemo(
      () => (roster ?? []).filter(r => r.configured), [roster]);

  const updateSeat = (key: string, patch: Partial<SeatDraft>) => {
    setSeats(prev => prev.map(s => (s.key === key ? { ...s, ...patch } : s)));
  };
  const addSeat = () => {
    const provider = configuredProviders[0]?.providerId ?? '';
    setSeats(prev => [
      ...prev,
      { key: newSeatKey(), providerId: provider, role: 'none', customPrompt: '' },
    ]);
  };
  const removeSeat = (key: string) => {
    setSeats(prev => prev.filter(s => s.key !== key));
    setLeadKey(prev => (prev === key ? null : prev));
  };

  // Exactly one lead: the explicit pick when it still points at a live
  // seat, otherwise the first seat.
  const effectiveLeadKey = useMemo(() => {
    if (leadKey !== null && seats.some(s => s.key === leadKey)) return leadKey;
    return seats[0]?.key ?? null;
  }, [leadKey, seats]);

  const validSeats = useMemo(() => seats.filter(s => s.providerId !== ''), [seats]);
  const submitDisabled = submitting || selectedPr === null || validSeats.length === 0;

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
            costCapMilli,
            independentFirst,
            // Land the review thread in the workspace the dialog was
            // opened from, so it shows in that workspace's thread list.
            workspaceId,
            // The composed panel — each seat is a model plus an optional
            // review-skill voice or typed prompt; exactly one is the lead.
            seats: validSeats.map(s => {
              const isLead = s.key === effectiveLeadKey;
              // The lead carries no persona — its role is fixed in code.
              // Strip any skill/prompt it may have held before being
              // promoted so it always submits model-only.
              return {
                providerId: s.providerId,
                customPrompt: !isLead && s.role === 'custom' ? s.customPrompt : null,
                roleSkillId: !isLead && s.role.startsWith('skill:')
                    ? Number(s.role.slice('skill:'.length))
                    : null,
                lead: isLead,
              };
            }),
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
          {initialPr !== undefined ? (
            <>
              <div style={sectionHeadStyle}>
                <span style={sectionLabelStyle}>Pull request</span>
              </div>
              <div style={fixedPrRowStyle}>
                <span style={prRowNumStyle}>#{initialPr.number}</span>
                <span style={fixedPrTitleStyle}>{initialPr.title}</span>
                <span style={prRowRepoStyle}>{initialPr.repo}</span>
              </div>
            </>
          ) : (
            <>
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
                placeholder="Search, or type a PR number / owner/repo#123 / paste a URL…"
                style={searchInputStyle}
              />
              <div style={prListStyle}>
                {prs === null ? (
                  <div style={mutedRowStyle}>Loading PRs…</div>
                ) : (
                  <>
                    {lookup.status === 'loading' && (
                      <div style={mutedRowStyle}>
                        Looking up {lookup.ref.repo}#{lookup.ref.number}…
                      </div>
                    )}
                    {lookup.status === 'found' && (
                      <PrRow
                        pr={lookup.pr}
                        selected={selectedPr?.repo === lookup.pr.repo
                          && selectedPr?.number === lookup.pr.number}
                        onSelect={() => setSelectedPr(lookup.pr)}
                        fromGitHub
                      />
                    )}
                    {lookup.status === 'notfound' && (
                      <div style={mutedRowStyle}>
                        No PR found at {lookup.ref.repo}#{lookup.ref.number} — check the
                        number, or paste the full PR URL.
                      </div>
                    )}
                    {lookup.status === 'error' && (
                      <div style={mutedRowStyle}>
                        Couldn't load {lookup.ref.repo}#{lookup.ref.number}: {lookup.message}
                      </div>
                    )}
                    {filteredPrs.map(pr => (
                      <PrRow
                        key={pr.id}
                        pr={pr}
                        selected={selectedPr?.id === pr.id}
                        onSelect={() => setSelectedPr(pr)}
                      />
                    ))}
                    {filteredPrs.length === 0 && lookup.status === 'idle' && (
                      <div style={mutedRowStyle}>
                        {prs.length === 0
                          ? 'No PRs awaiting your review right now.'
                          : 'No PRs match this search.'}
                      </div>
                    )}
                  </>
                )}
              </div>
            </>
          )}

          <div style={sectionHeadStyle}>
            <span style={sectionLabelStyle}>Panel</span>
            <span style={sectionMetaStyle}>cost splits evenly across seats · ★ marks the lead</span>
          </div>
          {roster !== null && configuredProviders.length === 0 ? (
            <div style={mutedRowStyle}>
              No AI models configured — add an API key in Settings → AI review.
            </div>
          ) : (
            <div style={seatListStyle}>
              {seats.map((seat, idx) => (
                <SeatRow
                  key={seat.key}
                  seat={seat}
                  index={idx}
                  isLead={seat.key === effectiveLeadKey}
                  roster={roster ?? []}
                  roleSkills={roleSkills}
                  canRemove={seats.length > 1}
                  onMakeLead={() => setLeadKey(seat.key)}
                  onChange={patch => updateSeat(seat.key, patch)}
                  onRemove={() => removeSeat(seat.key)}
                />
              ))}
              <button type="button" onClick={addSeat} style={addSeatBtnStyle}>
                + Add reviewer
              </button>
            </div>
          )}

          <div style={sectionHeadStyle}>
            <span style={sectionLabelStyle}>Limits</span>
          </div>
          <div style={limitsGridStyle}>
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
              Est. ~{formatUsd(estimateMilli(validSeats.length))} ·{' '}
              {validSeats.length === 0
                ? 'no reviewers'
                : `${validSeats.length} reviewer${validSeats.length === 1 ? '' : 's'}`} ·{' '}
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
  pr, selected, onSelect, fromGitHub = false,
}: {
  pr: PullRequestDto;
  selected: boolean;
  onSelect: () => void;
  /** True for an on-demand looked-up PR that isn't in the awaiting
   *  list — gets a small "from GitHub" badge and shows its repo, since
   *  it may live outside the workspace's default repo. */
  fromGitHub?: boolean;
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
          {fromGitHub && <span style={prRowRepoStyle}>{pr.repo}</span>}
          {pr.author !== null && <>{pr.author}</>}
          {/* Diff size / comment count / CI come from the detail fetch,
              not the on-demand single-PR lookup — they'd read 0 / green
              for a looked-up PR, so suppress them rather than mislead. */}
          {!fromGitHub && (
            <>
              <span style={prRowDiffPosStyle}>{` +${pr.additions}`}</span>
              <span style={prRowDiffNegStyle}>{` /-${pr.deletions}`}</span>
              {pr.commentCount > 0 && (
                <>{' · '}{pr.commentCount} {pr.commentCount === 1 ? 'comment' : 'comments'}</>
              )}
            </>
          )}
        </span>
      </span>
      {fromGitHub
        ? <span style={prRowFromGitHubStyle}>from GitHub</span>
        : <span style={ciStyle}>{ci}</span>}
    </button>
  );
}

/** One reviewer row in the panel builder: a ★ lead toggle, a model
 *  picker (configured providers only), and a "role" picker that is
 *  a review-skill voice from the vault, a typed prompt, or none. When
 *  "Custom prompt" is chosen a textarea reveals so the user can describe
 *  what this reviewer should focus on. */
function SeatRow({
  seat, index, isLead, roster, roleSkills, canRemove, onMakeLead, onChange, onRemove,
}: {
  seat: SeatDraft;
  index: number;
  isLead: boolean;
  roster: ReviewRosterEntryDto[];
  roleSkills: SkillDto[];
  canRemove: boolean;
  onMakeLead: () => void;
  onChange: (patch: Partial<SeatDraft>) => void;
  onRemove: () => void;
}) {
  return (
    <div style={seatRowStyle(isLead)}>
      <div style={seatTopRowStyle}>
        <button
          type="button"
          onClick={onMakeLead}
          disabled={isCliProvider(seat.providerId)}
          style={isCliProvider(seat.providerId)
              ? { ...seatLeadBtnStyle(isLead), opacity: 0.4, cursor: 'not-allowed' }
              : seatLeadBtnStyle(isLead)}
          aria-pressed={isLead}
          title={isCliProvider(seat.providerId)
              ? "A CLI agent can't be the lead — the lead coordinates via structured tools"
              : isLead ? 'Lead — runs consensus + moderates' : 'Make this the lead'}
        >
          {isLead ? '★' : '☆'}
        </button>
        <label style={seatFieldStyle}>
          <span style={seatFieldLabelStyle}>Model</span>
          <select
            value={seat.providerId}
            onChange={e => onChange({ providerId: e.target.value })}
            style={seatSelectStyle}
            aria-label={`Reviewer ${index + 1} model`}
          >
            {roster
              // The lead can't be a CLI agent, so keep CLI options out of
              // the lead seat's picker entirely.
              .filter(r => !isLead || !isCliProvider(r.providerId))
              .map(r => (
                <option key={r.providerId} value={r.providerId} disabled={!r.configured}>
                  {r.displayName}{r.configured ? '' : ' (no key)'}
                </option>
              ))}
          </select>
        </label>
        {isLead ? (
          // The lead's job is fixed and code-driven (summarize the PR,
          // dispatch reviewers, drive consensus), so it takes no
          // persona — picking the lead only picks which model runs that
          // role. The Role control is replaced by a static note.
          <span style={seatLeadNoteStyle}>
            Coordinates the panel — fixed role, no persona
          </span>
        ) : (
          <label style={seatFieldStyle}>
            <span style={seatFieldLabelStyle}>Role</span>
            <select
              value={seat.role}
              onChange={e => onChange({ role: e.target.value })}
              style={seatSelectStyle}
              aria-label={`Reviewer ${index + 1} role`}
            >
              <option value="none">— none (raw model) —</option>
              {roleSkills.length > 0 && (
                <optgroup label="Review skills">
                  {roleSkills.map(s => (
                    <option key={s.id} value={'skill:' + s.id}>
                      {s.name}
                    </option>
                  ))}
                </optgroup>
              )}
              <option value="custom">Custom prompt…</option>
            </select>
          </label>
        )}
        {canRemove && (
          <button
            type="button"
            onClick={onRemove}
            style={seatRemoveBtnStyle}
            aria-label={`Remove reviewer ${index + 1}`}
            title="Remove this reviewer"
          >
            ✕
          </button>
        )}
      </div>
      {!isLead && seat.role === 'custom' && (
        <textarea
          value={seat.customPrompt}
          onChange={e => onChange({ customPrompt: e.target.value })}
          placeholder="What should this reviewer focus on? e.g. “Be strict about error handling and concurrency.”"
          rows={2}
          style={seatPromptStyle}
          aria-label={`Reviewer ${index + 1} prompt`}
        />
      )}
    </div>
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
function estimateMilli(reviewers: number): number {
  // Nominal three lead-driven phases per pass — the cost cap, not a
  // round knob, is the real bound now.
  const rounds = 3;
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

const fixedPrRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 8,
  padding: '10px 12px',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 10,
  background: 'linear-gradient(180deg, rgba(124,58,237,0.06), rgba(124,58,237,0.02))',
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--ws-text-1)',
};

const fixedPrTitleStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const prRowMetaStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
};

const prRowRepoStyle: React.CSSProperties = {
  color: 'var(--ws-text-2, #475569)',
  fontWeight: 600,
  marginRight: 6,
};

const prRowFromGitHubStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '2px 8px',
  fontSize: 10,
  fontWeight: 600,
  borderRadius: 999,
  background: 'rgba(124, 58, 237, 0.12)',
  color: '#7c3aed',
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

const seatListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

function seatRowStyle(isLead: boolean): React.CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    gap: 8,
    padding: '10px 12px',
    border: isLead
        ? '1.5px solid var(--ws-accent, #7c3aed)'
        : '1px solid var(--ws-card-border)',
    borderRadius: 10,
    background: isLead
        ? 'linear-gradient(180deg, rgba(124,58,237,0.05), rgba(124,58,237,0.02))'
        : '#fff',
  };
}

const seatTopRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-end',
  gap: 8,
};

function seatLeadBtnStyle(isLead: boolean): React.CSSProperties {
  return {
    flexShrink: 0,
    width: 28,
    height: 28,
    borderRadius: 8,
    border: isLead ? '1px solid #d97706' : '1px solid var(--ws-card-border)',
    background: isLead ? 'rgba(217,119,6,0.12)' : '#fff',
    color: isLead ? '#d97706' : 'var(--ws-text-3)',
    fontSize: 15,
    lineHeight: 1,
    cursor: 'pointer',
    marginBottom: 1,
  };
}

const seatFieldStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 3,
};

const seatLeadNoteStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 11,
  fontStyle: 'italic',
  color: 'var(--ws-text-4, #94a3b8)',
  alignSelf: 'center',
};

const seatFieldLabelStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: 'var(--ws-text-4, #94a3b8)',
};

const seatSelectStyle: React.CSSProperties = {
  width: '100%',
  padding: '5px 8px',
  fontSize: 12,
  border: '1px solid var(--ws-card-border)',
  borderRadius: 6,
  background: '#fff',
  color: 'var(--ws-text-1)',
  boxSizing: 'border-box',
};

const seatRemoveBtnStyle: React.CSSProperties = {
  flexShrink: 0,
  width: 28,
  height: 28,
  borderRadius: 8,
  border: '1px solid var(--ws-card-border)',
  background: '#fff',
  color: 'var(--ws-text-3)',
  fontSize: 12,
  cursor: 'pointer',
  marginBottom: 1,
};

const seatPromptStyle: React.CSSProperties = {
  width: '100%',
  padding: '7px 9px',
  fontSize: 12,
  lineHeight: 1.45,
  border: '1px solid var(--ws-card-border)',
  borderRadius: 6,
  background: 'rgba(0,0,0,0.015)',
  color: 'var(--ws-text-1)',
  resize: 'vertical',
  fontFamily: 'inherit',
  boxSizing: 'border-box',
};

const addSeatBtnStyle: React.CSSProperties = {
  alignSelf: 'flex-start',
  padding: '6px 12px',
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--ws-accent, #7c3aed)',
  background: 'rgba(124,58,237,0.06)',
  border: '1px dashed rgba(124,58,237,0.4)',
  borderRadius: 8,
  cursor: 'pointer',
};

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
