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

/** A PR reference parsed from the parked `mark_ready` payload. */
export type MarkReadyPrRef = { owner: string; repo: string; number: number };

/** Split a reviewers field (comma/space/newline separated, `@`-prefixed or
 *  not) into distinct GitHub logins — mirrors the backend's own parse so the
 *  success count the user sees matches what's requested. */
export function parseReviewers(raw: string): string[] {
  return raw
    .split(/[,\s]+/)
    .map(s => s.trim())
    .map(s => (s.startsWith('@') ? s.slice(1) : s))
    .filter(s => s.length > 0)
    .filter((s, i, all) => all.indexOf(s) === i);
}

/**
 * The mark-ready gate hosted in the code page's pull-request pane. The draft
 * PR already exists (CI is green); approving flips it out of draft and — if
 * any reviewers were typed — requests them. Leaving the field empty just
 * marks the PR ready. A link jumps to the live PR on GitHub.
 */
export function MarkReadyPanel({ notificationId, pr, onMarked }: {
  notificationId: string;
  pr: MarkReadyPrRef;
  /** Called once the gate resolves `approved`, with how many reviewers were
   *  requested, so the page can confirm + navigate away. */
  onMarked: (requestedReviewers: number) => void;
}) {
  const [reviewers, setReviewers] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const prUrl = `https://github.com/${pr.owner}/${pr.repo}/pull/${pr.number}`;
  const requested = parseReviewers(reviewers);

  const markReady = async () => {
    if (busy) return;
    setBusy(true);
    setNote(null);
    try {
      // The backend re-parses the body into reviewers; send the raw text.
      const result = await window.bridge.approveNotification(notificationId, reviewers, 'mark_ready');
      if (result.resolution !== 'approved') {
        setNote(result.message);
        setBusy(false);
        return;
      }
      onMarked(requested.length);
    }
    catch (e) {
      setNote(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <div className="ship-description">
      <div className="ship-description__head">
        <span className="ship-description__label">Ready for review</span>
        <button
          type="button"
          className="button button--secondary"
          style={{ marginLeft: 'auto' }}
          onClick={() => { void window.bridge.openExternal(prUrl); }}
          title="Open this pull request on GitHub"
        >
          {pr.owner}/{pr.repo}#{pr.number} ↗
        </button>
      </div>
      <p className="diff-viewer__pr-blurb">
        Checks are green on the draft pull request. Marking it ready hands it
        off for review. Add reviewers below (comma or space separated GitHub
        usernames) — or leave it empty to just mark the PR ready.
      </p>
      <input
        className="ship-description__title"
        value={reviewers}
        onChange={(e) => setReviewers(e.target.value)}
        placeholder="reviewers — e.g. octocat, hubot (optional)"
        aria-label="Reviewers"
        disabled={busy}
      />
      {note !== null && <div className="diff-viewer__review-note" role="alert">{note}</div>}
      <div className="ship-description__actions">
        <button
          type="button"
          className="button button--submit"
          onClick={() => void markReady()}
          disabled={busy}
          title={requested.length > 0
            ? `Mark ready and request ${requested.length} reviewer${requested.length === 1 ? '' : 's'}`
            : 'Mark this pull request ready for review'}
        >
          {busy
            ? 'Working…'
            : requested.length > 0
              ? `Mark ready & request ${requested.length} reviewer${requested.length === 1 ? '' : 's'}`
              : 'Mark ready for review'}
        </button>
      </div>
    </div>
  );
}
