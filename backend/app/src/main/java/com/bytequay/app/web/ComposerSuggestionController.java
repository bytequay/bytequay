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

import com.bytequay.app.workspace.ComposerSuggestion;
import com.bytequay.app.workspace.ComposerSuggestionService;
import com.bytequay.app.workspace.ComposerSuggestionService.Command;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/composer-suggestions")
public class ComposerSuggestionController
{
    private final ComposerSuggestionService suggestions;

    public ComposerSuggestionController(ComposerSuggestionService suggestions)
    {
        this.suggestions = requireNonNull(suggestions, "suggestions is null");
    }

    @GetMapping
    public List<ComposerSuggestion> list(@PathVariable String workspaceId, @RequestParam Command command, @RequestParam(defaultValue = "") String search)
    {
        return suggestions.list(workspaceId, command, search);
    }
}
