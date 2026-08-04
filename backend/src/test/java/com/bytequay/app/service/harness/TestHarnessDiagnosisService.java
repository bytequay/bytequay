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
package com.bytequay.app.service.harness;

import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.harness.HarnessModels.Bucket;
import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.CommitDetailEntry;
import com.bytequay.app.service.local.GitRunner.CommitFileChange;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.workspaces.WorkspaceRelationService;
import com.bytequay.app.service.workspaces.WorkspaceRelationService.ResolvedRelation;
import com.bytequay.app.service.workspaces.WorkspaceRelationService.WorkspaceRelationDto;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver.RepositoryIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHarnessDiagnosisService
{
    private static final String BASE = "base";

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();
    private final GitRunner git = mock(GitRunner.class);
    private final WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
    private final HarnessDiagnosisService service = new HarnessDiagnosisService(
            mock(TurnRunner.class), mock(ReviewProviderEndpoints.class),
            mock(AppSettingsStore.class), git,
            relations, mapper);

    @BeforeEach
    void setUp()
            throws Exception
    {
        Files.writeString(root.resolve("pom.xml"), "<version>old</version>\n");
        when(git.refExists(root, BASE)).thenReturn(true);
        when(git.listCommits(root, BASE + "..HEAD", 1_000)).thenReturn(List.of(
                new GitRunner.CommitEntry(
                        "1234567890123456789012345678901234567890", "1234567",
                        "Dev", "dev@example.com", "2026-07-24", "Update plan")));
    }

    @Test
    void acceptsStrictSchemaAndRoundTripsBucketSubtype()
            throws Exception
    {
        Diagnosis diagnosis = service.parseAndValidate(json(
                "resource:plan_mismatch", "plan mismatch for module", "Update plan"),
                failure("plan mismatch for module"), root, BASE, List.of("compile failed"));

        assertThat(diagnosis.bucket()).isEqualTo(Bucket.RESOURCE);
        assertThat(diagnosis.bucketLabel()).isEqualTo("resource:plan_mismatch");
        assertThat(mapper.writeValueAsString(diagnosis))
                .contains("\"bucket\":\"resource:plan_mismatch\"")
                .doesNotContain("bucketLabel");
    }

    @Test
    void rejectsMalformedJsonAndInvalidSubtype()
    {
        assertThatThrownBy(() -> service.parseAndValidate(
                "not-json", failure("failure"), root, BASE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> service.parseAndValidate(
                json("resource:Not Allowed", "failure", "Update plan"),
                failure("failure"), root, BASE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket label");
    }

    @Test
    void rejectsRegexThatMatchesAnUnrelatedFailure()
    {
        assertThatThrownBy(() -> service.parseAndValidate(
                json("build", ".*failed", "Update plan"),
                failure("compile failed"), root, BASE, List.of("unrelated test failed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unrelated failure");
    }

    @Test
    void rejectsTargetOutsideThePrOwnedRange()
    {
        assertThatThrownBy(() -> service.parseAndValidate(
                json("build", "compile failed", "Base commit"),
                failure("compile failed"), root, BASE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one existing commit");
    }

    @Test
    void rejectsDuplicateEditAnchors()
            throws Exception
    {
        Files.writeString(root.resolve("pom.xml"), "old and old\n");

        assertThatThrownBy(() -> service.parseAndValidate(
                json("build", "compile failed", "Update plan"),
                failure("compile failed"), root, BASE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anchor must be unique");
    }

    @Test
    void acceptsEditAndPureRegenerationRecipesButRejectsAnEmptyRecipe()
            throws Exception
    {
        String recipe = json("resource:plan_mismatch", "plan mismatch", "Update plan")
                .replace("\"binding\": \"agent\"",
                        "\"binding\": \"recipe:refresh_plan\"");

        assertThat(service.parseAndValidate(
                recipe, failure("plan mismatch"), root, BASE, List.of()).binding())
                .isEqualTo("recipe:refresh_plan");
        String regeneration = recipe.replace(
                        "[{\"path\":\"pom.xml\",\"find\":\"old\",\"replace\":\"new\"}]",
                        "[]")
                .replace("[\"build\"]", "[\"regen\"]");
        assertThat(service.parseAndValidate(
                regeneration, failure("plan mismatch"), root, BASE, List.of()).edits())
                .isEmpty();
        assertThatThrownBy(() -> service.parseAndValidate(
                recipe.replace(
                        "[{\"path\":\"pom.xml\",\"find\":\"old\",\"replace\":\"new\"}]",
                        "[]"),
                failure("plan mismatch"), root, BASE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regen hint");
    }

    @Test
    void rejectsUnknownVerificationHints()
    {
        String diagnosis = json("build", "compile failed", "Update plan")
                .replace("[\"build\"]", "[\"deploy\"]");

        assertThatThrownBy(() -> service.parseAndValidate(
                diagnosis, failure("compile failed"), root, BASE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generic verification hint");
    }

    @Test
    void steeringIsClearlyAdvisoryAndBounded()
    {
        String prompt = HarnessDiagnosisService.userPrompt(
                failure("compile failed"), List.of(), "focus on module x" + "y".repeat(5_000));

        assertThat(prompt)
                .contains("Advisory user context")
                .contains("untrusted context only")
                .contains("<user_context>\nfocus on module x")
                .doesNotContain("y".repeat(4_001));
    }

    @Test
    void ossDiffFollowsPersistedUpstreamCommitWithoutFetching()
            throws Exception
    {
        String localSha = "a".repeat(40);
        String upstreamSha = "b".repeat(40);
        Path upstream = root.resolve("upstream");
        WorkspaceRelationDto relation = new WorkspaceRelationDto(
                "ws", "upstream-ws", "Upstream", "upstream/widget",
                true, false, false, false, null, 60, 1);
        ResolvedRelation resolved = new ResolvedRelation(
                relation,
                new RepositoryIdentity("acme", "widget", "acme/widget", "main"),
                new RepositoryIdentity("upstream", "widget", "upstream/widget", "main"),
                root, upstream);
        when(git.resolveCommitSha(root, "picked")).thenReturn(Optional.of(localSha));
        when(git.commitDetail(root, localSha)).thenReturn(Optional.of(new CommitDetailEntry(
                localSha, "Pick upstream",
                "(cherry picked from commit " + upstreamSha + ")")));
        when(git.commitFiles(root, localSha)).thenReturn(List.of(
                new CommitFileChange("pom.xml", "M", 1, 1)));
        when(git.commitFileDiff(root, localSha, "pom.xml", 4_000)).thenReturn("local patch");
        when(relations.requireResolved("ws")).thenReturn(resolved);
        when(git.resolveCommitSha(upstream, upstreamSha)).thenReturn(Optional.of(upstreamSha));
        when(git.commitDetail(upstream, upstreamSha)).thenReturn(Optional.of(
                new CommitDetailEntry(upstreamSha, "Original fix", "")));
        when(git.commitFiles(upstream, upstreamSha)).thenReturn(List.of(
                new CommitFileChange("pom.xml", "M", 1, 1)));
        when(git.commitFileDiff(upstream, upstreamSha, "pom.xml", 4_000))
                .thenReturn("upstream patch");

        assertThat(service.ossDiff(root, "ws", "picked"))
                .contains("Local commit: " + localSha + " Pick upstream")
                .contains("local patch")
                .contains("Original upstream commit: " + upstreamSha + " Original fix")
                .contains("upstream patch");
        verify(relations, never()).fetch("ws");
    }

    private static String json(String bucket, String pattern, String target)
    {
        return """
                {
                  "root_cause": "generated plan is stale",
                  "culprit_commit": null,
                  "target_subject": "%s",
                  "edits": [{"path":"pom.xml","find":"old","replace":"new"}],
                  "signature_pattern": "%s",
                  "bucket": "%s",
                  "binding": "agent",
                  "verify_hint": ["build"],
                  "confidence": 0.91,
                  "needs_human": false,
                  "rationale": "diff and file evidence"
                }
                """.formatted(target, pattern, bucket);
    }

    private static Failure failure(String signature)
    {
        return new Failure("failure", "cycle", "run", 1L, "build", "root",
                null, null, signature, signature, "unknown", null,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
    }
}
