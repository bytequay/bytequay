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
import type { SlackChannelFeedDto, SlackFeedMessageDto } from '../types';

/**
 * Renders dm.png — the DM expanded view. Reached by clicking a DM
 * inbox item; "← Inbox" returns to the inbox via the {@code onBack}
 * callback. "Mark as handled" archives the inbox row.
 *
 * Layout differences from the channel feed:
 *   - Conversational rendering (no thread-pill collapsing).
 *   - {@code YOU} badge + warning-color avatar on the user's own
 *     messages (so they're visible at-a-glance while scanning).
 *   - "NEW SINCE YOU LAST READ · N MESSAGES" red divider between the
 *     last ts the user has seen and the newer ones. Last-read state
 *     lives in localStorage keyed by {@code slack:dm-last-read:<ts>}.
 *   - No {@code @you} highlight — DMs are inherently directed.
 *   - Reply box is always visible at the bottom, posts top-level
 *     (threadTs=null) into the conversation.
 */
type Props = {
  channelId: string;
  /** Sender label for the header ("DM with Maria Reyes"). v1 just uses
   *  the underlying user id; richer name resolution lands when we
   *  add a users.info cache. */
  peerLabel: string;
  /** Connected user id — used to flip the {@code YOU} styling on the
   *  user's own messages. */
  authedUserId?: string;
  onBack: () => void;
  /** "Mark as handled" archives the inbox row keyed by the parent ts.
   *  Caller drives the routing back to the inbox after the call lands. */
  onMarkHandled: (ts: string) => Promise<void>;
};

const REFRESH_INTERVAL_MS = 30_000;

