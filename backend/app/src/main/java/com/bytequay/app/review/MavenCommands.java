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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Recognizes a Maven invocation in the command text an agent provider reports.
 *
 * <p>Neither provider tokenizes argv: Codex sends one string, usually wrapped in the user's shell, and Claude sends the
 * bare Bash script. The text is split into shell words, a leading {@code <shell> -c} script is unwrapped, and a command
 * matches when any {@code &&}, {@code ||}, {@code ;}, {@code |} or {@code &} segment runs {@code mvn} or {@code mvnw}.
 */
public final class MavenCommands
{
    private static final int MAXIMUM_DISPLAY_CHARACTERS = 16 * 1024;
    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    private static final Pattern SELECTOR = Pattern.compile("(" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)(?:#(" + IDENTIFIER + "))?");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*", Pattern.DOTALL);
    private static final Set<String> SHELLS = ImmutableSet.of("sh", "bash", "zsh", "dash", "ksh");
    private static final Set<String> WRAPPERS = ImmutableSet.of("env", "exec", "time", "command", "nohup");
    private static final Set<String> MAVEN = ImmutableSet.of("mvn", "mvnw");
    private static final Set<String> SEPARATORS = ImmutableSet.of("&&", "||", ";", "|", "&");
    private static final Set<String> REDIRECTS = ImmutableSet.of(">", ">>", "<", ">&", "<&");
    private static final Pattern SAVED_STATUS = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=\\$\\?");

    private MavenCommands() {}

    /**
     * @param script the unwrapped script, bounded for display
     * @param lone the script's exit status is Maven's: it is one Maven invocation, redirects aside, or it saves that invocation's
     * status and exits with it
     * @param testClass the class of the script's only {@code -Dtest} selector, when that selector names one class
     * @param testMethod the method of that selector, when it names one
     */
    record Maven(String script, boolean lone, Optional<String> testClass, Optional<String> testMethod)
    {
        Maven
        {
            requireNonNull(script, "script is null");
            requireNonNull(testClass, "testClass is null");
            requireNonNull(testMethod, "testMethod is null");
        }
    }

    static Optional<Maven> detect(List<String> argv)
    {
        String script = String.join(" ", argv);
        List<Word> words = split(script);
        Optional<String> wrapped = shellScript(words);
        if (wrapped.isPresent()) {
            script = wrapped.orElseThrow();
            words = split(script);
        }

        // Each segment with the separator that ends it, "" for the last.
        List<List<Word>> segments = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        List<Word> segment = new ArrayList<>();
        for (int index = 0; index <= words.size(); index++) {
            Word word = index < words.size() ? words.get(index) : null;
            if (word == null || word.operator() && SEPARATORS.contains(word.text())) {
                if (!segment.isEmpty()) {
                    segments.add(ImmutableList.copyOf(segment));
                    separators.add(word == null ? "" : word.text());
                }
                segment.clear();
                continue;
            }
            segment.add(word);
        }
        List<Integer> mavenSegments = new ArrayList<>();
        List<String> selectors = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            if (runsMaven(segments.get(index))) {
                mavenSegments.add(index);
                segments.get(index).stream()
                        .filter(part -> !part.operator() && part.text().startsWith("-Dtest="))
                        .map(part -> part.text().substring("-Dtest=".length()))
                        .forEach(selectors::add);
            }
        }
        if (mavenSegments.isEmpty()) {
            return Optional.empty();
        }
        boolean lone = mavenSegments.size() == 1 && exitsWithMaven(segments, separators, mavenSegments.getFirst());

        Optional<String> testClass = Optional.empty();
        Optional<String> testMethod = Optional.empty();
        if (selectors.size() == 1) {
            Matcher matcher = SELECTOR.matcher(selectors.getFirst());
            if (matcher.matches()) {
                testClass = Optional.of(matcher.group(1));
                testMethod = Optional.ofNullable(matcher.group(2));
            }
        }
        String display = script.length() <= MAXIMUM_DISPLAY_CHARACTERS ? script.strip() : script.substring(0, MAXIMUM_DISPLAY_CHARACTERS) + "…";
        return Optional.of(new Maven(display, lone, testClass, testMethod));
    }

    /** Whether the script ends with the status of its Maven segment: that segment is the whole script, or the script saves its status and exits with it. */
    private static boolean exitsWithMaven(List<List<Word>> segments, List<String> separators, int maven)
    {
        if (segments.get(maven).stream().anyMatch(word -> word.operator() && !REDIRECTS.contains(word.text()))) {
            return false;
        }
        if (segments.size() == 1) {
            return true;
        }
        // An agent that trims long output keeps Maven's status: `mvn … > log 2>&1; status=$?; tail log; exit "$status"`.
        int last = segments.size() - 1;
        if (maven + 2 > last || maven > 0 && !separators.get(maven - 1).equals(";") || !separators.get(maven).equals(";") || !separators.get(last - 1).equals(";")) {
            return false;
        }
        List<Word> save = segments.get(maven + 1);
        Matcher saved = SAVED_STATUS.matcher(save.getFirst().text());
        List<Word> exit = segments.get(last);
        return save.size() == 1
                && saved.matches()
                && exit.size() == 2
                && exit.getFirst().text().equals("exit")
                && (exit.getLast().text().equals("$" + saved.group(1)) || exit.getLast().text().equals("${" + saved.group(1) + "}"));
    }

