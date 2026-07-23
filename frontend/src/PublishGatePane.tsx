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
import { useState } from 'react';
import type {
  GenericParkedPayload,
  GenericPublishAction,
  NotificationDto,
  NextTaskParkedPayload,
  ParkedPublishPayload,
  PostCommentParkedPayload,
  PublishResultDto,
  PushParkedPayload,
  RequestReviewParkedPayload,
  ShipTaskParkedPayload,
} from './types';

type Props = {
  notification: NotificationDto;
  /** Called after the parked notification is resolved (approved or
   *  discarded) so the parent can refresh its list and collapse the
   *  expansion. Not called on an interrupted approval because the row
   *  remains available for local-only recovery. */
  onResolved: () => void;
  /** Open the PR this gate targets, when it has one. Renders a "View PR"
   *  button in the action row next to Approve. */
  onViewPr?: () => void;
  /** Title of the PR this gate targets, resolved from the cached PR list.
   *  Shown above the action review so the user sees what they're approving. */
  prTitle?: string;
  /** Overrides the approve button's label (e.g. "Merge when ready" when the
   *  target branch runs a merge queue). Falls back to the per-action label. */
  approveLabelOverride?: string;
};

/** Render reviewable content for an AWAITING_REVIEW notification plus
 *  the Approve / Discard buttons that call the backend's publish
 *  gate. */
function PublishGatePane({ notification, onResolved, onViewPr, prTitle, approveLabelOverride }: Props) {
  const rawAction = payloadAction(notification.payloadJson);
  const parsed = parsePayload(notification.payloadJson);
  const isResolving = notification.status === 'RESOLVING';
  // Actions whose parked payload carries an editable body. The user
  // sees a textarea and the typed value goes into the approve call;
  // every other action ignores editedBody on the backend.
  const initialBody = parsed?.action === 'post_comment'
      ? parsed.body
      : parsed !== null && hasEditableBody(parsed)
        ? (parsed.body ?? '')
        : '';
  const [editedBody, setEditedBody] = useState(initialBody);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resolution, setResolution] = useState<PublishResultDto | null>(null);
  const interrupted = isResolving || resolution?.resolution === 'interrupted';

  if (rawAction === 'mark_ready') {
    return <LegacyMarkReadyGate notification={notification} onResolved={onResolved} />;
  }

  if (!parsed) {
    return (
      <div style={paneStyle} data-testid="publish-gate-pane">
        <div style={{ fontSize: 13, color: 'var(--text-3)' }}>
          This parked notification doesn't carry a supported review payload
          — open the thread to handle it.
        </div>
      </div>
    );
  }

  const handleApprove = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await window.bridge.approveNotification(
        notification.id,
        bodyForApprove(parsed, editedBody),
        parsed.action);
      setResolution(result);
      if (result.ok) {
        // Give the user a moment to see the success line before the
        // parent collapses + refreshes.
        window.setTimeout(onResolved, 600);
      }
      else {
        setError(result.message);
      }
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusy(false);
    }
  };

  const handleDiscard = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await window.bridge.discardNotification(notification.id, parsed.action);
      setResolution(result);
      window.setTimeout(onResolved, 400);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusy(false);
    }
  };

  const approveLabel = interrupted
    ? 'Finish locally'
    : (approveLabelOverride ?? labelForAction(parsed));
  const approveDisabled = busy
      || (parsed.action === 'post_comment' && editedBody.trim().length === 0)
      || (hasEditableBody(parsed) && requiresEditedBody(parsed.action) && editedBody.trim().length === 0)
      || (resolution?.ok ?? false);

  return (
    <div style={paneStyle} data-testid="publish-gate-pane">
      {prTitle !== undefined && prTitle.length > 0 && (
        <div style={prTitleStyle}>{prTitle}</div>
      )}
      {isResolving && (
        <div style={warningStyle} role="status">
          A previous approval attempt was interrupted. Check remote state first; finishing locally
          will not repeat the publish action.
        </div>
      )}
      {parsed.action === 'push' && <PushReview parsed={parsed} />}
      {parsed.action === 'post_comment' && (
        <PostCommentReview
            parsed={parsed}
            editedBody={editedBody}
            onBodyChange={setEditedBody}
            disabled={busy || (resolution?.ok ?? false)}
          />
      )}
      {parsed.action === 'request_review' && <RequestReview parsed={parsed} />}
      {parsed.action === 'next_task' && <NextTaskReview parsed={parsed} />}
      {parsed.action === 'ship_task' && <ShipTaskReview parsed={parsed} />}
      {isGenericAction(parsed) && (
        <GenericReview
            parsed={parsed}
            editedBody={editedBody}
            onBodyChange={setEditedBody}
            disabled={busy || (resolution?.ok ?? false)}
        />
      )}

      {error && (
        <div style={errorStyle} role="alert">{error}</div>
      )}
      {resolution?.ok && (
        <div style={successStyle}>{resolution.message}</div>
      )}

      <div style={buttonsStyle}>
        <button
          type="button"
          onClick={() => { void handleApprove(); }}
          disabled={approveDisabled}
          style={approveButtonStyle}
        >
          {busy && resolution === null ? 'Working…' : approveLabel}
        </button>
        {onViewPr && (
          <button type="button" onClick={onViewPr} style={discardButtonStyle}>
            View PR
          </button>
        )}
        <button
          type="button"
          onClick={() => { void handleDiscard(); }}
          disabled={busy || (resolution?.ok ?? false)}
          style={{ ...discardButtonStyle, marginLeft: 'auto' }}
        >
          Discard
        </button>
      </div>
    </div>
  );
}

