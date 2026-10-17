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
package com.bytequay.core.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * One call of the assistant's action tool, done or refused. The app never shows the log; it keeps what the assistant was asked to do
 * and what came of it.
 *
 * @param itemId the user's message the call acted on
 * @param target the ref the call named
 * @param words the user's words the call said asked for it
 * @param reason why it was refused or failed
 * @param result the started task, or the decision recorded
 */
public record AssistantAction(String id, LocalDate day, String itemId, AssistantReceipt.Operation operation, String target, String words, AssistantReceipt.Outcome outcome,
        Optional<String> reason, Optional<String> result, Instant at)
{
    public AssistantAction
    {
        checkText(id, "id");
        requireNonNull(day, "day is null");
        checkText(itemId, "itemId");
        requireNonNull(operation, "operation is null");
        requireNonNull(target, "target is null");
        requireNonNull(words, "words is null");
        requireNonNull(outcome, "outcome is null");
        checkOptionalText(reason, "reason");
        checkOptionalText(result, "result");
        requireNonNull(at, "at is null");
    }

    public static AssistantAction of(AssistantItem message, AssistantReceipt.Operation operation, String target, String words, AssistantReceipt.Outcome outcome,
            Optional<String> reason, Optional<String> result, Instant at)
    {
        return new AssistantAction(UUID.randomUUID().toString(), message.day(), message.id(), operation, target, words, outcome, reason, result, at);
    }
}
