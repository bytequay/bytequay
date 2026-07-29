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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Parse/validation tests for the extraction contract: strict JSON, canonical
 * kinds, resolvable evidence only, and "no durable lesson" as a first-class
 * result. The model call itself is not exercised here.
 */
class TestLessonExtractor
{
    private LessonExtractor extractor;
    private ReviewProviderEndpoints endpoints;
    private WorkModelResolver workModels;
    private CliReviewRunner cliRunner;

    @BeforeEach
    void setUp()
    {
        endpoints = mock(ReviewProviderEndpoints.class);
        workModels = mock(WorkModelResolver.class);
        cliRunner = mock(CliReviewRunner.class);
        extractor = new LessonExtractor(
                mock(TurnRunner.class),
                endpoints,
                workModels,
                cliRunner,
                new ObjectMapper());
    }

    @Test
    void testUsesWorkspaceDefaultCliForExtraction()
    {
        WorkModel model = new WorkModel(WorkModelKind.CLI, "codex", null, null);
        when(workModels.resolveForWorkspace("ws-1", SessionAudience.REVIEW))
                .thenReturn(new WorkModelResolver.Resolved(model,
                        new WorkModelResolver.Provenance(
                                WorkModelResolver.Source.WORKSPACE, "ws-1", "workspace Widget")));
        when(cliRunner.run(
                eq(CliReviewRunner.Provider.CODEX), anyString(), isNull(), any(), isNull(), eq(30)))
                .thenReturn(new CliReviewRunner.Result("{\"lessons\": []}", null, 0));

        assertThat(extractor.extract("ws-1", bundle(), List.of())).isEmpty();

        verify(workModels).resolveForWorkspace("ws-1", SessionAudience.REVIEW);
        verify(cliRunner).run(
                eq(CliReviewRunner.Provider.CODEX), anyString(), isNull(), any(), isNull(), eq(30));
        verifyNoInteractions(endpoints);
    }

    @Test
    void testValidLessonParses()
    {
        List<ExtractedLesson> lessons = extractor.parse("""
                {"lessons": [{
                    "kind": "compatibility-contract",
                    "title": "Connector SPI stays frozen",
                    "statement": "Connector SPI signatures must not change within a release line.",
                    "rationale": "Reviewer cited plugin breakage.",
                    "appliesTo": {"paths": ["core/spi/Connector.java"], "symbols": ["Connector"]},
                    "audiences": ["dev", "review"],
                    "evidence": ["E1", "E3"],
                    "explicitSourceQuote": true,
                    "confidence": "high",
                    "duplicateOf": null,
                    "conflictsWith": [],
                    "route": "knowledge",
                    "memoryKind": null
                }]}
                """, 3, List.of());

        assertThat(lessons).hasSize(1);
        ExtractedLesson lesson = lessons.getFirst();
        assertThat(lesson.kind()).isEqualTo("compatibility-contract");
        assertThat(lesson.evidenceRefs()).containsExactly(0, 2);
        assertThat(lesson.explicitSourceQuote()).isTrue();
        assertThat(lesson.paths()).containsExactly("core/spi/Connector.java");
        assertThat(lesson.route()).isEqualTo("knowledge");
    }

    @Test
    void testNoDurableLessonIsAValidResult()
    {
        assertThat(extractor.parse("{\"lessons\": []}", 5, List.of())).isEmpty();
    }

    @Test
    void testFencedOutputIsUnwrapped()
    {
        assertThat(extractor.parse("""
                ```json
                {"lessons": []}
                ```
                """, 1, List.of())).isEmpty();
    }

    @Test
    void testLessonWithoutResolvableEvidenceIsDropped()
    {
        List<ExtractedLesson> lessons = extractor.parse("""
                {"lessons": [
                  {"kind": "recurring-concern", "statement": "Cites nothing.", "evidence": []},
                  {"kind": "recurring-concern", "statement": "Cites out of range.",
                   "evidence": ["E9"]},
                  {"kind": "recurring-concern", "statement": "Cites garbage.",
                   "evidence": ["file.java"]}
                ]}
                """, 2, List.of());
        assertThat(lessons).isEmpty();
    }

    @Test
    void testUnknownKindIsDropped()
    {
        List<ExtractedLesson> lessons = extractor.parse("""
                {"lessons": [{"kind": "vibe", "statement": "Nope.", "evidence": ["E1"]}]}
                """, 1, List.of());
        assertThat(lessons).isEmpty();
    }

    @Test
    void testConflictAndDuplicateIdsAreFilteredToKnownKnowledge()
    {
        KnowledgeItem known = new KnowledgeItem(
                "k-1", "ws-1", "acme/widget", "domain-invariant", null, "Known fact",
                null, List.of(), "high", "active", null, null, "pr-learning",
                null, "{}", 1, 1);
        List<ExtractedLesson> lessons = extractor.parse("""
                {"lessons": [{
                    "kind": "domain-invariant",
                    "statement": "A split is the smallest schedulable unit.",
                    "evidence": ["E1"],
                    "duplicateOf": "k-unknown",
                    "conflictsWith": ["k-1", "k-unknown"]
                }]}
                """, 1, List.of(known));

        ExtractedLesson lesson = lessons.getFirst();
        assertThat(lesson.duplicateOf()).isNull();
        assertThat(lesson.conflictsWith()).containsExactly("k-1");
    }

    @Test
    void testMalformedJsonFailsExtraction()
    {
        assertThatThrownBy(() -> extractor.parse("not json at all", 1, List.of()))
                .isInstanceOf(LessonExtractor.ExtractionFailedException.class);
        assertThatThrownBy(() -> extractor.parse("{\"other\": true}", 1, List.of()))
                .isInstanceOf(LessonExtractor.ExtractionFailedException.class);
    }

    private static PrEvidenceBundle bundle()
    {
        return new PrEvidenceBundle(
                "ws-1", "acme/widget", 7, "alice", "Title", "Body",
                "base", "head", "merge", "repo", List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), "complete", List.of(), List.of());
    }
}