/** Legacy recovery only. CI green now undrafts automatically, so this action
 * may be discarded but must never expose an approval button. */
function LegacyMarkReadyGate({ notification, onResolved }: Pick<Props, 'notification' | 'onResolved'>) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const discard = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await window.bridge.discardNotification(notification.id, 'mark_ready');
      if (!result.ok) setError(result.message);
      else window.setTimeout(onResolved, 400);
    }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  return (
    <div style={paneStyle} data-testid="publish-gate-pane">
      <div style={warningStyle} role="status">
        This ready-for-review gate is obsolete. Green CI now marks the Draft PR ready automatically.
      </div>
      {error !== null && <div style={errorStyle} role="alert">{error}</div>}
      <div style={buttonsStyle}>
        <button type="button" onClick={() => { void discard(); }} disabled={busy} style={discardButtonStyle}>
          {busy ? 'Discarding…' : 'Discard obsolete gate'}
        </button>
      </div>
    </div>
  );
}

function PushReview({ parsed }: { parsed: PushParkedPayload }) {
  return (
    <div>
      <div style={summaryLineStyle}>
        Push <strong>{parsed.branch ?? 'branch'}</strong> from{' '}
        <code style={codeStyle}>{parsed.worktreePath}</code>
        {parsed.diffBase && (
          <>
            {' '}· diff vs <code style={codeStyle}>{parsed.diffBase}</code>
          </>
        )}
      </div>
      <DiffPreview
        diff={parsed.diff ?? null}
        diffError={parsed.diffError}
        missingSuffix="You can still approve to push, or discard."
      />
    </div>
  );
}

function RequestReview({ parsed }: { parsed: RequestReviewParkedPayload }) {
  return (
    <div>
      <div style={summaryLineStyle}>
        Work is ready for review. Accepting resolves this local task without publishing remotely.
      </div>
      <div style={reviewTextStyle}>{parsed.summary || 'No summary supplied.'}</div>
      {parsed.draftReply && (
        <div style={draftReplyStyle}>
          <strong>Draft reply</strong>
          <div>{parsed.draftReply}</div>
        </div>
      )}
      {(parsed.diff !== undefined || parsed.diffError !== undefined) && (
        <DiffPreview
          diff={parsed.diff ?? null}
          diffError={parsed.diffError}
          missingSuffix="You can still accept or discard this review request."
        />
      )}
    </div>
  );
}

