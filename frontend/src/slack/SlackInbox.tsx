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
import type {
  SlackInboxFilter,
  SlackInboxItemDto,
  SlackInboxThreadDto,
} from '../types';

export type InboxChannelInfo = { id: string; name: string; isPrivate: boolean };

/**
 * Renders the inbox.png mockup. Mixed mentions + DMs, sorted by recency,
 * grouped by day. Four item visual states:
 *   - UNREAD   bold + blue dot
 *   - EXPANDED grows the card to show the thread + inline reply box
 *   - RESPONDED dimmed + amber "Auto-archives in …" countdown bar
 *   - BUMPED   re-bolded + red border + red "N NEW" pill
 *
 * The component owns one expanded item at a time (matching Slack's
 * native inbox behaviour). Auto-refreshes every 30 s to track the
 * polling cadence.
 */
type Props = {
  /** Followed channels — used to render "in #trino-core" instead of
   *  raw channel IDs. */
  followedChannels: InboxChannelInfo[];
  /** Caller passes the Slack-connected user id so we can collapse
   *  user-mention tokens like {@code <@U123>} into "@you" in the
   *  thread-context preview. */
  authedUserId?: string;
};

const REFRESH_INTERVAL_MS = 30_000;
const AUTO_ARCHIVE_MS = 4 * 60 * 60 * 1000;

