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
 * Java port of extension/src/expression/evaluate.ts's AbsentFacetPolicy.
 */
package org.bloomreach.forge.facetedsegmentation.expression;

/**
 * How a condition behaves when the request carries no value at all for that facet.
 *
 * <ul>
 *   <li>{@link #STRICT} — the facet must be present to be evaluated; both IN and NOT IN fail.</li>
 *   <li>{@link #PERMISSIVE} — IN still fails, but NOT IN passes: there is nothing to exclude, so
 *       the exclusion is satisfied.</li>
 * </ul>
 *
 * The default is {@link #STRICT} — fail closed — because this gates access to secured content and a
 * missing or misconfigured setting must never silently widen an audience.
 *
 * <p>{@link #PERMISSIVE} is correct when an absent value is <em>informative</em>: an editor writing
 * {@code Size NOT IN [Small]} means "every size except Small", and a visitor who genuinely has no
 * size belongs in "every other size".
 *
 * <p><strong>It is wrong when the absence is an artefact.</strong> If a profile/entitlement service
 * returned an error, or the visitor is mid-authentication, or anonymous, then "no value" says
 * nothing about the visitor — and treating that as a permission is what fails <em>open</em>. A
 * deployment using {@link #PERMISSIVE} should therefore force {@link #STRICT} for those requests.
 *
 * <p>That is why the policy is a per-{@link ExpressionEvaluator#evaluate} argument rather than a
 * static setting: the caller derives it from the state of each individual request.
 */
public enum AbsentFacetPolicy {
    STRICT,
    PERMISSIVE
}
