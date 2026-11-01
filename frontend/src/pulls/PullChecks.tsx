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
import { Check, CircleCheck, CircleDot, CircleMinus, CircleSlash2, CircleX, Clock3, ExternalLink, FileText, LoaderCircle, RotateCw } from 'lucide-react';
import { useEffect, useId, useMemo, useState } from 'react';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { pullRequestAttentionKey } from '../home/attention';
import { recordAttentionAction } from '../home/useAttentionVisit';
import { remoteErrorMessage } from '../remoteError';
import type { PullRequestDetailDto, PullRequestDto } from '../types';
import { usePullRequestBridge } from './PullRequestWorkspace';

export default function PullChecks({ pr, detail, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onRefresh: () => Promise<void>;
}) {
  const bridge = usePullRequestBridge();
  const [rerunning, setRerunning] = useState(false);
  const [rerunRequested, setRerunRequested] = useState(false);
  const [rerunError, setRerunError] = useState<string | null>(null);
  const rerunLabel = rerunning ? 'Requesting…' : rerunRequested ? 'Rerun requested' : 'Rerun failed tests';
  const groups = useMemo(() => {
    const out = Map.groupBy(detail.checkRuns, check => check.category);
    return (['failing', 'pending', 'successful', 'neutral', 'skipped', 'cancelled'] as const)
      .map(category => [category, out.get(category) ?? []] as const)
      .filter(([, checks]) => checks.length > 0);
  }, [detail.checkRuns]);
  const passing = detail.ciStatus === 'PASSING';
  const failing = detail.ciStatus === 'FAILING';
  const pending = detail.ciStatus === 'PENDING';
  const running = detail.checkRuns.some(check => check.status === 'in_progress');
  const SummaryIcon = passing ? CircleCheck : failing ? CircleX : pending ? running ? LoaderCircle : Clock3 : CircleDot;
  const canRerun = detail.viewerCanWrite && detail.checkRuns.some(check =>
    check.category === 'failing' && checkJobId(check) !== null);
  useEffect(() => {
    if (detail.ciStatus !== 'FAILING') setRerunRequested(false);
  }, [detail.ciStatus]);
  const rerun = async () => {
    setRerunning(true);
    setRerunError(null);
    try {
      await bridge.rerunFailedPrChecks(pr.repo, pr.number);
      recordAttentionAction(pullRequestAttentionKey(pr), 'update');
      setRerunRequested(true);
      await onRefresh();
    }
    catch (failure) {
      setRerunError(remoteErrorMessage(failure));
    }
    finally {
      setRerunning(false);
    }
  };
  return (
    <article className="pr-checks">
      <header><span className={passing ? 'is-success' : failing ? 'is-failure' : pending ? 'is-pending' : 'is-neutral'}>
        <SummaryIcon className={pending && running ? 'is-running' : undefined} aria-hidden="true" /></span>
        <strong>{passing ? 'All checks have passed' : failing ? 'Some checks were not successful' : pending ? 'Checks are in progress' : 'No checks reported'}</strong>
        {canRerun && <button type="button" className="pr-checks__rerun" disabled={rerunning || rerunRequested}
          aria-label={rerunLabel} title={rerunLabel} onClick={() => { void rerun(); }}>
          {rerunning ? <span className="bq-spinner is-spinning"><LoaderCircle size={16} aria-hidden="true" /></span>
            : rerunRequested ? <Check size={16} aria-hidden="true" /> : <RotateCw size={16} aria-hidden="true" />}
        </button>}
      </header>
      {rerunError && <div className="pr-checks__error" role="alert">{rerunError}</div>}
      <div className="pr-checks__groups">
        {groups.length === 0 && <span>No checks reported.</span>}
        {groups.map(([category, checks], index) => (
          <details key={category} open={index === 0 && category === 'failing'}>
            <summary>{groupLabel(category)} ({checks.length}) <span>›</span></summary>
            {checks.map((check, checkIndex) => (
              <CheckRow key={`${pr.repo}:${pr.number}:${pr.headPushedAt}:${check.source}:${check.remoteId ?? checkIndex}:${check.detailsUrl}:${check.startedAt}:${check.completedAt}:${check.category}`}
                pr={pr} check={check} />
            ))}
          </details>
        ))}
      </div>
    </article>
  );
}

export function checkJobId(check: PullRequestDetailDto['checkRuns'][number]): number | null {
  return check.source === 'job' && Number.isSafeInteger(check.remoteId) && (check.remoteId ?? 0) > 0 ? check.remoteId : null;
}

const CI_LOG_KEYWORDS = /(\b(?:errors?|exceptions?|failed|failures?|fatal|panic|traceback|assertion|assert|mismatch|\w*(?:Exception|Error))\b|\b(?:Caused by|Suppressed|timed out|segmentation fault|cannot find symbol)\b:?)/gi;

