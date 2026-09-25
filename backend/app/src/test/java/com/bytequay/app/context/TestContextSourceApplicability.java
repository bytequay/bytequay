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

import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.READY;
import static com.bytequay.core.context.ContextSelectedEntry.Applicability.MATCHED;
import static com.bytequay.core.context.ContextSelectedEntry.Applicability.UNVERIFIED;
import static com.bytequay.core.context.ContextSelectedEntry.Applicability.VERSION_MISMATCH;
import static com.bytequay.core.context.ContextSourceEvidence.Capture.AGENT_REPORTED;
import static com.bytequay.core.context.ContextSourceEvidence.Capture.BYTEQUAY_READ;
import static org.assertj.core.api.Assertions.assertThat;

class TestContextSourceApplicability
{
    private static final String COMMIT = "a".repeat(40);
    private static final String BLOB = "b".repeat(40);
    private static final String CHANGED = "c".repeat(40);
    private static final WorkspaceRepositoryContext WORKSPACE = new WorkspaceRepositoryContext("workspace", Path.of("/tmp/applicability"), 1,
            RepoRef.of("fork", "repo"), OptionalLong.of(7), RepoRef.of("upstream", "repo"), "upstream", "origin", "main", ImmutableList.of());
    private static final ContextReference.RepositoryFile FILE = new ContextReference.RepositoryFile(7, WORKSPACE.monitoredRepository(), "src/code.java", COMMIT);
    private static final ContextSourceEvidence SOURCE = new ContextSourceEvidence("source", FILE, "Captured file", "A conditional claim", BYTEQUAY_READ,
            Optional.of(Instant.EPOCH), Optional.of(BLOB), Optional.empty(), false);

    @Test
    void matchesCapturedBlobsAndRecordedDependencyInputsWithoutVerifyingClaims()
    {
        ContextEntry entry = note(SOURCE);
        Map<String, String> identities = ImmutableMap.of("repository", "7:upstream/repo", "git:src/code.java", "100644 blob " + BLOB,
                "git:pom.xml", "100644 blob " + COMMIT, "git:package-lock.json", "ABSENT");
        ContextScope prepared = prepared(entry, identities);

        ContextSourceApplicability.Result result = compare(entry, prepared, SOURCE, fingerprint(identities));

        assertThat(result.applicability()).isEqualTo(MATCHED);
        assertThat(result.reason()).contains("1 captured repository-file identities", "3 recorded source/dependency inputs", "Claims", "remain UNVERIFIED");
        Map<String, String> updated = ImmutableMap.<String, String>builder().putAll(identities).put("git:pom.xml", "100644 blob " + CHANGED).buildKeepingLast();
        ContextSourceApplicability.Result mismatch = compare(entry, prepared, SOURCE, fingerprint(updated));
        assertThat(mismatch.applicability()).isEqualTo(VERSION_MISMATCH);
        assertThat(mismatch.reason()).contains("git:pom.xml", COMMIT, CHANGED, "Effective dependency versions remain UNVERIFIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed", "absent", "symlink", "submodule"})
    void recordsConcreteChangedDeletedOrNonregularSourceIdentities(String kind)
    {
        String observed = switch (kind) {
            case "changed" -> "100644 blob " + CHANGED;
            case "absent" -> "ABSENT";
            case "symlink" -> "120000 blob " + BLOB;
            case "submodule" -> "160000 commit " + COMMIT;
            default -> throw new AssertionError(kind);
        };
        ContextSourceApplicability.Result result = compare(note(SOURCE), ContextScope.project(), SOURCE, fingerprint(ImmutableMap.of("git:src/code.java", observed)));

        assertThat(result.applicability()).isEqualTo(VERSION_MISMATCH);
        assertThat(result.reason()).contains("src/code.java", BLOB, observed, "not a defect finding");
    }

    @Test
    void comparesGroupedAncestorManifestsAndKeepsOmittedGroupsUnverified()
    {
        ContextEntry entry = note(SOURCE);
        Map<String, String> identities = ImmutableMap.of("repository", "7:upstream/repo", "git:src/code.java", "100644 blob " + BLOB,
                "git-manifests:src", "d".repeat(64));
        ContextScope prepared = prepared(entry, identities);
        assertThat(compare(entry, prepared, SOURCE, fingerprint(identities)).applicability()).isEqualTo(MATCHED);
        Map<String, String> changed = ImmutableMap.<String, String>builder().putAll(identities)
                .put("git-manifests:src", "e".repeat(64)).buildKeepingLast();
        ContextSourceApplicability.Result mismatch = compare(entry, prepared, SOURCE, fingerprint(changed));
        assertThat(mismatch.applicability()).isEqualTo(VERSION_MISMATCH);
        assertThat(mismatch.reason()).contains("git-manifests:src", "Effective dependency versions remain UNVERIFIED");
        ContextInputFingerprint omitted = fingerprint(ImmutableMap.of("repository", "7:upstream/repo", "git:src/code.java", "100644 blob " + BLOB));
        assertThat(compare(entry, prepared, SOURCE, omitted).applicability()).isEqualTo(UNVERIFIED);
    }

