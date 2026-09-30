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
import com.google.common.collect.ImmutableSet;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A document a topic's code implements, found by the links its code carries: a specification, protocol or reference page, or
 * another engine's source file, kept as a reference implementation to compare with and never as a rule.
 */
public record ContextSpecSource(String url, Kind kind, int links, List<String> sections, boolean approved)
{
    static final int MAX_SOURCES = 20;
    static final int MAX_SECTIONS = 40;
    private static final String LINK = "https?://[A-Za-z0-9./_#%~+-]+";
    private static final Pattern SOURCE_FILE = Pattern.compile(".*\\.(java|scala|kt|py|go|rs|c|cc|cpp|h|hpp|js|ts)$");
    private static final Pattern REPOSITORY_NOISE = Pattern.compile("/[^/]+/[^/]+(/(issues|pull|pulls|commit|commits|compare|releases|actions|tree)(/.*)?)?/?");
    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(10);
    private static final int SCAN_BYTES = 5 * 1024 * 1024;

    public ContextSpecSource
    {
        ContextLimits.documentationUrl(url);
        requireNonNull(kind, "kind is null");
        checkArgument(links >= 0, "links is negative");
        sections = ImmutableList.copyOf(sections);
    }

    public enum Kind
    {
        DOCUMENT, REFERENCE_CODE
    }

    /**
     * Groups the links in the topic's paths at the frozen baseline by address, most-linked documents first, then reference code;
     * issues, pull requests, repository pages and license links are left out. The topic's approved documentation follows.
     */
    static List<ContextSpecSource> scan(GitCommandRunner git, WorkspaceRepositoryContext workspace, String baseline, List<String> paths, List<String> approved)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Set<String>> sections = new LinkedHashMap<>();
        if (!paths.isEmpty()) {
            GitCommandRunner.Result output = git.runLocalBoundedStdout(workspace.repository(), SCAN_TIMEOUT, SCAN_BYTES,
                    ImmutableList.<String>builder().add("grep", "--no-color", "-I", "-h", "-o", "-E", "-e", LINK, baseline, "--")
                            .addAll(paths.stream().map(path -> ":(literal)" + path).toList()).build());
            checkArgument((output.successful() || output.exitCode() == 1) && !output.truncated(), "The topic's code has too many links to scan");
            for (String line : output.output().split("\n")) {
                // Pages are only read over HTTPS, which every spec host serves.
                String link = line.strip().replaceAll("[.,;:]+$", "").replaceFirst("^http://", "https://");
                int hash = link.indexOf('#');
                String address = hash < 0 ? link : link.substring(0, hash);
                if (address.isEmpty() || ignored(address)) {
                    continue;
                }
                counts.merge(address, 1, Integer::sum);
                if (hash >= 0 && hash + 1 < link.length()) {
                    sections.computeIfAbsent(address, ignored -> new LinkedHashSet<>()).add(link.substring(hash + 1));
                }
            }
        }
        List<ContextSpecSource> found = new ArrayList<>();
        counts.forEach((address, links) -> found.add(new ContextSpecSource(address, SOURCE_FILE.matcher(URI.create(address).getPath()).matches()
                ? Kind.REFERENCE_CODE : Kind.DOCUMENT, links, sections.getOrDefault(address, ImmutableSet.of()).stream().limit(MAX_SECTIONS).toList(),
                approved.contains(address))));
        found.sort(Comparator.comparing(ContextSpecSource::kind).thenComparing(Comparator.comparingInt(ContextSpecSource::links).reversed())
                .thenComparing(ContextSpecSource::url));
        approved.stream().filter(url -> !counts.containsKey(url)).forEach(url -> found.add(new ContextSpecSource(url, Kind.DOCUMENT, 0, ImmutableList.of(), true)));
        return found.stream().limit(MAX_SOURCES).collect(ImmutableList.toImmutableList());
    }

    private static boolean ignored(String address)
    {
        URI uri;
        try {
            uri = URI.create(address);
            ContextLimits.documentationUrl(address);
        }
        catch (IllegalArgumentException invalid) {
            return true;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        return host.isEmpty() || host.equals("localhost") || host.startsWith("127.") || host.matches("(.*\\.)?example\\.(com|org|net)")
                || path.contains("/licenses/") || (host.equals("github.com") && REPOSITORY_NOISE.matcher(path).matches());
    }
}
