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
package com.bytequay.app.review;

import com.bytequay.core.domain.DiffFile;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * What a script can see about a change before any model reads it: commit message sizes and markers, the options a
 * description marks under its template's sections, and tests the change adds switched off. Each line is a
 * measurement, never a judgement; whether it deserves a comment is the reviewer's call.
 */
final class ReviewFacts
{
    private static final int MAX_COMMITS = 30;
    private static final int MAX_SWITCHED_OFF = 10;
    private static final int MAX_QUOTE = 160;
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern UNFINISHED = Pattern.compile("^(?:wip|tmp|temp)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s*(.+?)\\s*#*\\s*$");
    // "( )", "()", "(x )" and the task-list form "- [x]".
    private static final Pattern OPTION = Pattern.compile("^\\s*(?:[-*]\\s*)?(?:\\(\\s*([xX]?)\\s*\\)|\\[\\s*([xX]?)\\s*\\])\\s*(\\S.*)$");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    private static final Pattern SWITCHED_OFF = Pattern.compile(
            "@Disabled\\b|@Ignore\\b|\\benabled\\s*=\\s*false\\b|\\b(?:it|test|describe)\\.skip\\(|\\bx(?:it|describe)\\(|@pytest\\.mark\\.skip|\\bt\\.Skip\\(");

    private ReviewFacts() {}

    /** One commit of the change, oldest first. */
    record Commit(String sha, String subject, String body)
    {
        Commit
        {
            requireNonNull(sha, "sha is null");
            requireNonNull(subject, "subject is null");
            requireNonNull(body, "body is null");
        }
    }

    /**
     * @param description the pull request description, absent for a local change or a replay
     * @param template the repository's pull request template, when it has one
     */
    static List<String> describe(List<Commit> commits, Optional<String> description, Optional<String> template, List<DiffFile> diff)
    {
        ImmutableList.Builder<String> facts = ImmutableList.builder();
        commits.stream().limit(MAX_COMMITS).map(ReviewFacts::commit).forEach(facts::add);
        if (commits.size() > MAX_COMMITS) {
            facts.add((commits.size() - MAX_COMMITS) + " more commits are not listed");
        }
        if (description.isPresent() && template.isPresent()) {
            facts.addAll(options(description.orElseThrow(), template.orElseThrow()));
        }
        facts.addAll(switchedOff(diff));
        return facts.build();
    }

    private static String commit(Commit commit)
    {
        String subject = commit.subject().strip();
        String lower = subject.toLowerCase(Locale.ROOT);
        StringBuilder fact = new StringBuilder("commit ").append(commit.sha(), 0, Math.min(11, commit.sha().length()))
                .append(" \"").append(quote(subject)).append("\": subject ").append(subject.codePointCount(0, subject.length())).append(" characters, body ")
                .append(WORD.matcher(commit.body()).results().count()).append(" words");
        if (lower.startsWith("fixup!") || lower.startsWith("squash!") || lower.startsWith("amend!")) {
            fact.append("; a fixup commit");
        }
        if (UNFINISHED.matcher(subject).find()) {
            fact.append("; a work-in-progress subject");
        }
        if (subject.startsWith("Merge ")) {
            fact.append("; a merge commit");
        }
        if (subject.endsWith(".")) {
            fact.append("; the subject ends with a period");
        }
        return fact.toString();
    }

    /** For each template section that offers options, the options the description marks under the same heading. */
    private static List<String> options(String description, String template)
    {
        List<String> facts = new ArrayList<>();
        List<Section> written = sections(description);
        for (Section offered : sections(template)) {
            // Options above the first heading have no section to find again in the description.
            if (offered.options().isEmpty() || offered.heading().isEmpty()) {
                continue;
            }
            Optional<Section> section = written.stream().filter(value -> value.heading().equalsIgnoreCase(offered.heading())).findFirst();
            if (section.isEmpty()) {
                facts.add("the description has no \"" + quote(offered.heading()) + "\" section, which the pull request template offers with options");
                continue;
            }
            List<String> marked = section.orElseThrow().options().stream().filter(Option::marked)
                    .map(option -> "marked \"" + quote(option.text()) + "\"").collect(toImmutableList());
            String name = "description section \"" + quote(offered.heading()) + "\": ";
            facts.add(name + (marked.isEmpty() ? "no option is marked" : (marked.size() == 1 ? "" : marked.size() + " options are marked: ") + String.join(", ", marked)));
        }
        return facts;
    }

    private static List<Section> sections(String text)
    {
        List<Section> sections = new ArrayList<>();
        String heading = "";
        List<Option> options = new ArrayList<>();
        boolean fenced = false;
        for (String line : text.replace("\r", "").split("\n")) {
            if (line.stripLeading().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher title = HEADING.matcher(line);
            if (title.matches()) {
                sections.add(new Section(heading, ImmutableList.copyOf(options)));
                heading = title.group(1);
                options = new ArrayList<>();
                continue;
            }
            Matcher option = OPTION.matcher(line);
            if (option.matches()) {
                boolean marked = !Optional.ofNullable(option.group(1)).orElse("").isEmpty() || !Optional.ofNullable(option.group(2)).orElse("").isEmpty();
                options.add(new Option(marked, option.group(3).strip()));
            }
        }
        sections.add(new Section(heading, ImmutableList.copyOf(options)));
        return sections;
    }

    private static List<String> switchedOff(List<DiffFile> diff)
    {
        List<String> facts = new ArrayList<>();
        for (DiffFile file : diff) {
            int line = 0;
            for (String raw : Optional.ofNullable(file.patch()).orElse("").split("\n")) {
                Matcher hunk = HUNK.matcher(raw);
                if (hunk.matches()) {
                    line = Integer.parseInt(hunk.group(1));
                    continue;
                }
                if (line == 0 || raw.startsWith("-") || raw.startsWith("\\")) {
                    continue;
                }
                if (raw.startsWith("+") && SWITCHED_OFF.matcher(raw).find()) {
                    if (facts.size() == MAX_SWITCHED_OFF) {
                        facts.add("more switched-off tests are not listed");
                        return facts;
                    }
                    facts.add(file.filename() + ":" + line + " adds a test that is switched off: " + quote(raw.substring(1).strip()));
                }
                line++;
            }
        }
        return facts;
    }

    private static String quote(String text)
    {
        String flat = text.replace('"', '\'').replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_QUOTE ? flat : flat.substring(0, MAX_QUOTE) + "…";
    }

    private record Section(String heading, List<Option> options) {}

    private record Option(boolean marked, String text) {}
}