function NextTaskReview({ parsed }: { parsed: NextTaskParkedPayload }) {
  return (
    <div>
      <div style={summaryLineStyle}>
        Start the next task after publishing <strong>{parsed.branch ?? 'branch'}</strong>
        {parsed.nextTitle ? <> as <strong>{parsed.nextTitle}</strong></> : null}
        {' '}({parsed.baseMode} base).
      </div>
      <DiffPreview
        diff={parsed.diff ?? null}
        diffError={parsed.diffError}
        missingSuffix="You can still approve advancing, or discard."
      />
    </div>
  );
}

function ShipTaskReview({ parsed }: { parsed: ShipTaskParkedPayload }) {
  return (
    <div>
      <div style={summaryLineStyle}>
        Ship and close <strong>{parsed.branch ?? 'branch'}</strong>, then start
        {parsed.nextTitle ? <> <strong>{parsed.nextTitle}</strong></> : ' the next task'}
        {' '}({parsed.baseMode} base).
      </div>
      <DiffPreview
        diff={parsed.diff ?? null}
        diffError={parsed.diffError}
        missingSuffix="You can still approve shipping, or discard."
      />
    </div>
  );
}

function DiffPreview({ diff, diffError, missingSuffix }: {
  diff: string | null;
  diffError?: string;
  missingSuffix: string;
}) {
  if (diff === '') {
    return <div style={diffMissingStyle}>No changes to show.</div>;
  }
  return diff === null
    ? (
      <div style={diffMissingStyle}>
        Couldn't compute a diff: {diffError ?? 'unknown error'}. {missingSuffix}
      </div>
    )
    : <DiffPre diff={diff} />;
}

function DiffPre({ diff }: { diff: string }) {
  const lines = diff.split('\n');
  return (
    <pre style={diffPreStyle} aria-label="proposed-diff">
      {lines.map((line, i) => (
        <div key={i} style={{ color: diffLineColor(line) }}>{line || ' '}</div>
      ))}
    </pre>
  );
}

function diffLineColor(line: string): string {
  if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('diff ')
      || line.startsWith('index ')) {
    return 'var(--text-3)';
  }
  if (line.startsWith('@@')) {
    return 'var(--text-2)';
  }
  if (line.startsWith('+')) {
    return '#237804';
  }
  if (line.startsWith('-')) {
    return '#cf1322';
  }
  return 'var(--text-1)';
}

function PostCommentReview({ parsed, editedBody, onBodyChange, disabled }: {
  parsed: PostCommentParkedPayload;
  editedBody: string;
  onBodyChange: (next: string) => void;
  disabled: boolean;
}) {
  return (
    <div>
      <div style={summaryLineStyle}>
        Comment on{' '}
        <strong>{parsed.pr.owner}/{parsed.pr.repo}#{parsed.pr.number}</strong>
        {' '}— edit body before sending if you like.
      </div>
      <textarea
        value={editedBody}
        onChange={e => onBodyChange(e.target.value)}
        disabled={disabled}
        aria-label="comment-body"
        style={textareaStyle}
      />
    </div>
  );
}

/** Catch-all review card for the publish-gate actions that don't need
 *  a bespoke renderer (merge_pr, approve_pr, set_issue_state, …). Reads
 *  the action's label + ref out of the payload and surfaces an editable
 *  body when the action accepts one. */
