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
package com.bytequay.app.service.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestToolFileOps
{
    private final ToolFileOps ops = new ToolFileOps(new ObjectMapper());

    @Test
    void readEmitsAReadOpWithZeroLines()
    {
        List<ToolFileOps.FileOp> out = ops.parse("Read",
                "{\"file_path\":\"src/main.ts\",\"limit\":100}");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path()).isEqualTo("src/main.ts");
        assertThat(out.get(0).operation()).isEqualTo("read");
        assertThat(out.get(0).linesAdded()).isZero();
        assertThat(out.get(0).linesRemoved()).isZero();
    }

    @Test
    void writeCountsContentLines()
    {
        List<ToolFileOps.FileOp> out = ops.parse("Write",
                "{\"file_path\":\"foo.txt\",\"content\":\"line1\\nline2\\nline3\"}");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).operation()).isEqualTo("write");
        assertThat(out.get(0).linesAdded()).isEqualTo(3);
        assertThat(out.get(0).linesRemoved()).isZero();
    }

    @Test
    void editComputesNetLineDelta()
    {
        // old has 1 line, new has 3 lines → +2 added, 0 removed
        List<ToolFileOps.FileOp> out = ops.parse("Edit",
                "{\"file_path\":\"a.ts\",\"old_string\":\"foo\","
                        + "\"new_string\":\"foo\\nbar\\nbaz\"}");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).operation()).isEqualTo("edit");
        assertThat(out.get(0).linesAdded()).isEqualTo(2);
        assertThat(out.get(0).linesRemoved()).isZero();
    }

    @Test
    void editReportsRemovedLinesWhenNewStringIsShorter()
    {
        // old has 4 lines, new has 1 line → 0 added, 3 removed
        List<ToolFileOps.FileOp> out = ops.parse("Edit",
                "{\"file_path\":\"a.ts\","
                        + "\"old_string\":\"a\\nb\\nc\\nd\","
                        + "\"new_string\":\"x\"}");

        assertThat(out.get(0).linesAdded()).isZero();
        assertThat(out.get(0).linesRemoved()).isEqualTo(3);
    }

    @Test
    void multiEditSumsAcrossEvery()
    {
        String input = """
                {"file_path":"x.ts","edits":[
                  {"old_string":"a","new_string":"a\\nb"},
                  {"old_string":"c\\nd\\ne","new_string":"z"}
                ]}
                """;

        List<ToolFileOps.FileOp> out = ops.parse("MultiEdit", input);

        assertThat(out).hasSize(1);
        // First edit: +1 added; second edit: -2 removed.
        assertThat(out.get(0).linesAdded()).isEqualTo(1);
        assertThat(out.get(0).linesRemoved()).isEqualTo(2);
    }

    @Test
    void unknownToolsAndMalformedInputReturnEmpty()
    {
        assertThat(ops.parse("Bash", "{\"command\":\"ls\"}")).isEmpty();
        assertThat(ops.parse("Read", "")).isEmpty();
        assertThat(ops.parse("Read", "not json")).isEmpty();
        assertThat(ops.parse(null, "{}")).isEmpty();
        // Path missing → no row.
        assertThat(ops.parse("Read", "{\"limit\":10}")).isEmpty();
    }
}
