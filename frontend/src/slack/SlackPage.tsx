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
import type { SlackConnectionDto } from '../types';

/**
 * Slack tab — Slice 2b. Pre-connect surface from Slice 1 plus:
 * - load /api/slack/connection on mount; render the connected sidebar
 *   if a workspace is linked.
 * - wire the Connect button to fetch the authorize URL and open it in
 *   the system browser; subscribe to slack:oauth-complete to flip to
 *   the connected state.
 * - render an "OAuth not configured" hint when the backend reports
 *   {configured:false} (env vars missing).
 *
 * The connected sidebar is intentionally minimal in this slice — full
 * Inbox / followed-channel / DM views land in later slices.
 */
type Phase =
  | { kind: 'loading' }
  | { kind: 'pre-connect' }
  | { kind: 'pre-connect-not-configured' }
  | { kind: 'awaiting-callback' }
  | { kind: 'connected'; team: SlackConnectionDto }
  | { kind: 'error'; message: string };

function SlackPage() {
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' });

  const refreshConnection = useCallback(async () => {
    try {
      const c = await window.bridge.getSlackConnection();
      if (c.connected) {
        setPhase({ kind: 'connected', team: c });
      } else {
        setPhase({ kind: 'pre-connect' });
      }
    } catch (e) {
      setPhase({ kind: 'error', message: e instanceof Error ? e.message : String(e) });
    }
  }, []);

  useEffect(() => { void refreshConnection(); }, [refreshConnection]);

  // Listen for the open-url callback completion. We ignore success/error
  // payload details on the renderer side and just re-fetch /connection
  // — the backend is the source of truth and a refetch handles every
  // outcome (success → connected, error → still pre-connect).
  useEffect(() => {
    const unsub = window.bridge.onSlackOauthComplete(() => { void refreshConnection(); });
    return unsub;
  }, [refreshConnection]);

  const handleConnect = useCallback(async () => {
    setPhase(prev => prev.kind === 'connected' ? prev : { kind: 'awaiting-callback' });
    try {
      const r = await window.bridge.getSlackAuthorizeUrl();
      if (!r.configured || !r.url) {
        setPhase({ kind: 'pre-connect-not-configured' });
        return;
      }
      await window.bridge.openExternal(r.url);
    } catch (e) {
      setPhase({ kind: 'error', message: e instanceof Error ? e.message : String(e) });
    }
  }, []);

  const handleDisconnect = useCallback(async () => {
    try {
      await window.bridge.disconnectSlack();
      setPhase({ kind: 'pre-connect' });
    } catch (e) {
      setPhase({ kind: 'error', message: e instanceof Error ? e.message : String(e) });
    }
  }, []);

  const sidebar = useMemo(() => {
    if (phase.kind === 'connected') {
      return <ConnectedSidebar team={phase.team} onDisconnect={handleDisconnect} />;
    }
    return <PreConnectSidebar />;
  }, [phase, handleDisconnect]);

  return (
    <div className="slack-page">
      {sidebar}
      <main className="slack-main">
        {phase.kind === 'loading' && <div className="slack-status">Loading…</div>}
        {phase.kind === 'error' && <ErrorCard message={phase.message} onRetry={refreshConnection} />}
        {phase.kind === 'connected' && <ConnectedCard team={phase.team} />}
        {phase.kind === 'pre-connect-not-configured' && <NotConfiguredCard />}
        {(phase.kind === 'pre-connect' || phase.kind === 'awaiting-callback') && (
          <ConnectCard
            awaiting={phase.kind === 'awaiting-callback'}
            onConnect={handleConnect}
            onCancelAwaiting={() => setPhase({ kind: 'pre-connect' })}
          />
        )}
      </main>
    </div>
  );
}

function PreConnectSidebar() {
  return (
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
  );
}

