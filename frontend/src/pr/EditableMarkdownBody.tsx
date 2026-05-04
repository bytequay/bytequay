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
import { useEffect, useState, type ReactNode } from 'react';
import { renderMarkdown } from '../markdown';

/**
 * Renders a comment body as markdown by default; when {@code canEdit}
 * is true and the user clicks the inline ✎ Edit pill, swaps to a
 * textarea + Save / Cancel pair. {@code onSave} owns the network call
 * and any local-state patching — this component just collects the new
 * text and surfaces errors. Mirrors {@code DescriptionCard}'s edit
 * affordance so all three editable surfaces (PR body, top-level
 * comments, per-line replies) feel identical.
 *
 * The {@code renderViewSlot} escape hatch lets a caller (e.g. review
 * threads that need the {@code CommentBodyWithSuggestions} renderer
 * for `suggestion` blocks) substitute its own view-mode rendering
 * without losing the edit affordance. When omitted we fall back to a
 * plain `dangerouslySetInnerHTML` against {@code renderMarkdown(body)}.
 */
export function EditableMarkdownBody({
  body,
  canEdit,
  onSave,
  className = 'prc-comment-body',
  renderViewSlot,
}: {
  body: string;
  canEdit: boolean;
  onSave: (newBody: string) => Promise<void>;
  className?: string;
  renderViewSlot?: (body: string) => ReactNode;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(body);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Re-sync the draft whenever the upstream body changes while we're
  // not editing (e.g. a background detail refetch landed). Mid-edit we
  // intentionally keep the user's in-progress text.
  useEffect(() => {
    if (!editing) setDraft(body);
  }, [body, editing]);

  const startEdit = () => {
    setDraft(body);
    setError(null);
    setEditing(true);
  };

  const cancel = () => {
    setDraft(body);
    setError(null);
    setEditing(false);
  };

  const save = async () => {
    if (draft === body) {
      setEditing(false);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave(draft);
      setEditing(false);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSaving(false);
    }
  };

  if (editing) {
    return (
      <div className="editable-comment-body editable-comment-body--editing">
        <textarea
          className="editable-comment-body__textarea"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          disabled={saving}
          autoFocus
        />
        <div className="editable-comment-body__actions">
          <button
            type="button"
            className="button button--primary"
            onClick={() => { void save(); }}
            disabled={saving || draft === body}
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button
            type="button"
            className="pr-comment-box__cancel"
            onClick={cancel}
            disabled={saving}
          >
            Cancel
          </button>
        </div>
        {error && <div className="editable-comment-body__error">{error}</div>}
      </div>
    );
  }

  return (
    <>
      {renderViewSlot
        ? renderViewSlot(body)
        // Content comes from the GitHub API; contextIsolation prevents
        // renderer escapes via the markdown render path.
        : <div className={className} dangerouslySetInnerHTML={{ __html: renderMarkdown(body) }} />}
      {canEdit && (
        <button
          type="button"
          className="editable-comment-body__edit"
          onClick={startEdit}
          title="Edit your comment"
        >
          ✎ Edit
        </button>
      )}
    </>
  );
}
