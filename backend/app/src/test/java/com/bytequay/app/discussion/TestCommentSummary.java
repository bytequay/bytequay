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
package com.bytequay.app.discussion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCommentSummary
{
    private static final String ONE = """
            {"ref":"c1","ask":" Rename the flag. ","valid":"yes","why":"The name hides its effect.","do":"yes","plan":"Rename it in Config.","size":"small"}""";
    private static final String TWO = """
            {"ref":"c2","ask":"Add a test.","valid":"partly","why":"One path is covered.","do":"no","plan":"Skip it.","size":"big"}""";

    @Test
    void normalizesOneVerdictPerCommentInRefOrder()
    {
        assertThat(CommentSummary.parse("{\"items\":[" + ONE + "]}", 1)).containsExactly("""
                {"ask":"Rename the flag.","valid":"yes","why":"The name hides its effect.","do":"yes","plan":"Rename it in Config.","size":"small"}""");
        // Refs, not positions, decide the order; and a comment not to act on keeps no plan or size.
        assertThat(CommentSummary.parse("{\"items\":[" + TWO + "," + ONE + "]}", 2)).containsExactly("""
                {"ask":"Rename the flag.","valid":"yes","why":"The name hides its effect.","do":"yes","plan":"Rename it in Config.","size":"small"}""", """
                {"ask":"Add a test.","valid":"partly","why":"One path is covered.","do":"no","plan":"","size":""}""");
        assertThat(CommentSummary.parse("""
                {"items":[{"ref":"c1","ask":"Add a test.","valid":"no","why":"It exists.","do":"no"}]}""", 1)).containsExactly("""
                {"ask":"Add a test.","valid":"no","why":"It exists.","do":"no","plan":"","size":""}""");
    }

    @Test
    void stripsOneSurroundingCodeFence()
    {
        assertThat(CommentSummary.parse("```json\n{\"items\":[" + ONE + "]}\n```", 1)).hasSize(1);
        assertThatInvalid("```json\n```json\n{\"items\":[" + ONE + "]}\n```\n```", 1);
    }

    @Test
    void refusesAnythingButTheVerdictShape()
    {
        assertThatInvalid("The comment is right.", 1);
        assertThatInvalid("{\"items\":[" + ONE + "]} and more", 1);
        assertThatInvalid("{\"items\":[" + ONE + "],\"items\":[" + ONE + "]}", 1);
        assertThatInvalid("{\"items\":[" + ONE + "],\"note\":\"x\"}", 1);
        assertThatInvalid("{\"items\":[" + ONE + "]}", 2);
        assertThatInvalid("{\"items\":[" + ONE + "," + ONE + "]}", 2);
        assertThatInvalid("{\"items\":[" + TWO + "]}", 1);
        assertThatInvalid("{\"items\":[" + ONE.replace("\"size\"", "\"extra\":\"x\",\"size\"") + "]}", 1);
        assertThatInvalid("{\"items\":[" + ONE.replace("\"yes\",\"why\"", "\"maybe\",\"why\"") + "]}", 1);
        assertThatInvalid("{\"items\":[" + ONE.replace("\"small\"", "\"huge\"") + "]}", 1);
        assertThatInvalid("{\"items\":[" + ONE.replace("\"small\"", "3") + "]}", 1);
        assertThatInvalid("{\"items\":[" + ONE.replace("\" Rename the flag. \"", "\"  \"") + "]}", 1);
    }

    @Test
    void anItemToActOnNeedsAPlan()
    {
        assertThatInvalid("{\"items\":[" + ONE.replace("\"Rename it in Config.\"", "\"\"") + "]}", 1);
        assertThatInvalid("{\"items\":[" + ONE.replace(",\"plan\":\"Rename it in Config.\"", "") + "]}", 1);
    }

    private static void assertThatInvalid(String answer, int count)
    {
        assertThatThrownBy(() -> CommentSummary.parse(answer, count))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The agent's summary was not in the expected form: ");
    }
}
