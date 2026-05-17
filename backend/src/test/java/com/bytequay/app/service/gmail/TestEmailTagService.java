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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestEmailTagService
{
    private static final String ACCOUNT = "me@gmail.com";
    private static final Instant NOW = Instant.parse("2026-05-17T10:00:00Z");

    @Test
    void testNoTagsLeavesThreadsUnchanged()
    {
        EmailTagService svc = service();
        List<EmailThreadMeta> threads = List.of(thread("t1", "Pull request opened"));
        List<EmailThreadMeta> out = svc.classify(ACCOUNT, threads);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).view()).isEqualTo(EmailThreadMeta.View.INBOX);
        assertThat(out.get(0).matchedTagId()).isNull();
    }

    @Test
    void testFocusTagClassifiesAsFocus()
    {
        EmailTagService svc = service();
        EmailTag tag = svc.createTag(ACCOUNT, "GitHub", "Pull request", EmailTag.Action.FOCUS);
        List<EmailThreadMeta> out = svc.classify(ACCOUNT, List.of(thread("t1", "Pull request opened")));
        assertThat(out.get(0).view()).isEqualTo(EmailThreadMeta.View.FOCUS);
        assertThat(out.get(0).matchedTagId()).isEqualTo(tag.id());
    }

    @Test
    void testSubstringMatchIsCaseInsensitive()
    {
        EmailTagService svc = service();
        svc.createTag(ACCOUNT, "GitHub", "pull request", EmailTag.Action.FOCUS);
        List<EmailThreadMeta> out = svc.classify(ACCOUNT, List.of(thread("t1", "[acme/widgets] Pull Request #42")));
        assertThat(out.get(0).view()).isEqualTo(EmailThreadMeta.View.FOCUS);
    }

    @Test
    void testIgnoreBeatsFocusBeatsArchive()
    {
        EmailTagService svc = service();
        EmailTag focus = svc.createTag(ACCOUNT, "GitHub", "GitHub", EmailTag.Action.FOCUS);
        EmailTag archive = svc.createTag(ACCOUNT, "Notify", "notification", EmailTag.Action.ARCHIVE);
        EmailTag ignore = svc.createTag(ACCOUNT, "CI", "build failed", EmailTag.Action.IGNORE);

        // Subject matches all three — precedence picks IGNORE.
        EmailThreadMeta allThree = thread("t1", "[GitHub] notification: build failed for main");
        List<EmailThreadMeta> out = svc.classify(ACCOUNT, List.of(allThree));
        assertThat(out.get(0).view()).isEqualTo(EmailThreadMeta.View.IGNORE);
        assertThat(out.get(0).matchedTagId()).isEqualTo(ignore.id());

        // Matches FOCUS and ARCHIVE only — FOCUS wins.
        EmailThreadMeta focusAndArchive = thread("t2", "[GitHub] notification: review requested");
        out = svc.classify(ACCOUNT, List.of(focusAndArchive));
        assertThat(out.get(0).view()).isEqualTo(EmailThreadMeta.View.FOCUS);
        assertThat(out.get(0).matchedTagId()).isEqualTo(focus.id());

        // Matches ARCHIVE only.
        EmailThreadMeta archiveOnly = thread("t3", "Stripe billing notification");
        out = svc.classify(ACCOUNT, List.of(archiveOnly));
        assertThat(out.get(0).view()).isEqualTo(EmailThreadMeta.View.ARCHIVE);
        assertThat(out.get(0).matchedTagId()).isEqualTo(archive.id());
    }

    @Test
    void testEmptySubjectMatchesNothing()
    {
        EmailTagService svc = service();
        svc.createTag(ACCOUNT, "Anything", "x", EmailTag.Action.ARCHIVE);
        EmailThreadMeta blank = thread("t1", "");
        EmailThreadMeta nullSubject = new EmailThreadMeta(
                "t2", "m2", "from@example.com", null, "snip", NOW, false, 1);
        List<EmailThreadMeta> out = svc.classify(ACCOUNT, List.of(blank, nullSubject));
        assertThat(out).extracting(EmailThreadMeta::view)
                .containsExactly(EmailThreadMeta.View.INBOX, EmailThreadMeta.View.INBOX);
    }

    @Test
    void testTagsScopedPerAccount()
    {
        EmailTagService svc = service();
        svc.createTag("me@gmail.com", "GitHub", "Pull request", EmailTag.Action.FOCUS);
        svc.createTag("alt@gmail.com", "GitHub", "Pull request", EmailTag.Action.ARCHIVE);

        EmailThreadMeta t = thread("t1", "Pull request #1 opened");
        assertThat(svc.classify("me@gmail.com", List.of(t)).get(0).view())
                .isEqualTo(EmailThreadMeta.View.FOCUS);
        assertThat(svc.classify("alt@gmail.com", List.of(t)).get(0).view())
                .isEqualTo(EmailThreadMeta.View.ARCHIVE);
    }

    @Test
    void testListTagsSortsByName()
    {
        EmailTagService svc = service();
        svc.createTag(ACCOUNT, "Zeta", "z", EmailTag.Action.FOCUS);
        svc.createTag(ACCOUNT, "alpha", "a", EmailTag.Action.IGNORE);
        svc.createTag(ACCOUNT, "Mu", "m", EmailTag.Action.ARCHIVE);
        assertThat(svc.listTags(ACCOUNT)).extracting(EmailTag::name)
                .containsExactly("alpha", "Mu", "Zeta");
    }

    @Test
    void testLogArchiveRoundTrips()
    {
        EmailTagService svc = service();
        EmailTag t = svc.createTag(ACCOUNT, "Newsletters", "unsubscribe", EmailTag.Action.ARCHIVE);
        EmailThreadMeta thread = thread("t1", "Weekly digest — unsubscribe");
        svc.logArchive(ACCOUNT, thread, t.id(), NOW);
        List<EmailTagArchiveEntry> entries = svc.listArchived(ACCOUNT);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).gmailThreadId()).isEqualTo("t1");
        assertThat(entries.get(0).tagId()).isEqualTo(t.id());

        svc.forgetArchived(ACCOUNT, "t1");
        assertThat(svc.listArchived(ACCOUNT)).isEmpty();
    }

    private static EmailTagService service()
    {
        return new EmailTagService(new InMemoryTagStore(), new InMemoryArchiveLogStore());
    }

    private static EmailThreadMeta thread(String id, String subject)
    {
        return new EmailThreadMeta(id, "m-" + id, "sender@example.com", subject,
                "snippet", NOW, false, 1);
    }

    /** Minimal in-memory store so the tests don't pull in Spring/JPA. */
    private static final class InMemoryTagStore
            implements EmailTagStore
    {
        private final Map<String, EmailTag> byId = new LinkedHashMap<>();

        @Override
        public void save(EmailTag tag)
        {
            byId.put(tag.id(), tag);
        }

        @Override
        public Optional<EmailTag> findById(String id)
        {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<EmailTag> listByAccount(String accountEmail)
        {
            return byId.values().stream()
                    .filter(t -> t.accountEmail().equals(accountEmail))
                    .toList();
        }

        @Override
        public void deleteById(String id)
        {
            byId.remove(id);
        }
    }

    private static final class InMemoryArchiveLogStore
            implements EmailTagArchiveLogStore
    {
        private final List<EmailTagArchiveEntry> rows = new ArrayList<>();

        @Override
        public void save(EmailTagArchiveEntry entry)
        {
            rows.removeIf(r -> r.accountEmail().equals(entry.accountEmail())
                    && r.gmailThreadId().equals(entry.gmailThreadId()));
            rows.add(entry);
        }

        @Override
        public void delete(String accountEmail, String gmailThreadId)
        {
            rows.removeIf(r -> r.accountEmail().equals(accountEmail)
                    && r.gmailThreadId().equals(gmailThreadId));
        }

        @Override
        public List<EmailTagArchiveEntry> listByAccount(String accountEmail)
        {
            return rows.stream()
                    .filter(r -> r.accountEmail().equals(accountEmail))
                    .sorted(Comparator.comparing(EmailTagArchiveEntry::archivedAt).reversed())
                    .toList();
        }
    }
}
