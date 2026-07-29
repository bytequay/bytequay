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
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  workspaceApi,
  type WorkspaceSessionDto,
} from './workspaceApi';

type SessionFilter = 'all' | 'plan' | 'dev' | 'review' | 'ci-fix';

export type WorkspaceReviewSessionTarget = {
  workspaceId: string;
  prId: string;
  prNumber: number | null;
  roundId: string;
};

type ReviewSessionTarget = WorkspaceReviewSessionTarget & {
  title: string;
  repo: string | null;
};

export default function WorkspaceSessionsPage({
  workspaceId,
  onOpenThread,
  onOpenReview,
  selectedSessionId,
  onOpenSession,
  onBackToList,
  listPresentation = 'provider',
  featuredSessionIds,
  dailySpendOverride,
  dailyTokensOverride,
  showFilters = true,
}: {
  workspaceId: string;
  onOpenThread?: (threadId: string) => void;
  onOpenReview?: (target: WorkspaceReviewSessionTarget) => void;
  selectedSessionId?: string;
  onOpenSession?: (sessionId: string) => void;
  onBackToList?: () => void;
  listPresentation?: 'status' | 'provider';
  featuredSessionIds?: readonly string[];
  dailySpendOverride?: number;
  dailyTokensOverride?: number;
  showFilters?: boolean;
}) {
  const [sessions, setSessions] = useState<WorkspaceSessionDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [kind, setKind] = useState<SessionFilter>('all');
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const rows = await workspaceApi.sessions(workspaceId);
      setSessions([...rows].sort(compareSessions));
      setError(null);
    }
    catch (reason) {
      setError(message(reason));
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 2_000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const shown = useMemo(() => {
    const filtered = kind === 'all' ? sessions : sessions.filter(session => session.kind === kind);
    if (featuredSessionIds === undefined) return filtered;
    const featured = new Set(featuredSessionIds);
    return filtered.filter(session => featured.has(session.id));
  }, [featuredSessionIds, kind, sessions]);
  const running = sessions.filter(session => session.status === 'running').length;
  const spend = dailySpendOverride
    ?? sessions.reduce((sum, session) => sum + session.costUsdMilli, 0);
  const dailyTokens = dailyTokensOverride
    ?? sessions.reduce((sum, session) => sum + session.tokensIn + session.tokensOut, 0);
  const selected = selectedSessionId === undefined
    ? undefined
    : sessions.find(session => session.id === selectedSessionId);

  if (selectedSessionId !== undefined) {
    return (
      <SessionDetail
        session={selected}
        loading={loading}
        sessionId={selectedSessionId}
        onBack={() => onBackToList?.()}
        onOpenThread={() => {
          if (selected?.trunkId !== null && selected?.trunkId !== undefined) {
            onOpenThread?.(selected.trunkId);
          }
        }}
        onOpenReview={onOpenReview}
      />
    );
  }

  return (
    <section className="wu-page wu-sessions">
      <header className="wu-page-header">
        <div className="wu-page-heading">
          <h1>Sessions</h1>
          <span className="wu-session-running"><i />{running} running</span>
        </div>
        <div className="wu-header-actions">
          {showFilters && <div className="wu-segmented wu-session-filters">
            {([
              ['all', 'All'],
              ['dev', 'Dev'],
              ['review', 'Review'],
              ['plan', 'Plan'],
            ] as const).map(([key, label]) => (
              <div role="button" tabIndex={0} key={key} className={kind === key ? 'active' : ''}
                onClick={() => setKind(key)}
                onKeyDown={event => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setKind(key);
                  }
                }}>{label}</div>
            ))}
          </div>}
          <span className="wu-session-total">today: ${(spend / 1000).toFixed(2)}</span>
        </div>
      </header>
      <div className={`wu-session-list-wrap ${listPresentation}`}>
        <div className="wu-session-list">
        {loading && <div className="wu-body-message">Loading sessions…</div>}
        {error !== null && <div className="wu-body-message error">{error}</div>}
        {!loading && error === null && shown.map(session => (
          <SessionListRow
            key={session.id}
            session={session}
            presentation={listPresentation}
            onOpen={() => onOpenSession?.(session.id)}
          />
        ))}
        {!loading && error === null && shown.length === 0 && (
          <div className="wu-body-message">No sessions match this view.</div>
        )}
        </div>
        {listPresentation === 'status' && !loading && error === null && (
          <div className="wu-session-list-footer">
            <span>
              Today: {sessions.filter(session => session.status !== 'queued').length} sessions ·{' '}
              <b>${(spend / 1000).toFixed(2)}</b> · {compactTokens(dailyTokens)} tokens
            </span>
            <a href={`#/workspace/${encodeURIComponent(workspaceId)}/insights`}>
              Cost breakdown in Insights →
            </a>
          </div>
        )}
      </div>
    </section>
  );
}

