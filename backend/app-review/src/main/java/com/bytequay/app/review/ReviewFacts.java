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
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * What a script can see about a change before any model reads it: commit message sizes and markers, tests that arrive
 * in a later commit than the code they test, the options a description marks under its template's sections, tests the
 * change adds switched off, and multi-line strings written as escapes. Each line is a measurement, never a judgement;
 * whether it deserves a comment is the reviewer's call.
 */
final class ReviewFacts
{
    private static final int MAX_COMMITS = 30;
    private static final int MAX_LATE_TESTS = 5;
    private static final int MAX_SWITCHED_OFF = 10;
    private static final int MAX_ESCAPED = 5;
    private static final int MAX_QUOTE = 160;
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern UNFINISHED = Pattern.compile("^(?:wip|tmp|temp)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s*(.+?)\\s*#*\\s*$");
    // "( )", "()", "(x )" and the task-list form "- [x]".
    private static final Pattern OPTION = Pattern.compile("^\\s*(?:[-*]\\s*)?(?:\\(\\s*([xX]?)\\s*\\)|\\[\\s*([xX]?)\\s*\\])\\s*(\\S.*)$");
    private static final Pattern SWITCHED_OFF = Pattern.compile(
            "@Disabled\\b|@Ignore\\b|\\benabled\\s*=\\s*false\\b|\\b(?:it|test|describe)\\.skip\\(|\\bx(?:it|describe)\\(|@pytest\\.mark\\.skip|\\bt\\.Skip\\(");

    private static final Pattern TEST = Pattern.compile("(?:^|/)(?:src/(?:test|it)|tests?|testing|__tests__|e2e)/"
            + "|(?:^|/)(?:Test[A-Z0-9][^/]*|[^/]*(?:Tests?|IT))\\.(?:java|kt|scala|groovy|cs|swift)$"
            + "|[._-](?:test|spec)\\.[cm]?[jt]sx?$|(?:^|/)test_[^/]*\\.py$|_(?:test|spec)\\.(?:py|go|rb|rs|c|cc|cpp)$");
    private static final Pattern PROSE = Pattern.compile("(?:^|/)docs?/|\\.(?:md|mdx|rst|txt|adoc)$", Pattern.CASE_INSENSITIVE);
    // A test's name without its marker names what it tests: TestLoader, LoaderTest, loader.test, test_loader, loader_test.
    private static final Pattern TESTED = Pattern.compile("^(?:Test(?=[A-Z0-9])|test_)?(.+?)(?:Tests?|IT|[._-](?:test|spec))?$");
    // Languages with a multi-line string literal, where writing the lines as escapes is a choice.
    private static final Pattern MULTI_LINE_LITERALS = Pattern.compile("\\.(?:java|kt|scala|groovy)$");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ESCAPE = Pattern.compile("\\\\(.)");

    private ReviewFacts() {}

    /**
     * Whether paths include tests and other code, so that those tests can be asked to notice the change being undone.
     * Names decide this, so a wrong guess costs the reviewer one sentence saying why the question does not apply.
     */
    static boolean touchesTestsAndCode(List<String> paths)
    {
        return paths.stream().anyMatch(ReviewFacts::test) && paths.stream().anyMatch(path -> !test(path) && !PROSE.matcher(path).find());
    }

    static boolean test(String path)
    {
        return TEST.matcher(path).find();
    }

    /**
     * One commit of the change, oldest first.
     *
     * @param added the files the commit adds
     */
    record Commit(String sha, String subject, String body, List<String> added)
    {
        Commit
        {
            requireNonNull(sha, "sha is null");
            requireNonNull(subject, "subject is null");
            requireNonNull(body, "body is null");
            added = ImmutableList.copyOf(added);
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
        facts.addAll(lateTests(commits));
        if (description.isPresent() && template.isPresent()) {
            facts.addAll(options(description.orElseThrow(), template.orElseThrow()));
        }
        List<Added> added = addedLines(diff);
        facts.addAll(switchedOff(added));
        facts.addAll(escapedStrings(added));
        return facts.build();
    }

