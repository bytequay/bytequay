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
package com.bytequay.app.domain;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

/**
 * A clarification an agent asked the user through {@code ask_user_question}.
 * The tool is non-blocking — it records the question and ends the turn; the
 * user's answer is recorded here and posted as the next message. {@code
 * taskId} routes the answer to a task's turn (null = the thread's trunk).
 */
public record AgentQuestion(
        String id,
        String threadId,
        String taskId,
        String toolCallId,
        String question,
        String context,
        List<Option> options,
        boolean allowFreeForm,
        String status,
        String answerOptionId,
        String answerFreeForm,
        Instant createdAt,
        Instant answeredAt,
        int answerRevision,
        String answerActor)
{
    public static final String STATUS_OPEN = "open";
    public static final String STATUS_ANSWERED = "answered";

    /** One multiple-choice option: a stable {@code id}, the {@code label}
     *  shown on the button, and an optional monospace {@code extra} hint. */
    public record Option(String id, String label, String extra)
    {
    }

    public AgentQuestion
    {
        options = options == null ? List.of() : ImmutableList.copyOf(options);
        if (answerRevision < 0) {
            throw new IllegalArgumentException("answerRevision is negative");
        }
    }

    public AgentQuestion(
            String id,
            String threadId,
            String taskId,
            String toolCallId,
            String question,
            String context,
            List<Option> options,
            boolean allowFreeForm,
            String status,
            String answerOptionId,
            String answerFreeForm,
            Instant createdAt,
            Instant answeredAt)
    {
        this(id, threadId, taskId, toolCallId, question, context, options,
                allowFreeForm, status, answerOptionId, answerFreeForm,
                createdAt, answeredAt,
                STATUS_ANSWERED.equals(status) ? 1 : 0, null);
    }

    /** Record the user's answer (an option id and/or free-form text). */
    public AgentQuestion withAnswer(String answerOptionId, String answerFreeForm, Instant when)
    {
        return new AgentQuestion(
                id, threadId, taskId, toolCallId, question, context, options, allowFreeForm,
                STATUS_ANSWERED, answerOptionId, answerFreeForm, createdAt, when,
                answerRevision + 1, "user");
    }
}
