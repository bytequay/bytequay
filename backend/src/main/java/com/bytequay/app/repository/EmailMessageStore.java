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
package com.bytequay.app.repository;

import com.bytequay.app.domain.EmailMessageMeta;
import com.bytequay.app.domain.EmailThreadMeta;

import java.util.List;
import java.util.Optional;

/**
 * Local mirror of Gmail messages, populated by EmailSyncService and
 * read by EmailService for the inbox list view. Per-account scoped.
 */
public interface EmailMessageStore
{
    /** Bulk upsert. Used by full-sync and incremental-sync to write
     *  fresh metadata for messages we've fetched from Gmail. */
    void upsertAll(String accountEmail, List<EmailMessageMeta> messages);

    /** Drops a single message — for {@code messagesDeleted} history
     *  events. Idempotent. */
    void deleteMessage(String accountEmail, String gmailMessageId);

    /** Drops every cached message for an account — used when the
     *  account is disconnected. */
    void deleteAllForAccount(String accountEmail);

    /** Updates a single message's labels in-place (in_inbox, unread).
     *  Returns true when the row existed; false otherwise. */
    boolean updateLabels(
            String accountEmail,
            String gmailMessageId,
            boolean inInbox,
            boolean unread);

    /** Inbox aggregated to threads, newest first, capped at limit.
     *  Picks the latest message per thread and reports the per-thread
     *  message count and any-unread state. Returns empty when the
     *  cache is empty. */
    List<EmailThreadMeta> listInboxThreads(String accountEmail, int limit);

    /** Every cached message ID in a thread — used by the mutation
     *  write-through so an archive/mark-read can update all of the
     *  thread's local rows without refetching from Gmail. */
    List<String> listMessageIdsInThread(String accountEmail, String gmailThreadId);

    /** Per-account sync state. Empty when the account has never been
     *  synced; the caller then runs a full sync. */
    Optional<SyncState> getSyncState(String accountEmail);

    /** Persists the latest historyId after a successful sync. */
    void setSyncState(String accountEmail, String lastHistoryId, long lastSyncAtMs);

    /** Drops the sync watermark — used when the account is
     *  disconnected. */
    void deleteSyncStateForAccount(String accountEmail);

    record SyncState(String lastHistoryId, long lastSyncAtMs) {}
}
