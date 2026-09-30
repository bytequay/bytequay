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
 * A document a topic's code links: a specification or protocol it implements, other documentation, or another engine's source
 * file, kept as a reference implementation to compare with and never as a rule.
 */
public record ContextSpecSource(String url, Kind kind, int links, List<String> sections, boolean approved)
{
    static final int MAX_SOURCES = 20;
    static final int MAX_SECTIONS = 40;
    private static final String LINK = "https?://[A-Za-z0-9./_#%~+-]+";
    private static final Pattern SOURCE_FILE = Pattern.compile(".*\\.(java|scala|kt|py|go|rs|c|cc|cpp|h|hpp|js|ts)$");
    private static final Pattern REPOSITORY_NOISE = Pattern.compile("/[^/]+/[^/]+(/(issues|pull|pulls|commit|commits|compare|releases|actions|tree)(/.*)?)?/?");
    // ponytail: the address must name what it is; a spec published under another name is listed as documentation, one click to select.
    private static final Pattern SPEC_NAME = Pattern.compile("(?i)(^|[/._+-])(specs?|specifications?|protocols?|rfc\\d*|formats?|standards?)([/._+-]|$)");
    private static final Pattern ISSUE_KEY = Pattern.compile(".*/browse/[A-Z][A-Z0-9_]+-[0-9]+/?");
    private static final Set<String> DISCUSSIONS = ImmutableSet.of("discord.com", "discord.gg", "gitter.im", "groups.google.com", "stackoverflow.com",
            "lists.apache.org", "www.mail-archive.com");
    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(10);
    private static final int SCAN_BYTES = 5 * 1024 * 1024;

    public ContextSpecSource
    {
        ContextLimits.documentationUrl(url);
        requireNonNull(kind, "kind is null");
        checkArgument(links >= 0, "links is negative");
        sections = ImmutableList.copyOf(sections);
    }

    /** Listed in this order; the check starts with the specs selected. */
    public enum Kind
    {
        SPEC, DOCUMENT, REFERENCE_CODE
    }

    /**
     * Groups the links in the topic's code and documentation at the frozen baseline by address: specs first, then other
     * documentation and reference code, each approved and most-linked first. Build files' XML namespaces, issues, pull
     * requests, discussions, site home pages and license links are left out.
     */
    static List<ContextSpecSource> scan(GitCommandRunner git, WorkspaceRepositoryContext workspace, String baseline, List<String> paths, List<String> approved)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Set<String>> sections = new LinkedHashMap<>();
        if (!paths.isEmpty()) {
            GitCommandRunner.Result output = git.runLocalBoundedStdout(workspace.repository(), SCAN_TIMEOUT, SCAN_BYTES,
                    ImmutableList.<String>builder().add("grep", "--no-color", "-I", "-h", "-o", "-E", "-e", LINK, baseline, "--")
                            .addAll(paths.stream().map(path -> ":(literal)" + path).toList())
                            .add(":(exclude,glob)**/*.xml", ":(exclude,glob)**/*.xsd").build());
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
        counts.forEach((address, links) -> found.add(new ContextSpecSource(address, classify(address), links,
                sections.getOrDefault(address, ImmutableSet.of()).stream().limit(MAX_SECTIONS).toList(), approved.contains(address))));
        approved.stream().filter(url -> !counts.containsKey(url)).forEach(url -> found.add(new ContextSpecSource(url, classify(url), 0, ImmutableList.of(), true)));
        found.sort(Comparator.comparing(ContextSpecSource::kind).thenComparing(source -> !source.approved())
                .thenComparing(Comparator.comparingInt(ContextSpecSource::links).reversed()).thenComparing(ContextSpecSource::url));
        return found.stream().limit(MAX_SOURCES).collect(ImmutableList.toImmutableList());
    }

    static Kind classify(String address)
    {
        URI uri = URI.create(address);
        if (SOURCE_FILE.matcher(uri.getPath()).matches()) {
            return Kind.REFERENCE_CODE;
        }
        return SPEC_NAME.matcher(uri.getHost() + uri.getPath()).find() ? Kind.SPEC : Kind.DOCUMENT;
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
                || path.isEmpty() || path.equals("/") || path.contains("/licenses/") || (host.equals("github.com") && REPOSITORY_NOISE.matcher(path).matches())
                // XML namespaces and schemas, issue trackers and discussion threads are never a spec.
                || (host.equals("www.w3.org") && path.matches("/[0-9]{4}/.*")) || (host.equals("maven.apache.org") && path.matches("/(POM|xsd)/.*"))
                || path.matches(".*\\.(xsd|dtd)") || ISSUE_KEY.matcher(path).matches() || path.contains("/-/issues/") || host.startsWith("issues.")
                || host.startsWith("jira.") || host.startsWith("bugs.") || host.equals("slack.com") || host.endsWith(".slack.com") || DISCUSSIONS.contains(host);
    }
}
