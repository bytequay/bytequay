import { useState } from 'react';
import type { CheckRunDto, CiStatus, PullRequestDetailDto } from '../types';
import { conclusionLabel, isCheckFailing } from './utils';

const isCheckPassing = (conclusion: string | null): boolean =>
  conclusion === 'success' || conclusion === 'neutral' || conclusion === 'skipped';

type CiDotProps = { status: CiStatus };

export function CiDot({ status }: CiDotProps) {
  const colors: Record<CiStatus, string> = {
    PASSING: '#3e8060',
    FAILING: '#aa4e3e',
    PENDING: '#ad8434',
    NONE: '#9e9e9e',
  };
  return (
    <span
      className="ci-dot"
      style={{ background: colors[status] }}
      title={status.charAt(0) + status.slice(1).toLowerCase()}
    />
  );
}

type CiSummaryProps = {
  ciStatus: CiStatus;
  checkRuns: CheckRunDto[];
  /** Optional manual-refresh hook. When provided, a refresh button is
   *  rendered next to the status label so the user can force a CI poll
   *  without waiting for the focus-driven interval. */
  onRefresh?: () => void | Promise<void>;
  refreshing?: boolean;
};

export function CiSummary({ ciStatus, checkRuns, onRefresh, refreshing }: CiSummaryProps) {
  // Folded by default — the user asked for "X of Y failing" + a chevron
  // they can pop open when they actually want to see which checks broke.
  const [open, setOpen] = useState(false);
  const failing = checkRuns.filter(c => isCheckFailing(c.conclusion));
  const label = ciStatus.charAt(0) + ciStatus.slice(1).toLowerCase();
  const total = checkRuns.length;
  const hasFailures = failing.length > 0;
  return (
    <div className={`ci-summary ci-summary--${ciStatus.toLowerCase()}`}>
      <div className="ci-summary__row">
        <button
          type="button"
          className="ci-summary__toggle"
          onClick={() => hasFailures && setOpen(v => !v)}
          disabled={!hasFailures}
          aria-expanded={hasFailures ? open : undefined}
        >
          <CiDot status={ciStatus} />
          <span className="ci-summary__label">{label}</span>
          {total > 0 && (
            <span className="ci-summary__count">
              {hasFailures
                ? `${failing.length} of ${total} failing`
                : `${total} check${total === 1 ? '' : 's'} passing`}
            </span>
          )}
          {hasFailures && (
            <span className="ci-summary__chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
          )}
        </button>
        {onRefresh && (
          <button
            type="button"
            className="ci-summary__refresh"
            onClick={() => { void onRefresh(); }}
            disabled={refreshing}
            title="Refresh CI status"
            aria-label="Refresh CI status"
          >
            <span className={`ci-summary__refresh-icon${refreshing ? ' ci-summary__refresh-icon--spin' : ''}`} aria-hidden="true">↻</span>
          </button>
        )}
      </div>
      {hasFailures && open && (
        <ul className="ci-failing-list">
          {failing.map((c, i) => (
            <li key={i} className="ci-failing-row">
              <span className="ci-failing-row__icon" aria-hidden="true">✗</span>
              <span className="ci-failing-row__name">{c.name || 'Check run'}</span>
              <span className="ci-failing-row__reason">{conclusionLabel(c.conclusion)}</span>
              {c.htmlUrl && (
                <a
                  className="ci-failing-row__link"
                  href={c.htmlUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  View ↗
                </a>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * Compact CI status row for the right-rail Status section. Failing checks
 * are always listed inline (no expand click required); pending checks can
 * be revealed with the chevron. Successful check names are intentionally
 * hidden — once a check is green its name rarely matters and the count in
 * the summary row is enough.
 */
export function CiChecksRow({ ciStatus, checkRuns }: { ciStatus: CiStatus; checkRuns: CheckRunDto[] }) {
  const [open, setOpen] = useState(false);
  const failing = checkRuns.filter(c => isCheckFailing(c.conclusion));
  const passing = checkRuns.filter(c => isCheckPassing(c.conclusion));
  const pending = checkRuns.filter(c => !isCheckFailing(c.conclusion) && !isCheckPassing(c.conclusion));
  // Anything that's not green is foldable. Successful check names stay
  // hidden — once a check is green its name rarely matters.
  const canExpand = failing.length > 0 || pending.length > 0;
  return (
    <>
      <button
        type="button"
        className="prc-status-row prc-status-row--button"
        onClick={() => canExpand && setOpen(v => !v)}
        disabled={!canExpand}
        aria-expanded={canExpand ? open : undefined}
      >
        <CiDot status={ciStatus} />
        <span>
          <b>CI {ciStatus.toLowerCase()}</b>
          {checkRuns.length > 0 && (
            <> — {failing.length > 0 && `${failing.length} failing, `}{passing.length} of {checkRuns.length} passing</>
          )}
        </span>
        {canExpand && (
          <span className="prc-status-row__chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
        )}
      </button>
      {open && failing.map((c, i) => renderCheckRow(c, 'fail', `fail-${i}`))}
      {open && pending.map((c, i) => renderCheckRow(c, 'pending', `pending-${i}`))}
    </>
  );
}

function renderCheckRow(c: CheckRunDto, kind: 'fail' | 'pending', key: string) {
  const dotColor = kind === 'fail' ? '#ef4444' : '#ad8434';
  const label = c.conclusion ? c.conclusion.replace(/_/g, ' ') : (c.status === 'in_progress' || c.status === 'queued' ? c.status.replace(/_/g, ' ') : 'pending');
  const displayName = c.name && c.name.trim().length > 0 ? c.name : '(unnamed check)';
  const inner = (
    <>
      <span className="prc-status-dot" style={{ background: dotColor }} />
      <span className="prc-check-row__name" title={displayName}>{displayName}</span>
      <span className={`prc-check-row__conclusion prc-check-row__conclusion--${kind}`}>{label}</span>
    </>
  );
  return c.htmlUrl
    ? (
        <a
          key={key}
          href={c.htmlUrl}
          target="_blank"
          rel="noreferrer"
          className="prc-check-row prc-check-row--link"
          title={`Open ${displayName} on GitHub`}
        >
          {inner}
          <span className="prc-check-row__arrow" aria-hidden="true">↗</span>
        </a>
      )
    : <div key={key} className="prc-check-row">{inner}</div>;
}
