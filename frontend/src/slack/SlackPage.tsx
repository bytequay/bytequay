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
import type { SlackChannelRowDto, SlackConnectionDto, SlackInboxItemDto } from '../types';
import SlackChannelPicker from './SlackChannelPicker';
import SlackInbox from './SlackInbox';
import SlackChannelFeed from './SlackChannelFeed';
import SlackDmView from './SlackDmView';

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
  | { kind: 'connected'; team: SlackConnectionDto; followed: FollowedChannel[]; view: ConnectedView }
  | { kind: 'pick-channels'; team: SlackConnectionDto; mode: 'first-run' | 'management' }
  | { kind: 'error'; message: string };

type ConnectedView =
  | { kind: 'inbox' }
  | { kind: 'channel-feed'; channelId: string }
  | { kind: 'dm-view'; channelId: string; peerLabel: string };

type FollowedChannel = { id: string; name: string; isPrivate: boolean };

type SlackPageProps = {
  onOpenIntegrationsSettings: () => void;
};

function SlackPage({ onOpenIntegrationsSettings }: SlackPageProps) {
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' });

  const refreshConnection = useCallback(async () => {
    try {
      const c = await window.bridge.getSlackConnection();
      if (!c.connected) {
        setPhase({ kind: 'pre-connect' });
        return;
      }
      // Load channels alongside the connection so the sidebar can show
      // the followed set, and so the first-run picker auto-opens when
      // smart-default flags come back from the backend.
      let rows: SlackChannelRowDto[];
      try {
        rows = await window.bridge.listSlackChannels();
      } catch {
        // If the channel list call fails (e.g. token revoked), still
        // land on the connected card — the user can disconnect from
        // there. Don't block the whole tab on a secondary call.
        setPhase({ kind: 'connected', team: c, followed: [], view: { kind: 'inbox' } });
        return;
      }
      const firstRun = rows.some(r => r.isSmartDefault);
      if (firstRun) {
        setPhase({ kind: 'pick-channels', team: c, mode: 'first-run' });
      } else {
        setPhase(prev => {
          // Preserve the current view across refreshes — switching back to
          // inbox on every poll would yank the user out of a channel feed.
          const view = prev.kind === 'connected' ? prev.view : { kind: 'inbox' as const };
          return { kind: 'connected', team: c, followed: toFollowed(rows), view };
        });
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

  const handleOpenPicker = useCallback(() => {
    setPhase(prev => prev.kind === 'connected'
      ? { kind: 'pick-channels', team: prev.team, mode: 'management' }
      : prev);
  }, []);

  const handlePickerSaved = useCallback((rows: SlackChannelRowDto[]) => {
    setPhase(prev => prev.kind === 'pick-channels'
      ? { kind: 'connected', team: prev.team, followed: toFollowed(rows), view: { kind: 'inbox' } }
      : prev);
  }, []);

  const navigateInbox = useCallback(() => {
    setPhase(prev => prev.kind === 'connected'
      ? { ...prev, view: { kind: 'inbox' } }
      : prev);
  }, []);

  const navigateChannelFeed = useCallback((channelId: string) => {
    setPhase(prev => prev.kind === 'connected'
      ? { ...prev, view: { kind: 'channel-feed', channelId } }
      : prev);
  }, []);

  const navigateDmView = useCallback((item: SlackInboxItemDto) => {
    setPhase(prev => prev.kind === 'connected'
      ? { ...prev, view: { kind: 'dm-view', channelId: item.channelId, peerLabel: item.userId ?? 'unknown' } }
      : prev);
  }, []);

  const handleMarkDmHandled = useCallback(async (channelId: string, ts: string) => {
    await window.bridge.archiveSlackInboxItem(channelId, ts);
    setPhase(prev => prev.kind === 'connected'
      ? { ...prev, view: { kind: 'inbox' } }
      : prev);
  }, []);

  const handlePickerExit = useCallback(() => {
    // Skip / Cancel — leave persisted state alone, just go back to the
    // inbox view. We re-fetch so the followed list reflects whatever
    // was last saved (handles the "skip on first-run" case where no
    // rows are persisted yet).
    void refreshConnection();
  }, [refreshConnection]);

  const sidebar = useMemo(() => {
    if (phase.kind === 'connected') {
      return (
        <ConnectedSidebar
          team={phase.team}
          followed={phase.followed}
          activeView={phase.view}
          onDisconnect={handleDisconnect}
          onPickChannels={handleOpenPicker}
          onNavigateInbox={navigateInbox}
          onNavigateChannel={navigateChannelFeed}
        />
      );
    }
    if (phase.kind === 'pick-channels') {
      return (
        <ConnectedSidebar
          team={phase.team}
          followed={[]}
          activeView={null}
          onDisconnect={handleDisconnect}
          onPickChannels={() => { /* already on picker */ }}
          onNavigateInbox={() => { /* picker is modal */ }}
          onNavigateChannel={() => { /* picker is modal */ }}
          activePicker
        />
      );
    }
    return <PreConnectSidebar />;
  }, [phase, handleDisconnect, handleOpenPicker, navigateInbox, navigateChannelFeed]);

  return (
    <div className="slack-page">
      {sidebar}
      <main className="slack-main">
        {phase.kind === 'loading' && <div className="slack-status">Loading…</div>}
        {phase.kind === 'error' && <ErrorCard message={phase.message} onRetry={refreshConnection} />}
        {phase.kind === 'connected' && phase.view.kind === 'inbox' && (
          <SlackInbox
            followedChannels={phase.followed}
            authedUserId={phase.team.authedUserId}
            onSelectDm={navigateDmView}
          />
        )}
        {phase.kind === 'connected' && phase.view.kind === 'channel-feed' && (
          <SlackChannelFeed
            channelId={phase.view.channelId}
            channelName={channelName(phase.followed, phase.view.channelId)}
            isPrivate={isChannelPrivate(phase.followed, phase.view.channelId)}
            authedUserId={phase.team.authedUserId}
          />
        )}
        {phase.kind === 'connected' && phase.view.kind === 'dm-view' && (
          <SlackDmView
            channelId={phase.view.channelId}
            peerLabel={phase.view.peerLabel}
            authedUserId={phase.team.authedUserId}
            onBack={navigateInbox}
            onMarkHandled={(ts) => handleMarkDmHandled(
              phase.kind === 'connected' && phase.view.kind === 'dm-view' ? phase.view.channelId : '', ts)}
          />
        )}
        {phase.kind === 'pick-channels' && (
          <SlackChannelPicker
            mode={phase.mode}
            onSaved={handlePickerSaved}
            onSkip={phase.mode === 'first-run' ? handlePickerExit : undefined}
            onCancel={phase.mode === 'management' ? handlePickerExit : undefined}
          />
        )}
        {phase.kind === 'pre-connect-not-configured' && (
          <NotConfiguredCard onOpenSettings={onOpenIntegrationsSettings} />
        )}
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

function ConnectedSidebar({
  team,
  followed,
  activeView,
  onDisconnect,
  onPickChannels,
  onNavigateInbox,
  onNavigateChannel,
  activePicker,
}: {
  team: SlackConnectionDto;
  followed: FollowedChannel[];
  activeView: ConnectedView | null;
  onDisconnect: () => void;
  onPickChannels: () => void;
  onNavigateInbox: () => void;
  onNavigateChannel: (channelId: string) => void;
  activePicker?: boolean;
}) {
  const initial = (team.teamName ?? '?').charAt(0).toUpperCase();
  const pickLabel = followed.length === 0 ? 'Pick channels…' : 'Manage followed channels…';
  const inboxActive = activeView?.kind === 'inbox' || activeView?.kind === 'dm-view';
  const activeChannelId = activeView?.kind === 'channel-feed' ? activeView.channelId : null;
  return (
    <aside className="slack-sidebar">
      <div className="slack-ws-header slack-ws-header--connected">
        <div className="slack-ws-icon slack-ws-icon--connected" aria-hidden="true">{initial}</div>
        <div className="slack-ws-meta">
          <div className="slack-ws-name slack-ws-name--connected">{team.teamName ?? 'Connected'}</div>
          <div className="slack-ws-handle">workspace linked</div>
        </div>
      </div>

      <button
        type="button"
        className={`slack-sb-item slack-sb-item--button${inboxActive ? ' slack-sb-item--active' : ''}`}
        onClick={onNavigateInbox}
      >
        <span className="slack-sb-glyph" aria-hidden="true">📥</span>
        <span>Inbox</span>
      </button>

      <div className="slack-sb-label">Followed channels</div>

      {followed.length === 0 && (
        <div className="slack-sb-item slack-sb-item--muted slack-sb-item--indent" aria-disabled="true">
          <span className="slack-sb-empty">no channels followed yet</span>
        </div>
      )}
      {followed.map(c => {
        const active = activeChannelId === c.id;
        return (
          <button
            key={c.id}
            type="button"
            className={`slack-sb-item slack-sb-item--button slack-sb-item--indent${active ? ' slack-sb-item--active' : ''}`}
            onClick={() => onNavigateChannel(c.id)}
          >
            <span className="slack-sb-glyph" aria-hidden="true">{c.isPrivate ? '🔒' : '#'}</span>
            <span>{c.name}</span>
          </button>
        );
      })}

      <button
        type="button"
        className={`slack-sb-pick${activePicker ? ' slack-sb-pick--active' : ''}`}
        onClick={onPickChannels}
        disabled={activePicker}
      >
        {pickLabel}
      </button>

      <div className="slack-sb-help">
        DM and inbox views: ⌘+Enter to send. Token stored locally.
        <button type="button" className="slack-disconnect-link" onClick={onDisconnect}>
          Disconnect workspace
        </button>
      </div>
    </aside>
  );
}

function channelName(followed: FollowedChannel[], channelId: string): string {
  const found = followed.find(c => c.id === channelId);
  return found ? found.name : channelId;
}

function isChannelPrivate(followed: FollowedChannel[], channelId: string): boolean {
  const found = followed.find(c => c.id === channelId);
  return found?.isPrivate ?? false;
}

function toFollowed(rows: readonly SlackChannelRowDto[]): FollowedChannel[] {
  return rows
    .filter(r => r.isFollowed)
    .map(r => ({ id: r.channel.id, name: r.channel.name, isPrivate: r.channel.isPrivate }));
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
          messages cache locally. Nothing leaves without a click. One
          click connects — no app registration on your side.
        </span>
      </div>
    </div>
  );
}

function NotConfiguredCard({ onOpenSettings }: { onOpenSettings: () => void }) {
  // Reachable only when this build was shipped without the embedded
  // PKCE client_id (dev builds, custom forks). End users on the
  // packaged dmg always hit ConnectCard. Settings → Integrations
  // exposes a BYO fallback for anyone running their own Slack app.
  return (
    <div className="slack-connect-card">
      <div className="slack-connect-icon" aria-hidden="true">#</div>
      <h1 className="slack-connect-title">Slack OAuth isn't configured</h1>
      <p className="slack-connect-desc">
        This build of ByteQuay shipped without an embedded Slack app.
        You can still connect by registering your own Slack app and
        pasting its <code>client_id</code> + <code>client_secret</code>{' '}
        into Settings → Integrations.
      </p>
      <button
        type="button"
        className="slack-connect-btn"
        onClick={onOpenSettings}
      >
        Open Settings → Integrations
      </button>
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