function ConnectedSidebar({ team, onDisconnect }: { team: SlackConnectionDto; onDisconnect: () => void }) {
  const initial = (team.teamName ?? '?').charAt(0).toUpperCase();
  return (
    <aside className="slack-sidebar">
      <div className="slack-ws-header slack-ws-header--connected">
        <div className="slack-ws-icon slack-ws-icon--connected" aria-hidden="true">{initial}</div>
        <div className="slack-ws-meta">
          <div className="slack-ws-name slack-ws-name--connected">{team.teamName ?? 'Connected'}</div>
          <div className="slack-ws-handle">workspace linked</div>
        </div>
      </div>

      <div className="slack-sb-item slack-sb-item--muted" aria-disabled="true">
        <span className="slack-sb-glyph" aria-hidden="true">📥</span>
        <span>Inbox</span>
      </div>

      <div className="slack-sb-label">Followed channels</div>

      <div className="slack-sb-item slack-sb-item--muted slack-sb-item--indent" aria-disabled="true">
        <span className="slack-sb-empty">no channels followed yet</span>
      </div>

      <div className="slack-sb-help">
        Inbox + followed-channel views ship in the next slices. For now this just confirms the workspace is linked.
        <button type="button" className="slack-disconnect-link" onClick={onDisconnect}>
          Disconnect workspace
        </button>
      </div>
    </aside>
  );
}

function ConnectCard({
  awaiting,
  onConnect,
  onCancelAwaiting,
}: {
  awaiting: boolean;
  onConnect: () => void;
  onCancelAwaiting: () => void;
}) {
  return (
    <div className="slack-connect-card">
      <div className="slack-connect-icon" aria-hidden="true">#</div>
      <h1 className="slack-connect-title">Connect your Slack workspace</h1>
      <p className="slack-connect-desc">
        ByteQuay's Slack tab gives you a focused inbox of{' '}
        <strong>@you mentions</strong> from any channel, plus your{' '}
        <strong>DMs</strong>, plus 2–3 channels you fully follow. Reply
        directly from the cockpit. The rest of Slack stays in Slack.
      </p>
      <button
        type="button"
        className="slack-connect-btn"
        onClick={onConnect}
        disabled={awaiting}
      >
        {awaiting ? 'Waiting for browser…' : 'Connect Slack workspace'}
      </button>
      {awaiting && (
        <button
          type="button"
          className="slack-connect-help-link"
          onClick={onCancelAwaiting}
        >
          Cancel
        </button>
      )}
      <div className="slack-connect-help">
        {/* TODO Slice 7: copy for these explainers isn't designed yet — silent no-op for now. */}
        <button type="button" className="slack-connect-help-link" onClick={() => { /* placeholder */ }}>
          Why these permissions?
        </button>
        <span className="slack-connect-help-sep" aria-hidden="true">·</span>
        <button type="button" className="slack-connect-help-link" onClick={() => { /* placeholder */ }}>
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
  );
}

function NotConfiguredCard() {
  return (
    <div className="slack-connect-card">
      <div className="slack-connect-icon" aria-hidden="true">#</div>
      <h1 className="slack-connect-title">Slack OAuth isn't configured</h1>
      <p className="slack-connect-desc">
        To enable the Slack integration, register a Slack app and supply{' '}
        <code>SLACK_CLIENT_ID</code> and <code>SLACK_CLIENT_SECRET</code> as
        environment variables to the backend. The renderer will pick up the
        change once the backend restarts.
      </p>
    </div>
  );
}

function ConnectedCard({ team }: { team: SlackConnectionDto }) {
  return (
    <div className="slack-connect-card">
      <div className="slack-connect-icon" aria-hidden="true">✓</div>
      <h1 className="slack-connect-title">{team.teamName ?? 'Workspace'} linked</h1>
      <p className="slack-connect-desc">
        Your Slack workspace is connected. The inbox, followed-channel feed,
        and DM views ship in upcoming slices — for now this just confirms
        the OAuth handshake completed and the user token is stored locally.
      </p>
      <div className="slack-local-first">
        <span className="slack-local-first-lock" aria-hidden="true">🔒</span>
        <span>
          <strong>Local-first.</strong> Tokens stay on your machine,
          messages cache locally. Nothing leaves without a click.
        </span>
      </div>
    </div>
  );
}

function ErrorCard({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="slack-connect-card">
      <div className="slack-connect-icon" aria-hidden="true">!</div>
      <h1 className="slack-connect-title">Couldn't load Slack state</h1>
      <p className="slack-connect-desc">{message}</p>
      <button type="button" className="slack-connect-btn" onClick={onRetry}>
        Retry
      </button>
    </div>
  );
}

export default SlackPage;
