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
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestArtifactTools
{
    private static final String THEMED = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"theme.css\"></head><body>%s</body></html>";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ArtifactTools.Page> saved = new ArrayList<>();
    private final List<ArtifactTools.Offer> offered = new ArrayList<>();
    private final ReviewTool save = ArtifactTools.save(mapper, page -> {
        saved.add(page);
        return "Saved";
    });
    private final ReviewTool offer = ArtifactTools.offer(mapper, value -> {
        offered.add(value);
        return "Offered";
    });

    @Test
    void keepsAThemedSelfContainedPageAndItsKey()
            throws Exception
    {
        String html = THEMED.formatted("<a href=\"#steps\">Steps</a><img src=\"data:image/png;base64,AA==\">"
                + "<svg><use href=\"#arrow\"/><rect fill=\"url(#shade)\"/></svg><button data-action=\"delete\" data-src=\"orders\">Delete</button>"
                + "<script>document.title = 'x';</script>");

        assertThat(call(save, ImmutableMap.of("title", " Delete files ", "html", html))).isEqualTo("Saved");
        assertThat(call(save, ImmutableMap.of("title", "Delete files", "html", html, "page", "p2"))).isEqualTo("Saved");
        assertThat(saved).containsExactly(new ArtifactTools.Page("Delete files", html, Optional.empty()), new ArtifactTools.Page("Delete files", html, Optional.of("p2")));
    }

    @Test
    void refusesAPageWithAReasonTheAgentCanFix()
            throws Exception
    {
        assertThat(call(save, ImmutableMap.of("title", "Delete files", "html", "<p>no theme</p>"))).contains("theme.css");
        assertThat(call(save, ImmutableMap.of("title", "Delete files", "html", THEMED.formatted("<script src=\"https://cdn.example/d3.js\"></script>"))))
                .contains("points outside itself at https://cdn.example/d3.js");
        assertThat(call(save, ImmutableMap.of("title", "Delete files", "html", THEMED.formatted("<a href='https://github.com/trinodb/trino'>source</a>"))))
                .contains("https://github.com/trinodb/trino");
        assertThat(call(save, ImmutableMap.of("title", "Delete files", "html", THEMED.formatted("<div style=\"background: url(//example.com/a.png)\"></div>"))))
                .contains("//example.com/a.png");
        assertThat(call(save, ImmutableMap.of("title", "", "html", THEMED.formatted("")))).contains("title");
        assertThat(call(save, ImmutableMap.of("title", "Big", "html", THEMED.formatted("x".repeat(ArtifactTools.MAXIMUM_PAGE))))).contains("KiB");
        assertThat(call(save, ImmutableMap.of("title", "Keyed", "html", THEMED.formatted(""), "page", "../p1"))).contains("page key");
        assertThat(save.call().apply("not json")).startsWith("Not saved:");
        assertThat(saved).isEmpty();
    }

    @Test
    void offersAPageWithATitleAndWhatItCovers()
            throws Exception
    {
        assertThat(call(offer, ImmutableMap.of("title", "Deletes on read", "covers", "Position and equality deletes on the read path."))).isEqualTo("Offered");
        assertThat(call(offer, ImmutableMap.of("title", "Deletes on read", "covers", ""))).startsWith("Not offered:");
        assertThat(call(offer, ImmutableMap.of("title", "Two\nlines", "covers", "Something"))).startsWith("Not offered:");
        assertThat(offered).containsExactly(new ArtifactTools.Offer("Deletes on read", "Position and equality deletes on the read path."));
    }

    private String call(ReviewTool tool, Object arguments)
            throws Exception
    {
        return tool.call().apply(mapper.writeValueAsString(arguments));
    }
}