    @Test
    void externalAndAgentReportedSourcesNeverAcquireAnInferredVersionMapping()
    {
        for (ContextSourceEvidence.Capture capture : ImmutableList.of(BYTEQUAY_READ, AGENT_REPORTED)) {
            ContextSourceEvidence document = new ContextSourceEvidence("document", new ContextReference.Document("docs", "https://example.com/v1.0/guide"),
                    "Reported version 1.0", "The API works this way", capture, Optional.of(Instant.EPOCH), Optional.of(BLOB), Optional.of("1.0"), false);
            ContextSourceApplicability.Result result = compare(note(document), ContextScope.project(), document, fingerprint(ImmutableMap.of()));
            assertThat(result.applicability()).isEqualTo(UNVERIFIED);
            assertThat(result.reason()).contains(capture == BYTEQUAY_READ ? "does not establish a version mapping" : "AGENT_REPORTED");
        }
        ContextSourceEvidence reportedFile = new ContextSourceEvidence(SOURCE.id(), FILE, SOURCE.provenance(), SOURCE.excerpt(), AGENT_REPORTED,
                SOURCE.retrievedAt(), SOURCE.contentHash(), SOURCE.version(), false);
        ContextSourceApplicability.Result result = compare(note(reportedFile), ContextScope.project(), reportedFile,
                fingerprint(ImmutableMap.of("git:src/code.java", "100644 blob " + BLOB)));
        assertThat(result.applicability()).isEqualTo(UNVERIFIED);
        assertThat(result.reason()).contains("AGENT_REPORTED");
    }

    @Test
    void unavailableTruncatedOrForeignEvidenceDoesNotProduceAMatch()
    {
        ContextInputFingerprint complete = fingerprint(ImmutableMap.of("git:src/code.java", "100644 blob " + BLOB));
        ContextSourceEvidence truncated = new ContextSourceEvidence(SOURCE.id(), FILE, SOURCE.provenance(), SOURCE.excerpt(), BYTEQUAY_READ,
                SOURCE.retrievedAt(), SOURCE.contentHash(), SOURCE.version(), true);
        ContextSourceEvidence foreign = new ContextSourceEvidence(SOURCE.id(), new ContextReference.RepositoryFile(1, WORKSPACE.selectedRepository(), FILE.path(), COMMIT),
                SOURCE.provenance(), SOURCE.excerpt(), BYTEQUAY_READ, SOURCE.retrievedAt(), SOURCE.contentHash(), SOURCE.version(), false);
        ContextSourceEvidence unidentified = new ContextSourceEvidence(SOURCE.id(), FILE, SOURCE.provenance(), SOURCE.excerpt(), BYTEQUAY_READ,
                SOURCE.retrievedAt(), Optional.empty(), SOURCE.version(), false);
        for (ContextSourceEvidence source : ImmutableList.of(truncated, foreign, unidentified)) {
            assertThat(compare(note(source), ContextScope.project(), source, complete).applicability()).isEqualTo(UNVERIFIED);
        }
        assertThat(compare(note(SOURCE), ContextScope.project(), SOURCE, fingerprint(ImmutableMap.of())).applicability()).isEqualTo(UNVERIFIED);
        assertThat(ContextSourceApplicability.compare(note(SOURCE), Optional.of(ContextScope.project()), ImmutableMap.of(SOURCE.id(), SOURCE),
                Optional.empty(), WORKSPACE).applicability()).isEqualTo(UNVERIFIED);
    }

    @Test
    void omittedInventoryAndLongGapRemainBoundedUnverifiedObservations()
    {
        ContextInputFingerprint incomplete = ContextInputFingerprint.create("project", ImmutableMap.of("git:src/code.java", "100644 blob " + BLOB),
                ImmutableList.of("界".repeat(666)));
        ContextSourceApplicability.Result result = compare(note(SOURCE), ContextScope.project(), SOURCE, incomplete);

        assertThat(result.applicability()).isEqualTo(UNVERIFIED);
        assertThat(ContextLimits.bytes(result.reason())).isLessThanOrEqualTo(2000);
        assertThat(result.reason()).startsWith("Source identity UNVERIFIED").endsWith("…");
        ContextInputFingerprint omitted = ContextInputFingerprint.create("project", ImmutableMap.of(), ImmutableList.of("Input budget exhausted"));
        assertThat(compare(note(SOURCE), ContextScope.project(), SOURCE, omitted).applicability()).isEqualTo(UNVERIFIED);
    }

    private static ContextSourceApplicability.Result compare(ContextEntry entry, ContextScope scope, ContextSourceEvidence source, ContextInputFingerprint reviewed)
    {
        return ContextSourceApplicability.compare(entry, Optional.of(scope), ImmutableMap.of(source.id(), source), Optional.of(reviewed), WORKSPACE);
    }

    private static ContextInputFingerprint fingerprint(Map<String, String> identities)
    {
        return ContextInputFingerprint.create("project", identities, ImmutableList.of());
    }

    private static ContextEntry note(ContextSourceEvidence source)
    {
        return new ContextEntry("knowledge:project", KNOWLEDGE, "project", "Project", "Conditional summary", "Conditional body", ImmutableList.of(),
                ImmutableList.of(source.id()), USER, true, 1);
    }

    private static ContextScope prepared(ContextEntry entry, Map<String, String> identities)
    {
        ContextRepositoryRead read = new ContextRepositoryRead("context-read:source", FILE, BLOB, Instant.EPOCH, 1, 1, 1, 19, false, Optional.of(SOURCE.id()));
        ContextPreparationResult result = new ContextPreparationResult("attempt", fingerprint(identities), READY, ImmutableList.of(entry.id()),
                ImmutableList.of(read), "Prepared conditional knowledge");
        return new ContextScope("project", "Project", ContextScope.project().match(), true, Optional.of(result), Optional.empty());
    }
}
