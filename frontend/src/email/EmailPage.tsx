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
import { useCallback, useEffect, useRef, useState, type Ref } from 'react';
import type {
  EmailMessageDetailDto,
  EmailTagArchiveEntryDto,
  EmailTagDto,
  EmailThreadDetailDto,
  EmailThreadMetaDto,
  LinkedRefDto,
} from '../types';
import EmailLeftNav, { type EmailActiveView } from './EmailLeftNav';
import ManageRulesModal from './ManageRulesModal';

type Account = { email: string; authMode: 'IMAP' };

type Props = {
  /** Click handler for "no Gmail account connected" empty state — jumps the
   *  user to Settings → Integrations to add one. */
  onOpenIntegrationsSettings: () => void;
  /** Routes a detected PR/issue link to the Repository PR/issues tab.
   *  Wired by App.tsx so the email surface can hand off triage. */
  onOpenLinkedRef: (ref: LinkedRefDto) => void;
};

/** Window during which an auto-archive can be one-clicked back to
 *  the inbox via the toast at the bottom of the page. Long enough
 *  to register a misclick, short enough not to clutter the screen.
 *  Mirrors Gmail's own "Undo" timing. */
const UNDO_GRACE_MS = 5_000;

/**
 * Master-detail inbox, thread-based. One row per Gmail conversation
 * (matches Gmail's web UI), with a (N) badge for multi-message
 * threads. Click a thread to load the full conversation in the right
 * pane — every message stacked oldest-first.
 *
 * <p>Archive / mark-read operate on the entire thread, like Gmail
 * itself does.
 */
