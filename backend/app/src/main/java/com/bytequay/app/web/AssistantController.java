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

import com.bytequay.app.assistant.AssistantPermissions;
import com.bytequay.app.assistant.AssistantService;
import com.bytequay.app.assistant.AssistantSettings;
import com.bytequay.app.assistant.AssistantSummary;
import com.bytequay.app.assistant.AssistantView;
import com.bytequay.core.domain.AssistantConversation;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api")
public class AssistantController
{
    private static final int REF = 200;
    private static final int VISIBLE = 100;

    private final AssistantService assistant;
    private final AssistantSettings settings;

    public AssistantController(AssistantService assistant, AssistantSettings settings)
    {
        this.assistant = requireNonNull(assistant, "assistant is null");
        this.settings = requireNonNull(settings, "settings is null");
    }

    @GetMapping("/assistant/conversations")
    public List<AssistantSummary> conversations()
    {
        return assistant.conversations();
    }

    @GetMapping("/assistant/conversations/{conversationId}")
    public AssistantView conversation(@PathVariable String conversationId)
    {
        checkConversation(conversationId);
        return assistant.conversation(conversationId);
    }

    @PostMapping("/assistant/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted send(@RequestBody MessageRequest request)
    {
        checkArgument(request != null, "message is required");
        checkConversation(request.conversationId());
        checkText(request.text(), "text", AssistantService.MAXIMUM_TEXT);
        checkText(request.key(), "key", REF);
        List<String> visible = Optional.ofNullable(request.visible()).orElse(ImmutableList.of());
        checkArgument(visible.size() <= VISIBLE, "visible lists at most %s cards", VISIBLE);
        visible.forEach(ref -> checkText(ref, "visible ref", REF));
        checkOptionalText(request.onScreen(), "onScreen", REF);
        return new Accepted(assistant.send(request.conversationId(), request.text(), visible, request.onScreen(), request.key()));
    }

    @PostMapping("/assistant/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop()
    {
        assistant.stop();
    }

    @PostMapping("/assistant/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted retry(@RequestBody RetryRequest request)
    {
        checkArgument(request != null, "conversationId is required");
        checkConversation(request.conversationId());
        return new Accepted(assistant.retry(request.conversationId()));
    }

    /** A card the user answered with its own button in the panel; the conversation keeps its receipt. */
    @PostMapping("/assistant/answers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void answered(@RequestBody AnsweredRequest request)
    {
        checkArgument(request != null, "approvalId is required");
        checkConversation(request.conversationId());
        checkText(request.approvalId(), "approvalId", REF);
        assistant.answered(request.conversationId(), request.approvalId());
    }

    /** A draft the user created with its own button in the panel; the conversation keeps its receipt. */
    @PostMapping("/assistant/drafts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void drafted(@RequestBody DraftedRequest request)
    {
        checkArgument(request != null, "itemId is required");
        checkConversation(request.conversationId());
        checkText(request.itemId(), "itemId", REF);
        assistant.drafted(request.conversationId(), request.itemId());
    }

    @GetMapping("/settings/assistant")
    public AssistantPermissions permissions()
    {
        return settings.permissions();
    }

    @PutMapping("/settings/assistant")
    public AssistantPermissions savePermissions(@RequestBody AssistantPermissions permissions)
    {
        checkArgument(permissions != null, "permissions are required");
        return settings.save(permissions);
    }

    private static void checkConversation(String conversationId)
    {
        checkArgument(AssistantConversation.isId(conversationId), "conversationId is not a conversation id");
    }

    /**
     * @param conversationId the conversation the message goes on; the panel names a new one itself
     * @param visible the refs of the cards on screen when the message was sent
     * @param onScreen the ref of the page the user is looking at
     * @param key the sender's own key for this message, so sending it twice adds it once
     */
    public record MessageRequest(String conversationId, String text, List<String> visible, Optional<String> onScreen, String key) {}

    public record Accepted(String itemId) {}

    public record RetryRequest(String conversationId) {}

    /**
     * @param conversationId the conversation on screen, or a new one the receipt begins
     */
    public record AnsweredRequest(String conversationId, String approvalId) {}

    public record DraftedRequest(String conversationId, String itemId) {}
}
