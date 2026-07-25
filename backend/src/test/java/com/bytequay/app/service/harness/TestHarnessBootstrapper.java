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
package com.bytequay.app.service.harness;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestHarnessBootstrapper
{
    @Test
    void ignoresOnlyRecognizedPureStatusJobs()
    {
        var aggregators = inspect(List.of(
                "jobs:",
                "  fan_in:",
                "    needs: [unit, integration]",
                "    runs-on: ubuntu-latest",
                "    steps:",
                "      - run: test ${{ needs.unit.result }} = success",
                "  needs_only:",
                "    needs: unit",
                "    runs-on: ubuntu-latest",
                "    steps:",
                "      - run: echo done"));

        assertThat(aggregators).containsExactlyInAnyOrder("fan_in");
    }

    @Test
    void reusableWorkflowWithNeedsIsNotAnAggregator()
    {
        var aggregators = inspect(List.of(
                "jobs:",
                "  delegated_tests:",
                "    needs: build",
                "    uses: acme/ci/.github/workflows/tests.yml@main"));

        assertThat(aggregators).isEmpty();
    }

    @Test
    void actionStepMakesAStatusJobSubstantive()
    {
        var aggregators = inspect(List.of(
                "jobs:",
                "  report:",
                "    needs: [unit, integration]",
                "    runs-on: ubuntu-latest",
                "    steps:",
                "      - uses: actions/github-script@v7",
                "      - run: test ${{ needs.unit.result }} = success"));

        assertThat(aggregators).isEmpty();
    }

    private static LinkedHashSet<String> inspect(List<String> workflow)
    {
        var aggregators = new LinkedHashSet<String>();
        HarnessBootstrapper.inspectWorkflow(
                workflow,
                new LinkedHashMap<>(),
                aggregators,
                new LinkedHashSet<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>());
        return aggregators;
    }
}
