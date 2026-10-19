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
package com.bytequay.app.artifact;

import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestArtifactSaveTool
{
    private static final String THEMED = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"theme.css\"></head><body>%s</body></html>";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ArtifactSaveTool.Page> saved = new ArrayList<>();
    private final ReviewTool tool = ArtifactSaveTool.tool(mapper, page -> {
        saved.add(page);
        return "Saved";
    });

    @Test
    void keepsAThemedSelfContainedPageWithItsCards()
            throws Exception
    {
        String html = THEMED.formatted("<a href=\"#steps\">Steps</a><img src=\"data:image/png;base64,AA==\">"
                + "<svg><use href=\"#arrow\"/><rect fill=\"url(#shade)\"/></svg><script>document.title = 'x';</script>");
        String answer = call(ImmutableMap.of("title", " Delete files ", "html", html,
                "next", ImmutableList.of(ImmutableMap.of("title", "Equality deletes", "ask", "Explain equality deletes"))));

        assertThat(answer).isEqualTo("Saved");
        assertThat(saved).containsExactly(new ArtifactSaveTool.Page("Delete files", html,
                ImmutableList.of(new ArtifactSaveTool.Card("Equality deletes", "Explain equality deletes"))));
    }

    @Test
    void refusesWithAReasonTheAgentCanFix()
            throws Exception
    {
        assertThat(call(ImmutableMap.of("title", "Delete files", "html", "<p>no theme</p>"))).contains("theme.css");
        assertThat(call(ImmutableMap.of("title", "Delete files", "html", THEMED.formatted("<script src=\"https://cdn.example/d3.js\"></script>"))))
                .contains("points outside itself at https://cdn.example/d3.js");
        assertThat(call(ImmutableMap.of("title", "Delete files", "html", THEMED.formatted("<a href='https://github.com/trinodb/trino'>source</a>"))))
                .contains("https://github.com/trinodb/trino");
        assertThat(call(ImmutableMap.of("title", "Delete files", "html", THEMED.formatted("<div style=\"background: url(//example.com/a.png)\"></div>"))))
                .contains("//example.com/a.png");
        assertThat(call(ImmutableMap.of("title", "", "html", THEMED.formatted("")))).contains("title");
        assertThat(call(ImmutableMap.of("title", "Big", "html", THEMED.formatted("x".repeat(ArtifactSaveTool.MAXIMUM_PAGE))))).contains("KiB");
        assertThat(call(ImmutableMap.of("title", "Cards", "html", THEMED.formatted(""), "next", ImmutableList.of(ImmutableMap.of("title", "No ask"))))).contains("ask");
        assertThat(tool.call().apply("not json")).startsWith("Not saved:");
        assertThat(saved).isEmpty();
    }

    private String call(Object arguments)
            throws Exception
    {
        return tool.call().apply(mapper.writeValueAsString(arguments));
    }
}
