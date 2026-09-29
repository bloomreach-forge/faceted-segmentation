/*
 * Copyright 2026 Bloomreach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Java port of extension/src/expression/evaluate.ts's EvaluateOptions.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

/**
 * Per-call configuration for {@link ExpressionEvaluator#evaluate}.
 *
 * @param absentFacetPolicy how a condition behaves when the request carries no value at all for
 *                          its facet — see {@link AbsentFacetPolicy}. Chosen by the CALLER on
 *                          every evaluation, not a static setting: a degraded session (an
 *                          entitlement API failure, mid-MFA, anonymous) must be evaluated under
 *                          {@link AbsentFacetPolicy#STRICT} regardless of the ordinary default.
 */
public record EvaluateOptions(AbsentFacetPolicy absentFacetPolicy) {

    public static final EvaluateOptions DEFAULT = new EvaluateOptions(AbsentFacetPolicy.STRICT);

    public EvaluateOptions {
        absentFacetPolicy = absentFacetPolicy == null ? AbsentFacetPolicy.STRICT : absentFacetPolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private AbsentFacetPolicy absentFacetPolicy = AbsentFacetPolicy.STRICT;

        public Builder absentFacetPolicy(final AbsentFacetPolicy policy) {
            this.absentFacetPolicy = policy;
            return this;
        }

        public EvaluateOptions build() {
            return new EvaluateOptions(absentFacetPolicy);
        }
    }
}
