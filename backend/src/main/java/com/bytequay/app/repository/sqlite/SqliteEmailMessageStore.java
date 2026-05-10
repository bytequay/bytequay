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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.EmailMessageMeta;
import com.bytequay.app.domain.EmailThreadMeta;
import com.bytequay.app.repository.EmailMessageStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteEmailMessageStore
        implements EmailMessageStore
{
    private final EmailMessageJpaRepository messages;
    private final EmailAccountSyncJpaRepository sync;

    public SqliteEmailMessageStore(
            EmailMessageJpaRepository messages,
            EmailAccountSyncJpaRepository sync)
    {
        this.messages = requireNonNull(messages, "messages is null");
        this.sync = requireNonNull(sync, "sync is null");
    }

    @Override
    @Transactional
    public void upsertAll(String accountEmail, List<EmailMessageMeta> incoming)
    {
        long now = System.currentTimeMillis();
        for (EmailMessageMeta m : incoming) {
            EmailMessageEntity row = messages.findByIdAccountEmailAndIdGmailMessageId(
                    accountEmail, m.id())
                    .orElseGet(() -> {
                        EmailMessageEntity fresh = new EmailMessageEntity();
                        fresh.setId(new EmailMessageEntity.EmailMessageKey(accountEmail, m.id()));
                        return fresh;
                    });
            row.setGmailThreadId(m.threadId());
            row.setFromAddr(m.from());
            row.setSubject(m.subject());
            row.setSnippet(m.snippet());
            row.setReceivedAtMs(m.receivedAt().toEpochMilli());
            row.setUnread(m.unread());
            // Anything we just fetched is in the inbox by definition —
            // it came from labelIds=INBOX. messagesDeleted history
            // events flip is_in_inbox via deleteMessage() / updateLabels().
            row.setInInbox(true);
            row.setCachedAtMs(now);
            messages.save(row);
        }
    }

    @Override
    @Transactional
    public void deleteMessage(String accountEmail, String gmailMessageId)
    {
        messages.deleteOne(accountEmail, gmailMessageId);
    }

    @Override
    @Transactional
    public void deleteAllForAccount(String accountEmail)
    {
        messages.deleteAllForAccount(accountEmail);
    }

    @Override
    @Transactional
    public boolean updateLabels(
            String accountEmail,
            String gmailMessageId,
            boolean inInbox,
            boolean unread)
    {
        return messages.findByIdAccountEmailAndIdGmailMessageId(accountEmail, gmailMessageId)
                .map(row -> {
                    row.setInInbox(inInbox);
                    row.setUnread(unread);
                    messages.save(row);
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmailThreadMeta> listInboxThreads(String accountEmail, int limit)
    {
        return messages.findInboxThreadHeads(accountEmail, limit).stream()
                .map(SqliteEmailMessageStore::toThreadMeta)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listMessageIdsInThread(String accountEmail, String gmailThreadId)
    {
        return messages.findByIdAccountEmailAndGmailThreadIdOrderByReceivedAtMsAsc(
                        accountEmail, gmailThreadId).stream()
                .map(e -> e.getId().getGmailMessageId())
                .collect(toImmutableList());
    }

    private static EmailThreadMeta toThreadMeta(Object[] row)
    {
        // Column order matches the SELECT in findInboxThreadHeads.
        String latestMessageId = (String) row[0];
        String threadId = (String) row[1];
        String fromAddr = (String) row[2];
        String subject = (String) row[3];
        String snippet = (String) row[4];
        long receivedAtMs = ((Number) row[5]).longValue();
        // is_unread (col 6) is the latest message's flag; for the
        // thread row we want the OR over the whole thread, which is
        // any_unread (col 8).
        int messageCount = ((Number) row[7]).intValue();
        boolean anyUnread = ((Number) row[8]).intValue() != 0;
        return new EmailThreadMeta(
                threadId,
                latestMessageId,
                fromAddr == null ? "" : fromAddr,
                subject == null ? "" : subject,
                snippet == null ? "" : snippet,
                Instant.ofEpochMilli(receivedAtMs),
                anyUnread,
                messageCount);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SyncState> getSyncState(String accountEmail)
    {
        return sync.findById(accountEmail)
                .filter(e -> e.getLastHistoryId() != null)
                .map(e -> new SyncState(
                        e.getLastHistoryId(),
                        e.getLastSyncAtMs() == null ? 0L : e.getLastSyncAtMs()));
    }

    @Override
    @Transactional
    public void setSyncState(String accountEmail, String lastHistoryId, long lastSyncAtMs)
    {
        EmailAccountSyncEntity row = sync.findById(accountEmail).orElseGet(() -> {
            EmailAccountSyncEntity fresh = new EmailAccountSyncEntity();
            fresh.setAccountEmail(accountEmail);
            return fresh;
        });
        row.setLastHistoryId(lastHistoryId);
        row.setLastSyncAtMs(lastSyncAtMs);
        sync.save(row);
    }

    @Override
    @Transactional
    public void deleteSyncStateForAccount(String accountEmail)
    {
        sync.deleteById(accountEmail);
    }
}
