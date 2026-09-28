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

import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.review.AgentReviewProjection.noteRead;
import static com.bytequay.app.review.AgentReviewProjection.noteSearches;
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

    @Test
    void laterSearchAndExactReadShowOnlyConfirmedReceipts()
    {
        String searchRef = "context-search:" + "a".repeat(64);
        String id = "knowledge:component";
        String digest = Hashing.sha256().hashString(searchRef + "\n" + id + "\n2", StandardCharsets.UTF_8).toString();
        String readRef = "context-note:" + digest;
        String search = """
                {"searchRef":"%s","notebookRevision":5,"originalNotebookRevision":3,"newerThanSnapshot":true,
                 "contextState":"MATCHES","query":"snapshot","paths":["src/"],"omittedPathHints":0,
                 "pathHintCount":4,"frozenPathHints":3,"omittedCount":0,"hits":[
                   {"entryId":"%s","entryVersion":2,"kind":"KNOWLEDGE","scopeId":"scope:component",
                    "title":"Component","summary":"Snapshot guidance","matchReasons":["Topic match only"],"applicability":"UNVERIFIED"}]}
                """.formatted(searchRef, id);
        String read = """
                {"status":"AVAILABLE","searchRef":"%s","entryId":"%s","entryVersion":2,
                 "text":"Exact saved note","evidenceRef":"%s","sourceEvidenceRefs":{"source":"E1"}}
                """.formatted(searchRef, id, readRef);
        ReviewRun run = run(true, ImmutableList.of(), ImmutableMap.of(
                searchRef, operation(search), readRef, new ReviewOperation(digest, read)), ImmutableMap.of(
                readRef, new ReviewEvidence(readRef, "Note", "Saved", "Exact saved note", "Unverified"),
                "E1", new ReviewEvidence("E1", "Source", "Saved", "Original passage", "Unverified")));
        ContextUsageEvent found = usage(searchRef, ContextUsageEvent.Stage.FOUND, ContextUsageEvent.Delivery.PREPARED);
        ContextUsageEvent opened = usage(readRef, ContextUsageEvent.Stage.OPENED, ContextUsageEvent.Delivery.CONFIRMED);

        AgentReviewProjection.ContextNoteSearch result = noteSearches(run, ImmutableList.of(found, opened), false).getFirst();
        assertThat(result.status()).isEqualTo(AgentReviewProjection.ContextNoteSearch.Status.AVAILABLE);
        assertThat(result.query()).isEqualTo("snapshot");
        assertThat(result.paths()).containsExactly("src/");
        assertThat(result.frozenPathHints()).isEqualTo(3);
        assertThat(result.delivery()).isEqualTo(AgentReviewProjection.ContextNoteSearch.Delivery.PREPARED);
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().matchReasons()).containsExactly("Topic match only");
        assertThat(result.hits().getFirst().initiallySelected()).isFalse();
        AgentReviewProjection.ContextNoteSearchRead exact = result.hits().getFirst().read().orElseThrow();
        assertThat(exact.delivery()).isEqualTo(AgentReviewProjection.ContextNoteSearch.Delivery.CONFIRMED);
        assertThat(exact.evidenceRef()).contains(readRef);
        assertThat(exact.sourceEvidenceRefs()).containsExactlyEntriesOf(ImmutableMap.of("source", "E1"));
        assertThat(noteSearches(run, ImmutableList.of(usage(searchRef, ContextUsageEvent.Stage.FOUND,
                ContextUsageEvent.Delivery.CONFIRMED), opened), false).getFirst().delivery())
                .isEqualTo(AgentReviewProjection.ContextNoteSearch.Delivery.CONFIRMED);
    }

    @Test
    void laterListedNoteShowsDirectExactReadWithoutSearchBinding()
    {
        String searchRef = "context-search:" + "c".repeat(64);
        String id = "knowledge:component";
        String digest = Hashing.sha256().hashString("run\n" + id + "\n2", StandardCharsets.UTF_8).toString();
        String readRef = "context-note:" + digest;
        String search = """
                {"searchRef":"%s","notebookRevision":5,"originalNotebookRevision":3,"newerThanSnapshot":true,
                 "contextState":"MATCHES","query":"","paths":[],"pathHintCount":0,"frozenPathHints":0,"omittedCount":0,
                 "hits":[{"entryId":"%s","entryVersion":2,"kind":"KNOWLEDGE","scopeId":"scope:component",
                          "title":"Component","summary":"Snapshot guidance","matchReasons":[],"applicability":"UNVERIFIED"}]}
                """.formatted(searchRef, id);
        String read = """
                {"status":"AVAILABLE","entryId":"%s","entryVersion":2,"text":"Exact saved note",
                 "evidenceRef":"%s","sourceEvidenceRefs":{}}
                """.formatted(id, readRef);
        ReviewRun run = run(true, ImmutableList.of(), ImmutableMap.of(searchRef, operation(search),
                readRef, new ReviewOperation(digest, read)), ImmutableMap.of(
                readRef, new ReviewEvidence(readRef, "Note", "Saved", "Exact saved note", "Unverified")));

        AgentReviewProjection.ContextNoteSearch result = noteSearches(run, ImmutableList.of(
                usage(readRef, ContextUsageEvent.Stage.OPENED, ContextUsageEvent.Delivery.CONFIRMED)), false).getFirst();
        assertThat(result.status()).isEqualTo(AgentReviewProjection.ContextNoteSearch.Status.AVAILABLE);
        assertThat(result.query()).isEmpty();
        assertThat(result.hits().getFirst().read().orElseThrow().delivery())
                .isEqualTo(AgentReviewProjection.ContextNoteSearch.Delivery.CONFIRMED);
        assertThat(result.hits().getFirst().read().orElseThrow().evidenceRef()).contains(readRef);
    }

    @Test
    void searchProjectionAcceptsOneBoundedPageOfTenHits()
    {
        String ref = "context-search:" + "d".repeat(64);
        String hit = """
                {"entryId":"knowledge:project","entryVersion":1,"kind":"KNOWLEDGE","scopeId":"project",
                 "title":"Project","summary":"Overview","matchReasons":[],"applicability":"UNVERIFIED"}
                """.strip();
        String prefix = """
                {"searchRef":"%s","notebookRevision":3,"originalNotebookRevision":3,"newerThanSnapshot":false,
                 "contextState":"MATCHES","query":"","pathHintCount":0,"frozenPathHints":0,"omittedCount":0,"hits":[
                """.formatted(ref);
        String ten = prefix + String.join(",", Collections.nCopies(10, hit)) + "]}";
        String eleven = prefix + String.join(",", Collections.nCopies(11, hit)) + "]}";

        assertThat(noteSearches(run(true, ImmutableList.of(), ImmutableMap.of(ref, operation(ten)), ImmutableMap.of()),
                ImmutableList.of(), false).getFirst().hits()).hasSize(10);
        assertThat(noteSearches(run(true, ImmutableList.of(), ImmutableMap.of(ref, operation(eleven)), ImmutableMap.of()),
                ImmutableList.of(), false).getFirst().status()).isEqualTo(AgentReviewProjection.ContextNoteSearch.Status.RECEIPT_UNAVAILABLE);
    }

    @Test
    void malformedSearchDoesNotInventHitsAndFrozenNotesAreMarked()
    {
        String ref = "context-search:" + "b".repeat(64);
        ContextSelectedEntry selected = new ContextSelectedEntry("knowledge:project", 3, "Project", Optional.empty(), ImmutableMap.of(),
                ImmutableList.of("Project scope"), ContextSelectedEntry.Applicability.UNVERIFIED, ContextSelectedEntry.Inclusion.DEFERRED);
        String result = """
                {"searchRef":"%s","notebookRevision":3,"originalNotebookRevision":3,"newerThanSnapshot":false,
                 "contextState":"MATCHES","query":"project","pathHintCount":0,"frozenPathHints":0,"omittedCount":0,"hits":[
                   {"entryId":"knowledge:project","entryVersion":3,"kind":"KNOWLEDGE","scopeId":"project",
                    "title":"Project","summary":"Overview","matchReasons":["Project scope"],"applicability":"UNVERIFIED"}]}
                """.formatted(ref);
        ReviewRun run = run(true, ImmutableList.of(selected), ImmutableMap.of(ref, operation(result)), ImmutableMap.of());
        assertThat(noteSearches(run, ImmutableList.of(), false).getFirst().hits().getFirst().initiallySelected()).isTrue();
        ReviewRun malformed = run(true, ImmutableList.of(), ImmutableMap.of(ref, operation("{\"searchRef\":\"wrong\"}")), ImmutableMap.of());
        AgentReviewProjection.ContextNoteSearch unavailable = noteSearches(malformed, ImmutableList.of(), true).getFirst();
        assertThat(unavailable.status()).isEqualTo(AgentReviewProjection.ContextNoteSearch.Status.RECEIPT_UNAVAILABLE);
        assertThat(unavailable.hits()).isEmpty();
        assertThat(unavailable.delivery()).isEqualTo(AgentReviewProjection.ContextNoteSearch.Delivery.UNKNOWN);
        assertThat(noteSearches(run(false, ImmutableList.of(), ImmutableMap.of(ref, operation(result)), ImmutableMap.of()), ImmutableList.of(), false)).isEmpty();
    }

    private static ReviewRun run(boolean enabled, List<ContextSelectedEntry> selected, Map<String, ReviewOperation> operations,
            Map<String, ReviewEvidence> evidence)
    {
        String sha = "a".repeat(40);
        ReviewContextSnapshot snapshot = new ReviewContextSnapshot("snapshot", enabled, 3, 1, Optional.of(sha),
                enabled ? Optional.of(ContextPacket.create("Saved packet")) : Optional.empty(), selected, ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", sha, sha, sha, Optional.empty(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, operations, evidence, Optional.of(snapshot), Optional.empty(), 0, now, now, Optional.empty());
    }

    private static ContextUsageEvent usage(String ref, ContextUsageEvent.Stage stage, ContextUsageEvent.Delivery delivery)
    {
        return new ContextUsageEvent("workspace", "task", Optional.of("turn"), Optional.of("run"), ContextUsageEvent.TaskKind.PR_REVIEW,
                stage, delivery, ref, Optional.of(new ContextUsageEvent.Note("knowledge:component", 2, ContextEntryKind.KNOWLEDGE,
                "scope:component", "Component")), Optional.empty(), Optional.empty(), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static ReviewOperation operation(String result)
    {
        return new ReviewOperation("digest", result);
    }
}
