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
package com.bytequay.core.context;

import com.bytequay.core.domain.RepoRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestContextSourceRead
{
    private static final String SHA = "1".repeat(40);
    private static final String CAPTURE = UUID.randomUUID().toString();
    private static final RegisteredContextSource ROOT = new RegisteredContextSource(
            "docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", ContextEntryOrigin.USER);

    @Test
    void requiresCompleteRepositoryIdentityAndValidNonemptyOrEmptyLineRanges()
    {
        ContextReference.RepositoryFile reference = new ContextReference.RepositoryFile(1, RepoRef.of("example", "repo"), "README.md", SHA);
        ContextRepositoryRead empty = new ContextRepositoryRead("context-read:empty", reference, SHA, Instant.EPOCH, 1, 0, 0, 0, false, Optional.empty());
        assertThat(empty.evidenceRef()).isEmpty();
        assertThatThrownBy(() -> new ContextRepositoryRead("context-read:bad", reference, SHA, Instant.EPOCH, 1, 0, 0, 1, false, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextRepositoryRead("context-read:bad", reference, SHA, Instant.EPOCH, 1, 0, 0, 0, true, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextRepositoryRead("context-read:bad", reference, SHA, Instant.EPOCH, 2, 1, 3, 5, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextRepositoryRead("context-read:bad", reference, SHA, Instant.EPOCH, 1, 2, 1, 5, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextRepositoryRead("context-read:bad", reference, "short", Instant.EPOCH, 1, 1, 1, 5, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextRepositoryRead("context-read:bad", reference, SHA, Instant.EPOCH, 1, 1, 1, 5, false, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsBlankDocumentPagesDistinctFromEvidenceAndFailedRefresh()
    {
        ContextDocumentRead blank = document(ROOT, "docs", "https://example.com/docs/page", 1, 1, 2, true, Optional.empty());
        assertThat(blank.utf8Bytes()).isEqualTo(2);
        assertThat(blank.blank()).isTrue();
        assertThat(blank.refreshFailed()).isTrue();
        assertThat(blank.readAt()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(() -> document(ROOT, "docs", "https://example.com/docs/page", 1, 1, 0, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document(ROOT, "docs", "https://example.com/docs/page", 1, 1, 4, true, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document(ROOT, "docs", "https://example.com/docs/page", 0, 1, 4, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document(ROOT, "docs", "https://example.com/docs/page", 2, 1, 4, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresDocumentIdentityToStayWithinCapturedRegistration()
    {
        assertThatThrownBy(() -> document(ROOT, "other", "https://example.com/docs/page", 1, 1, 4, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document(ROOT, "docs", "https://example.com/docs-other/page", 1, 1, 4, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
        RegisteredContextSource exact = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs/page", ContextEntryOrigin.USER);
        assertThat(document(exact, "docs", exact.url(), 1, 1, 4, false, Optional.of("evidence")).registration()).isEqualTo(exact);
        assertThatThrownBy(() -> document(exact, "docs", exact.url() + "/child", 1, 1, 4, false, Optional.of("evidence")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ContextDocumentRead document(
            RegisteredContextSource registration, String sourceId, String url, int page, int totalPages, int bytes, boolean blank, Optional<String> evidenceRef)
    {
        return new ContextDocumentRead("context-read:document", new ContextReference.Document(sourceId, url), registration, 7, CAPTURE, "a".repeat(64),
                Instant.EPOCH, page, totalPages, bytes, blank, false, true, evidenceRef);
    }
}