function SlackDmView({ channelId, peerLabel, authedUserId, onBack, onMarkHandled }: Props) {
  const [feed, setFeed] = useState<SlackChannelFeedDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [posting, setPosting] = useState(false);
  const [postError, setPostError] = useState<string | null>(null);
  const [text, setText] = useState('');

  const lastReadKey = `slack:dm-last-read:${channelId}`;
  const [lastReadAtMount] = useState<string | null>(() => {
    try { return window.localStorage.getItem(lastReadKey); } catch { return null; }
  });

  const refresh = useCallback(async () => {
    try {
      const f = await window.bridge.getSlackChannelFeed(channelId);
      setFeed(f);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [channelId]);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    const id = window.setInterval(() => { void refresh(); }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [refresh]);

  // Persist the highest-seen ts on unmount so the next visit's
  // divider lands at the right spot.
  useEffect(() => {
    return () => {
      const latest = feed?.messages.at(-1)?.ts;
      if (latest) {
        try { window.localStorage.setItem(lastReadKey, latest); } catch { /* noop */ }
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feed, lastReadKey]);

  const dividerIndex = useMemo(() => {
    if (!feed || !lastReadAtMount) return -1;
    // First message strictly newer than the watermark.
    return feed.messages.findIndex(m => Number.parseFloat(m.ts) > Number.parseFloat(lastReadAtMount));
  }, [feed, lastReadAtMount]);

  const sendReply = useCallback(async () => {
    if (!text.trim() || posting) return;
    setPosting(true);
    setPostError(null);
    try {
      await window.bridge.postSlackFeedMessage(channelId, text, null);
      setText('');
      await refresh();
    } catch (e) {
      setPostError(e instanceof Error ? e.message : String(e));
    } finally {
      setPosting(false);
    }
  }, [channelId, text, posting, refresh]);

  const handleMarkHandled = useCallback(async () => {
    // Archive the parent ts of the most recent message — Slice 5's
    // inbox state machine keys rows on (workspace, channel, root_ts),
    // which for a DM is the first message we ingested. Pick the
    // earliest stored ts as the parent.
    const root = feed?.messages[0]?.ts;
    if (!root) {
      onBack();
      return;
    }
    await onMarkHandled(root);
  }, [feed, onMarkHandled, onBack]);

  if (error) {
    return (
      <div className="slack-dm slack-dm--error">
        Couldn't load DM: {error}
        <button type="button" className="slack-inbox-error-retry" onClick={() => void refresh()}>Retry</button>
      </div>
    );
  }
  if (feed == null) {
    return <div className="slack-dm__loading">Loading DM…</div>;
  }

  const messages = feed.messages;

  return (
    <div className="slack-dm">
      <div className="slack-dm__breadcrumb">
        <button type="button" className="slack-dm__back" onClick={onBack}>← Inbox</button>
        <span className="slack-dm__breadcrumb-sep">·</span>
        <span>DM with <strong>{peerLabel}</strong></span>
      </div>

      <header className="slack-dm__head">
        <span className="slack-inbox-pill slack-inbox-pill--dm">DM</span>
        <h1 className="slack-dm__title">{peerLabel}</h1>
        <div className="slack-dm__head-actions">
          <button type="button" className="slack-feed__head-btn slack-feed__head-btn--ghost">
            Open in Slack ↗
          </button>
          <button type="button" className="slack-feed__head-btn slack-feed__head-btn--primary" onClick={() => void handleMarkHandled()}>
            Mark as handled
          </button>
        </div>
      </header>

      <div className="slack-dm__stream">
        {messages.length === 0 && (
          <div className="slack-feed__empty">No messages cached yet — give the polling loop a moment.</div>
        )}
        {messages.map((m, i) => (
          <DmMessageRow
            key={m.ts}
            message={m}
            isOwn={m.userId != null && m.userId === authedUserId}
            authedUserId={authedUserId}
            divider={i === dividerIndex ? messages.length - dividerIndex : 0}
          />
        ))}
      </div>

      <div className="slack-dm__replybox">
        <textarea
          className="slack-dm__replybox-textarea"
          placeholder={`Reply to ${peerLabel}…`}
          value={text}
          onChange={e => setText(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              e.preventDefault();
              void sendReply();
            }
          }}
          disabled={posting}
          rows={2}
        />
        <div className="slack-dm__replybox-footer">
          <span className="slack-dm__replybox-hint">
            ⌘+Enter to send · this DM stays in your inbox until handled
          </span>
          {postError && <span className="slack-dm__replybox-error">{postError}</span>}
          <div className="slack-dm__replybox-actions">
            <button
              type="button"
              className="slack-inbox-reply__btn slack-inbox-reply__btn--ghost"
              onClick={() => setText('')}
              disabled={posting || !text}
            >
              Cancel
            </button>
            <button
              type="button"
              className="slack-inbox-reply__btn slack-inbox-reply__btn--primary"
              onClick={() => void sendReply()}
              disabled={posting || !text.trim()}
            >
              {posting ? 'Sending…' : 'Reply'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function DmMessageRow({
  message,
  isOwn,
  authedUserId,
  divider,
}: {
  message: SlackFeedMessageDto;
  isOwn: boolean;
  authedUserId?: string;
  /** Number of messages after this row that count as "new since last read";
   *  0 means no divider. The divider renders ABOVE this row. */
  divider: number;
}) {
  return (
    <>
      {divider > 0 && (
        <div className="slack-dm__divider">
          NEW SINCE YOU LAST READ · {divider} {divider === 1 ? 'MESSAGE' : 'MESSAGES'}
        </div>
      )}
      <div className={`slack-dm-msg${isOwn ? ' slack-dm-msg--own' : ''}`}>
        <Avatar userId={message.userId} isOwn={isOwn} />
        <div className="slack-dm-msg__body">
          <div className="slack-dm-msg__head">
            <span className="slack-dm-msg__author">{displayUser(message.userId)}</span>
            {isOwn && <span className="slack-dm-msg__you-badge">YOU</span>}
            <span className="slack-dm-msg__time">{relativeTime(message.ts)}</span>
          </div>
          <div className="slack-dm-msg__text">{collapseMentions(message.text, authedUserId)}</div>
        </div>
      </div>
    </>
  );
}

function Avatar({ userId, isOwn }: { userId: string | null; isOwn?: boolean }) {
  const initial = (userId ?? '?').charAt(0).toUpperCase();
  // Own messages use a warning-color avatar so the user's contributions
  // pop while scrolling, per the dm.png spec.
  const ownColor = '#ffa726';
  const palette = ['#5b9bd5', '#7cb342', '#ec407a', '#ab47bc', '#ff7043', '#26a69a', '#42a5f5'];
  const hash = (userId ?? '').split('').reduce((acc, c) => (acc + c.charCodeAt(0)) % palette.length, 0);
  const bg = isOwn ? ownColor : palette[hash];
  return <span className="slack-feed-avatar" style={{ background: bg }}>{initial}</span>;
}

function collapseMentions(text: string | null, authedUserId?: string): string {
  if (!text) return '';
  return text.replace(/<@([A-Z0-9]+)(?:\|[^>]+)?>/g, (_match, uid) => {
    if (authedUserId && uid === authedUserId) return '@you';
    return '@' + uid.slice(0, 4).toLowerCase();
  });
}

function displayUser(userId: string | null): string {
  if (!userId) return 'unknown';
  return userId.length > 8 ? userId.slice(0, 8) : userId;
}

function relativeTime(ts: string): string {
  const seconds = Number.parseFloat(ts);
  if (!Number.isFinite(seconds)) return '';
  const diffMs = Date.now() - seconds * 1000;
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  return `${days}d ago`;
}

export default SlackDmView;
