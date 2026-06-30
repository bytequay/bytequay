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

import java.util.List;

/**
 * The {@code ask_user_question} clarification flow. An agent records a
 * question (and ends its turn); the frontend renders the amber card from the
 * open questions; the user's answer is recorded and posted as the next
 * message, which the agent reads on its next turn.
 */
public interface AgentQuestionService
{
    /** Record a new open question. 400 when threadId/question are blank. */
    AgentQuestion ask(
            String threadId,
            String taskId,
            String toolCallId,
            String question,
            String context,
            List<AgentQuestion.Option> options,
            boolean allowFreeForm);

    /** Open (unanswered) questions on a thread, oldest-first. */
    List<AgentQuestion> listOpen(String threadId);

    /** Record the user's answer (option id and/or free-form) and post it as
     *  the next message so the agent reads it on its next turn. 404 when
     *  unknown, 409 when already answered, 400 when no answer is supplied. */
    AgentQuestion answer(String questionId, String answerOptionId, String answerFreeForm);
}
