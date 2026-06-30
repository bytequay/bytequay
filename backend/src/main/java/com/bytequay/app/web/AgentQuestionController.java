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
import com.bytequay.app.service.question.AgentQuestionService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final AgentQuestionService questions;

    public AgentQuestionController(AgentQuestionService questions)
    {
        this.questions = requireNonNull(questions, "questions is null");
    }

    @GetMapping("/api/threads/{threadId}/questions")
    public List<AgentQuestionDto> open(@PathVariable String threadId)
    {
        return questions.listOpen(threadId).stream().map(AgentQuestionDto::from).toList();
    }

    @PostMapping("/api/questions/{id}/answer")
    public AgentQuestionDto answer(@PathVariable String id, @RequestBody AnswerQuestionRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "request body is required");
        }
        return AgentQuestionDto.from(
                questions.answer(id, body.answerOptionId(), body.answerFreeForm()));
    }
}
