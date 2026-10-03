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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A page a directory's code links, which the check reads to decide whether it is a specification the code implements, or another
 * engine's source file, kept as a reference implementation to compare with and never as a rule.
 */
public record ContextSpecSource(String url, Kind kind, int links, List<String> sections)
{
    static final int MAX_SOURCES = 20;
    static final int MAX_SECTIONS = 40;
    static final String LINK = "https?://[A-Za-z0-9./_#%~+-]+";
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
        PAGE, REFERENCE_CODE
    }

    /**
     * Groups the links in the directory's code and documentation at the baseline by address: pages first, then reference
     * code, each by the sections and links the code cites. Whether a page is a specification is for the
     * check to read; only what never is one is left out: build files' XML, license links, site home pages, and repository
     * pages other than files, such as issues and pull requests.
     */
    static List<ContextSpecSource> scan(GitCommandRunner git, WorkspaceRepositoryContext workspace, ContextBuildService.Baseline baseline, String directory)
    {
        ContextLimits.pathPrefix(directory);
        // Git reads `<rev>:<path>^{tree}` as a path ending in `^{tree}`, so the object's type tells a directory apart.
        GitCommandRunner.Result type = git.run(workspace.repository(), "cat-file", "-t", baseline.sha() + ":" + directory.substring(0, directory.length() - 1));
        checkArgument(type.successful() && type.output().strip().equals("tree"), "%s is not a directory on %s at %s", directory, baseline.branch(),
                baseline.sha().substring(0, 12));
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Set<String>> sections = new LinkedHashMap<>();
        GitCommandRunner.Result output = git.runLocalBoundedStdout(workspace.repository(), SCAN_TIMEOUT, SCAN_BYTES,
                ImmutableList.of("grep", "--no-color", "-I", "-h", "-o", "-E", "-e", LINK, baseline.sha(), "--", ":(literal)" + directory,
                        ":(exclude,glob)**/*.xml", ":(exclude,glob)**/*.xsd"));
        checkArgument((output.successful() || output.exitCode() == 1) && !output.truncated(), "The directory's code has too many links to scan");
        for (String line : output.output().split("\n")) {
            link(line).ifPresent(link -> {
                counts.merge(link.address(), 1, Integer::sum);
                link.section().ifPresent(section -> sections.computeIfAbsent(link.address(), ignored -> new LinkedHashSet<>()).add(section));
            });
        }
        List<ContextSpecSource> found = new ArrayList<>();
        counts.forEach((address, links) -> found.add(new ContextSpecSource(address, classify(address), links,
                sections.getOrDefault(address, ImmutableSet.of()).stream().limit(MAX_SECTIONS).toList())));
        found.sort(Comparator.comparing(ContextSpecSource::kind)
                .thenComparing(Comparator.comparingInt((ContextSpecSource source) -> source.sections().size()).reversed())
                .thenComparing(Comparator.comparingInt(ContextSpecSource::links).reversed()).thenComparing(ContextSpecSource::url));
        return found.stream().limit(MAX_SOURCES).collect(ImmutableList.toImmutableList());
    }

    /** A link as the code writes it, by its address and `#section`; empty when it never names a page that could be a spec. */
    static Optional<Link> link(String text)
    {
        // Pages are only read over HTTPS, which every spec host serves.
        String link = text.strip().replaceAll("[.,;:]+$", "").replaceFirst("^http://", "https://");
        int hash = link.indexOf('#');
        String address = hash < 0 ? link : link.substring(0, hash);
        if (address.isEmpty() || ignored(address)) {
            return Optional.empty();
        }
        return Optional.of(new Link(address, hash >= 0 && hash + 1 < link.length() ? Optional.of(link.substring(hash + 1)) : Optional.empty()));
    }

    record Link(String address, Optional<String> section) {}

    static Kind classify(String address)
    {
        return SOURCE_FILE.matcher(URI.create(address).getPath()).matches() ? Kind.REFERENCE_CODE : Kind.PAGE;
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
                || path.isEmpty() || path.equals("/") || path.contains("/licenses/") || (host.equals("github.com") && REPOSITORY_NOISE.matcher(path).matches());
    }
}
