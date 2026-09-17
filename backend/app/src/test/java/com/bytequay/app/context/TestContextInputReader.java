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

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextInputReader
{
    private static final String SHA = "a".repeat(40);
    private static final RepoRef MONITORED = RepoRef.of("trino", "trino");
    private static final ContextScope COMPONENT = scope("plugin/iceberg");

    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();

    @Test
    void unrelatedCommitsAndWorkingTreeChangesDoNotInvalidateCapturedInputs()
            throws IOException
    {
        initialize();
        write("README.md", "Project overview");
        write("pom.xml", "Dependency declarations");
        write("docs/concepts.md", "Architecture");
        write("plugin/iceberg/Metadata.java", "Relevant implementation");
        write("unrelated/Other.java", "Original unrelated implementation");
        String baseline = commit();
        RepositoryNotebook notebook = notebook(ImmutableList.of(), ImmutableList.of());
        List<ContextInputFingerprint> before = capture(baseline, notebook);

        write("unrelated/Other.java", "An unrelated commit");
        String unrelated = commit();
        write("README.md", "Uncommitted experiment");
        List<ContextInputFingerprint> after = capture(unrelated, notebook);

        assertThat(before).allSatisfy(input -> assertThat(input.digest()).isPresent());
        assertThat(after).isEqualTo(before);
        assertThat(capture(baseline, new RepositoryNotebook(1, 99, true, notebook.sources(), notebook.scopes(), notebook.entries(),
                notebook.suppressedKnowledgeIds(), notebook.sourceEvidence(), Optional.empty()))).isEqualTo(before);
        assertThat(before.getFirst().inputs()).doesNotContainKey("git:unrelated").doesNotContainKey("git:.");
    }

    @Test
    void overlappingLiteralSourcesKeepBothTreeAndFileIdentitiesAndAncestorManifests()
            throws IOException
    {
        initialize();
        String directory = "plugin/ice *[berg] 空间";
        String file = directory + "/:(literal) Metadata.java";
        ContextScope scope = scope(directory);
        write(file, "Implementation");
        String baseline = commit();
        ContextSourceEvidence source = source(new ContextReference.RepositoryFile(7, MONITORED, file, baseline));
        RepositoryNotebook notebook = notebook(scope, ImmutableList.of(), ImmutableList.of(source));
        ContextInputFingerprint before = capture(baseline, notebook).getLast();

        assertThat(before.digest()).isPresent();
        assertThat(before.inputs().get("git:" + directory)).startsWith("040000 tree ");
        assertThat(before.inputs().get("git:" + file)).startsWith("100644 blob ");
        assertThat(before.inputs()).containsEntry("git:plugin/pom.xml", "ABSENT");
        write("plugin/pom.xml", "Parent dependency declarations");
        String withParent = commit();
        ContextInputFingerprint changed = capture(withParent, notebook).getLast();
        assertThat(changed.digest()).isPresent().isNotEqualTo(before.digest());
        assertThat(changed.inputs().get("git:plugin/pom.xml")).startsWith("100644 blob ");
        assertThat(changed.inputs().get("git:" + directory)).isEqualTo(before.inputs().get("git:" + directory));

        write(file, "Changed implementation");
        assertThat(capture(commit(), notebook).getLast().digest()).isNotEqualTo(changed.digest());
    }

    @Test
    void deepReferencesReuseTheComponentTreeAndIgnoreRecaptureProvenance()
            throws IOException
    {
        initialize();
        String directory = "plugin/trino-iceberg";
        String first = directory + "/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java";
        String second = directory + "/src/main/java/io/trino/plugin/iceberg/IcebergTableHandle.java";
        write(first, "Metadata");
        write(second, "Handle");
        String baseline = commit();
        ContextScope scope = scope(directory);
        List<ContextSourceEvidence> sources = ImmutableList.of(
                fileSource("old-a", first, baseline), fileSource("old-b", second, baseline));
        ContextInputFingerprint before = capture(baseline, notebook(scope, ImmutableList.of(), sources)).getLast();
        assertThat(before.digest()).isPresent();
        assertThat(before.inputs()).containsKey("git:plugin/pom.xml").containsKey("git:" + first)
                .doesNotContainKey("git:" + directory + "/src/main/java/io/trino/plugin/pom.xml");

        write("unrelated.txt", "Unrelated commit");
        String later = commit();
        List<ContextSourceEvidence> recaptured = ImmutableList.of(fileSource("new-a", second, later), fileSource("new-b", first, later));
        ContextInputFingerprint after = capture(later, notebook(scope, ImmutableList.of(), recaptured)).getLast();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void unrelatedUnusualGitNamesDoNotInvalidateSelectedSourceInventories()
            throws IOException
    {
        initialize();
        write("README.md", "Overview");
        write("plugin/iceberg/Metadata.java", "Source");
        write("plugin/iceberg/-notes.txt", "Unrelated sibling");
        write("-notes.txt", "Unrelated root file");
        write("odd\\name.txt", "A literal backslash");
        write("line\nbreak.txt", "A literal line break");
        String baseline = commit();
        List<ContextInputFingerprint> inputs = capture(baseline, notebook(ImmutableList.of(),
                ImmutableList.of(fileSource("source", "plugin/iceberg/Metadata.java", baseline))));
        assertThat(inputs).allSatisfy(input -> {
            assertThat(input.digest()).isPresent();
            assertThat(input.inputs()).doesNotContainKey("git:-notes.txt").doesNotContainKey("git:plugin/iceberg/-notes.txt");
        });
    }

    @Test
    void projectStructureTracksRootLayoutWithoutTreatingEveryCodeEditAsAnOverviewChange()
            throws IOException
    {
        initialize();
        write("README.md", "Overview");
        write("src/Main.java", "Original source");
        ContextInputFingerprint before = capture(commit(), RepositoryNotebook.empty()).getFirst();
        write("src/Main.java", "Changed implementation");
        ContextInputFingerprint implementation = capture(commit(), RepositoryNotebook.empty()).getFirst();
        assertThat(implementation).isEqualTo(before);
        write("plugin/Connector.java", "New component");
        ContextInputFingerprint structure = capture(commit(), RepositoryNotebook.empty()).getFirst();
        assertThat(structure.digest()).isPresent().isNotEqualTo(before.digest());
        assertThat(structure.inputs().get("repository-root-layout")).isNotEqualTo(before.inputs().get("repository-root-layout"));
    }

    @Test
    void rootDocumentationAdditionsInvalidateTheProjectInventory()
            throws IOException
    {
        initialize();
        write("README.md", "Overview");
        String baseline = commit();
        ContextInputFingerprint before = capture(baseline, RepositoryNotebook.empty()).getFirst();
        assertThat(before.inputs()).containsEntry("git:docs", "ABSENT");

        write("docs/architecture.md", "New documentation");
        ContextInputFingerprint documented = capture(commit(), RepositoryNotebook.empty()).getFirst();
        assertThat(documented.digest()).isPresent().isNotEqualTo(before.digest());
        write("README.zh.md", "Another discovered overview");
        ContextInputFingerprint addedReadme = capture(commit(), RepositoryNotebook.empty()).getFirst();
        assertThat(addedReadme.digest()).isPresent().isNotEqualTo(documented.digest());
        assertThat(addedReadme.inputs().get("git:README.zh.md")).startsWith("100644 blob ");
    }

    @Test
    void modesAndSubmodulesRemainObjectIdentitiesWithoutFollowingTheirTargets()
            throws IOException
    {
        initialize();
        write("README.md", "Overview");
        String initial = commit();
        Files.createSymbolicLink(repository.resolve("docs"), Path.of("missing-outside-target"));
        command("update-index", "--add", "--cacheinfo", "160000," + initial + ",module");
        command("add", "docs");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Capture object modes");
        String baseline = command("rev-parse", "HEAD").strip();
        RepositoryNotebook notebook = notebook(scope("module"), ImmutableList.of(), ImmutableList.of());

        List<ContextInputFingerprint> captured = capture(baseline, notebook);
        assertThat(captured).allSatisfy(input -> assertThat(input.digest()).isPresent());
        assertThat(captured.getFirst().inputs().get("git:docs")).startsWith("120000 blob ");
        assertThat(captured.getLast().inputs().get("git:module")).isEqualTo("160000 commit " + initial);
        assertThat(repository.resolve("module")).doesNotExist();

        command("update-index", "--chmod=+x", "README.md");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Change mode");
        ContextInputFingerprint executable = capture(command("rev-parse", "HEAD").strip(), notebook).getFirst();
        assertThat(executable.inputs().get("git:README.md")).startsWith("100755 blob ");
        assertThat(executable.digest()).isNotEqualTo(captured.getFirst().digest());
    }

    @Test
    void configurationAndCatalogAreStableCompleteHashesIncludingLongUrls()
    {
        GitCommandRunner local = emptyGit();
        ContextInputReader reader = new ContextInputReader(local);
        RegisteredContextSource first = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/" + "x".repeat(2000) + "/", ContextEntryOrigin.USER, Optional.empty());
        RegisteredContextSource second = new RegisteredContextSource("other", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/other", ContextEntryOrigin.USER, Optional.empty());
        RepositoryNotebook initial = notebook(ImmutableList.of(first, second), ImmutableList.of());
        List<ContextInputFingerprint> before = reader.capture(workspace(), Optional.of(SHA), initial, initial.scopes());
        RepositoryNotebook reordered = notebook(ImmutableList.of(second, first), ImmutableList.of());
        assertThat(reader.capture(workspace(), Optional.of("b".repeat(40)), reordered, reordered.scopes())).isEqualTo(before);
        RepositoryNotebook changed = notebook(ImmutableList.of(first), ImmutableList.of());
        assertThat(reader.capture(workspace(), Optional.of(SHA), changed, changed.scopes()).getLast().digest()).isNotEqualTo(before.getLast().digest());
        ContextScope renamed = new ContextScope(COMPONENT.id(), "Different title", COMPONENT.match(), true, Optional.empty(), Optional.empty());
        RepositoryNotebook renamedNotebook = notebook(renamed, initial.sources(), ImmutableList.of());
        assertThat(reader.capture(workspace(), Optional.of(SHA), renamedNotebook, renamedNotebook.scopes()).getLast().digest()).isNotEqualTo(before.getLast().digest());
    }

    @Test
    void observedDocumentContentAndVersionInvalidateButRetrievalTimeDoesNot()
    {
        GitCommandRunner local = emptyGit();
        ContextInputReader reader = new ContextInputReader(local);
        RegisteredContextSource configured = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/docs/", ContextEntryOrigin.USER, Optional.empty());
        ContextSourceEvidence original = documentSource("content-one", "v1", ContextSourceEvidence.Capture.BYTEQUAY_READ, false);
        RepositoryNotebook notebook = notebook(ImmutableList.of(configured), ImmutableList.of(original));
        ContextInputFingerprint before = reader.capture(workspace(), Optional.of(SHA), notebook, notebook.scopes()).getLast();
        assertThat(before.digest()).isPresent();
        ContextSourceEvidence recaptured = new ContextSourceEvidence("new-id", original.reference(), "Different provenance", "Same observed content",
                original.capture(), Optional.of(Instant.EPOCH.plusSeconds(60)), original.contentHash(), original.version(), false);
        RepositoryNotebook recapturedNotebook = notebook(ImmutableList.of(configured), ImmutableList.of(recaptured));
        assertThat(reader.capture(workspace(), Optional.of(SHA), recapturedNotebook, recapturedNotebook.scopes()).getLast()).isEqualTo(before);
        for (ContextSourceEvidence changed : ImmutableList.of(documentSource("content-two", "v1", ContextSourceEvidence.Capture.BYTEQUAY_READ, false),
                documentSource("content-one", "v2", ContextSourceEvidence.Capture.BYTEQUAY_READ, false))) {
            RepositoryNotebook updated = notebook(ImmutableList.of(configured), ImmutableList.of(changed));
            assertThat(reader.capture(workspace(), Optional.of(SHA), updated, updated.scopes()).getLast().digest()).isPresent().isNotEqualTo(before.digest());
        }
        ContextSourceEvidence missingVersion = new ContextSourceEvidence(original.id(), original.reference(), original.provenance(), original.excerpt(),
                original.capture(), original.retrievedAt(), original.contentHash(), Optional.empty(), false);
        RepositoryNotebook missing = notebook(ImmutableList.of(configured), ImmutableList.of(missingVersion));
        RepositoryNotebook literalUnknown = notebook(ImmutableList.of(configured),
                ImmutableList.of(documentSource("content-one", "unknown", ContextSourceEvidence.Capture.BYTEQUAY_READ, false)));
        assertThat(reader.capture(workspace(), Optional.of(SHA), missing, missing.scopes()).getLast().digest()).isPresent()
                .isNotEqualTo(reader.capture(workspace(), Optional.of(SHA), literalUnknown, literalUnknown.scopes()).getLast().digest());
        for (ContextSourceEvidence unverified : ImmutableList.of(documentSource("content-one", "v1", ContextSourceEvidence.Capture.AGENT_REPORTED, false),
                documentSource("content-one", "v1", ContextSourceEvidence.Capture.BYTEQUAY_READ, true), source(original.reference()))) {
            RepositoryNotebook updated = notebook(ImmutableList.of(configured), ImmutableList.of(unverified));
            ContextInputFingerprint input = reader.capture(workspace(), Optional.of(SHA), updated, updated.scopes()).getLast();
            assertThat(input.digest()).isEmpty();
            assertThat(input.gaps()).anySatisfy(gap -> assertThat(gap).contains("unverified"));
        }
    }

    @Test
    void checkedBlankFileRemainsAnInputAfterEmptyPreparationAndInvalidatesWhenItsBlobChanges()
            throws IOException
    {
        initialize();
        write("README.md", "Project overview");
        write("plugin/iceberg/Metadata.java", "Implementation");
        write("reference/empty.txt", "");
        String baseline = commit();
        RepositoryNotebook initial = notebook(ImmutableList.of(), ImmutableList.of());
        ContextInputFingerprint admitted = capture(baseline, initial).getLast();
        ContextRepositoryRead blank = new ContextRepositoryRead("context-read:blank", new ContextReference.RepositoryFile(7, MONITORED, "reference/empty.txt", baseline),
                command("rev-parse", baseline + ":reference/empty.txt").strip(), Instant.EPOCH, 1, 0, 0, 0, false, Optional.empty());
        ContextPreparationResult preliminary = new ContextPreparationResult("attempt", admitted, ContextPreparationResult.Outcome.EMPTY,
                ImmutableList.of(), ImmutableList.of(blank), "The checked file was empty");
        ContextInputFingerprint acceptedInput = capture(baseline, preparedNotebook(preliminary, ImmutableList.of())).getLast();
        ContextPreparationResult accepted = new ContextPreparationResult("attempt", acceptedInput, ContextPreparationResult.Outcome.EMPTY,
                ImmutableList.of(), ImmutableList.of(blank), preliminary.explanation());
        RepositoryNotebook saved = preparedNotebook(accepted, ImmutableList.of());

        assertThat(acceptedInput.digest()).isPresent().isNotEqualTo(admitted.digest());
        assertThat(acceptedInput.inputs()).containsEntry("git:reference/empty.txt", "100644 blob " + blank.blobId());
        assertThat(capture(baseline, saved).getLast()).isEqualTo(acceptedInput);
        assertThat(saved.sourceEvidence()).isEmpty();
        write("unrelated.txt", "Unrelated change");
        assertThat(capture(commit(), saved).getLast()).isEqualTo(acceptedInput);
        write("reference/empty.txt", "New useful source text");
        ContextInputFingerprint changed = capture(commit(), saved).getLast();
        assertThat(changed.digest()).isPresent().isNotEqualTo(acceptedInput.digest());
        assertThat(changed.inputs().get("git:reference/empty.txt")).isNotEqualTo(acceptedInput.inputs().get("git:reference/empty.txt"));
    }

    @Test
    void checkedBlankDocumentRetainsItsObservedIdentityAndChangedRegistrationCreatesAGap()
            throws IOException
    {
        initialize();
        write("README.md", "Project overview");
        write("plugin/iceberg/Metadata.java", "Implementation");
        String baseline = commit();
        RegisteredContextSource configured = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/docs/", ContextEntryOrigin.USER, Optional.empty());
        RepositoryNotebook initial = notebook(ImmutableList.of(configured), ImmutableList.of());
        ContextInputFingerprint admitted = capture(baseline, initial).getLast();
        ContextDocumentRead blank = new ContextDocumentRead("context-read:blank-document", new ContextReference.Document("docs", configured.url() + "concepts"), configured,
                0, UUID.randomUUID().toString(), "b".repeat(64), Instant.EPOCH, 1, 1, 0, true, false, false, Optional.empty());
        ContextPreparationResult preliminary = new ContextPreparationResult("attempt", admitted, ContextPreparationResult.Outcome.EMPTY,
                ImmutableList.of(), ImmutableList.of(blank), "The captured page was blank");
        ContextInputFingerprint acceptedInput = capture(baseline, preparedNotebook(preliminary, ImmutableList.of(configured))).getLast();
        ContextPreparationResult accepted = new ContextPreparationResult("attempt", acceptedInput, ContextPreparationResult.Outcome.EMPTY,
                ImmutableList.of(), ImmutableList.of(blank), preliminary.explanation());
        RepositoryNotebook saved = preparedNotebook(accepted, ImmutableList.of(configured));

        assertThat(acceptedInput.digest()).isPresent().isNotEqualTo(admitted.digest());
        assertThat(capture(baseline, saved).getLast()).isEqualTo(acceptedInput);
        assertThat(saved.sourceEvidence()).isEmpty();
        RegisteredContextSource changed = new RegisteredContextSource("docs", configured.kind(), "https://example.com/new-docs/", configured.origin(), Optional.empty());
        ContextInputFingerprint after = capture(baseline, preparedNotebook(accepted, ImmutableList.of(changed))).getLast();
        assertThat(after.digest()).isEmpty();
        assertThat(after.gaps()).anySatisfy(gap -> assertThat(gap).contains("registration changed"));
    }

    @Test
    void savingAMemoryLessonDoesNotChangeBaselineKnowledgeInputs()
    {
        GitCommandRunner local = emptyGit();
        ContextInputReader reader = new ContextInputReader(local);
        RepositoryNotebook notebook = notebook(ImmutableList.of(), ImmutableList.of());
        List<ContextInputFingerprint> before = reader.capture(workspace(), Optional.of(SHA), notebook, notebook.scopes());
        ContextSourceEvidence source = source(new ContextReference.Review("review", "run", Optional.of("E1"), Optional.empty()));
        ContextEntry lesson = new ContextEntry("lesson", ContextEntryKind.MEMORY, COMPONENT.id(), "Lesson", "Summary", "Body", ImmutableList.of(),
                ImmutableList.of(source.id()), ContextEntryOrigin.USER, true, 1);
        RepositoryNotebook updated = new RepositoryNotebook(1, 1, true, notebook.sources(), notebook.scopes(), ImmutableList.of(lesson),
                ImmutableList.of(), ImmutableList.of(source), Optional.empty());

        assertThat(reader.capture(workspace(), Optional.of(SHA), updated, updated.scopes())).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HEAD", "--all", "a:b"})
    void absentOrNonImmutableBaselineDoesNotReadGitOrProduceADigest(String baseline)
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        Optional<String> capturedBaseline = baseline.isEmpty() ? Optional.empty() : Optional.of(baseline);
        ContextInputFingerprint fingerprint = new ContextInputReader(local)
                .capture(workspace(), capturedBaseline, RepositoryNotebook.empty(), ImmutableList.of(ContextScope.project())).getFirst();
        assertThat(fingerprint.digest()).isEmpty();
        assertThat(fingerprint.gaps()).isNotEmpty();
        verifyNoInteractions(local);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "truncated", "oversized", "unterminated", "invalid-mode", "invalid-type", "invalid-hash", "duplicate", "traversal"})
    void unavailableOrMalformedInventoryNeverBecomesAnEmptySuccessfulFingerprint(String kind)
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        String valid = "100644 blob " + SHA + "\tREADME.md\0";
        GitCommandRunner.Result result = switch (kind) {
            case "failure" -> GitCommandRunner.Result.failed("Cannot read object");
            case "truncated" -> new GitCommandRunner.Result(0, valid, true);
            case "oversized" -> new GitCommandRunner.Result(0, "x".repeat(256 * 1024 + 1), false);
            case "unterminated" -> new GitCommandRunner.Result(0, valid.substring(0, valid.length() - 1), false);
            case "invalid-mode" -> new GitCommandRunner.Result(0, valid.replace("100644", "100999"), false);
            case "invalid-type" -> new GitCommandRunner.Result(0, valid.replace("blob", "tree"), false);
            case "invalid-hash" -> new GitCommandRunner.Result(0, valid.replace(SHA, "not-a-hash"), false);
            case "duplicate" -> new GitCommandRunner.Result(0, valid + valid, false);
            case "traversal" -> new GitCommandRunner.Result(0, valid.replace("README.md", "../README.md"), false);
            default -> throw new AssertionError(kind);
        };
        when(local.runLocalBounded(eq(repository), any(Duration.class), eq(256 * 1024), anyList())).thenReturn(result);
        ContextInputFingerprint input = new ContextInputReader(local)
                .capture(workspace(), Optional.of(SHA), RepositoryNotebook.empty(), ImmutableList.of(ContextScope.project())).getFirst();
        assertThat(input.digest()).isEmpty();
        assertThat(input.gaps()).isNotEmpty();
        assertThat(input.inputs()).doesNotContainKey("git:README.md");
        verify(local, times(1)).runLocalBounded(eq(repository), any(Duration.class), eq(256 * 1024), anyList());
    }

    @Test
    void failedSelectedPathBatchDoesNotClaimItsPathsAreAbsent()
    {
        GitCommandRunner local = emptyGit();
        when(local.runLocalBounded(eq(repository), any(Duration.class), eq(256 * 1024), anyList()))
                .thenReturn(new GitCommandRunner.Result(0, "", false)).thenReturn(GitCommandRunner.Result.failed("Selected paths unavailable"));
        RepositoryNotebook notebook = notebook(ImmutableList.of(), ImmutableList.of());
        ContextInputFingerprint input = new ContextInputReader(local).capture(workspace(), Optional.of(SHA), notebook, notebook.scopes()).getLast();
        assertThat(input.digest()).isEmpty();
        assertThat(input.inputs()).doesNotContainKey("git:plugin/iceberg");
        assertThat(input.gaps()).anySatisfy(gap -> assertThat(gap).contains("unavailable"));
    }

    @Test
    void sharedPathBudgetBoundsCommandsAcrossManyScopesAndDisclosesExclusions()
    {
        GitCommandRunner local = emptyGit();
        List<ContextScope> scopes = ImmutableList.<ContextScope>builder().add(ContextScope.project())
                .addAll(IntStream.range(0, 99).mapToObj(index -> scope("module-" + index + "/component")).collect(toImmutableList())).build();
        RepositoryNotebook notebook = new RepositoryNotebook(1, 0, true, ImmutableList.of(), scopes, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty());
        List<ContextInputFingerprint> fingerprints = new ContextInputReader(local).capture(workspace(), Optional.of(SHA), notebook, scopes);

        assertThat(fingerprints).hasSize(100).anySatisfy(input -> {
            assertThat(input.digest()).isEmpty();
            assertThat(input.gaps()).anySatisfy(gap -> assertThat(gap).contains("shared limit"));
        });
        assertThat(fingerprints).allSatisfy(input -> {
            assertThat(input.inputs()).hasSizeLessThanOrEqualTo(128);
            assertThat(input.inputBytes()).isLessThanOrEqualTo(ContextInputFingerprint.MAXIMUM_INPUT_BYTES);
        });
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<List<String>> arguments = ArgumentCaptor.captor();
        verify(local, times(5)).runLocalBounded(eq(repository), timeout.capture(), eq(256 * 1024), arguments.capture());
        assertThat(timeout.getAllValues()).allSatisfy(value -> assertThat(value).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2)));
        assertThat(arguments.getAllValues()).allSatisfy(value -> {
            assertThat(value).startsWith("--literal-pathspecs", "ls-tree", "-t", "-z", "--full-tree", SHA, "--");
            assertThat(value).hasSizeLessThanOrEqualTo(135);
        });
    }

    @Test
    void termOnlyAndUnsupportedRetainedSourcesCannotClaimCompleteFreshness()
    {
        GitCommandRunner local = emptyGit();
        ContextScope termOnly = new ContextScope("iceberg", "Iceberg", new ContextMatch.Selected(ImmutableList.of(), ImmutableList.of("Iceberg")),
                true, Optional.empty(), Optional.empty());
        ContextSourceEvidence source = source(new ContextReference.Issue(7, MONITORED, 12, Optional.empty()));
        RepositoryNotebook notebook = notebook(termOnly, ImmutableList.of(), ImmutableList.of(source));

        ContextInputFingerprint input = new ContextInputReader(local).capture(workspace(), Optional.of(SHA), notebook, notebook.scopes()).getLast();

        assertThat(input.digest()).isEmpty();
        assertThat(input.gaps()).anySatisfy(gap -> assertThat(gap).contains("term-only"))
                .anySatisfy(gap -> assertThat(gap).contains("ISSUE"));
    }

    @Test
    void reviewedIdentitiesIncludeLessonFilesAtFrozenHeadWithoutChangingPreparationReuse()
            throws IOException
    {
        initialize();
        write("pom.xml", "Original declarations");
        write("plugin/iceberg/Metadata.java", "Original component");
        write("rules/contract.md", "Original lesson source");
        String reviewedHead = commit();
        String originalBlob = command("rev-parse", reviewedHead + ":rules/contract.md").strip();
        ContextSourceEvidence source = new ContextSourceEvidence("lesson-source", new ContextReference.RepositoryFile(7, MONITORED, "rules/contract.md", reviewedHead),
                "Captured lesson source", "Original lesson source", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(originalBlob), Optional.empty(), false);
        ContextEntry lesson = new ContextEntry("lesson:contract", ContextEntryKind.MEMORY, COMPONENT.id(), "Contract", "Summary", "Conditional lesson",
                ImmutableList.of(), ImmutableList.of(source.id()), ContextEntryOrigin.USER, true, 1);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 0, true, ImmutableList.of(), ImmutableList.of(ContextScope.project(), COMPONENT),
                ImmutableList.of(lesson), ImmutableList.of(), ImmutableList.of(source), Optional.empty());
        ContextInputReader reader = new ContextInputReader(git);
        ContextInputFingerprint preparation = capture(reviewedHead, notebook).getLast();

        write("rules/contract.md", "A later committed source");
        write("pom.xml", "Later declarations");
        String later = commit();
        command("replace", reviewedHead, later);
        write("rules/contract.md", "An uncommitted experiment");
        ContextInputFingerprint frozen = reader.captureReviewed(workspace(), reviewedHead, notebook, ImmutableList.of(COMPONENT), ImmutableList.of(lesson)).getFirst();
        ContextInputFingerprint current = reader.captureReviewed(workspace(), later, notebook, ImmutableList.of(COMPONENT), ImmutableList.of(lesson)).getFirst();

        assertThat(frozen.digest()).isPresent();
        assertThat(frozen.inputs()).containsEntry("git:rules/contract.md", "100644 blob " + originalBlob);
        assertThat(current.inputs().get("git:rules/contract.md")).isNotEqualTo(frozen.inputs().get("git:rules/contract.md"));
        assertThat(current.inputs().get("git:pom.xml")).isNotEqualTo(frozen.inputs().get("git:pom.xml"));
        assertThat(preparation.inputs()).doesNotContainKey("git:rules/contract.md");
        assertThat(capture(reviewedHead, notebook).getLast()).isEqualTo(preparation);
    }

    private List<ContextInputFingerprint> capture(String baseline, RepositoryNotebook notebook)
    {
        return new ContextInputReader(git).capture(workspace(), Optional.of(baseline), notebook, notebook.scopes());
    }

    private GitCommandRunner emptyGit()
    {
        GitCommandRunner local = mock(GitCommandRunner.class);
        when(local.runLocalBounded(eq(repository), any(Duration.class), eq(256 * 1024), anyList())).thenReturn(new GitCommandRunner.Result(0, "", false));
        return local;
    }

    private WorkspaceRepositoryContext workspace()
    {
        return new WorkspaceRepositoryContext("workspace", repository, 1, RepoRef.of("fork", "trino"), OptionalLong.of(7), MONITORED,
                "upstream", "origin", "main", ImmutableList.of());
    }

    private static ContextScope scope(String directory)
    {
        return new ContextScope("path:" + directory + "/", "Component", new ContextMatch.Selected(ImmutableList.of(directory + "/"), ImmutableList.of()),
                true, Optional.empty(), Optional.empty());
    }

    private static RepositoryNotebook notebook(List<RegisteredContextSource> catalog, List<ContextSourceEvidence> sources)
    {
        return notebook(COMPONENT, catalog, sources);
    }

    private static RepositoryNotebook preparedNotebook(ContextPreparationResult result, List<RegisteredContextSource> catalog)
    {
        ContextScope scope = new ContextScope(COMPONENT.id(), COMPONENT.title(), COMPONENT.match(), COMPONENT.enabled(), Optional.of(result), Optional.empty());
        return notebook(scope, catalog, ImmutableList.of());
    }

    private static RepositoryNotebook notebook(ContextScope scope, List<RegisteredContextSource> catalog, List<ContextSourceEvidence> sources)
    {
        List<ContextEntry> entries = sources.isEmpty() ? ImmutableList.of() : ImmutableList.of(new ContextEntry(ContextEntry.knowledgeId(scope.id()),
                ContextEntryKind.KNOWLEDGE, scope.id(), "Source note", "Summary", "Body", ImmutableList.of(), sources.stream().map(ContextSourceEvidence::id).toList(),
                ContextEntryOrigin.USER, true, 1));
        return new RepositoryNotebook(1, 0, true, catalog, ImmutableList.of(ContextScope.project(), scope), entries, ImmutableList.of(), sources, Optional.empty());
    }

    private static ContextSourceEvidence source(ContextReference reference)
    {
        return new ContextSourceEvidence("source", reference, "Captured source", "Excerpt", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private static ContextSourceEvidence fileSource(String id, String path, String commit)
    {
        return new ContextSourceEvidence(id, new ContextReference.RepositoryFile(7, MONITORED, path, commit), "Captured source", "Excerpt",
                ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private static ContextSourceEvidence documentSource(String hash, String version, ContextSourceEvidence.Capture capture, boolean truncated)
    {
        return new ContextSourceEvidence("source", new ContextReference.Document("docs", "https://example.com/docs/concepts"), "Observed document", "Content",
                capture, Optional.of(Instant.EPOCH), Optional.of(hash), Optional.of(version), truncated);
    }

    private void initialize()
    {
        command("init", "-q");
    }

    private void write(String path, String content)
            throws IOException
    {
        Path target = repository.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private String commit()
    {
        command("add", ".");
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
