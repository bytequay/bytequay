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
package com.bytequay.app.web;

import com.bytequay.app.beans.question.AgentQuestionDto;
import com.bytequay.app.beans.question.AnswerQuestionRequest;
import com.bytequay.app.developmentflow.userwait.V2UserWaitService;
import com.bytequay.app.service.question.AgentQuestionServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the {@code ask_user_question} flow: list a thread's open
 * questions (the frontend renders them as cards) and post an answer (which is
 * recorded and emitted as the next message for the waiting agent).
 */
@RestController
public class AgentQuestionController
{
    private final AgentQuestionServiceImpl questions;
    private final V2UserWaitService v2Waits;

    @Autowired
    public AgentQuestionController(
            AgentQuestionServiceImpl questions, V2UserWaitService v2Waits)
    {
        this.questions = requireNonNull(questions, "questions is null");
        this.v2Waits = requireNonNull(v2Waits, "v2Waits is null");
    }

    @GetMapping("/api/threads/{threadId}/questions")
    public List<AgentQuestionDto> open(@PathVariable String threadId)
    {
        List<AgentQuestionDto> open = new ArrayList<>();
        v2Waits.listOpen(threadId).stream()
                .map(AgentQuestionDto::from)
                .forEach(open::add);
        questions.listOpen(threadId).stream()
                .map(AgentQuestionDto::from)
                .forEach(open::add);
        open.sort(Comparator.comparingLong(AgentQuestionDto::createdAt));
        return List.copyOf(open);
    }

    @PostMapping("/api/questions/{id}/answer")
    public AgentQuestionDto answer(@PathVariable String id, @RequestBody AnswerQuestionRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "request body is required");
        }
        if (v2Waits.findQuestion(id).isPresent()) {
            int expectedRevision = body.expectedRevision() == null
                    ? 0 : body.expectedRevision();
            var resolved = v2Waits.answerQuestion(
                    id, expectedRevision, body.answerOptionId(),
                    body.answerFreeForm(), "local-user");
            if (!resolved.accepted()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(409), resolved.outcome());
            }
            return AgentQuestionDto.from(
                    v2Waits.toQuestion(resolved.question()));
        }
        return AgentQuestionDto.from(questions.answer(
                id, body.answerOptionId(), body.answerFreeForm()));
    }
}
