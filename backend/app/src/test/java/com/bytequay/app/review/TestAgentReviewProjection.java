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
package com.bytequay.app.review;

import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewOperation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.review.AgentReviewProjection.noteRead;
import static com.bytequay.app.review.AgentReviewProjection.preparationActivities;
import static org.assertj.core.api.Assertions.assertThat;

class TestAgentReviewProjection
{
    @Test
    void projectsRecordedPreparationWithoutRuntimeOrNotebookState()
    {
        String begin = "review-step:run:1:repository_context_prepare:BEGIN:request";
        String submit = "review-step:run:1:repository_context_prepare:SUBMIT:request";
        Map<String, ReviewOperation> operations = ImmutableMap.of(
                begin, operation("""
                        {"status":"OWNED","recordedAt":"2026-01-01T00:00:00Z","elapsedMillis":12,
                         "attempt":{"attemptId":"attempt","scopes":[{"scopeId":"project","digest":"digest"}]},
                         "reused":[{"scopeId":"connector","outcome":"EMPTY","explanation":"Checked sources were empty","noteIds":[],"checkedSourceIds":["read-1"]}],
                         "gaps":[]}
                        """),
                submit, operation("""
                        {"status":"APPLIED","attemptId":"attempt","recordedAt":"2026-01-01T00:00:30Z","elapsedMillis":7,
                         "scopeResults":[{"scopeId":"project","outcome":"READY","explanation":"Sources read","noteIds":["knowledge:project"],"checkedSourceIds":["read-2"]}]}
                        """));
        List<AgentReviewProjection.ContextPreparationActivity> activities = preparationActivities("run", operations);
        assertThat(activities).hasSize(2);
        assertThat(activities.getFirst().operationRef()).isEqualTo(begin);
        assertThat(activities.getFirst().status()).isEqualTo(AgentReviewProjection.ContextPreparationActivity.Status.OWNED);
        assertThat(activities.getFirst().recordedAt()).contains(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(activities.getFirst().elapsedMillis()).contains(12L);
        assertThat(activities.getFirst().scopes().getFirst().outcome()).isEmpty();
        assertThat(activities.getFirst().scopes().get(1).outcome()).contains("EMPTY");
        assertThat(activities.getFirst().scopes().get(1).checkedSourceIds()).containsExactly("read-1");
        assertThat(activities.getLast().status()).isEqualTo(AgentReviewProjection.ContextPreparationActivity.Status.APPLIED);
        assertThat(activities.getLast().attemptId()).contains("attempt");
        assertThat(activities.getLast().elapsedMillis()).contains(7L);
        assertThat(activities.getLast().scopes().getFirst().noteIds()).containsExactly("knowledge:project");
    }

    @Test
    void retainsWaitingDeferredAndReusedObservationsButNotUnrelatedOperations()
    {
        for (String status : ImmutableList.of("JOINED", "DEFERRED", "REUSED")) {
            List<AgentReviewProjection.ContextPreparationActivity> activities = preparationActivities("run", ImmutableMap.of(
                    "context-activity:observation", operation("{\"status\":\"" + status + "\",\"gaps\":[\"Recorded limitation\"]}"),
                    "review-step:another-run:1:repository_context_prepare:BEGIN:key", operation("{}"),
                    "review-step:run:1:repository_context_read:key", operation("{}")));
            assertThat(activities).hasSize(1);
            assertThat(activities.getFirst().status().name()).isEqualTo(status);
            assertThat(activities.getFirst().recordedAt()).isEmpty();
            assertThat(activities.getFirst().elapsedMillis()).isEmpty();
            assertThat(activities.getFirst().gaps()).containsExactly("Recorded limitation");
        }
    }

    @Test
    void malformedReceiptsStayExplicitlyUnavailable()
    {
        for (String result : ImmutableList.of("not json", "{\"status\":\"READY\"}", "{\"status\":\"OWNED\",\"elapsedMillis\":-1}",
                "{\"status\":\"OWNED\",\"recordedAt\":\"invalid\"}", "{\"status\":\"REUSED\",\"reused\":[{\"scopeId\":\"project\",\"outcome\":\"UNKNOWN\"}]}")) {
            AgentReviewProjection.ContextPreparationActivity activity = preparationActivities("run", ImmutableMap.of("context-activity:observation", operation(result))).getFirst();
            assertThat(activity.status()).isEqualTo(AgentReviewProjection.ContextPreparationActivity.Status.UNAVAILABLE);
            assertThat(activity.gaps()).containsExactly("The saved preparation receipt could not be read. Its outcome and timing are unavailable.");
            assertThat(activity.recordedAt()).isEmpty();
        }
    }

    @Test
    void exactNoteReadsUseFrozenSelectionAndRetainedEvidence()
    {
        ContextSelectedEntry selected = new ContextSelectedEntry("knowledge:project", 3, "Project", Optional.empty(), ImmutableMap.of(),
                ImmutableList.of("Project scope"), ContextSelectedEntry.Applicability.UNVERIFIED, ContextSelectedEntry.Inclusion.DEFERRED);
        String digest = Hashing.sha256().hashString("knowledge:project\n3", StandardCharsets.UTF_8).toString();
        String key = "context-note:" + digest;
        String result = """
                {"entryId":"knowledge:project","entryVersion":3,"status":"AVAILABLE","text":"Exact note", "evidenceRef":"%s",
                 "sourceEvidenceRefs":{"source":"E1"}}
                """.formatted(key);
        Map<String, ReviewOperation> operations = ImmutableMap.of(key, new ReviewOperation(digest, result));
        Map<String, ReviewEvidence> evidence = ImmutableMap.of(
                key, new ReviewEvidence(key, "Note", "Frozen version", "Exact note", "Unverified"),
                "E1", new ReviewEvidence("E1", "Source", "Frozen source", "Source excerpt", "Unverified"));
        AgentReviewProjection.ContextNoteRead read = noteRead(selected, operations, evidence).orElseThrow();
        assertThat(read.entryId()).isEqualTo("knowledge:project");
        assertThat(read.entryVersion()).isEqualTo(3);
        assertThat(read.status()).isEqualTo(AgentReviewProjection.ContextNoteRead.Status.AVAILABLE);
        assertThat(read.text()).contains("Exact note");
        assertThat(read.sourceEvidenceRefs()).containsExactlyEntriesOf(ImmutableMap.of("source", "E1"));
        assertThat(noteRead(selected, ImmutableMap.of(), evidence)).isEmpty();
        assertThat(noteRead(selected, operations, ImmutableMap.of()).orElseThrow().status()).isEqualTo(AgentReviewProjection.ContextNoteRead.Status.RECEIPT_UNAVAILABLE);
        assertThat(noteRead(selected, ImmutableMap.of(key, new ReviewOperation(digest, result.replace("Exact note", "Changed note"))), evidence)
                .orElseThrow().text()).isEmpty();
        ContextSelectedEntry newer = new ContextSelectedEntry(selected.entryId(), 4, selected.title(), Optional.empty(), ImmutableMap.of(),
                selected.matchReasons(), selected.applicability(), selected.inclusion());
        assertThat(noteRead(newer, operations, evidence)).isEmpty();
    }

    private static ReviewOperation operation(String result)
    {
        return new ReviewOperation("digest", result);
    }
}