export default function EmailPage({ onOpenIntegrationsSettings, onOpenLinkedRef }: Props) {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [accountsError, setAccountsError] = useState<string | null>(null);
  const [selectedAccount, setSelectedAccount] = useState<string | null>(null);

  const [threads, setThreads] = useState<EmailThreadMetaDto[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedThreadId, setSelectedThreadId] = useState<string | null>(null);

  // Tag rules + the audit log of tag-driven archives. Tags are loaded
  // on every account switch (cheap, ~handful of rows); archived entries
  // are loaded lazily the first time the user navigates to the Archived
  // bucket so a session that never touches it pays nothing.
  const [tags, setTags] = useState<EmailTagDto[]>([]);
  const [archiveEntries, setArchiveEntries] = useState<EmailTagArchiveEntryDto[]>([]);
  const [archiveLoaded, setArchiveLoaded] = useState(false);
  const [activeView, setActiveView] = useState<EmailActiveView>({ kind: 'inbox' });
  const [rulesModalOpen, setRulesModalOpen] = useState(false);

  // Session-scope cache of thread details so re-opening a thread (or
  // walking it again with j/k) doesn't pay the IMAP round-trip twice.
  // Keyed by Gmail thread id. Cleared on account switch and on explicit
  // inbox Refresh; the existing meta.messageCount-driven silent refresh
  // in ThreadDetailPane still picks up new replies inside an open thread.
  const detailsCacheRef = useRef<Map<string, EmailThreadDetailDto>>(new Map());
  // Stamp bumped whenever the cache is invalidated; ThreadDetailPane
  // watches it so a Refresh forces a re-fetch even when meta.id and
  // messageCount haven't changed.
  const [cacheVersion, setCacheVersion] = useState(0);
  // Threads the user opened in this session that auto-archived. They're
  // still in `threads` so the detail pane can render the open one, but
  // filtered out of the visible inbox list. "Keep in inbox" removes the
  // ID and the row pops back into the list.
  const [autoArchivedIds, setAutoArchivedIds] = useState<Set<string>>(() => new Set());
  // Tracks thread IDs we've already auto-acted on (mark-read + archive)
  // so re-selecting after "Keep in inbox" doesn't immediately re-archive.
  const autoActedRef = useRef<Set<string>>(new Set());
  // Most-recent action that's still inside the undo grace window.
  // Two kinds share the same toast slot — only the latest is undoable,
  // so muting then opening another thread cancels the mute toast.
  // Cleared by the timer (UNDO_GRACE_MS) or by a manual undo click.
  type UndoTarget =
    | { kind: 'archive'; id: string; subject: string }
    | { kind: 'mute'; id: string; subject: string; sender: string };
  const [undoTarget, setUndoTarget] = useState<UndoTarget | null>(null);
  const undoTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Load accounts on mount; auto-select the first one.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listGmailAccounts();
        if (cancelled) return;
        setAccounts(list);
        setSelectedAccount(list[0]?.email ?? null);
      }
      catch (e) {
        if (cancelled) return;
        setAccountsError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const loadInbox = useCallback(async (account: string, force = false) => {
    setLoading(true);
    setError(null);
    if (force) {
      // Explicit Refresh: bust the per-thread detail cache too so the
      // user gets fresh bodies, not just a fresh inbox list.
      detailsCacheRef.current = new Map();
      setCacheVersion(v => v + 1);
    }
    try {
      const list = force
        ? await window.bridge.refreshEmailThreads(account)
        : await window.bridge.listEmailThreads(account);
      setThreads(list);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setThreads(null);
    }
    finally {
      setLoading(false);
    }
  }, []);

  const loadTags = useCallback(async (account: string) => {
    try {
      const list = await window.bridge.listEmailTags(account);
      setTags(list);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const loadArchived = useCallback(async (account: string) => {
    try {
      const list = await window.bridge.listArchivedEmailThreads(account);
      setArchiveEntries(list);
      setArchiveLoaded(true);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  // Background poll of the inbox list — focus-gated so it pauses
  // when the user tabs away. Each tick opens a fresh imap.gmail.com
  // connection (~300ms login), so we keep the cadence at 5 min — long
  // enough not to burn Gmail's login budget, short enough that new
  // mail shows up within a "checked it earlier" window. Manual Refresh
  // still gives instant pickup.
  useEffect(() => {
    if (!selectedAccount) return;
    let interval: ReturnType<typeof setInterval> | null = null;
    const isVisible = () => document.visibilityState === 'visible' && document.hasFocus();
    const tick = async () => {
      try {
        const list = await window.bridge.listEmailThreads(selectedAccount);
        setThreads(list);
      }
      catch { /* best-effort: skip this tick */ }
    };
    const start = () => {
      if (interval != null) return;
      interval = setInterval(() => { void tick(); }, 5 * 60_000);
    };
    const stop = () => {
      if (interval != null) { clearInterval(interval); interval = null; }
    };
    const onVisibility = () => { isVisible() ? start() : stop(); };
    if (isVisible()) start();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', onVisibility);
    window.addEventListener('blur', onVisibility);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', onVisibility);
      window.removeEventListener('blur', onVisibility);
    };
  }, [selectedAccount, accounts]);

  // Load threads + tags whenever the selected account changes. The
  // archive log is loaded lazily on first navigation to the Archived
  // bucket — most sessions won't need it.
  useEffect(() => {
    // Drop the in-session detail cache when the account changes — the
    // entries are scoped to the previous account and have no use here.
    detailsCacheRef.current = new Map();
    setCacheVersion(v => v + 1);
    if (selectedAccount == null) {
      setThreads(null);
      setTags([]);
      setArchiveEntries([]);
      setArchiveLoaded(false);
      return;
    }
    void loadInbox(selectedAccount);
    void loadTags(selectedAccount);
    setArchiveEntries([]);
    setArchiveLoaded(false);
    setActiveView({ kind: 'inbox' });
    setSelectedThreadId(null);
  }, [selectedAccount, accounts, loadInbox, loadTags]);

  // Pull the archive log the first time the user navigates to the
  // Archived bucket. After that, refreshes pick it up implicitly when
  // the user re-enters the view.
  useEffect(() => {
    if (!selectedAccount) return;
    if (activeView.kind !== 'archived') return;
    if (archiveLoaded) return;
    void loadArchived(selectedAccount);
  }, [activeView, selectedAccount, archiveLoaded, loadArchived]);

  // Switching views invalidates any thread selection — the picked
  // thread might not be in the new view's universe.
  useEffect(() => {
    setSelectedThreadId(null);
  }, [activeView]);

  // Auto-fired when the user opens an unread thread: flip it to read
  // and remove from the visible inbox in one Gmail call. The thread
  // stays in `threads` so the detail pane keeps rendering it; the
  // visible-list filter (autoArchivedIds) hides the row. "Keep in
  // inbox" undoes both.
  /** Replace any in-flight undo toast with a new one (or clear). The
   *  ref-based timer survives renders so we can cancel cleanly. */
  const queueUndoToast = (target: UndoTarget | null) => {
    if (undoTimerRef.current) {
      clearTimeout(undoTimerRef.current);
      undoTimerRef.current = null;
    }
    setUndoTarget(target);
    if (target) {
      undoTimerRef.current = setTimeout(() => {
        setUndoTarget(curr => (curr && curr.id === target.id && curr.kind === target.kind ? null : curr));
        undoTimerRef.current = null;
      }, UNDO_GRACE_MS);
    }
  };

  const readAndArchive = async (id: string) => {
    if (!selectedAccount) return;
    const prev = threads;
    if (!prev) return;
    const target = prev.find(t => t.id === id);
    setThreads(prev.map(t => t.id === id ? { ...t, unread: false } : t));
    setAutoArchivedIds(s => {
      const next = new Set(s);
      next.add(id);
      return next;
    });
    try {
      await window.bridge.readAndArchiveEmailThread(selectedAccount, id);
      queueUndoToast({ kind: 'archive', id, subject: target?.subject ?? '(no subject)' });
    }
    catch (e) {
      setThreads(prev);
      setAutoArchivedIds(s => {
        const next = new Set(s);
        next.delete(id);
        return next;
      });
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  /** Mute the thread's sender + archive the open thread in one click —
   *  the user's intent here is "never show this person again, including
   *  the one I'm looking at". The archive piece reuses the existing
   *  read-and-archive flow so the row vanishes from the visible list
   *  the same way an auto-archive would.
   *
   *  Replaces any in-flight archive toast with a mute toast — the latter
   *  is the more interesting action to undo (it's a sticky preference,
   *  not just a one-row hide). */
  const muteSender = async (thread: EmailThreadMetaDto) => {
    if (!selectedAccount) return;
    try {
      await window.bridge.muteEmailSender(selectedAccount, thread.from);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return;
    }
    if (!autoArchivedIds.has(thread.id)) {
      void readAndArchive(thread.id);
    }
    queueUndoToast({
      kind: 'mute',
      id: thread.id,
      subject: thread.subject || '(no subject)',
      sender: thread.from,
    });
  };

  /** Reverse a mute: drop the sender from the local mute list and put
   *  the thread back in the inbox. Errors surface but don't roll back —
   *  this is itself an undo action. */
  const undoMute = async (target: { id: string; sender: string }) => {
    if (!selectedAccount) return;
    queueUndoToast(null);
    try {
      await window.bridge.unmuteEmailSender(selectedAccount, target.sender);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    if (autoArchivedIds.has(target.id)) {
      void keepInInbox(target.id);
    }
  };

  const keepInInbox = async (id: string) => {
    if (!selectedAccount) return;
    const prevArchived = autoArchivedIds;
    const prev = threads;
    if (!prev) return;
    setAutoArchivedIds(s => {
      const next = new Set(s);
      next.delete(id);
      return next;
    });
    setThreads(prev.map(t => t.id === id ? { ...t, unread: false } : t));
    // Manual unarchive supersedes the archive toast for the same thread.
    // A mute toast for the same id is a different intent — leave it be
    // (the user can still click Undo on the mute to lift the mute).
    if (undoTarget && undoTarget.kind === 'archive' && undoTarget.id === id) queueUndoToast(null);
    try {
      await window.bridge.keepEmailThreadInInbox(selectedAccount, id);
    }
    catch (e) {
      setAutoArchivedIds(prevArchived);
      setThreads(prev);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  // Cancel any pending undo timer when the page unmounts.
  useEffect(() => {
    return () => {
      if (undoTimerRef.current) clearTimeout(undoTimerRef.current);
    };
  }, []);

  // Auto-action on opening any inbox thread (read or unread): mark-
  // read + archive in a single round trip. Per-thread dedup via
  // autoActedRef so re-selecting after "Keep in inbox" doesn't bounce
  // the user's choice within the same session. After a page refresh
  // the dedup is empty, so a kept-in-inbox thread will re-archive on
  // next click — that's the intentional "in inbox = needs action;
  // opening = dealt with" model.
  //
  // Only fires for inbox-style views. Opening a thread from Archived
  // or Ignored is a "go look at this" gesture, not "deal with it",
  // so it shouldn't take any action.
  useEffect(() => {
    if (!selectedAccount || !selectedThreadId || !threads) return;
    if (activeView.kind !== 'inbox' && activeView.kind !== 'tag') return;
    const t = threads.find(th => th.id === selectedThreadId);
    if (!t || t.view === 'IGNORE') return;
    if (autoActedRef.current.has(selectedThreadId)) return;
    autoActedRef.current.add(selectedThreadId);
    void readAndArchive(selectedThreadId);
    // readAndArchive is captured as a closure; we only want to re-run
    // when the selection changes, not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedThreadId, selectedAccount, activeView]);

  // Reset the auto-act dedup when the account changes — per-account
  // threads are a fresh universe.
  useEffect(() => {
    autoActedRef.current = new Set();
    setAutoArchivedIds(new Set());
  }, [selectedAccount]);

  // Keyboard shortcuts: j/k or arrow keys to walk the visible list.
  // Archive/mark-read are no longer manual — opening already does both
  // — so e/u are gone. Walking still uses the unfiltered threads array
  // so j/k after auto-archiving the current row jumps to the next
  // visible row rather than getting stuck on the now-hidden one.
  // Suppressed when focus is in an input or contenteditable so it
  // doesn't fight typing.
  useEffect(() => {
    if (!threads) return;
    const visible = computeVisibleList({
      activeView,
      threads,
      archiveEntries,
      autoArchivedIds,
      selectedThreadId,
    });
    if (visible.length === 0) return;
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable) return;
      }
      const idx = selectedThreadId
        ? visible.findIndex(t => t.id === selectedThreadId)
        : -1;
      if (e.key === 'j' || e.key === 'ArrowDown') {
        e.preventDefault();
        const next = idx < 0 ? 0 : Math.min(idx + 1, visible.length - 1);
        setSelectedThreadId(visible[next].id);
      }
      else if (e.key === 'k' || e.key === 'ArrowUp') {
        e.preventDefault();
        const next = idx < 0 ? 0 : Math.max(idx - 1, 0);
        setSelectedThreadId(visible[next].id);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [threads, selectedThreadId, selectedAccount, accounts, autoArchivedIds, activeView, archiveEntries]);

  if (accountsError) {
    return (
      <div className="email-page calm-page">
        <div className="repo-error">{accountsError}</div>
      </div>
    );
  }

  if (accounts == null) {
    return <div className="email-page calm-page"><div className="repo-loading">Loading accounts…</div></div>;
  }

  if (accounts.length === 0) {
    return (
      <div className="email-page email-page--empty calm-page">
        <h2>No Gmail account connected</h2>
        <p>Connect a Gmail account in Settings → Integrations to start triaging email here.</p>
        <button className="button button--primary" type="button" onClick={onOpenIntegrationsSettings}>
          Open Integrations settings
        </button>
      </div>
    );
  }

  const account = accounts.find(a => a.email === selectedAccount);
  const visibleThreads = computeVisibleList({
    activeView,
    threads: threads ?? [],
    archiveEntries,
    autoArchivedIds,
    selectedThreadId,
  });
  const selectedThread = visibleThreads.find(t => t.id === selectedThreadId) ?? null;

  return (
    <div className="email-page calm-page">
      <header className="email-page__header">
        <div className="email-page__accounts">
          {accounts.map(acc => (
            <button
              key={acc.email}
              type="button"
              className={`email-account-chip${acc.email === selectedAccount ? ' email-account-chip--active' : ''}`}
              onClick={() => setSelectedAccount(acc.email)}
              title={acc.email}
            >
              <span className="email-account-chip__email">{acc.email}</span>
            </button>
          ))}
        </div>
        <div className="email-page__actions">
          <button
            className="button button--secondary"
            type="button"
            onClick={() => selectedAccount && void loadInbox(selectedAccount, true)}
            disabled={loading || !account}
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {error && <div className="repo-error">{error}</div>}

      {account && threads != null && (
        <div className="email-pane email-pane--with-nav">
          <div className="email-pane__nav">
            <EmailLeftNav
              tags={tags}
              threads={threads}
              archiveEntries={archiveEntries}
              activeView={activeView}
              onSelect={setActiveView}
              onOpenManageRules={() => setRulesModalOpen(true)}
            />
          </div>
          <div className="email-pane__list">
            {visibleThreads.length === 0 && (
              <div className="email-page__hint">{emptyHintFor(activeView)}</div>
            )}
            <ul className="email-list email-list--inset">
              {visibleThreads.map(t => (
                <li
                  key={t.id}
                  className={`email-row${t.unread ? ' email-row--unread' : ''}${t.id === selectedThreadId ? ' email-row--selected' : ''}${autoArchivedIds.has(t.id) ? ' email-row--archived' : ''}`}
                  onClick={() => setSelectedThreadId(t.id)}
                >
                  <div className="email-row__rail" aria-hidden="true" />
                  <div className="email-row__body">
                    <div className="email-row__line1">
                      <span className="email-row__from">
                        {t.view === 'FOCUS' && (
                          <span
                            className="email-row__tag-icon"
                            aria-hidden="true"
                            title={tagNameFor(t.matchedTagId, tags)}
                          >
                            ⭐{' '}
                          </span>
                        )}
                        {shortenFrom(t.from)}
                        {t.messageCount > 1 && (
                          <span className="email-row__count" title={`${t.messageCount} messages in this thread`}>
                            {' '}({t.messageCount})
                          </span>
                        )}
                      </span>
                      <span className="email-row__time">{formatRelative(t.receivedAt)}</span>
                    </div>
                    <div className="email-row__subject">{t.subject || '(no subject)'}</div>
                    <div className="email-row__snippet">{t.snippet}</div>
                  </div>
                </li>
              ))}
            </ul>
          </div>
          <div className="email-pane__detail">
            {selectedThread ? (
              <ThreadDetailPane
                key={selectedThread.id}
                account={selectedAccount!}
                meta={selectedThread}
                archived={autoArchivedIds.has(selectedThread.id) || activeView.kind === 'archived'}
                onKeepInInbox={() => void keepInInbox(selectedThread.id)}
                onMuteSender={() => void muteSender(selectedThread)}
                onOpenLinkedRef={onOpenLinkedRef}
                cachedDetail={detailsCacheRef.current.get(selectedThread.id) ?? null}
                cacheVersion={cacheVersion}
                onDetailFetched={(d) => {
                  detailsCacheRef.current.set(d.id, d);
                }}
              />
            ) : (
              <div className="email-page__hint">Pick a thread on the left.</div>
            )}
          </div>
        </div>
      )}

      {rulesModalOpen && selectedAccount && (
        <ManageRulesModal
          account={selectedAccount}
          tags={tags}
          threads={threads ?? []}
          onClose={() => setRulesModalOpen(false)}
          onSaved={() => {
            setRulesModalOpen(false);
            void loadTags(selectedAccount);
            void loadInbox(selectedAccount, true);
          }}
        />
      )}

      {loading && threads == null && <div className="repo-loading">Loading inbox…</div>}

      {undoTarget && (
        <div className="email-undo-toast" role="status" aria-live="polite">
          <span className="email-undo-toast__msg">
            {undoTarget.kind === 'archive' ? (
              <>Archived <span className="email-undo-toast__subject">{undoTarget.subject || '(no subject)'}</span></>
            ) : (
              <>Muted <span className="email-undo-toast__subject">{shortenFrom(undoTarget.sender)}</span></>
            )}
          </span>
          <button
            type="button"
            className="email-undo-toast__btn"
            onClick={() => {
              if (undoTarget.kind === 'archive') void keepInInbox(undoTarget.id);
              else void undoMute({ id: undoTarget.id, sender: undoTarget.sender });
            }}
          >
            Undo
          </button>
        </div>
      )}
    </div>
  );
}

type DetailProps = {
  account: string;
  meta: EmailThreadMetaDto;
  /** True when the thread was auto-archived in this session — drives
   *  the "Keep in inbox" button's enabled state. Once cleared, the
   *  button disables (the thread is already in the inbox). */
  archived: boolean;
  onKeepInInbox: () => void;
  /** Adds the thread's sender to the per-account mute list and
   *  archives the open thread in one click. Future inbox listings
   *  will filter out the sender's threads. */
  onMuteSender: () => void;
  onOpenLinkedRef: (ref: LinkedRefDto) => void;
  /** Pre-fetched detail from the EmailPage-scoped session cache.
   *  When present and {@code messageCount} matches, skips the
   *  IMAP round-trip entirely so re-opening a thread is instant. */
  cachedDetail: EmailThreadDetailDto | null;
  /** Bumped by EmailPage when the cache is invalidated (Refresh
   *  button, account switch). Watched here as an effect dep so
   *  the same thread re-fetches after a Refresh. */
  cacheVersion: number;
  /** Called with the freshly-fetched detail so EmailPage can
   *  populate its cache for the next open. */
  onDetailFetched: (detail: EmailThreadDetailDto) => void;
};

function ThreadDetailPane({
  account, meta, archived, onKeepInInbox, onMuteSender, onOpenLinkedRef,
  cachedDetail, cacheVersion, onDetailFetched,
}: DetailProps) {
  // Seed from cache when available so the first paint already shows
  // content — no spinner flash on re-opens.
  const initial = cachedDetail && cachedDetail.id === meta.id ? cachedDetail : null;
  const [detail, setDetail] = useState<EmailThreadDetailDto | null>(initial);
  const [loading, setLoading] = useState(initial == null);
  const [error, setError] = useState<string | null>(null);
  // Scroll target for the "jump to the latest message on open" behavior.
  // Refs the wrapper of the most-recent message; scrolled into view via
  // an effect once detail is populated.
  const lastMessageRef = useRef<HTMLDivElement | null>(null);
  // Inline reply composer — collapsed by default, opens below the
  // last message. v1 is plain-text body only; To/Subject are derived
  // server-side from the latest message in the thread.
  const [composerOpen, setComposerOpen] = useState(false);
  const [replyBody, setReplyBody] = useState('');
  const [sendState, setSendState] = useState<'idle' | 'sending'>('idle');
  const [sendError, setSendError] = useState<string | null>(null);

  // Reset the composer when the user picks a different thread —
  // half-typed text in one thread shouldn't bleed into another.
  useEffect(() => {
    setComposerOpen(false);
    setReplyBody('');
    setSendState('idle');
    setSendError(null);
  }, [meta.id]);

  const handleSend = async () => {
    if (sendState === 'sending') return;
    const trimmed = replyBody.trim();
    if (!trimmed) return;
    setSendState('sending');
    setSendError(null);
    try {
      await window.bridge.replyToEmailThread(account, meta.id, trimmed);
      setReplyBody('');
      setComposerOpen(false);
      setSendState('idle');
      // Pull the sent message into view. The 30s inbox poll would
      // catch it eventually; this just tightens the loop so the user
      // sees their reply land at the bottom of the thread immediately.
      try {
        const fresh = await window.bridge.getEmailThread(account, meta.id);
        setDetail(fresh);
      }
      catch { /* best-effort — inbox poll will catch up */ }
    }
    catch (e) {
      setSendError(e instanceof Error ? e.message : String(e));
      setSendState('idle');
    }
  };

  // Re-fetch when:
  //  - the user picks a different thread (meta.id changes), OR
  //  - the inbox poll shows the open thread now has more messages than
  //    we have on screen (meta.messageCount changes), OR
  //  - the cache was busted by a Refresh (cacheVersion changes).
  //
  // If the cache already has a detail whose messageCount matches the
  // inbox row, we skip the fetch entirely — that's the fast path that
  // makes re-opens instant. Loading state only flips for the first
  // case so the silent-refresh path doesn't flash a spinner over
  // content the user is already reading.
  useEffect(() => {
    let cancelled = false;
    const haveFreshCache = detail !== null
            && detail.id === meta.id
            && detail.messages.length >= meta.messageCount;
    if (haveFreshCache) {
      return;
    }
    const isInitialLoad = detail === null || detail.id !== meta.id;
    if (isInitialLoad) {
      setLoading(true);
      setError(null);
      setDetail(null);
    }
    void (async () => {
      try {
        const d = await window.bridge.getEmailThread(account, meta.id);
        if (cancelled) return;
        setDetail(d);
        onDetailFetched(d);
      }
      catch (e) {
        if (cancelled) return;
        if (isInitialLoad) setError(e instanceof Error ? e.message : String(e));
        // Silent-refresh errors are swallowed — keep the existing detail.
      }
      finally {
        if (!cancelled && isInitialLoad) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account, meta.id, meta.messageCount, cacheVersion]);

  // Jump to the bottom (most-recent reply) whenever a fresh detail
  // lands — both on initial open and after a reply is sent. We defer
  // by ~350ms so embedded HTML iframes have had a chance to measure
  // their content height; without that, the offsetTop of the last
  // message is still stale and the scroll lands too high.
  useEffect(() => {
    if (!detail || detail.messages.length === 0) return;
    const target = lastMessageRef.current;
    if (!target) return;
    const timer = setTimeout(() => {
      target.scrollIntoView({ block: 'start', behavior: 'auto' });
    }, 350);
    return () => clearTimeout(timer);
  }, [detail?.id, detail?.messages.length]);

  return (
    <div className="email-detail">
      <div className="email-detail__actions">
        {/* Opening an unread thread already marks-read + archives it
            in one Gmail call. The only manual action left is undoing
            that — putting the thread back in the inbox while keeping
            it read. Disabled when the thread is already in the inbox
            (i.e. user opened a previously-read thread, or already
            clicked "Keep in inbox"). */}
        <button
          className="button button--primary"
          type="button"
          onClick={onKeepInInbox}
          disabled={!archived}
          title={archived
            ? 'Re-add this thread to your inbox (stays marked as read)'
            : 'Already in inbox'}
        >
          📥 Keep in inbox
        </button>
        <button
          className="button button--secondary"
          type="button"
          onClick={onMuteSender}
          title={`Hide future emails from ${shortenFrom(meta.from)} from your inbox (local-only — does not affect gmail.com)`}
        >
          🔕 Mute sender
        </button>
        {detail && detail.messages.length > 1 && (
          <span className="email-detail__count">
            {detail.messages.length} messages
          </span>
        )}
      </div>
      <div className="email-detail__head">
        <div className="email-detail__subject">{detail?.subject ?? meta.subject ?? '(no subject)'}</div>
      </div>
      {loading && <div className="repo-loading">Loading thread…</div>}
      {error && <div className="repo-error">{error}</div>}
      {detail && detail.linkedRefs.length > 0 && (() => {
        // The detector returns one row per occurrence of a link in the
        // thread body, so the same PR/issue mentioned in the subject
        // *and* a reply line yields two identical entries. Dedupe by
        // URL so the card surfaces each target exactly once.
        const dedupedRefs = Array.from(
          new Map(detail.linkedRefs.map(ref => [ref.url, ref])).values(),
        );
        return (
          <div className="email-linked-refs">
            <div className="email-linked-refs__label">Detected links · open in ByteQuay</div>
            <div className="email-linked-refs__items">
              {dedupedRefs.map(ref => (
                <button
                  key={ref.url}
                  type="button"
                  className="email-linked-refs__item"
                  onClick={() => onOpenLinkedRef(ref)}
                  title={ref.url}
                >
                  <span className="email-linked-refs__kind">{ref.kind}</span>
                  <span className="email-linked-refs__num">
                    {ref.kind === 'COMMIT' ? ref.slug : `#${ref.slug}`}
                  </span>
                  <span className="email-linked-refs__repo">{ref.owner}/{ref.repo}</span>
                  <span className="email-linked-refs__arrow">→</span>
                </button>
              ))}
            </div>
          </div>
        );
      })()}
      {detail && (
        <div className="email-detail__messages">
          {detail.messages.map((m, i) => {
            const isLast = i === detail.messages.length - 1;
            return (
              <ThreadMessage
                key={m.id}
                message={m}
                isLast={isLast}
                wrapperRef={isLast ? lastMessageRef : undefined}
              />
            );
          })}
        </div>
      )}
      {detail && (
        <div className="email-reply">
          {composerOpen ? (
            <>
              <textarea
                className="email-reply__textarea"
                placeholder={`Reply to ${shortenFrom(detail.messages[detail.messages.length - 1]?.from ?? '')}…`}
                value={replyBody}
                onChange={e => setReplyBody(e.target.value)}
                rows={6}
                autoFocus
                disabled={sendState === 'sending'}
              />
              {sendError && <div className="repo-error">{sendError}</div>}
              <div className="email-reply__buttons">
                <button
                  type="button"
                  className="button button--primary"
                  onClick={() => void handleSend()}
                  disabled={sendState === 'sending' || !replyBody.trim()}
                >
                  {sendState === 'sending' ? 'Sending…' : 'Send'}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => { setComposerOpen(false); setSendError(null); }}
                  disabled={sendState === 'sending'}
                >
                  Cancel
                </button>
              </div>
            </>
          ) : (
            <button
              type="button"
              className="button button--secondary email-reply__open"
              onClick={() => setComposerOpen(true)}
            >
              ↩ Reply
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function ThreadMessage({ message, isLast, wrapperRef }: {
  message: EmailMessageDetailDto;
  isLast: boolean;
  wrapperRef?: Ref<HTMLDivElement>;
}) {
  return (
    <div
      ref={wrapperRef}
      className={`thread-message${isLast ? ' thread-message--latest' : ''}`}
    >
      <div className="thread-message__head">
        <div className="thread-message__from">{message.from}</div>
        <div className="thread-message__time">{new Date(message.receivedAt).toLocaleString()}</div>
      </div>
      {message.to && <div className="thread-message__to">to {message.to}</div>}
      <div className="thread-message__body">
        {message.bodyHtml
          ? <SanitizedHtml html={message.bodyHtml} />
          : <pre className="thread-message__plain">{message.bodyText ?? '(empty body)'}</pre>}
      </div>
    </div>
  );
}

/** Renders Gmail HTML inside an iframe sandbox.
 *
 *  <p>Sandbox tokens:
 *  <ul>
 *    <li>{@code allow-top-navigation-by-user-activation} — link clicks
 *        navigate the top frame so {@code main.ts}'s will-navigate
 *        handler routes them into the in-app overlay (with ←/→/×
 *        chrome).</li>
 *    <li>{@code allow-same-origin} — lets the parent measure
 *        {@code contentDocument.body.scrollHeight} on load so we can
 *        size the iframe to its content. Safe without allow-scripts:
 *        the email can't execute JS, can't access localStorage, can't
 *        submit forms — it's a read-only document tree.</li>
 *  </ul>
 *  Without auto-sizing the iframe held a fixed slab regardless of
 *  content, so the "boundary" between the email and the empty space
 *  below sat in the middle of the pane. */
function SanitizedHtml({ html }: { html: string }) {
  const safeHtml = html.replace(/<script[\s\S]*?<\/script>/gi, '');
  const wrapped = '<base target="_top">' + safeHtml;
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  // Tracks the ResizeObserver attached to the iframe's body. Disconnected
  // on each new load (srcDoc change) and on unmount.
  const observerRef = useRef<ResizeObserver | null>(null);
  // Resize the iframe to fit its content. Runs on each load (srcDoc
  // change triggers a load), once more after a small delay for inline
  // images to settle, and again whenever the ResizeObserver fires —
  // covers window resize, dragging the inbox/detail split, and any
  // late content reflow inside the email itself.
  const fit = () => {
    const iframe = iframeRef.current;
    if (!iframe) return;
    let doc: Document | null = null;
    try {
      doc = iframe.contentDocument;
    }
    catch {
      return;
    }
    if (!doc?.body || !doc.documentElement) return;
    const height = Math.max(doc.body.scrollHeight, doc.documentElement.scrollHeight);
    // +4 buys a tiny margin so the last line never sits flush against
    // the iframe's bottom edge (which causes a phantom scrollbar).
    iframe.style.height = `${height + 4}px`;
  };

  // Disconnect the observer on unmount. The per-load disconnect lives
  // inside onLoad below — that path swaps observers as the document is
  // replaced, this one cleans up the final outstanding observer.
  useEffect(() => {
    return () => {
      if (observerRef.current) {
        observerRef.current.disconnect();
        observerRef.current = null;
      }
    };
  }, []);

  return (
    <iframe
      ref={iframeRef}
      title="Email body"
      className="email-detail__iframe"
      sandbox="allow-top-navigation-by-user-activation allow-same-origin"
      srcDoc={wrapped}
      onLoad={() => {
        fit();
        // Re-measure after images settle. 200ms covers most inline
        // images cached by Gmail's CDN; for slow loads the ResizeObserver
        // below will catch later reflows.
        setTimeout(fit, 200);
        // Watch the iframe's body for size changes — fires when the
        // user resizes the window, drags the pane split, or when an
        // image loads later than our 200ms timer. Each load swaps the
        // observer (the previous document is being torn down).
        if (observerRef.current) observerRef.current.disconnect();
        const doc = (() => { try { return iframeRef.current?.contentDocument; } catch { return null; } })();
        if (doc?.body && typeof ResizeObserver !== 'undefined') {
          const ro = new ResizeObserver(() => fit());
          ro.observe(doc.body);
          observerRef.current = ro;
        }
      }}
    />
  );
}

/** Strips the address from a "Display Name <addr@host>" header. */
function shortenFrom(raw: string): string {
  if (!raw) return '';
  const angleStart = raw.indexOf('<');
  if (angleStart > 0) {
    const name = raw.slice(0, angleStart).trim().replace(/^"|"$/g, '');
    return name || raw;
  }
  return raw;
}

/** Light-weight relative-time formatter. */
function formatRelative(iso: string): string {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '';
  const deltaSec = Math.floor((Date.now() - t) / 1000);
  if (deltaSec < 60) return 'just now';
  if (deltaSec < 3600) return Math.floor(deltaSec / 60) + 'm ago';
  if (deltaSec < 86400) return Math.floor(deltaSec / 3600) + 'h ago';
  if (deltaSec < 86400 * 7) return Math.floor(deltaSec / 86400) + 'd ago';
  return new Date(t).toLocaleDateString();
}

/**
 * Picks the list rows for the current view.
 *
 * <p>For inbox-style views ({@code inbox} or a focus/ignore tag), we
 * filter the classified threads. The Archived view is special — it
 * reads from the archive-log and maps each entry to a thread-shaped
 * row so the existing row renderer + detail pane work without
 * branching. Auto-archived-this-session threads are still hidden
 * from inbox-style lists unless they're the currently-selected row,
 * matching the pre-existing "don't yank the highlight out from under
 * the user" behavior.
 */
function computeVisibleList({
  activeView, threads, archiveEntries, autoArchivedIds, selectedThreadId,
}: {
  activeView: EmailActiveView;
  threads: EmailThreadMetaDto[];
  archiveEntries: EmailTagArchiveEntryDto[];
  autoArchivedIds: Set<string>;
  selectedThreadId: string | null;
}): EmailThreadMetaDto[] {
  if (activeView.kind === 'archived') {
    return archiveEntries.map(archiveEntryToThreadMeta);
  }
  const keep = (t: EmailThreadMetaDto) => {
    if (activeView.kind === 'inbox') {
      return t.view === 'INBOX' || t.view === 'FOCUS';
    }
    if (activeView.kind === 'ignored') {
      return t.view === 'IGNORE';
    }
    // tag view — match the rule that won precedence for this thread.
    return t.matchedTagId === activeView.tagId;
  };
  return threads.filter(t =>
    keep(t) && (!autoArchivedIds.has(t.id) || t.id === selectedThreadId));
}

function archiveEntryToThreadMeta(entry: EmailTagArchiveEntryDto): EmailThreadMetaDto {
  return {
    id: entry.gmailThreadId,
    latestMessageId: null,
    from: entry.fromAddr ?? '',
    subject: entry.subject ?? '',
    snippet: entry.snippet ?? '',
    receivedAt: entry.receivedAt,
    unread: false,
    messageCount: 1,
    matchedTagId: entry.tagId,
    view: 'ARCHIVE',
  };
}

function emptyHintFor(view: EmailActiveView): string {
  switch (view.kind) {
    case 'inbox': return 'Inbox is empty.';
    case 'archived': return 'Nothing has been archived yet.';
    case 'ignored': return 'No ignored threads in the current inbox window.';
    case 'tag': return 'No threads match this tag right now.';
  }
}

function tagNameFor(tagId: string | null, tags: EmailTagDto[]): string | undefined {
  if (!tagId) return undefined;
  return tags.find(t => t.id === tagId)?.name;
}
