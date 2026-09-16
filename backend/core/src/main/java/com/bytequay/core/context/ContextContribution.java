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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/** A sealed investigation brief or later collection supplement, including its explicit source gaps. */
public record ContextContribution(int investigationStep, String text, List<String> evidenceRefs, List<String> preparationRefs, List<String> limitations)
{
    public static final int MAXIMUM_BYTES = 8 * 1024;
    public static final int MAXIMUM_REFS = 80;
    public static final int MAXIMUM_PREPARATION_REF_BYTES = 400;
    public static final int MAXIMUM_LIMITATIONS = 10;
    public static final int MAXIMUM_LIMITATION_BYTES = 1000;

    public ContextContribution
    {
        checkArgument(investigationStep > 0, "Context contribution requires a positive investigation step");
        ContextLimits.text(text, "context brief", MAXIMUM_BYTES);
        evidenceRefs = ImmutableList.copyOf(evidenceRefs);
        preparationRefs = ImmutableList.copyOf(preparationRefs);
        limitations = ImmutableList.copyOf(limitations);
        ContextLimits.unique(evidenceRefs, "context evidence references", MAXIMUM_REFS);
        ContextLimits.unique(preparationRefs, "context preparation references", MAXIMUM_REFS);
        evidenceRefs.forEach(ref -> ContextLimits.text(ref, "context evidence reference", 200));
        preparationRefs.forEach(ref -> ContextLimits.text(ref, "context preparation reference", MAXIMUM_PREPARATION_REF_BYTES));
        checkArgument(limitations.size() <= MAXIMUM_LIMITATIONS, "Too many context limitations");
        limitations.forEach(value -> ContextLimits.text(value, "context limitation", MAXIMUM_LIMITATION_BYTES));
        checkArgument(ContextLimits.bytes(render(investigationStep, text, evidenceRefs, preparationRefs, limitations)) <= MAXIMUM_BYTES,
                "Context brief including references and limitations exceeds %s UTF-8 bytes", MAXIMUM_BYTES);
    }

    public String rendered()
    {
        return render(investigationStep, text, evidenceRefs, preparationRefs, limitations);
    }

    private static String render(int step, String text, List<String> evidence, List<String> preparation, List<String> limitations)
    {
        return "Investigation step " + step + "\n" + text + "\nEvidence references: " + String.join(", ", evidence)
                + "\nPreparation receipts: " + String.join(", ", preparation) + "\nLimitations: "
                + (limitations.isEmpty() ? "None reported" : "\n- " + String.join("\n- ", limitations)) + "\n";
    }
}
