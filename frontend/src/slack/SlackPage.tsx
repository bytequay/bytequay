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

/**
 * Slack tab — Slice 1 (pre-connect). See
 * docs/mockups/design/slack/SUMMARY.md "Implementation roadmap" for the
 * full slice plan. This file ships the static pre-connect surface only:
 * sidebar with muted entries + main pane with the Connect card. None of
 * the buttons here are wired — OAuth lives in Slice 2.
 */
function SlackPage() {
  return (
    <div className="slack-page">
      <aside className="slack-sidebar">
        <div className="slack-ws-header">
          <div className="slack-ws-icon" aria-hidden="true">?</div>
          <div className="slack-ws-meta">
            <div className="slack-ws-name">Not connected</div>
            <div className="slack-ws-handle">no workspace yet</div>
          </div>
        </div>

        <div className="slack-sb-item slack-sb-item--muted" aria-disabled="true">
          <span className="slack-sb-glyph" aria-hidden="true">📥</span>
          <span>Inbox</span>
        </div>

        <div className="slack-sb-label">Followed channels</div>

        <div className="slack-sb-item slack-sb-item--muted slack-sb-item--indent" aria-disabled="true">
          <span className="slack-sb-empty">not yet set up</span>
        </div>

        <div className="slack-sb-help">
          Once you connect a Slack workspace, your <strong>@you mentions and DMs</strong> show up here, and you can pick up to 3 channels to follow in full.
        </div>
      </aside>

      <main className="slack-main">
        <div className="slack-connect-card">
          <div className="slack-connect-icon" aria-hidden="true">#</div>
          <h1 className="slack-connect-title">Connect your Slack workspace</h1>
          <p className="slack-connect-desc">
            ByteQuay's Slack tab gives you a focused inbox of{' '}
            <strong>@you mentions</strong> from any channel, plus your{' '}
            <strong>DMs</strong>, plus 2–3 channels you fully follow. Reply
            directly from the cockpit. The rest of Slack stays in Slack.
          </p>
          {/* TODO Slice 2: launch OAuth flow (system browser → bytequay://slack-oauth-callback → exchange code → token in Keychain). */}
          <button
            type="button"
            className="slack-connect-btn"
            onClick={() => { /* no-op until Slice 2 wires OAuth */ }}
          >
            Connect Slack workspace
          </button>
          <div className="slack-connect-help">
            {/* TODO Slice 7: copy for these explainers isn't designed yet — silent no-op for now. */}
            <button
              type="button"
              className="slack-connect-help-link"
              onClick={() => { /* placeholder until copy lands */ }}
            >
              Why these permissions?
            </button>
            <span className="slack-connect-help-sep" aria-hidden="true">·</span>
            <button
              type="button"
              className="slack-connect-help-link"
              onClick={() => { /* placeholder until copy lands */ }}
            >
              What gets stored locally?
            </button>
          </div>
          <div className="slack-local-first">
            <span className="slack-local-first-lock" aria-hidden="true">🔒</span>
            <span>
              <strong>Local-first.</strong> Tokens stay on your machine,
              messages cache locally. Nothing leaves without a click.
            </span>
          </div>
        </div>
      </main>
    </div>
  );
}

export default SlackPage;
