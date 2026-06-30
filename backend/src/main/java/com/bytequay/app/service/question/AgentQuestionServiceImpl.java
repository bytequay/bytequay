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
package com.bytequay.app.service.question;

import com.bytequay.app.domain.AgentQuestion;
import com.bytequay.app.repository.AgentQuestionStore;
import com.bytequay.app.service.threads.ThreadService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class AgentQuestionServiceImpl
        implements AgentQuestionService
{
    private final AgentQuestionStore store;
    private final ThreadService threadService;

    public AgentQuestionServiceImpl(AgentQuestionStore store, ThreadService threadService)
    {
        this.store = requireNonNull(store, "store is null");
        this.threadService = requireNonNull(threadService, "threadService is null");
    }

    @Override
    public AgentQuestion ask(
            String threadId,
            String taskId,
            String toolCallId,
            String question,
            String context,
            List<AgentQuestion.Option> options,
            boolean allowFreeForm)
    {
        String threadIdValue = nullToEmpty(threadId).strip();
        String questionValue = nullToEmpty(question).strip();
        if (threadIdValue.isEmpty()) {
            throw status(400, "threadId is required");
        }
        if (questionValue.isEmpty()) {
            throw status(400, "question is required");
        }
        AgentQuestion record = new AgentQuestion(
                UUID.randomUUID().toString(),
                threadIdValue,
                emptyToNull(taskId),
                emptyToNull(toolCallId),
                questionValue,
                emptyToNull(context),
                options == null ? List.of() : options,
                allowFreeForm,
                AgentQuestion.STATUS_OPEN,
                /* answerOptionId */ null,
                /* answerFreeForm */ null,
                Instant.now(),
                /* answeredAt */ null);
        return store.save(record);
    }

    @Override
    public List<AgentQuestion> listOpen(String threadId)
    {
        return store.findOpenByThread(nullToEmpty(threadId).strip());
    }

    @Override
    public AgentQuestion answer(String questionId, String answerOptionId, String answerFreeForm)
    {
        AgentQuestion question = store.findById(nullToEmpty(questionId).strip())
                .orElseThrow(() -> status(404, "question not found: " + questionId));
        if (!AgentQuestion.STATUS_OPEN.equals(question.status())) {
            throw status(409, "question already answered");
        }
        String optionId = emptyToNull(answerOptionId);
        String freeForm = emptyToNull(answerFreeForm);
        String text = composeAnswer(question, optionId, freeForm);
        if (text == null) {
            throw status(400, "an answer (option id or free-form text) is required");
        }
        // Post the answer as the next message so the waiting agent reads it on
        // its next turn — routed to the asking task, or the trunk when none.
        if (question.taskId() != null && !question.taskId().isBlank()) {
            threadService.send(question.threadId(), question.taskId(), text);
        }
        else {
            threadService.sendTrunk(question.threadId(), text);
        }
        return store.save(question.withAnswer(optionId, freeForm, Instant.now()));
    }

    /** The message text for an answer: the picked option's label, the
     *  free-form text, or both. Null when neither was supplied. */
    private static String composeAnswer(AgentQuestion question, String optionId, String freeForm)
    {
        String label = optionId == null ? null : question.options().stream()
                .filter(o -> optionId.equals(o.id()))
                .map(AgentQuestion.Option::label)
                .findFirst()
                .orElse(optionId);
        if (label != null && freeForm != null) {
            return label + " — " + freeForm;
        }
        if (label != null) {
            return label;
        }
        return freeForm;
    }

    private static String emptyToNull(String value)
    {
        String stripped = nullToEmpty(value).strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