function GenericReview({ parsed, editedBody, onBodyChange, disabled }: {
  parsed: GenericParkedPayload;
  editedBody: string;
  onBodyChange: (next: string) => void;
  disabled: boolean;
}) {
  const refLabel = parsed.pr !== undefined
      ? `${parsed.pr.owner}/${parsed.pr.repo}#${parsed.pr.number}`
      : parsed.issue !== undefined
        ? `${parsed.issue.owner}/${parsed.issue.repo}#${parsed.issue.number}`
        : parsed.repo !== undefined
          ? `${parsed.repo.owner}/${parsed.repo.repo}`
          : null;
  return (
    <div>
      <div style={summaryLineStyle}>
        <strong>{describeAction(parsed.action)}</strong>
        {refLabel !== null && <> on <strong>{refLabel}</strong></>}
        {parsed.summary !== undefined && parsed.summary.length > 0 && (
          <> — {parsed.summary}</>
        )}
      </div>
      {/* create_review_comment anchors to a specific file/line — the
          reviewer must see where the inline comment lands before
          authorizing the publish, not just the PR ref. */}
      {parsed.action === 'create_review_comment' && parsed.filePath !== undefined && (
        <div style={summaryLineStyle}>
          Anchored to <code style={codeStyle}>{parsed.filePath}</code>
          {parsed.line !== undefined && <>:{parsed.line}</>}
          {parsed.side !== undefined && <> ({parsed.side})</>}
        </div>
      )}
      {/* New PR / issue proposals carry metadata the user needs to see before approving:
          the proposed title and the head→base ref. Keep this read-only
          for now (the editable body lands below). */}
      {(parsed.action === 'open_pr' || parsed.action === 'create_issue')
          && (parsed.title !== undefined || parsed.head !== undefined) && (
        <div style={summaryLineStyle}>
          {parsed.title !== undefined && parsed.title.length > 0 && (
            <>Title: <strong>{parsed.title}</strong>{' · '}</>
          )}
          {parsed.head !== undefined && parsed.base !== undefined && (
            <><code style={codeStyle}>{parsed.head}</code> → <code style={codeStyle}>{parsed.base}</code></>
          )}
        </div>
      )}
      {hasEditableBody(parsed) && (
        <textarea
          value={editedBody}
          onChange={e => onBodyChange(e.target.value)}
          disabled={disabled}
          aria-label={`${parsed.action}-body`}
          style={textareaStyle}
        />
      )}
    </div>
  );
}

function describeAction(action: GenericPublishAction): string {
  switch (action) {
    case 'reply_review_thread':   return 'Reply on review thread';
    case 'approve_pr':            return 'Approve PR';
    case 'merge_pr':              return 'Merge PR';
    case 'create_review_comment': return 'Post inline review comment';
    case 'update_pr_body':        return 'Update PR body';
    case 'request_reviewer':      return 'Request a reviewer';
    case 'comment_on_issue':      return 'Comment on issue';
    case 'set_issue_state':       return 'Set issue state';
    case 'create_issue':          return 'Create GitHub issue';
    case 'open_pr':               return 'Open PR';
    case 'publish_review':        return 'Publish review';
  }
}

/** Actions whose backend handler refuses a blank body — Approve must
 *  stay disabled until the user types something. */
function requiresEditedBody(action: GenericPublishAction): boolean {
  return action === 'reply_review_thread'
      || action === 'create_review_comment'
      || action === 'update_pr_body'
      || action === 'comment_on_issue'
      || action === 'create_issue';
}

/** Actions whose backend handler accepts an editable body — either
 *  required (above) or optional. PostCommentParkedPayload has its own
 *  dedicated renderer and is handled separately. */
function hasEditableBody(parsed: ParkedPublishPayload): parsed is GenericParkedPayload {
  if (!isGenericAction(parsed)) return false;
  return requiresEditedBody(parsed.action)
      || parsed.action === 'approve_pr'
      || parsed.action === 'create_issue'
      || parsed.action === 'open_pr'
      || parsed.action === 'publish_review';
}

function isGenericAction(parsed: ParkedPublishPayload): parsed is GenericParkedPayload {
  return parsed.action !== 'push'
      && parsed.action !== 'post_comment'
      && parsed.action !== 'request_review'
      && parsed.action !== 'next_task'
      && parsed.action !== 'ship_task';
}

function bodyForApprove(parsed: ParkedPublishPayload, editedBody: string): string | null {
  if (parsed.action === 'post_comment') return editedBody;
  if (hasEditableBody(parsed)) return editedBody;
  return null;
}

function labelForAction(parsed: ParkedPublishPayload): string {
  switch (parsed.action) {
    case 'push':             return 'Approve & push';
    case 'post_comment':     return 'Post comment';
    case 'next_task':        return 'Approve & start next';
    case 'ship_task':        return 'Approve & ship';
    case 'request_review':   return 'Accept review';
    case 'merge_pr':         return 'Approve & merge';
    case 'approve_pr':       return 'Approve PR';
    case 'set_issue_state':  return 'Apply state change';
    case 'create_issue':     return 'Create issue';
    case 'open_pr':          return 'Open PR';
    case 'publish_review':   return 'Publish review';
    default:                 return 'Approve';
  }
}

