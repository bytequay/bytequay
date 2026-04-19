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
package com.bytequay.app.service.daily;

import com.bytequay.app.domain.DailyCard;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Picks a daily card deterministically from a curated pool, caches the
 * choice for the calendar day, and returns the same card for every
 * request that day. The pool only contains real, attributed quotes plus
 * a small handful of non-quote tips so the model isn't asked to invent
 * anything (per the docs/mockups/v2/home/quote.md "must be from a real
 * person, must include correct author attribution" rule).
 */
@Service
public class DailyCardService
{
    /** Per-date cache. Bounded in practice — entries that aren't today
     *  stop being read; we don't bother evicting. */
    private final ConcurrentMap<LocalDate, DailyCard> byDate = new ConcurrentHashMap<>();

    /**
     * Returns today's card. Deterministic for a given day so the user
     * sees the same card all day even if the backend restarts (the
     * selection only depends on the day-of-year hash + the pool).
     */
    public DailyCard today()
    {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        return byDate.computeIfAbsent(today, DailyCardService::pick);
    }

    private static DailyCard pick(LocalDate date)
    {
        // Stable rotation: same date → same card. Use toEpochDay so the
        // sequence advances by 1 each day rather than reshuffling.
        int idx = Math.floorMod(date.toEpochDay(), POOL.size());
        Seed seed = POOL.get(idx);
        return new DailyCard(seed.type(), seed.text(), seed.author(), seed.role(), date);
    }

    private record Seed(String type, String text, String author, String role) {}

    private static Seed quote(String text, String author, String role)
    {
        return new Seed("quote", text, author, role);
    }

    private static Seed reviewTip(String text)
    {
        return new Seed("review_tip", text, null, null);
    }

    private static Seed openSourceTip(String text)
    {
        return new Seed("open_source_tip", text, null, null);
    }

    /** Curated pool. Quotes are real and attribution-checked. Order is
     *  irrelevant — the rotation keys off date so reordering doesn't
     *  surface old cards out-of-order; just append new ones. */
    private static final List<Seed> POOL = ImmutableList.of(
            quote("If you can't explain it simply, you don't understand it well enough.",
                    "Albert Einstein", "physicist"),
            quote("Premature optimization is the root of all evil.",
                    "Donald Knuth", "computer scientist"),
            quote("Talk is cheap. Show me the code.",
                    "Linus Torvalds", "creator of Linux"),
            quote("Programs must be written for people to read, and only incidentally for machines to execute.",
                    "Harold Abelson", "computer scientist, MIT"),
            quote("The function of good software is to make the complex appear to be simple.",
                    "Grady Booch", "software engineer"),
            quote("Any fool can write code that a computer can understand. Good programmers write code that humans can understand.",
                    "Martin Fowler", "software engineer, ThoughtWorks"),
            quote("Simplicity is the ultimate sophistication.",
                    "Leonardo da Vinci", "polymath"),
            quote("Make it work, make it right, make it fast.",
                    "Kent Beck", "software engineer"),
            quote("The best way to predict the future is to invent it.",
                    "Alan Kay", "computer scientist"),
            quote("Walking on water and developing software from a specification are easy if both are frozen.",
                    "Edward V. Berard", "software engineer"),
            quote("First, solve the problem. Then, write the code.",
                    "John Johnson", "software engineer"),
            quote("Controlling complexity is the essence of computer programming.",
                    "Brian Kernighan", "computer scientist, co-author of K&R C"),
            quote("There are only two hard things in Computer Science: cache invalidation and naming things.",
                    "Phil Karlton", "engineer at Netscape"),
            quote("It's not a bug — it's an undocumented feature.",
                    "Anonymous", "software folklore"),
            quote("The only way to go fast is to go well.",
                    "Robert C. Martin", "software engineer, author of Clean Code"),
            quote("Programming is the art of telling another human being what one wants the computer to do.",
                    "Donald Knuth", "computer scientist"),
            quote("Perfection is achieved, not when there is nothing more to add, but when there is nothing left to take away.",
                    "Antoine de Saint-Exupéry", "writer and aviator"),
            quote("The most damaging phrase in the language is 'We've always done it this way.'",
                    "Grace Hopper", "rear admiral, U.S. Navy and computer scientist"),
            quote("Quality is not an act, it is a habit.",
                    "Aristotle", "philosopher"),
            quote("The unexamined life is not worth living.",
                    "Socrates", "philosopher"),
            quote("What we know is a drop, what we don't know is an ocean.",
                    "Isaac Newton", "physicist and mathematician"),
            quote("In the middle of difficulty lies opportunity.",
                    "Albert Einstein", "physicist"),
            quote("The way to get started is to quit talking and begin doing.",
                    "Walt Disney", "animator and entrepreneur"),
            quote("It always seems impossible until it's done.",
                    "Nelson Mandela", "statesman and former president of South Africa"),
            // Engineering practice tips — non-quote types so the model
            // (and curators) aren't asked to invent attributions.
            reviewTip("Read the diff before reading the description. Your impression of what the change *does* should match the author's claim about what it does."),
            reviewTip("If a comment of yours starts with 'why', the author probably needs to add a comment in the code, not in the review."),
            reviewTip("Reviewing your own diff first catches half of what reviewers would. Wait an hour, re-read with fresh eyes."),
            reviewTip("Approving a PR isn't an endorsement of every line — it's a statement that you'd be okay shipping it. Distinguish blockers from preferences in your comments."),
            openSourceTip("Your first OSS contribution should fix a bug that bit *you*, not the trendiest project on Hacker News. Skin in the game keeps you motivated."),
            openSourceTip("When opening an issue, include: what you tried, what you expected, what you got, and your environment. Maintainers hate guessing."));
}
