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

import com.bytequay.app.service.harness.HarnessModels.Bucket;
import com.bytequay.app.service.harness.HarnessModels.Rule;
import com.bytequay.app.service.harness.HarnessModels.RuleStatus;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.util.Objects.requireNonNull;

/** Auditable priority matcher over active per-repository rules. */
@Component
public class HarnessClassifier
{
    private final HarnessStore store;

    public HarnessClassifier(HarnessStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    public Classification classify(
            String workspaceId, String owner, String repo,
            String module, String signature, String excerpt, long nowMs)
    {
        for (Rule rule : store.activeRules(workspaceId, owner, repo)) {
            if (rule.status() != RuleStatus.ACTIVE) {
                continue;
            }
            if (rule.scope() != null && !rule.scope().isBlank()
                    && !rule.scope().equals(module)) {
                continue;
            }
            try {
                Pattern matcher = Pattern.compile(rule.matcherPattern(),
                        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
                if (matcher.matcher(signature).find()) {
                    return new Classification(rule.bucket(), store.touchRule(rule.id(), nowMs));
                }
            }
            catch (PatternSyntaxException ignored) {
                // A malformed persisted rule fails closed to UNKNOWN. It cannot
                // redirect a failure onto a recipe path.
            }
        }
        return new Classification(Bucket.UNKNOWN, null);
    }

    public record Classification(Bucket bucket, Rule rule) {}
}