function payloadAction(json: string | null): string | null {
  if (json === null) return null;
  try {
    const action = JSON.parse(json)?.action;
    return typeof action === 'string' ? action : null;
  }
  catch { return null; }
}

function parsePayload(json: string | null): ParkedPublishPayload | null {
  if (!json) return null;
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  }
  catch {
    return null;
  }
  if (typeof raw !== 'object' || raw === null) return null;
  const obj = raw as Record<string, unknown>;
  if (obj.action === 'create_issue') {
    const repo = parseRepoRef(obj.repo);
    if (repo === undefined
        || typeof obj.title !== 'string'
        || obj.title.trim().length === 0
        || typeof obj.body !== 'string') {
      return null;
    }
  }
  if (obj.action === 'push' && typeof obj.worktreePath === 'string') {
    return normalizedDiffPayload(obj) as PushParkedPayload;
  }
  if (obj.action === 'post_comment'
      && typeof obj.body === 'string'
      && typeof obj.pr === 'object') {
    return obj as unknown as PostCommentParkedPayload;
  }
  if ((obj.action === 'request_review'
      || (obj.action === undefined && obj.source === 'mcp:request_review'))
      && typeof obj.summary === 'string') {
    return normalizedDiffPayload({ ...obj, action: 'request_review' }) as RequestReviewParkedPayload;
  }
  if (obj.action === 'next_task'
      && typeof obj.worktreePath === 'string'
      && (obj.baseMode === 'main' || obj.baseMode === 'stacked')) {
    return normalizedDiffPayload({
      ...obj,
      nextTitle: typeof obj.nextTitle === 'string' ? obj.nextTitle : '',
    }) as NextTaskParkedPayload;
  }
  if (obj.action === 'ship_task'
      && typeof obj.worktreePath === 'string'
      && (obj.baseMode === 'main' || obj.baseMode === 'stacked')) {
    return normalizedDiffPayload({
      ...obj,
      nextTitle: typeof obj.nextTitle === 'string' ? obj.nextTitle : '',
    }) as ShipTaskParkedPayload;
  }
  // Generic publish actions — every other action PublishService can
  // resolve. The pane renders a minimal review card (action label +
  // PR/issue ref + optional editable body) so these actions aren't
  // stranded in the bell without an Approve / Discard affordance.
  if (typeof obj.action === 'string'
      && GENERIC_PUBLISH_ACTIONS.has(obj.action as GenericPublishAction)) {
    return {
      action: obj.action as GenericPublishAction,
      body: typeof obj.body === 'string' ? obj.body : null,
      pr: parseRef(obj.pr),
      issue: parseRef(obj.issue),
      repo: parseRepoRef(obj.repo),
      title: typeof obj.title === 'string' ? obj.title : undefined,
      head: typeof obj.head === 'string' ? obj.head : undefined,
      base: typeof obj.base === 'string' ? obj.base : undefined,
      filePath: typeof obj.filePath === 'string' ? obj.filePath : undefined,
      line: typeof obj.line === 'number' ? obj.line : undefined,
      side: typeof obj.side === 'string' ? obj.side : undefined,
      summary: typeof obj.summary === 'string' ? obj.summary : undefined,
      source: typeof obj.source === 'string' ? obj.source : '',
    };
  }
  return null;
}

const GENERIC_PUBLISH_ACTIONS = new Set<GenericPublishAction>([
  'reply_review_thread',
  'approve_pr',
  'merge_pr',
  'create_review_comment',
  'update_pr_body',
  'request_reviewer',
  'comment_on_issue',
  'set_issue_state',
  'create_issue',
  'open_pr',
  'publish_review',
]);

function parseRef(raw: unknown): { owner: string; repo: string; number: number } | undefined {
  if (typeof raw !== 'object' || raw === null) return undefined;
  const ref = raw as { owner?: unknown; repo?: unknown; number?: unknown };
  if (typeof ref.owner === 'string'
      && typeof ref.repo === 'string'
      && typeof ref.number === 'number') {
    return { owner: ref.owner, repo: ref.repo, number: ref.number };
  }
  return undefined;
}

