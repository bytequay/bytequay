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
import { useEffect, useMemo, useRef, useState } from 'react';
import type {
  AgentProviderStatus,
  CherryPickExecution,
  CherryPickExecutionItem,
  CherryPickPreview,
  CherryPickPreviewCommit,
} from '../types';
import {
  completedExecutionItems,
  countCommits,
  executionHeadline,
  executionResultLabel,
  groupCommits,
  groupSelection,
  isSkippable,
  matchesSearch,
  retainSelections,
  skippedShasForConfirmation,
  toggleCommit,
  toggleGroup,
} from './model';

const CONFLICT_LABELS: Record<string, string> = {
  CLEAN: 'No conflict',
  CONFLICT: 'Conflicts',
  NOT_ANALYZED: 'Not analyzed',
};

const ACTIVE_EXECUTION_KEY = 'bytequay.activeCherryPickExecution';

type StoredExecution = {
  executionId: string;
  repository: string;
};

function readStoredExecution(): StoredExecution | null {
  try {
    const value = JSON.parse(localStorage.getItem(ACTIVE_EXECUTION_KEY) ?? 'null') as unknown;
    if (typeof value !== 'object' || value === null) return null;
    const candidate = value as Partial<StoredExecution>;
    return typeof candidate.executionId === 'string' && typeof candidate.repository === 'string'
      ? { executionId: candidate.executionId, repository: candidate.repository }
      : null;
  } catch {
    return null;
  }
}

function providerName(laneId: string): string {
  if (laneId === 'claude') return 'Claude';
  if (laneId === 'codex') return 'Codex';
  return laneId;
}

function shortSha(sha: string): string {
  return sha.slice(0, 12);
}

/** Tri-state needs the DOM property; React only renders `checked`. */
function TriStateCheckbox({
  state,
  disabled,
  label,
  onChange,
}: {
  state: 'none' | 'some' | 'all';
  disabled?: boolean;
  label: string;
  onChange: () => void;
}) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) {
      ref.current.indeterminate = state === 'some';
    }
  }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      checked={state === 'all'}
      disabled={disabled}
      aria-label={label}
      onChange={onChange}
    />
  );
}

