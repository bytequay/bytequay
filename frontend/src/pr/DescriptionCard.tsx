import { useEffect, useState } from 'react';
import { marked } from 'marked';
import type { PullRequestDto } from '../types';
import Avatar from '../Avatar';
import { formatRelativeTime } from './utils';

export function DescriptionCard({
  pr,
  body,
  onSaved,
}: {
  pr: PullRequestDto;
  body: string;
  onSaved: (body: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(body);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
    if (draft === body) { setEditing(false); return; }
    setSaving(true);
    setError(null);
    try {
      await window.bridge.updatePrBody(pr.repo, pr.number, draft);
      onSaved(draft);
      setEditing(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const cleaned = body.replace(/<!--[\s\S]*?-->/g, '').trim();
  const html = cleaned
    ? (marked(cleaned, { gfm: true, breaks: true }) as string)
    : '<p class="description-card__empty">No description.</p>';

  // Render as a dialog-style comment card: PR author avatar on the left,
  // bordered "speech bubble" body on the right. Matches the timeline's
  // comment cards so the description reads as the PR author's opening
  // message in the conversation. The bubble's solid background also hides
  // the timeline rail in this region — see docs/mockups/v2/detail/description.png.
  const author = pr.author ?? '';
  const createdAt = pr.createdAt ?? pr.updatedAt;
  const wasEdited = !!pr.createdAt && pr.updatedAt !== pr.createdAt;
  return (
    <article className="prc-comment-card prc-comment-card--description">
      <Avatar login={author} size={40} className="prc-comment-avatar" />
      <div className="prc-comment-card-body">
        <header className="prc-comment-head prc-comment-head--description">
          {author ? (
            <a
              href={`https://github.com/${author}`}
              target="_blank"
              rel="noreferrer"
              className="prc-comment-author"
            >
              {author}
            </a>
          ) : (
            <span className="prc-comment-author">unknown</span>
          )}
          <span className="prc-comment-verb">commented</span>
          {createdAt && (
            <span className="prc-comment-time">{formatRelativeTime(createdAt)}</span>
          )}
          {wasEdited && <span className="prc-comment-verb">· edited</span>}
          {!editing && (
            <button
              type="button"
              className="description-card__edit"
              onClick={startEdit}
              title="Edit the PR description (only the PR author can save changes)."
            >
              ✎ Edit
            </button>
          )}
        </header>
        {editing ? (
          <div className="description-card description-card--editing">
            <textarea
              className="description-card__textarea"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder="Describe the change — markdown supported."
              disabled={saving}
              autoFocus
            />
            <div className="description-card__actions">
              <button
                type="button"
                className="button button--primary"
                onClick={save}
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
            {error && <div className="description-card__error">{error}</div>}
          </div>
        ) : (
          <div className="prc-comment-body description-card__body">
            {/* No "Description" heading: GitHub PR bodies almost always
                start with "## Description" themselves, and a wrapper
                heading would just duplicate it. The header row above
                ("[author] commented [time]") already labels the card. */}
            {/* Content comes from the GitHub API; contextIsolation prevents renderer escapes */}
            <div className="md-body" dangerouslySetInnerHTML={{ __html: html }} />
          </div>
        )}
      </div>
    </article>
  );
}