    /**
     * The words of a command that runs one program, its {@code <shell> -c} wrapper removed, so the exit status reported for it is
     * that program's. Empty for a script with separators, pipes or redirects.
     */
    public static Optional<List<String>> loneCommand(List<String> argv)
    {
        List<Word> words = split(String.join(" ", argv));
        Optional<String> wrapped = shellScript(words);
        if (wrapped.isPresent()) {
            words = split(wrapped.orElseThrow());
        }
        return words.isEmpty() || words.stream().anyMatch(Word::operator) ? Optional.empty() : Optional.of(words.stream().map(Word::text).toList());
    }

    /** A command as the agent typed it: the {@code <shell> -c} wrapper Codex adds removed and runs of whitespace collapsed, so two runs compare. */
    public static String typedCommand(List<String> argv)
    {
        String text = String.join(" ", argv);
        return shellScript(split(text)).orElse(text).strip().replaceAll("\\s+", " ");
    }

    /** The script of {@code <shell> [flags] -c <script>}, as Codex wraps commands in the user's login shell. */
    private static Optional<String> shellScript(List<Word> words)
    {
        if (words.size() < 3 || words.getFirst().operator() || !SHELLS.contains(basename(words.getFirst().text()))) {
            return Optional.empty();
        }
        for (int index = 1; index < words.size() - 1; index++) {
            Word flag = words.get(index);
            if (flag.operator() || !flag.text().startsWith("-")) {
                return Optional.empty();
            }
            if (flag.text().indexOf('c') > 0) {
                return words.get(index + 1).operator() ? Optional.empty() : Optional.of(words.get(index + 1).text());
            }
        }
        return Optional.empty();
    }

    private static boolean runsMaven(List<Word> segment)
    {
        for (Word word : segment) {
            if (word.operator()) {
                return false;
            }
            if (ASSIGNMENT.matcher(word.text()).matches() || WRAPPERS.contains(word.text()) || word.text().startsWith("-")) {
                continue;
            }
            return MAVEN.contains(basename(word.text()));
        }
        return false;
    }

    private static String basename(String word)
    {
        return word.substring(word.lastIndexOf('/') + 1);
    }

    /** POSIX-style words: quotes and backslashes are resolved, and unquoted control characters become operator words. */
    static List<Word> split(String text)
    {
        ImmutableList.Builder<Word> words = ImmutableList.builder();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '\'') {
                int end = text.indexOf('\'', index + 1);
                end = end < 0 ? text.length() : end;
                current.append(text, index + 1, end);
                inWord = true;
                index = end + 1;
            }
            else if (character == '"') {
                index++;
                while (index < text.length() && text.charAt(index) != '"') {
                    if (text.charAt(index) == '\\' && index + 1 < text.length() && "\"\\$`".indexOf(text.charAt(index + 1)) >= 0) {
                        index++;
                    }
                    current.append(text.charAt(index));
                    index++;
                }
                inWord = true;
                index++;
            }
            else if (character == '\\') {
                if (index + 1 < text.length() && text.charAt(index + 1) != '\n') {
                    current.append(text.charAt(index + 1));
                    inWord = true;
                }
                index += 2;
            }
            else if (character == '#' && !inWord) {
                int end = text.indexOf('\n', index);
                index = end < 0 ? text.length() : end;
            }
            else if (character == '\n' || ";&|<>()".indexOf(character) >= 0) {
                if (inWord) {
                    words.add(new Word(current.toString(), false));
                    current.setLength(0);
                    inWord = false;
                }
                String operator = character == '\n' ? ";" : String.valueOf(character);
                if ((character == '&' || character == '|' || character == '>') && index + 1 < text.length() && text.charAt(index + 1) == character) {
                    operator += character;
                    index++;
                }
                // `2>&1` duplicates a descriptor; its `&` does not end the command.
                else if ((character == '>' || character == '<') && index + 1 < text.length() && text.charAt(index + 1) == '&') {
                    operator += '&';
                    index++;
                }
                words.add(new Word(operator, true));
                index++;
            }
            else if (Character.isWhitespace(character)) {
                if (inWord) {
                    words.add(new Word(current.toString(), false));
                    current.setLength(0);
                    inWord = false;
                }
                index++;
            }
            else {
                current.append(character);
                inWord = true;
                index++;
            }
        }
        if (inWord) {
            words.add(new Word(current.toString(), false));
        }
        return words.build();
    }

    record Word(String text, boolean operator)
    {
        Word
        {
            requireNonNull(text, "text is null");
        }
    }
}