export default function CherryPickRangeScreen() {
  const [repository, setRepository] = useState('');
  const [sourceRemote, setSourceRemote] = useState('origin');
  const [sourceBaseRef, setSourceBaseRef] = useState('');
  const [sourceTipRef, setSourceTipRef] = useState('');
  const [targetRemote, setTargetRemote] = useState('origin');
  const [targetBranch, setTargetBranch] = useState('master');
  const [worktreeParent, setWorktreeParent] = useState('');

  const [preview, setPreview] = useState<CherryPickPreview | null>(null);
  const [previewRepository, setPreviewRepository] = useState('');
  const [skipped, setSkipped] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [providers, setProviders] = useState<AgentProviderStatus[]>([]);
  const [providersLoaded, setProvidersLoaded] = useState(false);
  const [laneId, setLaneId] = useState('');
  const [providerError, setProviderError] = useState<string | null>(null);
  const [execution, setExecution] = useState<CherryPickExecution | null>(null);
  const [executionRepository, setExecutionRepository] = useState('');
  const [message, setMessage] = useState('');
  const [actionBusy, setActionBusy] = useState(false);
  const idempotencyKey = useRef<string | null>(null);

  const groups = useMemo(() => (preview ? groupCommits(preview.commits) : []), [preview]);
  const counts = useMemo(
    () => countCommits(preview?.commits ?? [], skipped),
    [preview, skipped],
  );

  useEffect(() => {
    let active = true;
    void window.bridge.getAgentProviderStatuses()
      .then(statuses => {
        if (!active) return;
        setProviders(statuses);
        const preferred = statuses.find(status => status.health !== 'UNAVAILABLE') ?? statuses[0];
        setLaneId(current => current || preferred?.laneId || '');
      })
      .catch(failure => {
        if (active) setProviderError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => {
        if (active) setProvidersLoaded(true);
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const stored = readStoredExecution();
    if (!stored) return;
    let active = true;
    void window.bridge.getCherryPickExecution(stored.executionId)
      .then(value => {
        if (!active) return;
        setExecution(value);
        setExecutionRepository(stored.repository);
      })
      .catch(() => localStorage.removeItem(ACTIVE_EXECUTION_KEY));
    return () => { active = false; };
  }, []);

  const executionId = execution?.executionId;
  const executionStatus = execution?.status;
  useEffect(() => {
    if (!executionId || executionStatus !== 'RUNNING') return;
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
    const timer = window.setInterval(() => { void refresh(); }, 1500);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [executionId, executionStatus]);

  async function runPreview() {
    setBusy(true);
    setError(null);
    try {
      const next = await window.bridge.previewCherryPickRange({
        repository,
        sourceRemote,
        sourceBaseRef,
        sourceTipRef,
        targetRemote,
        targetBranch,
      });
      // Selections survive a re-preview when the commits do; the rest cannot be
      // carried over silently.
      setSkipped(current => retainSelections(next, current));
      setPreview(next);
      setPreviewRepository(repository.trim());
      setConfirming(false);
      idempotencyKey.current = null;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  }

  async function chooseRepository() {
    const chosen = await window.bridge.chooseDirectory('Choose the repository clone');
    if (chosen) {
      setRepository(chosen);
    }
  }

  async function chooseWorktreeParent() {
    const chosen = await window.bridge.chooseDirectory('Choose where ByteQuay should create the work branch');
    if (chosen) setWorktreeParent(chosen);
  }

  async function startExecution() {
    if (!preview) return;
    setActionBusy(true);
    setError(null);
    try {
      idempotencyKey.current ??= crypto.randomUUID();
      const next = await window.bridge.startCherryPickExecution({
        repository: previewRepository,
        worktreeParent: worktreeParent.trim(),
        previewId: preview.previewId,
        expectedSourceBaseSha: preview.sourceBaseSha,
        expectedSourceTipSha: preview.sourceTipSha,
        expectedTargetSha: preview.targetSha,
        userSkippedSourceShas: skippedShasForConfirmation(preview, skipped),
        laneId,
        idempotencyKey: idempotencyKey.current,
      });
      setExecution(next);
      setExecutionRepository(previewRepository);
      localStorage.setItem(ACTIVE_EXECUTION_KEY, JSON.stringify({
        executionId: next.executionId,
        repository: previewRepository,
      } satisfies StoredExecution));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setActionBusy(false);
    }
  }

  async function sendMessage() {
    if (!execution || !message.trim()) return;
    setActionBusy(true);
    setError(null);
    try {
      setExecution(await window.bridge.sendCherryPickMessage(execution.executionId, message.trim()));
      setMessage('');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setActionBusy(false);
    }
  }

  async function cancelExecution() {
    if (!execution || !executionRepository) return;
    if (!window.confirm('Cancel this cherry-pick and remove its generated work branch?')) return;
    setActionBusy(true);
    setError(null);
    try {
      setExecution(await window.bridge.cancelCherryPickExecution(
        execution.executionId,
        executionRepository,
      ));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setActionBusy(false);
    }
  }

  function startNewRange() {
    localStorage.removeItem(ACTIVE_EXECUTION_KEY);
    setExecution(null);
    setExecutionRepository('');
    setPreview(null);
    setSkipped(new Set());
    setConfirming(false);
    setMessage('');
    setError(null);
    idempotencyKey.current = null;
  }

  function toggleExpanded(sha: string) {
    setExpanded(current => {
      const next = new Set(current);
      if (next.has(sha)) {
        next.delete(sha);
      } else {
        next.add(sha);
      }
      return next;
    });
  }

  const canPreview = repository.trim() !== ''
    && sourceBaseRef.trim() !== ''
    && sourceTipRef.trim() !== ''
    && targetBranch.trim() !== '';
  const selectedProvider = providers.find(provider => provider.laneId === laneId);
  const canStart = preview !== null
    && worktreeParent.trim() !== ''
    && selectedProvider !== undefined
    && selectedProvider.health !== 'UNAVAILABLE';

  if (execution) {
    return (
      <ExecutionProgress
        execution={execution}
        repositoryAvailable={executionRepository !== ''}
        message={message}
        busy={actionBusy}
        error={error}
        onMessageChange={setMessage}
        onSendMessage={() => { void sendMessage(); }}
        onCancel={() => { void cancelExecution(); }}
        onNewRange={startNewRange}
      />
    );
  }

  return (
    <div className="cherry-pick">
      <div className="cherry-pick-form">
        <div className="cherry-pick-field">
          <label htmlFor="cp-repository">Repository clone</label>
          <div className="cherry-pick-field-path">
            <input
              id="cp-repository"
              value={repository}
              placeholder="/Users/you/IdeaProjects/trino"
              onChange={event => setRepository(event.target.value)}
            />
            <button type="button" onClick={chooseRepository}>Choose…</button>
          </div>
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-source-remote">Source remote</label>
          <input
            id="cp-source-remote"
            value={sourceRemote}
            onChange={event => setSourceRemote(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-base">Range from (exclusive)</label>
          <input
            id="cp-base"
            value={sourceBaseRef}
            placeholder="477"
            onChange={event => setSourceBaseRef(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-tip">Range to (inclusive)</label>
          <input
            id="cp-tip"
            value={sourceTipRef}
            placeholder="478"
            onChange={event => setSourceTipRef(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-target-remote">Target remote</label>
          <input
            id="cp-target-remote"
            value={targetRemote}
            onChange={event => setTargetRemote(event.target.value)}
          />
        </div>
        <div className="cherry-pick-field">
          <label htmlFor="cp-target-branch">Target branch</label>
          <input
            id="cp-target-branch"
            value={targetBranch}
            onChange={event => setTargetBranch(event.target.value)}
          />
        </div>
      </div>

      <div className="cherry-pick-actions">
        <button type="button" disabled={!canPreview || busy} onClick={runPreview}>
          {busy ? 'Preparing…' : 'Preview range'}
        </button>
        {busy && (
          <span className="cherry-pick-group-meta">
            Synchronizing {targetRemote}/{targetBranch}. This is the only step that uses the network.
          </span>
        )}
      </div>

      {error && <div className="cherry-pick-error" role="alert">{error}</div>}

      {preview && (
        <>
          <div className="cherry-pick-frozen">
            <span>{preview.repository}</span>
            <span>base {shortSha(preview.sourceBaseSha)}</span>
            <span>tip {shortSha(preview.sourceTipSha)}</span>
            <span>target {preview.localTargetBranch} @ {shortSha(preview.targetSha)}</span>
          </div>

          {preview.warnings.map(warning => (
            <div className="cherry-pick-warning" key={warning}>{warning}</div>
          ))}

          <div className="cherry-pick-counts">
            <span><strong>{counts.total}</strong> source commits</span>
            <span><strong>{counts.alreadyOnTarget}</strong> already on target</span>
            <span><strong>{counts.userSkipped}</strong> skipped by you</span>
            <span><strong>{counts.toCherryPick}</strong> will be cherry-picked</span>
          </div>

          <div className="cherry-pick-field">
            <label htmlFor="cp-search">Search</label>
            <input
              id="cp-search"
              value={search}
              placeholder="pull request number, title, or commit SHA"
              onChange={event => setSearch(event.target.value)}
            />
          </div>

          <div className="cherry-pick-groups">
            {groups.map(group => {
              const visible = group.commits.filter(commit => matchesSearch(commit, search));
              if (visible.length === 0) {
                return null;
              }
              const eligible = group.commits.filter(isSkippable);
              const state = groupSelection(group, skipped);
              const presentCount = group.commits.length - eligible.length;
              return (
                <div className="cherry-pick-group" key={group.key ?? group.commits[0].sourceSha}>
                  <div className="cherry-pick-group-head">
                    <TriStateCheckbox
                      state={state}
                      disabled={eligible.length === 0}
                      label={group.key === null
                        ? `Skip commit ${group.title}`
                        : `Skip pull request ${group.key}`}
                      onChange={() => setSkipped(current => toggleGroup(group, current))}
                    />
                    <span className="cherry-pick-group-title">
                      {group.key === null ? group.title : `#${group.key}  ${group.title}`}
                    </span>
                    <span className="cherry-pick-group-meta">
                      {group.mergedAt ? `Merged ${group.mergedAt.slice(0, 10)} · ` : ''}
                      {group.commits.length} commit{group.commits.length === 1 ? '' : 's'}
                      {presentCount > 0 ? ` · ${presentCount} already on target` : ''}
                    </span>
                  </div>
                  <div className="cherry-pick-commits">
                    {visible.map(commit => (
                      <CommitRow
                        key={commit.sourceSha}
                        commit={commit}
                        skipped={skipped.has(commit.sourceSha)}
                        expanded={expanded.has(commit.sourceSha)}
                        onToggleSkip={() => setSkipped(current => toggleCommit(commit, current))}
                        onToggleExpanded={() => toggleExpanded(commit.sourceSha)}
                      />
                    ))}
                  </div>
                </div>
              );
            })}
          </div>

          <div className="cherry-pick-actions">
            <button
              type="button"
              disabled={counts.toCherryPick === 0}
              onClick={() => setConfirming(true)}
            >
              Review {counts.toCherryPick} commit{counts.toCherryPick === 1 ? '' : 's'}…
            </button>
          </div>

          {confirming && (
            <div className="cherry-pick-warning">
              <p>
                <strong>{counts.toCherryPick}</strong> commits would be cherry-picked onto a new work
                branch from {preview.localTargetBranch} at {shortSha(preview.targetSha)},
                skipping <strong>{counts.userSkipped}</strong> you chose
                and <strong>{counts.alreadyOnTarget}</strong> already present.
              </p>
              <p className="cherry-pick-group-meta">
                Confirmation sends these exact commit IDs:{' '}
                {skippedShasForConfirmation(preview, skipped).map(shortSha).join(', ') || 'none'}
              </p>
              <div className="cherry-pick-confirm-fields">
                <div className="cherry-pick-field">
                  <label htmlFor="cp-worktree-parent">Work branch folder</label>
                  <div className="cherry-pick-field-path">
                    <input
                      id="cp-worktree-parent"
                      value={worktreeParent}
                      placeholder="/Users/you/IdeaProjects/worktrees"
                      onChange={event => setWorktreeParent(event.target.value)}
                    />
                    <button type="button" onClick={() => { void chooseWorktreeParent(); }}>Choose…</button>
                  </div>
                </div>
                <div className="cherry-pick-field">
                  <label htmlFor="cp-provider">Conflict resolver</label>
                  <select id="cp-provider" value={laneId} onChange={event => setLaneId(event.target.value)}>
                    {providers.map(provider => (
                      <option key={provider.laneId} value={provider.laneId}>
                        {providerName(provider.laneId)}
                        {provider.health === 'UNAVAILABLE' ? ' · unavailable' : ''}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              {selectedProvider && (
                <p className="cherry-pick-group-meta">
                  {providerName(selectedProvider.laneId)} {selectedProvider.version ?? ''}
                  {' · '}{selectedProvider.diagnostic}
                </p>
              )}
              {providersLoaded && providers.length === 0 && !providerError && (
                <p className="cherry-pick-error" role="alert">
                  No conflict resolver is configured.
                </p>
              )}
              {!providersLoaded && !providerError && (
                <p className="cherry-pick-group-meta">Checking available conflict resolvers…</p>
              )}
              {providerError && <p className="cherry-pick-error" role="alert">{providerError}</p>}
              <div className="cherry-pick-actions">
                <button type="button" disabled={!canStart || actionBusy} onClick={() => { void startExecution(); }}>
                  {actionBusy ? 'Starting…' : 'Start cherry-pick'}
                </button>
                <button type="button" disabled={actionBusy} onClick={() => setConfirming(false)}>Back</button>
              </div>
            </div>
          )}
        </>
      )}

      {preview && preview.commits.length === 0 && (
        <div className="cherry-pick-empty">This range contains no commits.</div>
      )}
    </div>
  );
}

function ExecutionProgress({
  execution,
  repositoryAvailable,
  message,
  busy,
  error,
  onMessageChange,
  onSendMessage,
  onCancel,
  onNewRange,
}: {
  execution: CherryPickExecution;
  repositoryAvailable: boolean;
  message: string;
  busy: boolean;
  error: string | null;
  onMessageChange: (value: string) => void;
  onSendMessage: () => void;
  onCancel: () => void;
  onNewRange: () => void;
}) {
  const completed = completedExecutionItems(execution.items);
  const active = execution.status !== 'FINISHED';
  const statusLabel = execution.status === 'RUNNING'
    ? 'In progress'
    : execution.status === 'PAUSED'
      ? 'Needs attention'
      : execution.finishReason === 'CANCELLED' ? 'Cancelled' : 'Done';

  return (
    <div className="cherry-pick">
      <div className="cherry-pick-execution-head">
        <div>
          <h2>{executionHeadline(execution)}</h2>
          <p className="cherry-pick-group-meta">
            {execution.repository} · {execution.workBranch} from {execution.localTargetBranch}
          </p>
        </div>
        <span className={`cherry-pick-status cherry-pick-status-${execution.status.toLowerCase()}`}>
          {statusLabel}
        </span>
      </div>

      <progress
        className="cherry-pick-progress"
        max={Math.max(execution.items.length, 1)}
        value={completed}
      />
      <div className="cherry-pick-counts">
        <span><strong>{completed}</strong> finished</span>
        <span><strong>{execution.items.length - completed}</strong> remaining</span>
        <span>HEAD <strong className="cherry-pick-sha">{shortSha(execution.currentTargetSha)}</strong></span>
      </div>

      {error && <div className="cherry-pick-error" role="alert">{error}</div>}

      {execution.status === 'PAUSED' && (
        <div className="cherry-pick-warning">
          <p>{execution.summary ?? 'ByteQuay paused before changing any more commits.'}</p>
          <div className="cherry-pick-field">
            <label htmlFor="cp-retry-message">Note for the conflict resolver</label>
            <textarea
              id="cp-retry-message"
              value={message}
              maxLength={4000}
              placeholder="Explain what should be checked or changed before retrying."
              onChange={event => onMessageChange(event.target.value)}
            />
          </div>
          <div className="cherry-pick-actions">
            <button type="button" disabled={busy || message.trim() === ''} onClick={onSendMessage}>
              {busy ? 'Retrying…' : 'Send note and retry'}
            </button>
          </div>
        </div>
      )}

      {execution.status === 'FINISHED' && execution.summary && (
        <div className="cherry-pick-warning">{execution.summary}</div>
      )}

      <div className="cherry-pick-execution-list">
        {execution.items.map(item => (
          <ExecutionItemRow
            key={item.sourceSha}
            item={item}
            current={item.position === execution.currentPosition && item.result === 'PENDING'}
          />
        ))}
      </div>

      <div className="cherry-pick-actions">
        {active && repositoryAvailable && (
          <button type="button" disabled={busy} onClick={onCancel}>Cancel cherry-pick</button>
        )}
        {execution.status === 'FINISHED' && (
          <button type="button" onClick={onNewRange}>Start another range</button>
        )}
      </div>
    </div>
  );
}

function ExecutionItemRow({
  item,
  current,
}: {
  item: CherryPickExecutionItem;
  current: boolean;
}) {
  return (
    <div className={`cherry-pick-execution-row${current ? ' is-current' : ''}`}>
      <span className="cherry-pick-position">{item.position}</span>
      <span className="cherry-pick-sha">{shortSha(item.sourceSha)}</span>
      <span className="cherry-pick-commit-title">{item.title}</span>
      {item.pullRequestNumber && (
        <span className="cherry-pick-group-meta">#{item.pullRequestNumber}</span>
      )}
      <span className={`cherry-pick-result cherry-pick-result-${item.result.toLowerCase()}`}>
        {executionResultLabel(item, current ? item.position : null)}
      </span>
      {item.targetSha && (
        <span className="cherry-pick-sha">→ {shortSha(item.targetSha)}</span>
      )}
    </div>
  );
}

function CommitRow({
  commit,
  skipped,
  expanded,
  onToggleSkip,
  onToggleExpanded,
}: {
  commit: CherryPickPreviewCommit;
  skipped: boolean;
  expanded: boolean;
  onToggleSkip: () => void;
  onToggleExpanded: () => void;
}) {
  const conflicted = commit.conflictStatus === 'CONFLICT';
  return (
    <>
      <div className="cherry-pick-commit">
        <input
          type="checkbox"
          checked={skipped}
          disabled={commit.alreadyOnTarget}
          aria-label={`Skip commit ${shortSha(commit.sourceSha)}`}
          onChange={onToggleSkip}
        />
        <span className="cherry-pick-position">{commit.position}</span>
        <span className="cherry-pick-sha">{shortSha(commit.sourceSha)}</span>
        <span className="cherry-pick-commit-title">{commit.title}</span>
        {commit.alreadyOnTarget && (
          <span className="cherry-pick-tag cherry-pick-tag-present">
            {commit.targetMatchKind === 'DIRECT_ANCESTOR' ? 'Already on target' : 'Cherry-picked'}
            {commit.targetMatchCommitSha ? ` · ${shortSha(commit.targetMatchCommitSha)}` : ''}
          </span>
        )}
        {commit.originallyEmpty && <span className="cherry-pick-tag">Empty upstream</span>}
        {commit.patchEquivalent && <span className="cherry-pick-tag">Looks applied</span>}
        {!commit.alreadyOnTarget && (
          <button
            type="button"
            className={`cherry-pick-tag${conflicted ? ' cherry-pick-tag-conflict' : ''}`}
            aria-expanded={expanded}
            onClick={onToggleExpanded}
          >
            {CONFLICT_LABELS[commit.conflictStatus] ?? commit.conflictStatus}
            {conflicted ? ` · ${commit.conflictFiles.length}` : ''}
          </button>
        )}
      </div>
      {expanded && (
        <div className="cherry-pick-conflict-detail">
          {commit.conflictStatus === 'NOT_ANALYZED' && (
            <span>
              Not analyzed: an earlier commit conflicts, and the resolution you choose decides what
              this one meets.
            </span>
          )}
          {commit.conflictStatus === 'CLEAN' && <span>Applies cleanly after the commits before it.</span>}
          {conflicted && (
            <ul>
              {commit.conflictFiles.map(file => (
                <li key={file.path}>
                  {file.path} · {file.type}
                  {file.module ? ` · ${file.module}` : ''}
                  {file.sections.length > 0
                    ? ` · lines ${file.sections.map(([from, to]) => `${from}-${to}`).join(', ')}`
                    : ''}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </>
  );
}
