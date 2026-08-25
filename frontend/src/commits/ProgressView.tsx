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
import { useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react';
import type {
  CherryPickExecution,
  CherryPickExecutionItem,
  PullRequestDetailDto,
  WorkspaceDeliveryStatus,
} from '../types';
import PullOverview from '../pulls/PullOverview';
import { remotePullRequest } from '../pulls/PullsScreen';
import { completedExecutionItems, foldNav, shortSha, taskState } from './model';

type ItemView = {
  item: CherryPickExecutionItem;
  /** applied | empty | already | user | resolving | paused | pending */
  kind: string;
  sub: string;
};

function itemView(
  item: CherryPickExecutionItem,
  execution: CherryPickExecution,
): ItemView {
  const current = execution.currentPosition === item.position && item.result === 'PENDING';
  if (item.result === 'APPLIED') {
    return { item, kind: 'applied', sub: `Applied → ${item.targetSha ? shortSha(item.targetSha) : ''}` };
  }
  if (item.result === 'EMPTY_SKIPPED') return { item, kind: 'empty', sub: 'Empty — skipped' };
  if (item.result === 'ALREADY_ON_TARGET') return { item, kind: 'already', sub: 'Already on target' };
  if (item.result === 'USER_SKIPPED') return { item, kind: 'user', sub: 'Skipped by you' };
  if (current && execution.status === 'PAUSED') {
    return { item, kind: 'paused', sub: 'Paused — needs your input' };
  }
  if (current && execution.currentStep === 'RESOLVING') {
    return { item, kind: 'resolving', sub: 'Resolving conflict — agent turn' };
  }
  if (current) return { item, kind: 'resolving', sub: 'Applying…' };
  return { item, kind: 'pending', sub: 'Pending' };
}

export default function ProgressView({
  executionId,
  workspaceId,
  upstreamSlug,
  onBackToTasks,
  onStartAnotherRange,
}: {
  executionId: string;
  workspaceId: string;
  /** "owner/repo" of the linked upstream, for the PR panel. */
  upstreamSlug: string | null;
  onBackToTasks: () => void;
  onStartAnotherRange: () => void;
}) {
  const [execution, setExecution] = useState<CherryPickExecution | null>(null);
  const [selectedSha, setSelectedSha] = useState<string | null>(null);
  const [openCards, setOpenCards] = useState<Record<string, boolean>>({});
  const [navExpanded, setNavExpanded] = useState(false);
  const [message, setMessage] = useState('');
  const [sentNotes, setSentNotes] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [delivery, setDelivery] = useState<WorkspaceDeliveryStatus | null>(null);
  const [deliveryBusy, setDeliveryBusy] = useState(false);
  const [leftWidth, setLeftWidth] = useState(260);
  const [rightWidth, setRightWidth] = useState(380);

  const status = execution?.status;
  useEffect(() => {
    let active = true;
    let polling = false;
    const refresh = async () => {
      if (polling) return;
      polling = true;
      try {
        const value = await window.bridge.getCherryPickExecution(executionId);
        if (active) setExecution(value);
      } catch (failure) {
        if (active) setError(failure instanceof Error ? failure.message : String(failure));
      } finally {
        polling = false;
      }
    };
    void refresh();
    if (status !== 'RUNNING' && status !== undefined) return () => { active = false; };
    const timer = window.setInterval(() => { void refresh(); }, 1500);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [executionId, status]);

  useEffect(() => {
    if (execution?.status !== 'FINISHED' || execution.finishReason !== 'COMPLETED') return;
    let active = true;
    window.bridge.getWorkspaceDeliveryStatus(workspaceId, execution.executionId)
      .then(value => { if (active) setDelivery(value); })
      .catch(failure => {
        if (active) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => { active = false; };
  }, [execution?.executionId, execution?.finishReason, execution?.status, workspaceId]);

  const views = useMemo(
    () => (execution ? execution.items.map(item => itemView(item, execution)) : []),
    [execution],
  );
  const activeIndex = views.findIndex(view =>
    view.kind === 'resolving' || view.kind === 'paused' || view.kind === 'pending');
  const navRows = foldNav(views, activeIndex, navExpanded);
  const settled = execution ? completedExecutionItems(execution.items) : 0;

  const selected = views.find(view => view.item.sourceSha === selectedSha)
    ?? views[activeIndex >= 0 ? activeIndex : views.length - 1]
    ?? null;

  const drag = (side: 'left' | 'right') => (event: ReactMouseEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = side === 'left' ? leftWidth : rightWidth;
    const move = (moveEvent: MouseEvent) => {
      const delta = (moveEvent.clientX - startX) * (side === 'left' ? 1 : -1);
      const next = Math.max(side === 'left' ? 180 : 280, Math.min(side === 'left' ? 420 : 560, startWidth + delta));
      if (side === 'left') setLeftWidth(next);
      else setRightWidth(next);
    };
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };

  const send = async () => {
    if (!execution || !message.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const next = await window.bridge.sendCherryPickMessage(execution.executionId, message.trim());
      setExecution(next);
      setSentNotes(current => [...current, message.trim()]);
      setMessage('');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    if (!execution) return;
    if (!window.confirm('Cancel this cherry-pick and remove its generated work branch?')) return;
    setBusy(true);
    try {
      setExecution(await window.bridge.cancelCherryPickExecution(workspaceId, execution.executionId));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const refreshDelivery = async () => {
    if (!execution) return;
    setDelivery(await window.bridge.getWorkspaceDeliveryStatus(workspaceId, execution.executionId));
  };

  const push = async () => {
    if (!execution || !delivery) return;
    if (!window.confirm(
      `Push ${execution.workBranch} to origin? This writes a new remote branch and never force-pushes.`,
    )) return;
    setDeliveryBusy(true);
    setError(null);
    try {
      await window.bridge.pushWorkspaceExecution(workspaceId, execution.executionId);
      await refreshDelivery();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setDeliveryBusy(false);
    }
  };

  const createPullRequest = async () => {
    if (!execution || !delivery) return;
    const base = `${delivery.baseRepository.owner}/${delivery.baseRepository.repo}:${delivery.baseBranch}`;
    if (!window.confirm(
      `Create a pull request from ${delivery.head} into ${base}? This is a separate GitHub write.`,
    )) return;
    setDeliveryBusy(true);
    setError(null);
    try {
      await window.bridge.createWorkspacePullRequest(workspaceId, execution.executionId);
      await refreshDelivery();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setDeliveryBusy(false);
    }
  };

  const openPullRequest = async () => {
    const url = delivery?.pullRequest?.url;
    if (url) await window.bridge.openExternal(url);
  };

  if (!execution) {
    return <div className="cmt-loading">{error ?? 'Loading execution…'}</div>;
  }

  const state = taskState(execution);
  const pill = state === 'running'
    ? { text: 'RUNNING', className: 'is-running' }
    : state === 'paused'
      ? { text: 'PAUSED', className: 'is-paused' }
      : { text: 'FINISHED', className: 'is-finished' };
  const stepLabel = state === 'running'
    ? execution.currentPosition
      ? `step · ${execution.currentStep ?? 'APPLYING'} — item ${execution.currentPosition} of ${execution.items.length}`
      : 'running'
    : state === 'paused'
      ? `step · paused on item ${execution.currentPosition ?? '?'}`
      : 'all items settled';

  const applied = execution.items.filter(item => item.result === 'APPLIED').length;
  const userSkipped = execution.items.filter(item => item.result === 'USER_SKIPPED').length;
  const already = execution.items.filter(item => item.result === 'ALREADY_ON_TARGET').length;
  const empty = execution.items.filter(item => item.result === 'EMPTY_SKIPPED').length;

  const active = state === 'running' || state === 'paused';

  return (
    <div
      className="cmt-progress"
      style={{ gridTemplateColumns: `${leftWidth}px 5px minmax(480px, 1fr) 5px ${rightWidth}px` }}
    >
      <div className="cmt-prog-nav">
        <div className="cmt-prog-nav-head">
          <div className="cmt-prog-nav-title">Range items</div>
          <div className="cmt-prog-nav-sub">
            {settled} of {execution.items.length} settled · execution {execution.executionId.slice(0, 8)}
          </div>
          <div className="cmt-prog-bar">
            <div style={{ width: `${Math.round((settled / Math.max(1, execution.items.length)) * 100)}%` }} />
          </div>
        </div>
        <div className="cmt-prog-nav-list">
          {navRows.map((row, index) => row.kind === 'fold' ? (
            <button
              type="button"
              key={`fold-${index}`}
              className="cmt-prog-fold"
              onClick={() => setNavExpanded(row.expand)}
            >
              <span>{row.expand ? '▸' : '▾'}</span> {row.label}
            </button>
          ) : (
            <button
              type="button"
              key={row.value.item.sourceSha}
              className={`cmt-prog-item${selected?.item.sourceSha === row.value.item.sourceSha ? ' is-active' : ''}`}
              onClick={() => setSelectedSha(row.value.item.sourceSha)}
            >
              <span className={`cmt-status-dot is-${row.value.kind}`} />
              <span className="cmt-prog-item-main">
                <span className="cmt-prog-item-title">
                  {row.value.item.position} · {row.value.item.title}
                </span>
                <span className={`cmt-prog-item-sub is-${row.value.kind}`}>{row.value.sub}</span>
              </span>
            </button>
          ))}
        </div>
      </div>

      <div className="cmt-prog-divider" onMouseDown={drag('left')} title="Drag to resize" />

      <div className="cmt-prog-center">
        <div className="cmt-prog-head">
          <button type="button" className="cmt-prog-back" onClick={onBackToTasks}>‹ Tasks</button>
          <span className={`cmt-prog-pill ${pill.className}`}>{pill.text}</span>
          <span className="cmt-prog-step">{stepLabel}</span>
          <span className="cmt-prog-branch">{execution.workBranch}</span>
          <div className="cmt-tasks-spacer" />
          {active && (
            <button
              type="button"
              className="cmt-btn-danger"
              disabled={busy}
              onClick={() => { void cancel(); }}
            >
              Cancel execution
            </button>
          )}
        </div>

        {state === 'paused' && (
          <div className="cmt-banner is-paused">
            <div className="cmt-banner-title">Paused — needs your input</div>
            <div className="cmt-banner-text">
              {execution.summary ?? 'ByteQuay paused before changing any more commits.'}
              {' '}The worktree is preserved. Add a note for the agent and retry, or cancel.
            </div>
          </div>
        )}
        {state === 'done' && (
          <div className="cmt-banner is-finished">
            <div className="cmt-banner-main">
              <div className="cmt-banner-title">Finished — final accounting passed</div>
              <div className="cmt-banner-text">
                {applied} applied · {userSkipped} skipped by you · {already} already on target
                {empty > 0 ? ` · ${empty} empty` : ''} — every applied commit carries its -x line.
              </div>
            </div>
            <button type="button" onClick={onStartAnotherRange}>Start another range</button>
          </div>
        )}
        {state === 'done' && delivery && (
          <div className="cmt-delivery">
            <div className="cmt-delivery-copy">
              <strong>Deliver this generated branch</strong>
              <span>
                {delivery.pullRequest
                  ? `Pull request #${delivery.pullRequest.number} targets ${delivery.baseRepository.owner}/${delivery.baseRepository.repo}:${delivery.baseBranch}.`
                  : delivery.push
                    ? `${delivery.push.head} is pushed to origin. Create the pull request when you are ready.`
                    : delivery.disabledReason
                      ?? `Push to origin, then propose ${delivery.head} into ${delivery.baseBranch}.`}
              </span>
            </div>
            <div className="cmt-delivery-actions">
              {!delivery.push && (
                <button
                  type="button"
                  disabled={deliveryBusy || !delivery.pushWritable}
                  title={delivery.disabledReason ?? undefined}
                  onClick={() => { void push(); }}
                >
                  {deliveryBusy ? 'Pushing…' : 'Push branch'}
                </button>
              )}
              {delivery.push && !delivery.pullRequest && (
                <button
                  type="button"
                  disabled={deliveryBusy}
                  onClick={() => { void createPullRequest(); }}
                >
                  {deliveryBusy ? 'Creating…' : 'Create pull request'}
                </button>
              )}
              {delivery.pullRequest && (
                <button
                  type="button"
                  onClick={() => { void openPullRequest(); }}
                >
                  Open pull request ↗
                </button>
              )}
            </div>
          </div>
        )}
        {state === 'canceled' && (
          <div className="cmt-banner is-canceled">
            <div className="cmt-banner-main">
              <div className="cmt-banner-title">Canceled</div>
              <div className="cmt-banner-text">{execution.summary ?? 'This execution was canceled.'}</div>
            </div>
            <button type="button" onClick={onStartAnotherRange}>Start another range</button>
          </div>
        )}

        {error && <div className="cmt-error" role="alert">{error}</div>}

        <div className="cmt-prog-log">
          <div className="cmt-log-line">
            execution {execution.executionId.slice(0, 8)} · work branch {execution.workBranch} created
            at {shortSha(execution.initialTargetSha)} (frozen {execution.localTargetBranch})
          </div>
          {already > 0 && (
            <div className="cmt-log-line">
              {views.filter(view => view.kind === 'already')
                .map(view => `${view.item.position} · ${shortSha(view.item.sourceSha)}`)
                .join('      ')} already on target — Git never invoked
            </div>
          )}
          {userSkipped > 0 && (
            <div className="cmt-log-line">
              {views.filter(view => view.kind === 'user')
                .map(view => shortSha(view.item.sourceSha))
                .join(' ')} skipped by you at confirmation — transmitted as source SHAs
            </div>
          )}
          {views
            .filter(view => view.kind !== 'already' && view.kind !== 'user' && view.kind !== 'pending')
            .map(view => {
              const sha = view.item.sourceSha;
              const activeCard = view.kind === 'resolving' || view.kind === 'paused';
              const open = openCards[sha] ?? activeCard;
              return (
                <div key={sha} className="cmt-log-card">
                  <button
                    type="button"
                    className="cmt-log-card-head"
                    onClick={() => setOpenCards(current => ({ ...current, [sha]: !open }))}
                  >
                    <span className="cmt-log-chev">{open ? '▾' : '▸'}</span>
                    <span className={`cmt-status-dot is-${view.kind}`} />
                    <span className="cmt-log-card-title">
                      {view.item.position} · {shortSha(sha)} — {view.item.title}
                    </span>
                    <span className={`cmt-log-card-right is-${view.kind}`}>{view.sub}</span>
                  </button>
                  {open && (
                    <div className="cmt-log-card-body">
                      <div className="cmt-log-cmd">$ git cherry-pick -x {shortSha(sha)}</div>
                      {view.kind === 'applied' && (
                        <div className="cmt-log-ok">
                          recorded APPLIED · targetSha {view.item.targetSha ? shortSha(view.item.targetSha) : '?'}
                        </div>
                      )}
                      {view.kind === 'empty' && (
                        <div className="cmt-log-dim">
                          proven empty (change already on target) · recorded EMPTY_SKIPPED · no commit created
                        </div>
                      )}
                      {view.kind === 'resolving' && (
                        <div className="cmt-agent-turn">
                          <div className="cmt-agent-head">✦ Agent resolving this commit</div>
                          <div className="cmt-agent-tail">
                            <span className="cmt-spinner" />
                            waiting for the fixup commit — the service verifies parent, subject, and markers
                          </div>
                        </div>
                      )}
                      {view.kind === 'paused' && (
                        <div className="cmt-log-err">
                          {execution.pauseReason ?? 'PAUSED'} · execution paused · worktree preserved
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          {sentNotes.map((note, index) => (
            <div key={`note-${index}`}>
              <div className="cmt-user-bubble-row"><div className="cmt-user-bubble">{note}</div></div>
              <div className="cmt-log-line">
                note queued for the current agent turn — delivered as untrusted user context
              </div>
            </div>
          ))}
        </div>

        {active && (
          <div className="cmt-composer">
            <div className="cmt-composer-box">
              <input
                value={message}
                placeholder="Message the agent — e.g. keep our resolver retry property in pom.xml…"
                onChange={event => setMessage(event.target.value)}
                onKeyDown={event => { if (event.key === 'Enter') void send(); }}
              />
              <button
                type="button"
                aria-label="Send note"
                disabled={busy || !message.trim()}
                onClick={() => { void send(); }}
              >
                ↑
              </button>
            </div>
            <div className="cmt-composer-hint">
              Delivered to the current item's agent turn as an untrusted note — the service still
              verifies every commit.
            </div>
          </div>
        )}
      </div>

      <div className="cmt-prog-divider" onMouseDown={drag('right')} title="Drag to resize" />

      <div className="cmt-prog-pr">
        <PrPanel
          workspaceId={workspaceId}
          slug={upstreamSlug}
          number={delivery?.pullRequest?.number ?? selected?.item.pullRequestNumber ?? null}
        />
      </div>
    </div>
  );
}

function PrPanel({ workspaceId, slug, number }: { workspaceId: string; slug: string | null; number: number | null }) {
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDetail(null);
    setError(null);
    if (slug === null || number === null) return;
    let cancelled = false;
    window.bridge.fetchPullRequestDetailByRef(slug, number)
      .then(value => { if (!cancelled) setDetail(value); })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => { cancelled = true; };
  }, [slug, number]);

  if (number === null || slug === null) {
    return (
      <div className="cmt-pr-empty">
        <div>⑂</div>
        <div>No pull request is associated with this commit.</div>
        <small>It was pushed directly to the upstream branch.</small>
      </div>
    );
  }
  if (error) return <div className="cmt-error" role="alert">{error}</div>;
  if (!detail) return <div className="cmt-loading">Loading pull request #{number}…</div>;

  return (
    <div className="cmt-pr-overview">
      <PullOverview
        pr={remotePullRequest(detail)}
        detail={detail}
        contributorsWorkspaceId={workspaceId}
        onRefresh={async () => {
          setDetail(await window.bridge.refreshAllPullRequestDetailByRef(slug, number));
        }}
      />
      <div className="cmt-pr-footnote">
        Showing the pull request for the selected commit — pick another commit on the left to switch.
      </div>
    </div>
  );
}
