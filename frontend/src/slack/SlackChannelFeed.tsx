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
 * Renders channel-feed.png — chronological message stream for one
 * followed channel, with inline thread expansion. Each thread parent
 * shows a "↳ N replies · last Xm ago" pill that, when clicked,
 * folds the replies inline (left-border indent) plus a thread-scoped
 * reply box.
 *
 * `@you` mentions keep their yellow highlight inside the feed —
 * channel context doesn't dilute the mention signal.
 */
type Props = {
  channelId: string;
  channelName: string;
  isPrivate: boolean;
  authedUserId?: string;
  onUnfollow?: () => void;
};

const REFRESH_INTERVAL_MS = 30_000;

type ThreadGroup = {
  parent: SlackFeedMessageDto;
  replies: SlackFeedMessageDto[];
};

function SlackChannelFeed({ channelId, channelName, isPrivate, authedUserId, onUnfollow }: Props) {
  const [feed, setFeed] = useState<SlackChannelFeedDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedThreadTs, setExpandedThreadTs] = useState<string | null>(null);

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

  // Reset the expanded thread when switching channels.
  useEffect(() => { setExpandedThreadTs(null); }, [channelId]);

  const groups = useMemo(() => groupByThread(feed?.messages ?? []), [feed]);

  const onReplyInThread = useCallback(async (threadTs: string, text: string) => {
    await window.bridge.postSlackFeedMessage(channelId, text, threadTs);
    await refresh();
  }, [channelId, refresh]);

  if (error) {
    return (
      <div className="slack-feed slack-feed--error">
        Couldn't load #{channelName}: {error}
        <button type="button" className="slack-inbox-error-retry" onClick={() => void refresh()}>Retry</button>
      </div>
    );
  }
  if (feed == null) {
    return <div className="slack-feed__loading">Loading #{channelName}…</div>;
  }

  return (
    <div className="slack-feed">
      <header className="slack-feed__head">
        <h1 className="slack-feed__title">
          {isPrivate ? '🔒 ' : '#'}{channelName}
        </h1>
        <div className="slack-feed__head-actions">
          <button type="button" className="slack-feed__head-btn slack-feed__head-btn--ghost">
            Channel info
          </button>
          {onUnfollow && (
            <button type="button" className="slack-feed__head-btn slack-feed__head-btn--ghost" onClick={onUnfollow}>
              Unfollow
            </button>
          )}
        </div>
      </header>

      {groups.length === 0 && (
        <div className="slack-feed__empty">No messages cached yet — give the polling loop a moment.</div>
      )}

      {groups.length > 0 && (
        <>
          <div className="slack-feed__day-label">TODAY</div>
          <div className="slack-feed__stream">
            {groups.map(g => (
              <FeedMessageRow
                key={g.parent.ts}
                group={g}
                expanded={expandedThreadTs === g.parent.ts}
                onToggleThread={() => setExpandedThreadTs(prev => prev === g.parent.ts ? null : g.parent.ts)}
                onReply={(text) => onReplyInThread(g.parent.ts, text)}
                authedUserId={authedUserId}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function FeedMessageRow({
  group,
  expanded,
  onToggleThread,
  onReply,
  authedUserId,
}: {
  group: ThreadGroup;
  expanded: boolean;
  onToggleThread: () => void;
  onReply: (text: string) => Promise<void>;
  authedUserId?: string;
}) {
  const { parent, replies } = group;
  const hasReplies = replies.length > 0;
  return (
    <article className={`slack-feed-msg${parent.hasAtYou ? ' slack-feed-msg--mention' : ''}`}>
      <Avatar userId={parent.userId} />
      <div className="slack-feed-msg__body">
        <div className="slack-feed-msg__head">
          <span className="slack-feed-msg__author">{displayUser(parent.userId)}</span>
          <span className="slack-feed-msg__time">{relativeTime(parent.ts)}</span>
        </div>
        <div className="slack-feed-msg__text">{collapseMentions(parent.text, authedUserId)}</div>

        {hasReplies && !expanded && (
          <button type="button" className="slack-feed-thread-pill" onClick={onToggleThread}>
            ↳ {replies.length} {replies.length === 1 ? 'reply' : 'replies'} · last {relativeTime(replies[replies.length - 1].ts)}
          </button>
        )}

        {expanded && (
          <ExpandedThread
            replies={replies}
            authedUserId={authedUserId}
            onCollapse={onToggleThread}
            onReply={onReply}
          />
        )}
      </div>
    </article>
  );
}

function ExpandedThread({
  replies,
  authedUserId,
  onCollapse,
  onReply,
}: {
  replies: SlackFeedMessageDto[];
  authedUserId?: string;
  onCollapse: () => void;
  onReply: (text: string) => Promise<void>;
}) {
  return (
    <div className="slack-feed-thread">
      <div className="slack-feed-thread__head">
        <span>Thread · {replies.length} {replies.length === 1 ? 'reply' : 'replies'}</span>
        <button type="button" className="slack-feed-thread__collapse" onClick={onCollapse}>
          ↑ Collapse thread
        </button>
      </div>
      {replies.map(r => (
        <div
          key={r.ts}
          className={`slack-feed-thread__reply${r.hasAtYou ? ' slack-feed-thread__reply--mention' : ''}`}
        >
          <Avatar userId={r.userId} small />
          <div className="slack-feed-thread__reply-body">
            <div className="slack-feed-thread__reply-head">
              <span className="slack-feed-thread__reply-author">{displayUser(r.userId)}</span>
              <span className="slack-feed-thread__reply-time">{relativeTime(r.ts)}</span>
            </div>
            <div className="slack-feed-thread__reply-text">{collapseMentions(r.text, authedUserId)}</div>
          </div>
        </div>
      ))}
      <ThreadReplyBox onReply={onReply} />
    </div>
  );
}

function ThreadReplyBox({ onReply }: { onReply: (text: string) => Promise<void> }) {
  const [text, setText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const send = async () => {
    if (!text.trim() || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await onReply(text);
      setText('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <div className="slack-feed-thread__replybox">
      <input
        type="text"
        className="slack-feed-thread__replybox-input"
        placeholder="Reply in thread…"
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            void send();
          }
        }}
        disabled={submitting}
      />
      {error && <span className="slack-feed-thread__replybox-error">{error}</span>}
    </div>
  );
}

function Avatar({ userId, small }: { userId: string | null; small?: boolean }) {
  const initial = (userId ?? '?').charAt(0).toUpperCase();
  // Stable per-user color from the user id hash so the same user
  // looks the same across rows. A real avatar fetch is out of scope
  // for v1 — this is the same approach Slack uses while loading.
  const palette = ['#5b9bd5', '#7cb342', '#ec407a', '#ab47bc', '#ff7043', '#26a69a', '#ffa726', '#42a5f5'];
  const hash = (userId ?? '').split('').reduce((acc, c) => (acc + c.charCodeAt(0)) % palette.length, 0);
  const bg = palette[hash];
  return (
    <span className={`slack-feed-avatar${small ? ' slack-feed-avatar--small' : ''}`} style={{ background: bg }}>
      {initial}
    </span>
  );
}

function groupByThread(messages: SlackFeedMessageDto[]): ThreadGroup[] {
  const parents = new Map<string, ThreadGroup>();
  const orphanReplies: SlackFeedMessageDto[] = [];
  // First pass: parents — any message whose threadTs is null OR equal
  // to its own ts. Slack sets thread_ts==ts on the parent when a
  // thread exists; we treat both shapes as parents for robustness.
  for (const m of messages) {
    const isParent = m.threadTs == null || m.threadTs === m.ts;
    if (isParent) {
      parents.set(m.ts, { parent: m, replies: [] });
    }
  }
  // Second pass: replies.
  for (const m of messages) {
    if (m.threadTs != null && m.threadTs !== m.ts) {
      const group = parents.get(m.threadTs);
      if (group) {
        group.replies.push(m);
      } else {
        orphanReplies.push(m);
      }
    }
  }
  // Synthesize parents for orphan replies (parent message wasn't
  // ingested locally yet) so they still appear in the feed.
  for (const orphan of orphanReplies) {
    const synthesized: SlackFeedMessageDto = {
      ts: orphan.threadTs as string,
      userId: null,
      text: '(thread parent not cached yet)',
      threadTs: null,
      hasAtYou: false,
    };
    const group: ThreadGroup = { parent: synthesized, replies: [orphan] };
    parents.set(synthesized.ts, group);
  }
  // Sort each thread's replies oldest-first, and the overall list by parent ts asc.
  const groups = Array.from(parents.values());
  for (const g of groups) {
    g.replies.sort((a, b) => Number.parseFloat(a.ts) - Number.parseFloat(b.ts));
  }
  groups.sort((a, b) => Number.parseFloat(a.parent.ts) - Number.parseFloat(b.parent.ts));
  return groups;
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

export default SlackChannelFeed;
