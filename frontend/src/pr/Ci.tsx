import { useState } from 'react';
import type { CheckRunDto, CiStatus, PullRequestDetailDto } from '../types';
import { conclusionLabel, isCheckFailing } from './utils';

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

export function CiSummary({ detail }: { detail: PullRequestDetailDto }) {
  const [open, setOpen] = useState(false);
  const failing = detail.checkRuns.filter(c => isCheckFailing(c.conclusion));
  const label = detail.ciStatus.charAt(0) + detail.ciStatus.slice(1).toLowerCase();
  const total = detail.checkRuns.length;
  const hasFailures = failing.length > 0;
  return (
    <div className={`ci-summary ci-summary--${detail.ciStatus.toLowerCase()}`}>
      <button
        type="button"
        className="ci-summary__row"
        onClick={() => hasFailures && setOpen(v => !v)}
        disabled={!hasFailures}
        aria-expanded={hasFailures ? open : undefined}
      >
        <CiDot status={detail.ciStatus} />
        <span className="ci-summary__label">{label}</span>
        {total > 0 && (
          <span className="ci-summary__count">
            {hasFailures
              ? `${failing.length} of ${total} checks failed`
              : `${total} check${total === 1 ? '' : 's'} passing`}
          </span>
        )}
        {hasFailures && (
          <span className="ci-summary__chevron" aria-hidden="true">
            {open ? '▾' : '▸'}
          </span>
        )}
      </button>
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
 * Compact CI summary row that toggles a per-check list when clicked. Shows
 * each check run's name, conclusion, and a link to the run on GitHub —
 * mirrors what GitHub puts under the Conversation tab's status section.
 * Lists every check (passing, failing, pending) when expanded so the user
 * doesn't have to leave the app to see what's running.
 */
export function CiChecksRow({ ciStatus, checkRuns }: { ciStatus: CiStatus; checkRuns: CheckRunDto[] }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        className="prc-status-row prc-status-row--button"
        onClick={() => setOpen(v => !v)}
        disabled={checkRuns.length === 0}
        aria-expanded={open}
      >
        <CiDot status={ciStatus} />
        <span><b>CI {ciStatus.toLowerCase()}</b> — {checkRuns.length} check{checkRuns.length === 1 ? '' : 's'}</span>
        {checkRuns.length > 0 && (
          <span className="prc-status-row__chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
        )}
      </button>
      {open && checkRuns.map((c, i) => {
        const failing = c.conclusion === 'failure' || c.conclusion === 'cancelled' || c.conclusion === 'timed_out' || c.conclusion === 'action_required';
        const success = c.conclusion === 'success' || c.conclusion === 'neutral' || c.conclusion === 'skipped';
        const dotColor = failing ? '#ef4444' : success ? '#16a34a' : '#ad8434';
        const label = c.conclusion ? c.conclusion.replace(/_/g, ' ') : (c.status === 'in_progress' || c.status === 'queued' ? c.status.replace(/_/g, ' ') : 'pending');
        const displayName = c.name && c.name.trim().length > 0 ? c.name : '(unnamed check)';
        // Whole row is a link when GitHub gave us an htmlUrl — avoids the
        // earlier UX where the user had to aim at a tiny "View ↗" sliver.
        const inner = (
          <>
            <span className="prc-status-dot" style={{ background: dotColor }} />
            <span className="prc-check-row__name" title={displayName}>{displayName}</span>
            <span className={`prc-check-row__conclusion prc-check-row__conclusion--${failing ? 'fail' : success ? 'ok' : 'pending'}`}>{label}</span>
          </>
        );
        return c.htmlUrl
          ? (
              <a
                key={i}
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
          : <div key={i} className="prc-check-row">{inner}</div>;
      })}
    </>
  );
}
