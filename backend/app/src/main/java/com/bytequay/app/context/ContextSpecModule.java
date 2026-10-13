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
package com.bytequay.app.context;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextLimits;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * A module of the repository a spec check can start from: a directory holding a build file, with the page its own code cites in the most
 * sections. That is how a specification the module implements usually shows: Trino's Delta Lake connector cites twenty sections of the
 * Delta protocol.
 *
 * @param page the page the module's own code cites in the most sections; tests, docs and bundled third-party code are not its own
 * @param sections how many sections of that page the code cites
 * @param links how often the code cites that page
 */
public record ContextSpecModule(String directory, Optional<String> page, int sections, int links)
{
    static final int MAX_MODULES = 1000;
    private static final Pattern BUILD_FILE = Pattern.compile("(.+/)(pom\\.xml|build\\.gradle(\\.kts)?|package\\.json|go\\.mod|Cargo\\.toml|pyproject\\.toml|setup\\.py)");
    private static final Pattern NOT_OWN_CODE = Pattern.compile("(.*/)?(tests?|testing|__tests__|docs?|vendor|node_modules|dist|third[_-]party)/.*|.*\\.min\\.js");
    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(30);
    private static final int SCAN_BYTES = 32 * 1024 * 1024;

    public ContextSpecModule
    {
        ContextLimits.pathPrefix(directory);
        requireNonNull(page, "page is null");
        checkArgument(sections >= 0 && links >= 0, "counts are negative");
    }

    /**
     * The repository's modules at the baseline: those whose own code cites a page in the most sections first, then the rest by path. A
     * repository with more links than the scan reads still lists its modules, unranked.
     */
    static List<ContextSpecModule> scan(GitCommandRunner git, WorkspaceRepositoryContext workspace, ContextBuildService.Baseline baseline)
    {
        GitCommandRunner.Result files = git.runLocalBoundedStdout(workspace.repository(), SCAN_TIMEOUT, SCAN_BYTES,
                ImmutableList.of("ls-tree", "-r", "-z", "--name-only", baseline.sha()));
        checkArgument(files.successful() && !files.truncated(), "The files on %s at %s could not be listed", baseline.branch(), baseline.sha().substring(0, 12));
        Set<String> modules = new TreeSet<>();
        for (String file : files.output().split("\0")) {
            Matcher build = BUILD_FILE.matcher(file);
            if (build.matches() && !NOT_OWN_CODE.matcher(file).matches() && checkable(build.group(1))) {
                modules.add(build.group(1));
            }
        }
        Map<String, Map<String, Integer>> links = new HashMap<>();
        Map<String, Map<String, Set<String>>> sections = new HashMap<>();
        GitCommandRunner.Result found = git.runLocalBoundedStdout(workspace.repository(), SCAN_TIMEOUT, SCAN_BYTES,
                ImmutableList.of("grep", "--no-color", "-I", "-o", "-E", "-e", ContextSpecSource.LINK, baseline.sha(), "--", ":(exclude,glob)**/*.xml", ":(exclude,glob)**/*.xsd"));
        if ((found.successful() || found.exitCode() == 1) && !found.truncated()) {
            String prefix = baseline.sha() + ":";
            for (String line : found.output().split("\n")) {
                // Each match reads <commit>:<path>:<link>.
                int colon = line.indexOf(":http", prefix.length());
                if (!line.startsWith(prefix) || colon < 0 || NOT_OWN_CODE.matcher(line.substring(prefix.length(), colon)).matches()) {
                    continue;
                }
                Optional<String> module = module(modules, line.substring(prefix.length(), colon));
                Optional<ContextSpecSource.Link> link = ContextSpecSource.link(line.substring(colon + 1))
                        .filter(value -> ContextSpecSource.classify(value.address()) == ContextSpecSource.Kind.PAGE);
                if (module.isPresent() && link.isPresent()) {
                    String address = link.get().address();
                    links.computeIfAbsent(module.get(), ignored -> new HashMap<>()).merge(address, 1, Integer::sum);
                    Set<String> cited = sections.computeIfAbsent(module.get(), ignored -> new HashMap<>()).computeIfAbsent(address, ignored -> new HashSet<>());
                    link.get().section().ifPresent(cited::add);
                }
            }
        }
        return modules.stream()
                .map(directory -> {
                    Map<String, Integer> cited = links.getOrDefault(directory, Map.of());
                    Map<String, Set<String>> named = sections.getOrDefault(directory, Map.of());
                    return cited.keySet().stream()
                            .max(Comparator.comparingInt((String address) -> named.getOrDefault(address, Set.of()).size()).thenComparingInt(cited::get)
                                    .thenComparing(Comparator.<String>reverseOrder()))
                            .map(top -> new ContextSpecModule(directory, Optional.of(top), named.getOrDefault(top, Set.of()).size(), cited.get(top)))
                            .orElseGet(() -> new ContextSpecModule(directory, Optional.empty(), 0, 0));
                })
                .sorted(Comparator.comparing((ContextSpecModule module) -> module.page().isEmpty())
                        .thenComparing(Comparator.comparingInt(ContextSpecModule::sections).reversed())
                        .thenComparing(Comparator.comparingInt(ContextSpecModule::links).reversed())
                        .thenComparing(ContextSpecModule::directory))
                .limit(MAX_MODULES)
                .collect(toImmutableList());
    }

    /** The innermost module holding the file. */
    private static Optional<String> module(Set<String> modules, String file)
    {
        for (int slash = file.lastIndexOf('/'); slash > 0; slash = file.lastIndexOf('/', slash - 1)) {
            String directory = file.substring(0, slash + 1);
            if (modules.contains(directory)) {
                return Optional.of(directory);
            }
        }
        return Optional.empty();
    }

    /** A directory a check can start from; one named like an option or holding control characters is left out. */
    private static boolean checkable(String directory)
    {
        try {
            ContextLimits.pathPrefix(directory);
            return true;
        }
        catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
