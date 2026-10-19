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

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static java.util.Objects.requireNonNull;

/**
 * What one action left in the assistant's conversation, done or refused, whether the assistant took it from the user's words or the
 * user clicked a card's own button in the panel.
 *
 * @param detail a second line, such as where a started task runs
 * @param target where the receipt opens
 * @param page the app page or Settings section the receipt opens instead, such as {@code settings/models}
 * @param turnId a started task's first turn, which Stop cancels
 * @param fix where a refused action can be done instead
 * @param words the user's words the assistant acted on, exactly as it passed them; none for a button
 */
public record AssistantReceipt(Operation operation, Outcome outcome, Optional<String> detail, Optional<AgendaTarget> target, Optional<String> page, Optional<String> turnId,
        Optional<Fix> fix, Optional<String> words)
{
    public enum Operation
    {
        START_TASK, ANSWER_APPROVAL, ANSWER_QUESTION, REPLY_TO_TASK, OPEN, MARK_EPIC, ADD_TO_EPIC, CREATE_WORK_ITEM, CREATE_EPIC, ACKNOWLEDGE, SNOOZE
    }

    public enum Outcome
    {
        DONE, REFUSED, FAILED
    }

    /** Settings holds the switch that is off; the card's buttons answer what words may not. */
    public enum Fix
    {
        SETTINGS, BUTTONS
    }

    public AssistantReceipt
    {
        requireNonNull(operation, "operation is null");
        requireNonNull(outcome, "outcome is null");
        checkOptionalText(detail, "detail");
        requireNonNull(target, "target is null");
        checkOptionalText(page, "page");
        checkOptionalText(turnId, "turnId");
        requireNonNull(fix, "fix is null");
        requireNonNull(words, "words is null");
    }

    public static AssistantReceipt done(Operation operation, Optional<AgendaTarget> target)
    {
        return new AssistantReceipt(operation, Outcome.DONE, Optional.empty(), target, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static AssistantReceipt refused(Operation operation, Optional<Fix> fix)
    {
        return new AssistantReceipt(operation, Outcome.REFUSED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), fix, Optional.empty());
    }

    public AssistantReceipt withWords(String said)
    {
        return new AssistantReceipt(operation, outcome, detail, target, page, turnId, fix, Optional.of(said));
    }
}
