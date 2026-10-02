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
package com.bytequay.app.context;

import com.bytequay.app.context.ContextSourceCheck.Source;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextSelectedEntry.Applicability;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.context.ContextSourceCheck.Status.ABSENT;
import static com.bytequay.app.context.ContextSourceCheck.Status.CHANGED;
import static com.bytequay.app.context.ContextSourceCheck.Status.UNCHANGED;
import static com.bytequay.app.context.ContextSourceCheck.Status.UNCHECKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextSourceChecker
{
    private static final String WORKSPACE = "workspace";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final ContextPageCaptures pages = mock(ContextPageCaptures.class);
    private final ContextSourceChecker checker = new ContextSourceChecker(git, workspaces, pages);

    @BeforeEach
    void initialize()
    {
        command("init", "-q");
        when(workspaces.requireLocalRepository(WORKSPACE)).thenReturn(workspace());
    }

    @Test
    void comparesCitedLinesRatherThanWholeFiles()
            throws IOException
    {
        write("pom.xml", "<project>", "<java>25</java>", "<maven>3.9</maven>", "</project>");
        write("ci.yml", "jobs:", "  check:", "    run: ./mvnw validate");
        write("README.md", "Build", "Run tests");
        write("DEVELOPMENT.md", "Style", "Use airstyle");
        String learned = commit();
        Map<String, ContextSourceEvidence> sources = ImmutableMap.of(
                "moved", source(learned, "pom.xml", 2, 3),
                "changed", source(learned, "ci.yml", 3, 3),
                "absent", source(learned, "README.md", 1, 2),
                "same", source(learned, "DEVELOPMENT.md", 1, 2));
        write("pom.xml", "<project>", "<dependency>1.2</dependency>", "<dependency>3.4</dependency>", "<java>25</java>", "<maven>3.9</maven>", "</project>");
        write("ci.yml", "jobs:", "  check:", "    run: ./mvnw verify");
        Files.delete(repository.resolve("README.md"));
        String current = commit();

        ContextSourceCheck check = checker.check(WORKSPACE, Optional.of(current), sources);

        assertThat(check.commit()).contains(current);
        assertThat(check.gaps()).isEmpty();
        assertThat(check.sources()).extracting(Source::ref, Source::path, Source::lines, Source::currentLines, Source::status).containsExactly(
                tuple("moved", Optional.of("pom.xml"), Optional.of("2-3"), Optional.of("4-5"), UNCHANGED),
                tuple("changed", Optional.of("ci.yml"), Optional.of("3-3"), Optional.empty(), CHANGED),
                tuple("absent", Optional.of("README.md"), Optional.of("1-2"), Optional.empty(), ABSENT),
                tuple("same", Optional.of("DEVELOPMENT.md"), Optional.of("1-2"), Optional.empty(), UNCHANGED));
        assertThat(check.sources()).extracting(Source::recordedCommit).containsOnly(Optional.of(learned));
        assertThat(check.applicability()).isEqualTo(Applicability.VERSION_MISMATCH);
        assertThat(checker.check(WORKSPACE, Optional.of(current), ImmutableMap.of("moved", sources.get("moved"), "same", sources.get("same"))).applicability())
                .isEqualTo(Applicability.MATCHED);
        verifyNoInteractions(pages);
    }

    @Test
    void aCitedPageChangedWhenALearningRunLaterCapturedItWithoutTheCitedText()
    {
        String url = "https://example.com/spec";
        ContextSourceEvidence page = new ContextSourceEvidence("page", new ContextReference.Document("page:1", url), "Provenance", "Spec text",
                ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.parse("2026-09-01T00:00:00Z")), Optional.of("a".repeat(64)), Optional.empty(), false);
        when(pages.find(WORKSPACE)).thenReturn(ImmutableList.of(new ContextPageCaptures.Page(url, "b".repeat(64), Instant.parse("2026-09-30T00:00:00Z"),
                ImmutableList.of("page"))));

        ContextSourceCheck check = checker.check(WORKSPACE, Optional.empty(), ImmutableMap.of("page", page));

        assertThat(check.sources()).extracting(Source::status).containsExactly(CHANGED);
        assertThat(check.applicability()).isEqualTo(Applicability.VERSION_MISMATCH);
        assertThat(check.gaps()).anySatisfy(gap -> assertThat(gap).contains("The text this note cites from " + url + " is no longer on that page"));
        // Edited elsewhere: the newer capture still holds the cited text.
        when(pages.find(WORKSPACE)).thenReturn(ImmutableList.of(new ContextPageCaptures.Page(url, "b".repeat(64), Instant.parse("2026-09-30T00:00:00Z"),
                ImmutableList.of())));
        assertThat(checker.check(WORKSPACE, Optional.empty(), ImmutableMap.of("page", page)).sources()).extracting(Source::status).containsExactly(UNCHECKED);
        when(pages.find(WORKSPACE)).thenThrow(new ContextStorageException("Saved page captures cannot be read", new IllegalStateException()));
        ContextSourceCheck unreadable = checker.check(WORKSPACE, Optional.empty(), ImmutableMap.of("page", page));
        assertThat(unreadable.sources()).extracting(Source::status).containsExactly(UNCHECKED);
        assertThat(unreadable.gaps()).anySatisfy(gap -> assertThat(gap).startsWith("Cited pages could not be compared"));
    }

    @Test
    void uncheckableSourcesAndFailedLookupsLeaveTheNoteUnverified()
            throws IOException
    {
        write("pom.xml", "<java>25</java>", "<java>25</java>", "</project>");
        String learned = commit();
        ContextSourceEvidence repeated = source(learned, "pom.xml", 1, 1);
        ContextSourceEvidence document = new ContextSourceEvidence("document", new ContextReference.Document("docs", "https://example.com/develop"), "Provenance",
                "Documentation text", ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.empty(), Optional.of("hash"), Optional.empty(), false);
        ContextSourceEvidence reported = withCapture(repeated, ContextSourceEvidence.Capture.AGENT_REPORTED, repeated.excerpt());
        ContextSourceEvidence partial = withCapture(repeated, ContextSourceEvidence.Capture.BYTEQUAY_READ, "1  <java>2");
        ContextSourceEvidence otherRepository = new ContextSourceEvidence("other", new ContextReference.RepositoryFile(99, RepoRef.of("other", "project"), "pom.xml", learned),
                "Provenance", repeated.excerpt(), ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.empty(), repeated.contentHash(), Optional.empty(), false);
        write("pom.xml", "</project>", "<java>25</java>", "<java>25</java>");
        String current = commit();

        ContextSourceCheck check = checker.check(WORKSPACE, Optional.of(current),
                ImmutableMap.of("repeated", repeated, "document", document, "reported", reported, "partial", partial, "other", otherRepository));

        assertThat(check.sources()).extracting(Source::ref, Source::currentLines, Source::status).containsExactly(
                tuple("repeated", Optional.empty(), UNCHANGED),
                tuple("document", Optional.empty(), UNCHECKED),
                tuple("reported", Optional.empty(), UNCHECKED),
                tuple("partial", Optional.empty(), UNCHECKED),
                tuple("other", Optional.empty(), UNCHECKED));
        assertThat(check.sources().get(1).kind()).isEqualTo("DOCUMENT");
        assertThat(check.gaps()).singleElement().asString().contains("pom.xml end in a partial line");
        assertThat(check.applicability()).isEqualTo(Applicability.UNVERIFIED);

        ContextSourceCheck unknown = checker.check(WORKSPACE, Optional.of("f".repeat(40)), ImmutableMap.of("repeated", repeated));
        assertThat(unknown.sources()).extracting(Source::status).containsExactly(UNCHECKED);
        assertThat(unknown.gaps()).singleElement().asString().contains("could not be listed");
        assertThat(unknown.applicability()).isEqualTo(Applicability.UNVERIFIED);

        ContextSourceCheck none = checker.check(WORKSPACE, Optional.empty(), ImmutableMap.of("repeated", repeated));
        assertThat(none.commit()).isEmpty();
        assertThat(none.sources()).extracting(Source::lines, Source::status).containsExactly(tuple(Optional.of("1-1"), UNCHECKED));
        assertThat(none.gaps()).containsExactly("No commit was available to compare cited lines with.");
    }

    @Test
    void savedLinesMustBeOnTheMonitoredBranchAtTheTaskBase()
            throws IOException
    {
        write("Engine.java", "class Engine", "{", "    void run() {}", "}");
        String base = commit();
        command("update-ref", "refs/remotes/upstream/main", base);
        write("Engine.java", "class Engine", "{", "    void run() {}", "    void stop() {}", "}");
        String task = commit();

        assertThat(checker.base(WORKSPACE, task)).contains(base);
        assertThat(checker.base(WORKSPACE, "not-a-commit")).isEmpty();
        checker.requireAtBase(WORKSPACE, base, ImmutableMap.of("run", source(task, "Engine.java", 3, 3)));
        assertThatThrownBy(() -> checker.requireAtBase(WORKSPACE, base, ImmutableMap.of("stop", source(task, "Engine.java", 4, 4))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Engine.java:4-4").hasMessageContaining("own changes");
        assertThat(checker.newerThan(WORKSPACE, base, ImmutableList.of(source(task, "Engine.java", 3, 3)))).isTrue();
        assertThat(checker.newerThan(WORKSPACE, task, ImmutableList.of(source(base, "Engine.java", 3, 3)))).isFalse();
        assertThat(checker.newerThan(WORKSPACE, base, ImmutableList.of(source(base, "Engine.java", 3, 3)))).isFalse();
    }

    @Test
    void headIgnoresUncommittedEdits()
            throws IOException
    {
        write("README.md", "committed");
        String committed = commit();
        write("README.md", "edited");

        assertThat(checker.head(repository)).contains(committed);
        assertThat(checker.head(repository.resolve("missing"))).isEmpty();
    }

    @Test
    void savedExcerptsNeedCompleteConsecutiveNumberedLines()
    {
        assertThat(ContextSourceChecker.excerpt("7  first\n8    indented\n9  \n")).hasValueSatisfying(excerpt -> {
            assertThat(excerpt.start()).isEqualTo(7);
            assertThat(excerpt.lines()).containsExactly("first", "  indented", "");
        });
        assertThat(ContextSourceChecker.excerpt("")).isEmpty();
        assertThat(ContextSourceChecker.excerpt("7  partial")).isEmpty();
        assertThat(ContextSourceChecker.excerpt("7  first\n9  skipped\n")).isEmpty();
        assertThat(ContextSourceChecker.excerpt("first\n")).isEmpty();
    }

    private ContextSourceEvidence source(String commit, String path, int startLine, int endLine)
    {
        ContextBaselineReader.Read read = new ContextBaselineReader(git, CLOCK).read(workspace(), 7, commit, path, startLine, Optional.of(endLine));
        return new ContextSourceEvidence(path, read.reference(), "Provenance", read.excerpt(), ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(read.readAt()), Optional.of(read.blobId()), Optional.empty(), read.truncated());
    }

    private static ContextSourceEvidence withCapture(ContextSourceEvidence source, ContextSourceEvidence.Capture capture, String excerpt)
    {
        return new ContextSourceEvidence(source.id() + "-" + capture, source.reference(), source.provenance(), excerpt, capture, source.retrievedAt(),
                source.contentHash(), source.version(), source.truncated());
    }

    private WorkspaceRepositoryContext workspace()
    {
        return new WorkspaceRepositoryContext(WORKSPACE, repository, 1, RepoRef.of("fork", "trino"), OptionalLong.of(7), RepoRef.of("trino", "trino"),
                "upstream", "origin", "main", ImmutableList.of());
    }

    private void write(String path, String... lines)
            throws IOException
    {
        Files.writeString(repository.resolve(path), String.join("\n", lines) + "\n");
    }

    private String commit()
    {
        command("add", "-A");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Fixture");
        return command("rev-parse", "HEAD").strip();
    }

    private String command(String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }
}