function SessionListRow({
  session,
  presentation,
  onOpen,
}: {
  session: WorkspaceSessionDto;
  presentation: 'status' | 'provider';
  onOpen: () => void;
}) {
  const queued = session.status === 'queued';
  const done = session.status === 'done';
  const errored = session.status === 'errored';
  return (
    <div
      className={`wu-session-list-row ${session.status}`}
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen();
        }
      }}
    >
      {presentation === 'provider' && (
        <span
          className={`wu-session-provider-tile ${providerTone(session.provider)}`}
          title={`Provider icon: ${providerName(session.provider)}`}
        >
          {providerMonogram(session.provider)}
        </span>
      )}
      <span className={`wu-session-list-state ${session.status}`}>
        {session.status === 'running'
          ? <i />
          : queued
            ? <ClockIcon />
            : done
              ? <CheckIcon />
              : errored
                ? <FailureIcon />
                : null}
      </span>
      <div className="wu-session-list-main">
        <span>{session.headline ?? session.launchInput ?? `${session.kind} session`}</span>
        <small>
          <SessionListSubtitle session={session} presentation={presentation} />
        </small>
      </div>
      <span className={`wu-session-kind ${session.kind}`}>
        {session.kind === 'ci-fix' ? 'ci fix' : session.kind}
      </span>
      {presentation === 'status' && (
        <span className="wu-session-list-model">{shortModel(session)}</span>
      )}
      <span className={`wu-session-list-usage${queued ? ' queued' : ''}`}>
        {queued
          ? 'queued'
          : `$${(session.costUsdMilli / 1000).toFixed(2)} · ${
            compactTokens(session.tokensIn + session.tokensOut)
          } · ${elapsed(session)}`}
      </span>
      <span className={`wu-session-list-action ${queued ? 'queued' : ''}`}>
        {session.durableReview
          ? 'Open →'
          : session.status === 'running'
          ? 'Watch →'
          : errored
            ? 'Restart'
            : queued
              ? '→'
              : relativeFinished(session)}
      </span>
    </div>
  );
}

function SessionListSubtitle({
  session,
  presentation,
}: {
  session: WorkspaceSessionDto;
  presentation: 'status' | 'provider';
}) {
  if (presentation === 'provider') {
    if (session.status === 'running') {
      return <>{session.model} · task #{session.taskNumber ?? taskNumberFromId(session.taskId)} ·{' '}
        <span className="wu-session-branch-ref">{session.branch}</span></>;
    }
    if (session.kind === 'plan') {
      return <>{session.model} · {session.outcome}</>;
    }
    if (session.kind === 'ci-fix') {
      return <>{session.model} · errored after 3 iterations</>;
    }
    return <>{session.model ?? session.provider} · {session.outcome ?? session.launchInput}</>;
  }
  if (session.status === 'running') {
    return <>task #{session.taskNumber ?? taskNumberFromId(session.taskId)} · branch{' '}
      <span className="wu-session-branch-ref">{session.branch}</span></>;
  }
  if (session.status === 'queued') return <>scheduled · queued behind running session</>;
  if (session.kind === 'dev') {
    return <>task #{session.taskNumber ?? taskNumberFromId(session.taskId)} · merged into{' '}
      <span className="wu-session-branch-ref">{session.branch ?? 'dev/clamp-fix'}</span></>;
  }
  return <>{session.outcome}</>;
}