function SlackInbox({ followedChannels, authedUserId }: Props) {
  const [filter, setFilter] = useState<SlackInboxFilter>('all');
  const [items, setItems] = useState<SlackInboxItemDto[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [expandedKey, setExpandedKey] = useState<string | null>(null);
  const [thread, setThread] = useState<SlackInboxThreadDto | null>(null);
  const [threadLoading, setThreadLoading] = useState(false);

  const refresh = useCallback(async (currentFilter: SlackInboxFilter) => {
    try {
      const rows = await window.bridge.listSlackInbox(currentFilter);
      setItems(rows);
      setLoadError(null);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { void refresh(filter); }, [filter, refresh]);

  // Tick the inbox every 30s so the polling layer's new rows surface
  // without a manual refresh. Same cadence as the backend poll.
  useEffect(() => {
    const id = window.setInterval(() => { void refresh(filter); }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [filter, refresh]);

  const channelNamesById = useMemo(() => {
    const map = new Map<string, { name: string; isPrivate: boolean }>();
    for (const r of followedChannels) {
      map.set(r.id, { name: r.name, isPrivate: r.isPrivate });
    }
    return map;
  }, [followedChannels]);

  const counts = useMemo(() => {
    if (!items) return { all: 0, mentions: 0, dms: 0 };
    let mentions = 0;
    let dms = 0;
    for (const it of items) {
      if (it.inboxKind === 'mention') mentions++;
      else if (it.inboxKind === 'dm') dms++;
    }
    // Filter narrows the request, so when filter !== 'all' the other
    // counts come from the same payload — only displayed counts that
    // match the current filter are accurate. Frontend re-counts on
    // each filter switch via the refetch.
    return { all: items.length, mentions, dms };
  }, [items]);

  const dayGroups = useMemo(() => groupByDay(items ?? []), [items]);

  const onExpand = useCallback(async (item: SlackInboxItemDto) => {
    const key = itemKey(item);
    if (expandedKey === key) {
      setExpandedKey(null);
      return;
    }
    setExpandedKey(key);
    setThread(null);
    setThreadLoading(true);
    // Mark expanded server-side first so a quick refresh doesn't snap
    // the row back to UNREAD; failures are non-fatal — the local
    // expand-in-UI behaviour still works.
    void window.bridge.expandSlackInboxItem(item.channelId, item.ts).catch((): void => undefined);
    try {
      // DMs don't have thread context worth fetching — they ARE the
      // conversation. Skipping saves an API round-trip.
      if (item.inboxKind === 'mention') {
        const t = await window.bridge.getSlackInboxThread(item.channelId, item.ts);
        setThread(t);
      } else {
        setThread({ channelId: item.channelId, threadTs: item.ts, messages: [] });
      }
    } catch {
      setThread({ channelId: item.channelId, threadTs: item.ts, messages: [] });
    } finally {
      setThreadLoading(false);
    }
  }, [expandedKey]);

  const onReply = useCallback(async (item: SlackInboxItemDto, text: string) => {
    await window.bridge.replySlackInboxItem(item.channelId, item.ts, text);
    setExpandedKey(null);
    await refresh(filter);
  }, [filter, refresh]);

  const onArchive = useCallback(async (item: SlackInboxItemDto) => {
    await window.bridge.archiveSlackInboxItem(item.channelId, item.ts);
    await refresh(filter);
  }, [filter, refresh]);

  if (loadError) {
    return (
      <div className="slack-inbox slack-inbox--error">
        Couldn't load inbox: {loadError}
        <button type="button" className="slack-inbox-error-retry" onClick={() => void refresh(filter)}>
          Retry
        </button>
      </div>
    );
  }
  if (items == null) {
    return <div className="slack-inbox__loading">Loading inbox…</div>;
  }

  const unreadAndBumped = items.filter(i => i.state === 'unread' || i.state === 'bumped').length;
  const respondedCount = items.filter(i => i.state === 'responded').length;

  return (
    <div className="slack-inbox">
      <header className="slack-inbox__head">
        <h1 className="slack-inbox__title">Inbox</h1>
        <span className="slack-inbox__counts">
          <strong>{unreadAndBumped} unread</strong> · {respondedCount} awaiting your reply
        </span>
      </header>

      <div className="slack-inbox__filter-tabs">
        <FilterPill label={`All ${counts.all}`} active={filter === 'all'} onClick={() => setFilter('all')} />
        <FilterPill label={`Mentions ${counts.mentions}`} active={filter === 'mentions'} onClick={() => setFilter('mentions')} />
        <FilterPill label={`DMs ${counts.dms}`} active={filter === 'dms'} onClick={() => setFilter('dms')} />
      </div>

      {items.length === 0 && (
        <div className="slack-inbox__empty">
          You're all caught up.
        </div>
      )}

      <div className="slack-inbox__stream">
        {dayGroups.map(group => (
          <div key={group.label} className="slack-inbox__day-group">
            <div className="slack-inbox__day-label">{group.label}</div>
            {group.items.map(item => {
              const key = itemKey(item);
              const isExpanded = expandedKey === key;
              return (
                <InboxItemCard
                  key={key}
                  item={item}
                  expanded={isExpanded}
                  thread={isExpanded ? thread : null}
                  threadLoading={isExpanded && threadLoading}
                  channelInfo={channelNamesById.get(item.channelId)}
                  authedUserId={authedUserId}
                  onExpand={() => void onExpand(item)}
                  onReply={(text) => onReply(item, text)}
                  onArchive={() => void onArchive(item)}
                />
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

function FilterPill({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      className={`slack-inbox__filter-pill${active ? ' slack-inbox__filter-pill--active' : ''}`}
      onClick={onClick}
    >
      {label}
    </button>
  );
}

function InboxItemCard({
  item,
  expanded,
  thread,
  threadLoading,
  channelInfo,
  authedUserId,
  onExpand,
  onReply,
  onArchive,
}: {
  item: SlackInboxItemDto;
  expanded: boolean;
  thread: SlackInboxThreadDto | null;
  threadLoading: boolean;
  channelInfo: { name: string; isPrivate: boolean } | undefined;
  authedUserId?: string;
  onExpand: () => void;
  onReply: (text: string) => Promise<void>;
  onArchive: () => void;
}) {
  const stateClass = `slack-inbox-item--${item.state}`;
  const channelLabel = channelInfo
    ? `${channelInfo.isPrivate ? '🔒 ' : '#'}${channelInfo.name}`
    : item.channelId;
  return (
    <article className={`slack-inbox-item ${stateClass}`}>
      <button type="button" className="slack-inbox-item__header" onClick={onExpand}>
        <span className={`slack-inbox-pill slack-inbox-pill--${item.inboxKind}`}>
          {item.inboxKind === 'mention' ? 'MENTION' : 'DM'}
        </span>
        <span className="slack-inbox-item__source">
          {item.inboxKind === 'mention' ? <>in <strong>{channelLabel}</strong></> : <>{channelLabel}</>}
        </span>
        <span className="slack-inbox-item__sender">
          from <strong>{displayUser(item.userId)}</strong>
        </span>
        <span className="slack-inbox-item__snippet">
          {summarize(item.text)}
        </span>
        {item.state === 'bumped' && item.newReplyCount > 0 && (
          <span className="slack-inbox-item__bumped-pill">{item.newReplyCount} NEW</span>
        )}
        <span className="slack-inbox-item__time">{relativeTime(item.ts)}</span>
        {(item.state === 'unread' || item.state === 'bumped') && (
          <span className="slack-inbox-item__dot" aria-hidden="true" />
        )}
      </button>

      {item.state === 'responded' && !expanded && (
        <RespondedFooter respondedAt={item.respondedAt} onArchive={onArchive} />
      )}

      {expanded && (
        <div className="slack-inbox-item__expanded">
          {item.inboxKind === 'mention' && (
            <ThreadContext thread={thread} loading={threadLoading} authedUserId={authedUserId} />
          )}
          <ReplyBox
            channelLabel={channelLabel}
            placeholder={item.inboxKind === 'mention' ? 'Reply in thread…' : 'Reply…'}
            onReply={onReply}
            onCancel={onExpand}
          />
        </div>
      )}
    </article>
  );
}

function RespondedFooter({ respondedAt, onArchive }: { respondedAt: string | null; onArchive: () => void }) {
  const remainingMs = remainingArchiveMs(respondedAt);
  return (
    <div className="slack-inbox-item__responded-footer">
      <span className="slack-inbox-item__archive-eta">
        {remainingMs > 0
          ? <>Auto-archives in <strong>{formatDuration(remainingMs)}</strong></>
          : <>Archiving on the next sweep…</>}
      </span>
      <button type="button" className="slack-inbox-item__archive-link" onClick={onArchive}>
        Archive now
      </button>
    </div>
  );
}

function ThreadContext({
  thread,
  loading,
  authedUserId,
}: {
  thread: SlackInboxThreadDto | null;
  loading: boolean;
  authedUserId?: string;
}) {
  if (loading) {
    return <div className="slack-inbox-thread slack-inbox-thread--loading">Loading thread…</div>;
  }
  if (!thread || thread.messages.length === 0) {
    return null;
  }
  return (
    <div className="slack-inbox-thread">
      <div className="slack-inbox-thread__label">Thread context</div>
      {thread.messages.map(m => (
        <div key={m.ts} className={`slack-inbox-thread__msg${m.hasAtYou ? ' slack-inbox-thread__msg--mention' : ''}`}>
          <span className="slack-inbox-thread__author">{displayUser(m.userId)}</span>
          <span className="slack-inbox-thread__text">{collapseMentions(m.text, authedUserId)}</span>
        </div>
      ))}
    </div>
  );
}

function ReplyBox({
  channelLabel,
  placeholder,
  onReply,
  onCancel,
}: {
  channelLabel: string;
  placeholder: string;
  onReply: (text: string) => Promise<void>;
  onCancel: () => void;
}) {
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
    <div className="slack-inbox-reply">
      <textarea
        className="slack-inbox-reply__textarea"
        placeholder={placeholder}
        value={text}
        onChange={e => setText(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            void send();
          }
        }}
        disabled={submitting}
        rows={2}
      />
      <div className="slack-inbox-reply__footer">
        <span className="slack-inbox-reply__hint">
          ⌘+Enter to send · reply posts to <strong>{channelLabel}</strong>
        </span>
        {error && <span className="slack-inbox-reply__error">{error}</span>}
        <div className="slack-inbox-reply__actions">
          <button
            type="button"
            className="slack-inbox-reply__btn slack-inbox-reply__btn--ghost"
            onClick={onCancel}
            disabled={submitting}
          >
            Cancel
          </button>
          <button
            type="button"
            className="slack-inbox-reply__btn slack-inbox-reply__btn--primary"
            onClick={() => void send()}
            disabled={submitting || !text.trim()}
          >
            {submitting ? 'Sending…' : 'Reply'}
          </button>
        </div>
      </div>
    </div>
  );
}

function itemKey(item: SlackInboxItemDto): string {
  return `${item.channelId}|${item.ts}`;
}

function summarize(text: string | null): string {
  if (!text) return '';
  // Strip user-mention tokens for the snippet — Slack renders <@U123>
  // anywhere in the text but the inbox preview reads better with @… .
  const cleaned = text.replace(/<@[A-Z0-9]+(\|[^>]+)?>/g, '@…');
  return cleaned.length > 120 ? cleaned.slice(0, 117) + '…' : cleaned;
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
  // Slack profile lookup is out of scope for Slice 5 — the inbox row
  // shows the raw user id (truncated). A follow-up will add a
  // user-display-name cache fed by users.info.
  return userId.length > 8 ? userId.slice(0, 8) : userId;
}

/** Slack ts is "<seconds>.<microseconds>". Render as "Nm/h/d ago". */
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

function remainingArchiveMs(respondedAt: string | null): number {
  if (!respondedAt) return 0;
  const responded = Date.parse(respondedAt);
  if (!Number.isFinite(responded)) return 0;
  return responded + AUTO_ARCHIVE_MS - Date.now();
}

function formatDuration(ms: number): string {
  if (ms <= 0) return '0m';
  const totalMins = Math.round(ms / 60_000);
  if (totalMins < 60) return `${totalMins}m`;
  const hrs = Math.floor(totalMins / 60);
  const mins = totalMins % 60;
  return mins === 0 ? `${hrs}h` : `${hrs}h ${mins}m`;
}

type DayGroup = { label: string; items: SlackInboxItemDto[] };

function groupByDay(items: SlackInboxItemDto[]): DayGroup[] {
  const groups = new Map<string, SlackInboxItemDto[]>();
  for (const it of items) {
    const seconds = Number.parseFloat(it.ts);
    const date = Number.isFinite(seconds) ? new Date(seconds * 1000) : new Date();
    const label = dayLabel(date);
    const list = groups.get(label) ?? [];
    list.push(it);
    groups.set(label, list);
  }
  // Map preserves insertion order, but TODAY then YESTERDAY then dates
  // is exactly the recency order Slack returns rows in, so iteration
  // order is already correct.
  return Array.from(groups.entries()).map(([label, list]) => ({ label, items: list }));
}

function dayLabel(date: Date): string {
  const today = new Date();
  const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const todayStart = startOfDay(today);
  const dateStart = startOfDay(date);
  const dayDiff = Math.round((todayStart - dateStart) / (24 * 60 * 60 * 1000));
  if (dayDiff === 0) return 'TODAY';
  if (dayDiff === 1) return 'YESTERDAY';
  return date.toLocaleDateString();
}

export default SlackInbox;
