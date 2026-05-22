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
  NotificationDto,
  ParkedPublishPayload,
  PostCommentParkedPayload,
  PublishResultDto,
  PushParkedPayload,
} from './types';

type Props = {
  notification: NotificationDto;
  /** Called after the parked notification is resolved (approved or
   *  discarded) so the parent can refresh its list and collapse the
   *  expansion. Not called on side-effect failure — the row stays
   *  parked so the user can retry. */
  onResolved: () => void;
};

/** Render the diff / body for an AWAITING_REVIEW notification plus
 *  the Approve / Discard buttons that call the backend's publish
 *  gate. The component only knows two payload shapes — push and
 *  post_comment — and gracefully degrades for anything else (a
 *  request_review notification, say) by telling the user to open the
 *  thread instead. */
function PublishGatePane({ notification, onResolved }: Props) {
  const parsed = parsePayload(notification.payloadJson);
  const initialBody = parsed?.action === 'post_comment' ? parsed.body : '';
  const [editedBody, setEditedBody] = useState(initialBody);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resolution, setResolution] = useState<PublishResultDto | null>(null);

  if (!parsed) {
    return (
      <div style={paneStyle} data-testid="publish-gate-pane">
        <div style={{ fontSize: 13, color: 'var(--text-3)' }}>
          This parked notification doesn't carry a push or comment payload
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
        parsed.action === 'post_comment' ? editedBody : null);
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
      const result = await window.bridge.discardNotification(notification.id);
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

  const approveLabel = parsed.action === 'push' ? 'Approve & push' : 'Post comment';
  const approveDisabled = busy
      || (parsed.action === 'post_comment' && editedBody.trim().length === 0)
      || (resolution?.ok ?? false);

  return (
    <div style={paneStyle} data-testid="publish-gate-pane">
      {parsed.action === 'push'
        ? <PushReview parsed={parsed} />
        : <PostCommentReview
            parsed={parsed}
            editedBody={editedBody}
            onBodyChange={setEditedBody}
            disabled={busy || (resolution?.ok ?? false)}
          />}

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
        <button
          type="button"
          onClick={() => { void handleDiscard(); }}
          disabled={busy || (resolution?.ok ?? false)}
          style={discardButtonStyle}
        >
          Discard
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
      {parsed.diff
        ? <DiffPre diff={parsed.diff} />
        : (
          <div style={diffMissingStyle}>
            Couldn't compute a diff: {parsed.diffError ?? 'unknown error'}.
            You can still approve to push, or discard.
          </div>
        )}
    </div>
  );
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
  if (obj.action === 'push' && typeof obj.worktreePath === 'string') {
    return obj as unknown as PushParkedPayload;
  }
  if (obj.action === 'post_comment'
      && typeof obj.body === 'string'
      && typeof obj.pr === 'object') {
    return obj as unknown as PostCommentParkedPayload;
  }
  return null;
}

const paneStyle: React.CSSProperties = {
  marginTop: 12,
  padding: 16,
  background: 'var(--bg-1)',
  border: '1px solid var(--border)',
  borderRadius: 8,
};

const summaryLineStyle: React.CSSProperties = {
  fontSize: 13,
  color: 'var(--text-3)',
  marginBottom: 10,
};

const codeStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  fontSize: 12,
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

const textareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 140,
  padding: 10,
  fontFamily: 'inherit',
  fontSize: 13,
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
  marginTop: 12,
  display: 'flex',
  gap: 8,
};

const approveButtonStyle: React.CSSProperties = {
  padding: '8px 14px',
  fontSize: 13,
  fontWeight: 600,
  border: 'none',
  borderRadius: 6,
  background: '#0066cc',
  color: '#fff',
  cursor: 'pointer',
};

const discardButtonStyle: React.CSSProperties = {
  padding: '8px 14px',
  fontSize: 13,
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: 'var(--bg-2)',
  color: 'var(--text-1)',
  cursor: 'pointer',
};

export default PublishGatePane;