function SessionDetail({
  session, loading, sessionId, onBack, onOpenThread, onOpenReview,
}: {
  session: WorkspaceSessionDto | undefined;
  loading: boolean;
  sessionId: string;
  onBack: () => void;
  onOpenThread: () => void;
  onOpenReview?: (target: WorkspaceReviewSessionTarget) => void;
}) {
  const [reviewTarget, setReviewTarget] = useState<ReviewSessionTarget | null>(null);
  const [reviewTargetError, setReviewTargetError] = useState<string | null>(null);
  const reviewRoundId = session?.durableReview ? session.reviewRoundId ?? null : null;
  const reviewWorkspaceId = session?.workspaceId ?? null;

  useEffect(() => {
    let cancelled = false;
    setReviewTarget(null);
    setReviewTargetError(null);
    if (reviewRoundId === null || reviewWorkspaceId === null) return () => { cancelled = true; };

    void (async () => {
      try {
        const log = await window.bridge.getAgentReviewRoundLog(reviewRoundId);
        const bundle = await window.bridge.getLocalPrBundle(log.review.pr_id)
          .catch((_reason: unknown): null => null);
        if (!cancelled) {
          setReviewTarget({
            workspaceId: reviewWorkspaceId,
            prId: log.review.pr_id,
            prNumber: bundle?.pr.remotePrNumber ?? null,
            roundId: reviewRoundId,
            title: bundle?.pr.title ?? 'Pull request',
            repo: bundle?.pr.repo ?? null,
          });
        }
      }
      catch (reason) {
        if (!cancelled) setReviewTargetError(message(reason));
      }
    })();
    return () => { cancelled = true; };
  }, [reviewRoundId, reviewWorkspaceId]);

  if (loading) {
    return <section className="wu-page"><div className="wu-body-message">Loading session…</div></section>;
  }
  if (session === undefined) {
    return (
      <section className="wu-page">
        <header className="wu-page-header">
          <div className="wu-page-heading"><h1>Session</h1><span>{sessionId}</span></div>
          <button type="button" className="wu-icon-button" onClick={onBack}>← Sessions</button>
        </header>
        <div className="wu-body-message">This session is no longer available.</div>
      </section>
    );
  }

  const phases = session.phases ?? defaultPhases(session.kind);
  const currentPhase = Math.max(0, Math.min(phases.length - 1, session.stepCursor - 1));
  const timeline = session.timeline ?? derivedTimeline(session);
  const changes = session.changes ?? { additions: 0, deletions: 0, files: [] };
  const budgetRatio = session.budget === null || session.budget <= 0
    ? 0
    : Math.min(100, (session.costUsdMilli / session.budget) * 100);
  const taskNumber = session.taskNumber ?? taskNumberFromId(session.taskId);
  const openReview = (target: ReviewSessionTarget) => onOpenReview?.({
    workspaceId: target.workspaceId,
    prId: target.prId,
    prNumber: target.prNumber,
    roundId: target.roundId,
  });

  return (
    <section className="wu-page wu-session-live-detail">
      <header className="wu-session-live-header">
        <a href={`#/workspace/${encodeURIComponent(session.workspaceId)}/sessions`}
          className="wu-session-breadcrumb" onClick={(event) => {
            event.preventDefault();
            onBack();
          }}>
          <ChevronLeftIcon />
          Sessions
        </a>
        <span className="wu-session-slash">/</span>
        <span className={`wu-session-provider ${providerTone(session.provider)}`}>
          {providerMonogram(session.provider)}
        </span>
        <strong>{capitalize(session.kind)} — step {Math.max(1, session.stepCursor)} of {phases.length}</strong>
        <span className={`wu-session-live-status ${session.status}`}>
          {(session.status === 'running' || session.status === 'queued') && <i />}
          {session.status}
        </span>
        <span className="wu-session-live-usage">
          {session.model ?? session.provider ?? 'Agent'} · ${(session.costUsdMilli / 1000).toFixed(2)}
          {' · '}{compactTokens(session.tokensIn + session.tokensOut)} · {elapsed(session)}
        </span>
        <span className="wu-session-live-spacer" />
        {session.durableReview ? (
          <button type="button" className="wu-session-open-trunk"
            disabled={reviewTarget === null || onOpenReview === undefined}
            onClick={() => { if (reviewTarget !== null) openReview(reviewTarget); }}>
            Open pull request
          </button>
        ) : (
          <button type="button" className="wu-session-open-trunk"
            disabled={session.trunkId === null} onClick={onOpenThread}>
            <TrunkIcon />
            Open thread
          </button>
        )}
      </header>
      <div className="wu-session-context">
        {session.durableReview ? (
          <span>
            Reviews {reviewTarget === null ? (
              reviewTargetError === null ? 'pull request…' : 'an unavailable pull request'
            ) : (
              <a href={reviewTargetHref(reviewTarget)} onClick={(event) => {
                if (onOpenReview !== undefined) {
                  event.preventDefault();
                  openReview(reviewTarget);
                }
              }}>
                {reviewTarget.repo === null ? reviewTarget.title : reviewTarget.repo}
                {reviewTarget.prNumber === null ? '' : `#${reviewTarget.prNumber}`}
              </a>
            )}
            {reviewTargetError !== null && <small> · {reviewTargetError}</small>}
          </span>
        ) : (
          <span>
            {session.trunkId === null ? 'No owning thread' : (
              <>Belongs to <a href={`#/workspace/${encodeURIComponent(session.workspaceId)}/trunks/${
                encodeURIComponent(session.trunkId)
              }`} onClick={(event) => {
                event.preventDefault();
                onOpenThread();
              }}>
                {session.trunkTitle ?? 'owning thread'}
              </a></>
            )}
            {taskNumber !== null && <> · task #{taskNumber}</>}
          </span>
        )}
        {session.branch !== undefined && (
          <span className="wu-session-branch">{session.branch}</span>
        )}
        <span className="wu-session-live-spacer" />
        <span className="wu-session-phase-list">
          Steps:{' '}
          {phases.map((phase, index) => (
            <span key={phase}>
              {index > 0 && ' · '}
              <b className={index === currentPhase ? 'active' : ''}>{phase}</b>
              {index < currentPhase && ' ✓'}
            </span>
          ))}
        </span>
      </div>
      <div className="wu-session-live-main">
        <div className="wu-session-timeline">
          {timeline.map(item => (
            <div key={item.id}
              className={`wu-session-timeline-item ${item.status}${item.output === undefined ? '' : ' with-output'}`}>
              <div className="wu-session-timeline-summary">
                <span className="wu-session-timeline-icon">
                  {item.status === 'done' ? <CheckIcon /> : item.status === 'running' ? <i /> : null}
                </span>
                <div>
                  <strong>{item.title}</strong>
                  {item.detail !== null && <SessionTimelineDetail text={item.detail} />}
                </div>
                <time>{item.timeLabel}</time>
              </div>
              {item.output !== undefined && (
                <div className="wu-session-stream">
                  {item.output.map((line, index) => (
                    <span key={`${line.text}-${index}`} className={line.tone}>{line.text}</span>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
        <aside className="wu-session-live-side">
          <section className="wu-session-change-card">
            <strong>Changes so far</strong>
            <span className="wu-session-change-counts">
              <b>+{changes.additions}</b> <b>−{changes.deletions}</b> · {changes.files.length} files
            </span>
            <div>
              {changes.files.slice(0, 3).map(file => (
                <span key={`${file.status}-${file.path}`}>{file.status} {file.path}</span>
              ))}
              {changes.files.length === 0 && <span>No file changes yet</span>}
            </div>
            <a href={session.branch === undefined
              ? `#/workspace/${encodeURIComponent(session.workspaceId)}/branches`
              : `#/workspace/${encodeURIComponent(session.workspaceId)}/branches/${
                encodeURIComponent(session.branch)
              }`}>View diff →</a>
          </section>
          <section className="wu-session-budget-card">
            <strong>Budget</strong>
            <div>
              <span><i style={{ width: `${budgetRatio}%` }} /></span>
              <code>
                ${(session.costUsdMilli / 1000).toFixed(2)} /{' '}
                {session.budget === null ? 'no cap' : `$${(session.budget / 1000).toFixed(2)}`}
              </code>
            </div>
            <small>
              {session.pauseReason ?? (session.budget === null
                ? 'No per-session cap'
                : 'Pauses for approval at the cap')}
            </small>
          </section>
        </aside>
      </div>
    </section>
  );
}

function SessionTimelineDetail({ text }: { text: string }) {
  const changes = /^(\+\d+)\s+(−\d+)(.*)$/.exec(text);
  if (changes === null) return <span>{text}</span>;
  return (
    <span className="changes">
      <b className="added">{changes[1]}</b>{' '}
      <b className="removed">{changes[2]}</b>{changes[3]}
    </span>
  );
}

function reviewTargetHref(target: WorkspaceReviewSessionTarget): string {
  return `#/workspace/${encodeURIComponent(target.workspaceId)}/prs${
    target.prNumber === null ? '' : `/${target.prNumber}`
  }?prId=${encodeURIComponent(target.prId)}&agent=1`;
}

function derivedTimeline(session: WorkspaceSessionDto): NonNullable<WorkspaceSessionDto['timeline']> {
  const rows: NonNullable<WorkspaceSessionDto['timeline']> = [{
    id: `${session.id}-started`,
    title: session.launchInput === null ? 'Session started' : 'Launch request received',
    detail: session.launchInput,
    timeLabel: formatClock(session.startedAt),
    status: 'done',
  }];
  rows.push({
    id: `${session.id}-current`,
    title: session.headline ?? `${capitalize(session.kind)} agent`,
    detail: session.pauseReason ?? (session.outcome === null
      ? `Step ${Math.max(1, session.stepCursor)}`
      : session.outcome),
    timeLabel: session.finishedAt === null ? 'now' : formatClock(session.finishedAt),
    status: session.status === 'errored'
      ? 'errored'
      : session.status === 'done'
        ? 'done'
        : session.status === 'running'
          ? 'running'
          : 'pending',
  });
  return rows;
}

function defaultPhases(kind: WorkspaceSessionDto['kind']): string[] {
  if (kind === 'dev' || kind === 'ci-fix') return ['plan', 'scaffold', 'tests', 'impl', 'CI', 'summary'];
  if (kind === 'review') return ['context', 'diff', 'review', 'drafts', 'summary'];
  return ['context', 'plan', 'approval', 'summary'];
}

function taskNumberFromId(taskId: string | null): number | null {
  if (taskId === null) return null;
  const match = /\d+/.exec(taskId);
  return match === null ? null : Number(match[0]);
}

function CheckIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

function ChevronLeftIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="m15 18-6-6 6-6" />
    </svg>
  );
}

function TrunkIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="12" r="2.4" />
      <path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" />
    </svg>
  );
}

function capitalize(value: string): string {
  return value.length === 0 ? value : `${value[0].toUpperCase()}${value.slice(1)}`;
}

function compactTokens(tokens: number): string {
  if (tokens < 1_000) return String(tokens);
  const thousands = tokens / 1_000;
  return `${Number.isInteger(thousands) ? thousands : thousands.toFixed(1)}k`;
}

function formatClock(epochMs: number): string {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(epochMs));
}

function providerMonogram(provider: string | null): string {
  const normalized = provider?.toLowerCase() ?? '';
  if (normalized.includes('claude') || normalized.includes('anthropic')) return 'C';
  if (normalized.includes('openai') || normalized.includes('gpt')) return 'G';
  if (normalized.includes('local')) return 'L';
  return provider?.[0]?.toUpperCase() ?? 'A';
}

function providerName(provider: string | null): string {
  const normalized = provider?.toLowerCase() ?? '';
  if (normalized.includes('claude') || normalized.includes('anthropic')) return 'Claude';
  if (normalized.includes('openai') || normalized.includes('gpt')) return 'GPT';
  if (normalized.includes('local')) return 'Local';
  return provider ?? 'Agent';
}

function providerTone(provider: string | null): string {
  const normalized = provider?.toLowerCase() ?? '';
  if (normalized.includes('claude') || normalized.includes('anthropic')) return 'claude';
  if (normalized.includes('openai') || normalized.includes('gpt')) return 'openai';
  return 'local';
}

function elapsed(session: WorkspaceSessionDto): string {
  const minutes = Math.max(0, Math.floor(session.durationMs / 60_000));
  return minutes < 60 ? `${minutes}m` : `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

function shortModel(session: WorkspaceSessionDto): string {
  if (session.kind === 'ci-fix') return 'sonnet';
  const model = session.model ?? session.provider ?? 'agent';
  if (model.includes('sonnet')) return 'sonnet';
  if (model.includes('opus')) return 'opus';
  return model;
}

function relativeFinished(session: WorkspaceSessionDto): string {
  if (session.finishedAt === null) return '';
  const hours = Math.max(1, Math.round((Date.now() - session.finishedAt) / 3_600_000));
  return `${hours}h`;
}

function compareSessions(left: WorkspaceSessionDto, right: WorkspaceSessionDto): number {
  const rank: Record<WorkspaceSessionDto['status'], number> = {
    running: 0,
    queued: 1,
    paused: 2,
    done: 3,
    errored: 4,
  };
  const byStatus = rank[left.status] - rank[right.status];
  return byStatus === 0 ? right.startedAt - left.startedAt : byStatus;
}

function ClockIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 8v4l2.5 2" />
    </svg>
  );
}

function FailureIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}

function message(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}
