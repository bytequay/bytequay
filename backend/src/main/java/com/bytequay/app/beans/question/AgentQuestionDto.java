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
package com.bytequay.app.beans.question;

import com.bytequay.app.domain.AgentQuestion;

import java.util.List;

/** Wire shape of an {@link AgentQuestion}. Timestamps are epoch-millis. */
public record AgentQuestionDto(
        String id,
        String threadId,
        String taskId,
        String question,
        String context,
        List<OptionDto> options,
        boolean allowFreeForm,
        String status,
        String answerOptionId,
        String answerFreeForm,
        long createdAt,
        Long answeredAt)
{
    /** Wire shape of one multiple-choice option. */
    public record OptionDto(String id, String label, String extra)
    {
    }

    public static AgentQuestionDto from(AgentQuestion q)
    {
        return new AgentQuestionDto(
                q.id(),
                q.threadId(),
                q.taskId(),
                q.question(),
                q.context(),
                q.options().stream()
                        .map(o -> new OptionDto(o.id(), o.label(), o.extra()))
                        .toList(),
                q.allowFreeForm(),
                q.status(),
                q.answerOptionId(),
                q.answerFreeForm(),
                q.createdAt().toEpochMilli(),
                q.answeredAt() == null ? null : q.answeredAt().toEpochMilli());
    }
}
