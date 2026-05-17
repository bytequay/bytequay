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
package com.bytequay.app.service.gmail;

import com.bytequay.app.domain.EmailTag;
import com.bytequay.app.domain.EmailTagArchiveEntry;
import com.bytequay.app.domain.EmailThreadMeta;
import com.bytequay.app.repository.EmailTagArchiveLogStore;
import com.bytequay.app.repository.EmailTagStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Owns per-account subject-matching rules for the email surface and
 * the in-memory side of the classification pipeline:
 *
 * <ol>
 *   <li>CRUD on tag rules (UUIDs minted here).</li>
 *   <li>{@link #classify} stamps each inbox thread with the
 *       {@link EmailThreadMeta#matchedTagId()} of the rule that won
 *       precedence and the resulting {@link EmailThreadMeta.View}.</li>
 *   <li>{@link #logArchive} records a row in the archive log when a
 *       thread is actually archived on Gmail (the caller does the
 *       IMAP call; this just persists the audit row).</li>
 * </ol>
 *
 * <p>Precedence between matching rules is fixed: IGNORE beats FOCUS
 * beats ARCHIVE. Rationale: an explicit "ignore" rule should always
 * win (the user wrote it to ban X), and an explicit "focus" should
 * override a catch-all "archive". Stored ordering is therefore
 * unnecessary.
 */
@Service
public class EmailTagService
{
    private final EmailTagStore tagStore;
    private final EmailTagArchiveLogStore archiveLogStore;

    public EmailTagService(EmailTagStore tagStore, EmailTagArchiveLogStore archiveLogStore)
    {
        this.tagStore = requireNonNull(tagStore, "tagStore is null");
        this.archiveLogStore = requireNonNull(archiveLogStore, "archiveLogStore is null");
    }

    /** Lists tags for an account, alphabetised by name for stable rendering. */
    public List<EmailTag> listTags(String accountEmail)
    {
        requireNonBlank(accountEmail, "accountEmail");
        return tagStore.listByAccount(accountEmail).stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    /** Creates a new tag. Mints the UUID id and stamps timestamps here so
     *  the controller never has to know about them. */
    public EmailTag createTag(String accountEmail, String name, String subjectContains, EmailTag.Action action)
    {
        validateInputs(name, subjectContains, action);
        Instant now = Instant.now();
        EmailTag tag = new EmailTag(
                UUID.randomUUID().toString(),
                accountEmail,
                name.trim(),
                subjectContains.trim(),
                action,
                now,
                now);
        tagStore.save(tag);
        return tag;
    }

    /** Updates an existing tag. Preserves createdAt; bumps updatedAt. */
    public EmailTag updateTag(String id, String name, String subjectContains, EmailTag.Action action)
    {
        requireNonBlank(id, "id");
        validateInputs(name, subjectContains, action);
        EmailTag existing = tagStore.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(404), "tag not found: " + id));
        EmailTag updated = new EmailTag(
                existing.id(),
                existing.accountEmail(),
                name.trim(),
                subjectContains.trim(),
                action,
                existing.createdAt(),
                Instant.now());
        tagStore.save(updated);
        return updated;
    }

    public void deleteTag(String id)
    {
        requireNonBlank(id, "id");
        tagStore.deleteById(id);
    }

    /**
     * Returns the input list with each thread carrying the matched
     * tag id and resulting view set. Threads with no matching rule
     * pass through with {@code matchedTagId = null} and
     * {@code view = INBOX}.
     */
    public List<EmailThreadMeta> classify(String accountEmail, List<EmailThreadMeta> threads)
    {
        if (threads.isEmpty()) {
            return threads;
        }
        List<EmailTag> tags = tagStore.listByAccount(accountEmail);
        if (tags.isEmpty()) {
            return threads;
        }
        List<EmailThreadMeta> out = new ArrayList<>(threads.size());
        for (EmailThreadMeta t : threads) {
            EmailTag winner = pickWinner(tags, t.subject());
            if (winner == null) {
                out.add(t);
                continue;
            }
            out.add(t.withClassification(winner.id(), toView(winner.action())));
        }
        return List.copyOf(out);
    }

    /** Writes an archive-log row for a thread we just removed from
     *  Gmail's INBOX. Caller is responsible for the actual IMAP archive
     *  call — this service only persists the audit record. */
    public void logArchive(String accountEmail, EmailThreadMeta thread, String tagId, Instant archivedAt)
    {
        archiveLogStore.save(new EmailTagArchiveEntry(
                accountEmail,
                thread.id(),
                tagId,
                thread.subject(),
                thread.from(),
                thread.snippet(),
                thread.receivedAt() == null ? Instant.EPOCH : thread.receivedAt(),
                archivedAt));
    }

    /** Returns the audit log for the Archived view, newest first. */
    public List<EmailTagArchiveEntry> listArchived(String accountEmail)
    {
        requireNonBlank(accountEmail, "accountEmail");
        return archiveLogStore.listByAccount(accountEmail);
    }

    /** Drops a row from the audit log — used when the user manually
     *  re-adds a previously-archived thread to the inbox. */
    public void forgetArchived(String accountEmail, String gmailThreadId)
    {
        archiveLogStore.delete(accountEmail, gmailThreadId);
    }

    private static EmailTag pickWinner(List<EmailTag> tags, String subject)
    {
        String haystack = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (haystack.isEmpty()) {
            return null;
        }
        EmailTag bestIgnore = null;
        EmailTag bestFocus = null;
        EmailTag bestArchive = null;
        for (EmailTag tag : tags) {
            String needle = tag.subjectContains().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || !haystack.contains(needle)) {
                continue;
            }
            switch (tag.action()) {
                case IGNORE -> {
                    if (bestIgnore == null) {
                        bestIgnore = tag;
                    }
                }
                case FOCUS -> {
                    if (bestFocus == null) {
                        bestFocus = tag;
                    }
                }
                case ARCHIVE -> {
                    if (bestArchive == null) {
                        bestArchive = tag;
                    }
                }
            }
        }
        if (bestIgnore != null) {
            return bestIgnore;
        }
        if (bestFocus != null) {
            return bestFocus;
        }
        return bestArchive;
    }

    private static EmailThreadMeta.View toView(EmailTag.Action action)
    {
        return switch (action) {
            case FOCUS -> EmailThreadMeta.View.FOCUS;
            case ARCHIVE -> EmailThreadMeta.View.ARCHIVE;
            case IGNORE -> EmailThreadMeta.View.IGNORE;
        };
    }

    private static void validateInputs(String name, String subjectContains, EmailTag.Action action)
    {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "name must not be blank");
        }
        if (subjectContains == null || subjectContains.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "subjectContains must not be blank");
        }
        if (action == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "action must not be null");
        }
    }

    private static void requireNonBlank(String value, String fieldName)
    {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    fieldName + " must not be blank");
        }
    }
}
