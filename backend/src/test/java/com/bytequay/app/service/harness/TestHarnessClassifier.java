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

import com.bytequay.app.service.harness.HarnessModels.Bucket;
import com.bytequay.app.service.harness.HarnessModels.Rule;
import com.bytequay.app.service.harness.HarnessModels.RuleStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHarnessClassifier
{
    private final HarnessStore store = mock(HarnessStore.class);
    private final HarnessClassifier classifier = new HarnessClassifier(store);

    @Test
    void ignoresCandidateRulesEvenIfTheStoreReturnsOne()
    {
        Rule candidate = rule("candidate", "compile failed", "build", "agent",
                RuleStatus.CANDIDATE);
        when(store.activeRules("ws", "acme", "widget")).thenReturn(List.of(candidate));

        assertThat(classifier.classify(
                "ws", "acme", "widget", "root", "compile failed", "", 10).bucket())
                .isEqualTo(Bucket.UNKNOWN);
        verify(store, never()).touchRule(candidate.id(), 10);
    }

    @Test
    void honorsActiveDeferPrecedenceAndPreservesSubtype()
    {
        Rule defer = rule("defer", "timeout", "infra:runner_capacity", "defer",
                RuleStatus.ACTIVE);
        Rule agent = rule("agent", "timeout", "test:integration", "agent",
                RuleStatus.ACTIVE);
        when(store.activeRules("ws", "acme", "widget")).thenReturn(List.of(defer, agent));
        when(store.touchRule("defer", 11)).thenReturn(defer);

        HarnessClassifier.Classification result = classifier.classify(
                "ws", "acme", "widget", "root", "timeout waiting for runner",
                "incidental compile failed", 11);

        assertThat(result.bucket()).isEqualTo(Bucket.INFRA);
        assertThat(result.rule().bucketLabel()).isEqualTo("infra:runner_capacity");
        assertThat(result.rule().binding()).isEqualTo("defer");
        verify(store, never()).touchRule("agent", 11);
    }

    @Test
    void neverRoutesOnAnIncidentalExcerptMatch()
    {
        Rule active = rule("agent", "compile failed", "build", "agent", RuleStatus.ACTIVE);
        when(store.activeRules("ws", "acme", "widget")).thenReturn(List.of(active));

        assertThat(classifier.classify(
                "ws", "acme", "widget", "root", "test timed out",
                "Earlier log line: compile failed", 12).bucket())
                .isEqualTo(Bucket.UNKNOWN);
        verify(store, never()).touchRule("agent", 12);
    }

    private static Rule rule(
            String id, String pattern, String bucket, String binding, RuleStatus status)
    {
        return new Rule(id, "ws", "acme", "widget", pattern, null, bucket, binding,
                null, status, "human", 100, "[]", 1, 1, 1, null);
    }
}