/** Parse a bare repo ref (no PR number). open_pr is the one action
 *  whose target doesn't exist yet, so its payload carries
 *  `{ owner, repo }` only. */
function parseRepoRef(raw: unknown): { owner: string; repo: string } | undefined {
  if (typeof raw !== 'object' || raw === null) return undefined;
  const ref = raw as { owner?: unknown; repo?: unknown };
  if (typeof ref.owner === 'string' && typeof ref.repo === 'string'
      && ref.owner.trim().length > 0 && ref.repo.trim().length > 0) {
    return { owner: ref.owner.trim(), repo: ref.repo.trim() };
  }
  return undefined;
}

function normalizedDiffPayload(obj: Record<string, unknown>): Record<string, unknown> {
  return {
    ...obj,
    diff: typeof obj.diff === 'string' || obj.diff === null ? obj.diff : null,
    diffError: typeof obj.diffError === 'string' ? obj.diffError : undefined,
  };
}

const paneStyle: React.CSSProperties = {
  marginTop: 8,
  padding: 9,
  background: 'var(--bg-1)',
  border: '1px solid var(--border)',
  borderRadius: 8,
};

const summaryLineStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
  marginBottom: 6,
};

const prTitleStyle: React.CSSProperties = {
  fontSize: 11.5,
  fontWeight: 600,
  color: 'var(--text-1)',
  marginBottom: 6,
};

const codeStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 11,
  padding: '1px 5px',
  background: 'var(--bg-2)',
  borderRadius: 4,
};

const diffPreStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 12,
  lineHeight: 1.5,
  background: 'var(--bg-2)',
  border: '1px solid var(--border)',
  padding: 12,
  borderRadius: 6,
  maxHeight: 480,
  overflow: 'auto',
  margin: 0,
  whiteSpace: 'pre',
};

const diffMissingStyle: React.CSSProperties = {
  padding: 12,
  background: 'var(--bg-2)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  fontSize: 13,
  color: 'var(--text-2)',
};

const warningStyle: React.CSSProperties = {
  marginBottom: 10,
  padding: 12,
  background: '#fff7ed',
  border: '1px solid #fdba74',
  borderRadius: 6,
  fontSize: 13,
  color: '#9a3412',
};

const reviewTextStyle: React.CSSProperties = {
  marginBottom: 10,
  padding: 12,
  background: 'var(--bg-2)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  fontSize: 12,
  color: 'var(--text-1)',
};

const draftReplyStyle: React.CSSProperties = {
  marginBottom: 10,
  padding: 12,
  background: 'var(--bg-2)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  fontSize: 12,
  color: 'var(--text-1)',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const textareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 140,
  padding: 10,
  fontFamily: 'inherit',
  fontSize: 12,
  lineHeight: 1.5,
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: 'var(--bg-2)',
  color: 'var(--text-1)',
  resize: 'vertical',
  boxSizing: 'border-box',
};

const errorStyle: React.CSSProperties = {
  marginTop: 10,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 6,
  color: '#cf1322',
  fontSize: 13,
};

const successStyle: React.CSSProperties = {
  marginTop: 10,
  padding: 10,
  background: 'rgba(35, 120, 4, 0.08)',
  border: '1px solid rgba(35, 120, 4, 0.4)',
  borderRadius: 6,
  color: '#237804',
  fontSize: 13,
};

const buttonsStyle: React.CSSProperties = {
  marginTop: 8,
  display: 'flex',
  gap: 5,
};

const approveButtonStyle: React.CSSProperties = {
  padding: '3px 9px',
  fontSize: 11.5,
  fontWeight: 600,
  border: 'none',
  borderRadius: 5,
  background: '#0066cc',
  color: '#fff',
  cursor: 'pointer',
};

const discardButtonStyle: React.CSSProperties = {
  padding: '3px 9px',
  fontSize: 11.5,
  border: '1px solid var(--border)',
  borderRadius: 5,
  background: 'var(--bg-2)',
  color: 'var(--text-1)',
  cursor: 'pointer',
};

export default PublishGatePane;