    private static String commit(Commit commit)
    {
        String subject = commit.subject().strip();
        String lower = subject.toLowerCase(Locale.ROOT);
        StringBuilder fact = new StringBuilder("commit ").append(abbreviated(commit))
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

    private static String abbreviated(Commit commit)
    {
        return commit.sha().substring(0, Math.min(11, commit.sha().length()));
    }

    /** Tests a commit adds for code that an earlier commit of the same change added, paired by name alone. */
    private static List<String> lateTests(List<Commit> commits)
    {
        List<String> facts = new ArrayList<>();
        Map<String, String> code = new HashMap<>();
        for (Commit commit : commits) {
            for (String path : commit.added()) {
                Optional<String> origin = test(path) ? tested(path).map(code::get) : Optional.empty();
                origin.ifPresent(value -> facts.add("commit " + abbreviated(commit) + " adds " + name(path) + ", the test of " + value));
            }
            // After the tests, so a commit that brings code with its own test pairs nothing.
            for (String path : commit.added()) {
                if (!test(path) && !PROSE.matcher(path).find()) {
                    code.putIfAbsent(stem(path), name(path) + ", which commit " + abbreviated(commit) + " added");
                }
            }
        }
        return capped(facts, MAX_LATE_TESTS, "more tests added after their code are not listed");
    }

    /** What a test file's name says it tests; empty when the name carries no test marker. */
    private static Optional<String> tested(String path)
    {
        String stem = stem(path);
        Matcher named = TESTED.matcher(stem);
        return named.matches() && !named.group(1).equals(stem) ? Optional.of(named.group(1)) : Optional.empty();
    }

    private static String name(String path)
    {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String stem(String path)
    {
        String name = name(path);
        return name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name;
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

    /** The lines the change adds, each at its line number in the new file. */
    private static List<Added> addedLines(List<DiffFile> diff)
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(diff);
        return diff.stream().flatMap(file -> anchors.hunks(file.filename()).stream()).flatMap(hunk -> hunk.lines().stream())
                .filter(line -> line.kind() == '+').map(line -> new Added(line.path(), line.line(), line.text())).collect(toImmutableList());
    }

    private static List<String> switchedOff(List<Added> added)
    {
        return capped(added.stream().filter(line -> SWITCHED_OFF.matcher(line.text()).find())
                .map(line -> line.path() + ":" + line.line() + " adds a test that is switched off: " + quote(line.text().strip()))
                .collect(toImmutableList()), MAX_SWITCHED_OFF, "more switched-off tests are not listed");
    }

    /** Strings that hold several lines as escapes on one line, in the languages that could write them as a multi-line literal. */
    private static List<String> escapedStrings(List<Added> added)
    {
        List<String> facts = new ArrayList<>();
        for (Added line : added) {
            String text = line.text().strip();
            // A commented-out line builds no string.
            if (!MULTI_LINE_LITERALS.matcher(line.path()).find() || text.startsWith("//") || text.startsWith("*") || text.startsWith("/*")) {
                continue;
            }
            int breaks = STRING.matcher(text).results().mapToInt(literal -> lineBreaks(literal.group(1))).max().orElse(0);
            if (breaks >= 2) {
                facts.add(line.path() + ":" + line.line() + " writes a string of several lines with " + breaks + " \\n escapes on one line: " + quote(text));
            }
        }
        return capped(facts, MAX_ESCAPED, "more such strings are not listed");
    }

    /** A literal's line-break escapes; none when they separate no text, as in a separator made of two of them. */
    private static int lineBreaks(String literal)
    {
        int breaks = 0;
        int texts = 0;
        int from = 0;
        Matcher escape = ESCAPE.matcher(literal);
        while (escape.find()) {
            if (escape.group(1).equals("n")) {
                breaks++;
                texts += literal.substring(from, escape.start()).isBlank() ? 0 : 1;
                from = escape.end();
            }
        }
        texts += literal.substring(from).isBlank() ? 0 : 1;
        return texts >= 2 ? breaks : 0;
    }

    private static List<String> capped(List<String> facts, int maximum, String more)
    {
        return facts.size() <= maximum ? facts : ImmutableList.<String>builder().addAll(facts.subList(0, maximum)).add(more).build();
    }

    private static String quote(String text)
    {
        String flat = text.replace('"', '\'').replaceAll("\\s+", " ").strip();
        return Ascii.truncate(flat, MAX_QUOTE + 1, "…");
    }

    private record Added(String path, int line, String text) {}

    private record Section(String heading, List<Option> options) {}

    private record Option(boolean marked, String text) {}
}
