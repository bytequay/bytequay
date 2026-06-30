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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.AgentQuestion;
import com.bytequay.app.service.question.AgentQuestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestQuestionToolHandlers
{
    private final AgentQuestionService service = mock(AgentQuestionService.class);
    private final QuestionToolHandlers handlers = new QuestionToolHandlers(service);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void askRecordsTheQuestionWithItsOptionsAndEndsTheTurn()
            throws Exception
    {
        JsonNode options = mapper.readTree(
                "[{\"id\":\"a\",\"label\":\"Postgres\"},{\"id\":\"b\",\"label\":\"SQLite\",\"extra\":\"embedded\"}]");

        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.askUserQuestion(
                new QuestionToolHandlers.AskUserQuestionArgs("Which DB?", "ctx", options, true),
                new ToolCall("t1", null, AgentRole.TRUNK, "task-1", null));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("shown to the user");
        verify(service).ask(
                eq("t1"), eq("task-1"), isNull(), eq("Which DB?"), eq("ctx"),
                argThat((List<AgentQuestion.Option> opts) ->
                        opts.size() == 2 && "Postgres".equals(opts.get(0).label())),
                eq(true));
    }

    @Test
    void askRejectsABlankQuestion()
    {
        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.askUserQuestion(
                new QuestionToolHandlers.AskUserQuestionArgs("  ", null, null, null),
                new ToolCall("t1", null, AgentRole.TRUNK));

        assertThat(result.isError()).isTrue();
        verify(service, never()).ask(any(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