function CiLogText({ text }: { text: string }) {
  return text.split(CI_LOG_KEYWORDS).map((part, index) => index % 2 === 0 ? part : <mark key={index}>{part}</mark>);
}

function CheckRow({ pr, check }: { pr: PullRequestDto; check: PullRequestDetailDto['checkRuns'][number] }) {
  const bridge = usePullRequestBridge();
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [log, setLog] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [browserError, setBrowserError] = useState<string | null>(null);
  const logId = useId();
  const logLabel = loading ? 'Loading…' : error ? 'Retry log' : expanded ? 'Hide log' : 'Show log';
  const jobId = checkJobId(check);
  const browserUrl = check.detailsUrl && /^https?:\/\//i.test(check.detailsUrl) ? check.detailsUrl : null;
  const openBrowser = async () => {
    if (browserUrl === null) return;
    setBrowserError(null);
    try {
      await bridge.openExternal(browserUrl);
    }
    catch (failure) {
      setBrowserError(remoteErrorMessage(failure));
    }
  };
  const toggleLog = async () => {
    if (expanded && error === null) {
      setExpanded(false);
      return;
    }
    setExpanded(true);
    if ((log !== null && error === null) || jobId === null) return;
    setLoading(true);
    setError(null);
    try {
      const result = await bridge.getPrCheckLog(pr.repo, pr.number, jobId);
      setLog(previous => result.unavailableReason === null ? result.text : result.text || previous);
      setError(result.unavailableReason);
    }
    catch (failure) {
      setError(remoteErrorMessage(failure));
    }
    finally {
      setLoading(false);
    }
  };
  return (
    <div className="pr-check">
      <CheckStatusIcon check={check} />
      <strong>{check.name ?? 'Check'}</strong>
      {check.completedAt
        ? <time dateTime={check.completedAt}>Finished {relativeTime(check.completedAt)}</time>
        // GitHub stamps started_at when a run is created, so only in_progress has really started.
        : check.startedAt && <time dateTime={check.startedAt}>
          {check.status === 'in_progress' ? 'Started' : 'Queued'} {relativeTime(check.startedAt)}</time>}
      <span className="pr-check__actions">
        {check.source === 'status' && <small>status</small>}
        {check.category === 'failing' && <>
          <button type="button" disabled={browserUrl === null} aria-label="Open in browser" title="Open in browser"
            onClick={() => { void openBrowser(); }}>
            <ExternalLink size={16} aria-hidden="true" />
          </button>
          <button type="button" aria-label={logLabel} aria-expanded={expanded} aria-controls={logId} disabled={loading || jobId === null}
            title={jobId === null ? 'Inline logs are available for jobs' : logLabel}
            onClick={() => { void toggleLog(); }}>
            {loading ? <span className="bq-spinner is-spinning"><LoaderCircle size={16} aria-hidden="true" /></span>
              : error ? <RotateCw size={16} aria-hidden="true" /> : <FileText size={16} aria-hidden="true" />}
          </button>
        </>}
      </span>
      {browserError && <div className="pr-check__log"><div role="alert">{browserError}</div></div>}
      {expanded && <div className="pr-check__log" id={logId}>
        {loading && <div role="status">Loading log…</div>}
        {error && <div role="alert">{error}</div>}
        {log !== null && <>
          <p>{error ? 'Failure annotations. Open in browser for job details.' : 'Diagnostic excerpt. Open in browser for the complete log.'}</p>
          <pre tabIndex={0} aria-label={`${check.name ?? 'Check'} log`}><CiLogText text={log || 'This job returned an empty log.'} /></pre>
        </>}
      </div>}
    </div>
  );
}

const CHECK_STATUS_ICONS = {
  failing: { Icon: CircleX, label: 'Failed' },
  pending: { Icon: Clock3, label: 'Pending' },
  successful: { Icon: CircleCheck, label: 'Successful' },
  neutral: { Icon: CircleDot, label: 'Neutral' },
  skipped: { Icon: CircleMinus, label: 'Skipped' },
  cancelled: { Icon: CircleSlash2, label: 'Cancelled' },
} as const;

function CheckStatusIcon({ check }: { check: PullRequestDetailDto['checkRuns'][number] }) {
  const running = check.category === 'pending' && check.status === 'in_progress';
  const queued = check.category === 'pending' && check.status === 'queued';
  const { Icon, label } = running
    ? { Icon: LoaderCircle, label: 'Running' }
    : queued ? { Icon: Clock3, label: 'Queued' } : CHECK_STATUS_ICONS[check.category];
  const icon = <Icon className={`pr-check__icon is-${check.category}${running ? ' is-running' : ''}`} role="img" aria-label={label} />;
  return running ? <span className="bq-spinner is-spinning">{icon}</span> : icon;
}

function groupLabel(category: string) {
  return category.charAt(0).toUpperCase() + category.slice(1);
}
