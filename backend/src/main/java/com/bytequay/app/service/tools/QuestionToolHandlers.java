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

import com.bytequay.app.developmentflow.userwait.V2UserWaitService;
import com.bytequay.app.domain.AgentQuestion;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.service.question.AgentQuestionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The {@code ask_user_question} clarification tool. It records the question
 * (so the frontend renders the amber card and it survives a reload) and ends
 * the turn — it does <em>not</em> block. The user's reply arrives as the next
 * message, which the agent reads on its next turn.
 */
@Component
public class QuestionToolHandlers
{
    private static final String SHOWN_MESSAGE = ""
            + "Your question has been shown to the user as a card with every option. "
            + "STOP NOW: end the turn, do not re-ask the question in prose, do not "
            + "apologize or summarise. The user's reply will arrive as the next message.";

    private final AgentQuestionService questions;
    private final V2UserWaitService v2Waits;

    @Autowired
    public QuestionToolHandlers(
            AgentQuestionService questions, V2UserWaitService v2Waits)
    {
        this.questions = requireNonNull(questions, "questions is null");
        this.v2Waits = requireNonNull(v2Waits, "v2Waits is null");
    }

    /** Compatibility constructor for focused legacy tool tests. */
    public QuestionToolHandlers(AgentQuestionService questions)
    {
        this.questions = requireNonNull(questions, "questions is null");
        this.v2Waits = null;
    }

    /** Args for {@code ask_user_question}. {@code options} is a raw JSON array
     *  so the schema stays simple; the handler validates each entry. */
    public record AskUserQuestionArgs(
            @ToolParam(description = "The question to ask the user.", required = true)
            String question,
            @ToolParam(description = "Optional markdown explaining what prompted the question.")
            String context,
            @ToolParam(
                    description = "Optional multiple-choice options — a JSON array of objects, each with "
                            + "id (string), label (string shown on the button), and extra "
                            + "(optional short monospace hint).")
            JsonNode options,
            @ToolParam(description = "Whether to also allow a free-form typed answer. Defaults to true.")
            Boolean allowFreeForm)
    {
    }

    @AgentTool(
            name = "ask_user_question",
            description = "Ask for any user decision or confirmation, including approval to cut or create "
                    + "a task. Always use this instead of asking only in prose. Renders as a card "
                    + "with optional multiple-choice options plus a free-form reply. This does NOT block: "
                    + "record the question, then END YOUR TURN — the user's answer arrives as the next "
                    + "message. Use during exploration / planning whenever you'd otherwise guess.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome askUserQuestion(AskUserQuestionArgs args, ToolCall call)
    {
        String threadId = call.threadId();
        if (threadId == null || threadId.isBlank()) {
            return ToolOutcome.Completed.error("no thread bound to this call");
        }
        if (args == null || args.question() == null || args.question().isBlank()) {
            return ToolOutcome.Completed.error("question is required");
        }
        boolean allowFreeForm = args.allowFreeForm() == null || args.allowFreeForm();
        List<AgentQuestion.Option> options = readOptions(args.options());
        try {
            Optional<String> v2Wait = v2Waits == null
                    ? Optional.empty()
                    : v2Waits.askQuestion(
                            threadId, call.runtimeAgentKey(), call.callId(),
                            args.question(),
                            args.context(), options, allowFreeForm);
            if (v2Wait.isPresent()) {
                return new ToolOutcome.WaitForUser(
                        SHOWN_MESSAGE, "QUESTION:" + v2Wait.orElseThrow());
            }
            questions.ask(
                    threadId,
                    call.scope() == ThreadScope.TRUNK ? null : call.requireTaskId(),
                    /* toolCallId */ null,
                    args.question(),
                    args.context(),
                    options,
                    allowFreeForm);
        }
        catch (RuntimeException e) {
            return ToolOutcome.Completed.error("could not record the question: " + e.getMessage());
        }
        return ToolOutcome.Completed.ok(SHOWN_MESSAGE);
    }

    private static List<AgentQuestion.Option> readOptions(JsonNode node)
    {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AgentQuestion.Option> out = new ArrayList<>();
        for (JsonNode element : node) {
            String id = element.path("id").asText("").strip();
            String label = element.path("label").asText("").strip();
            if (id.isEmpty() || label.isEmpty()) {
                continue;
            }
            String extra = element.hasNonNull("extra") ? element.get("extra").asText() : null;
            out.add(new AgentQuestion.Option(id, label, extra));
        }
        return out;
    }
}
