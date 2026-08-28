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
package com.bytequay.app.web.agent;

import com.bytequay.app.service.ai.BetterWordsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/agent")
public class BetterWordsController
{
    private final BetterWordsService betterWords;

    public BetterWordsController(BetterWordsService betterWords)
    {
        this.betterWords = requireNonNull(betterWords, "betterWords is null");
    }

    @PostMapping("/better-words")
    public BetterWordsResponse improve(@RequestBody BetterWordsRequest request)
    {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        return new BetterWordsResponse(betterWords.improve(
                request.text(),
                request.repository(),
                Optional.ofNullable(request.workspaceId()).filter(value -> !value.isBlank())));
    }

    public record BetterWordsRequest(String text, String repository, String workspaceId) {}

    public record BetterWordsResponse(String text) {}
}
