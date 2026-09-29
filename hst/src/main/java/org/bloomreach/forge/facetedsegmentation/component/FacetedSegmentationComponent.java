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
package org.bloomreach.forge.facetedsegmentation.component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hippoecm.hst.component.support.bean.BaseHstComponent;
import org.hippoecm.hst.content.beans.ObjectBeanManagerException;
import org.hippoecm.hst.content.beans.standard.HippoBean;
import org.hippoecm.hst.core.component.HstComponentException;
import org.hippoecm.hst.core.component.HstRequest;
import org.hippoecm.hst.core.component.HstResponse;
import org.hippoecm.hst.core.parameters.ParametersInfo;
import org.hippoecm.hst.core.request.HstRequestContext;
import org.bloomreach.forge.facetedsegmentation.catalog.CatalogReaderHolder;
import org.bloomreach.forge.facetedsegmentation.catalog.ValueListCatalogReader;
import org.bloomreach.forge.facetedsegmentation.expression.AbsentFacetPolicy;
import org.bloomreach.forge.facetedsegmentation.expression.Audience;
import org.bloomreach.forge.facetedsegmentation.expression.EvaluateOptions;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionEvaluator;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionParseException;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the sections of a document whose segmentation rules match the current request.
 *
 * Request Contributions:
 * document — the configured document bean.
 * matchedSections — every section that matched, in stored order. Empty if none did.
 * matchedSection — the first matched section, or null. Kept for templates that expect one section.
 * renderPublicContent — whether the template should also render the document's public content.
 * audience — the resolved facet values, for debugging a template.
 *
 * Section Ordering:
 * Sections are always evaluated in stored order, so a CMS user reorders them to change the outcome.
 * How many may win is the {@code sectionMatchMode} parameter:
 *
 * first-match — stop at the earliest match. Order is priority, and at most one section renders.
 * all-matches — evaluate every section and keep all that pass. Order is presentation order only.
 *
 * Whether public content still renders is the separate {@code publicContentMode} parameter:
 *
 * fallback — public content is the ungated alternative, rendered only when nothing matched.
 * always   — public content is a preamble, rendered regardless of what matched.
 *
 * The two are deliberately independent: "how many sections win" and "is public content an
 * alternative or a preamble" are different questions, and projects answer them differently.
 *
 * Facet Values:
 * This implementation reads them from query parameters, keyed by facet id with value keys.
 * e.g. ?day-of-the-week=monday&signedin=in&size=M
 *
 * In the Experience Manager's preview — where the page is rendered inside a CMS-controlled iframe
 * and an author has no way to attach query parameters to it — the real request's query string is
 * ignored and the {@code previewModeQueryParams} component parameter is parsed instead, using that
 * same {@code key=value&key=value} shape. This lets an author preview each section by editing that
 * parameter rather than the (unreachable) URL.
 *
 * The audience is deliberately one per request, not one per component instance.
 * A real deployment usually has an identity or entitlement service instead. To integrate one,
 * subclass and override {@link #resolveAudience(HstRequest, ValueListCatalogReader.Facets)}; nothing
 * else needs to change. Query parameters are a deliberate choice for a plugin default because they
 * need no infrastructure and make the behavior directly testable from a browser.
 */
@ParametersInfo(type = FacetedSegmentationInfo.class)
public class FacetedSegmentationComponent extends BaseHstComponent {

    private static final Logger log = LoggerFactory.getLogger(FacetedSegmentationComponent.class);

    public static final String ATTRIBUTE_DOCUMENT = "document";
    public static final String ATTRIBUTE_MATCHED_SECTIONS = "matchedSections";
    public static final String ATTRIBUTE_MATCHED_SECTION = "matchedSection";
    public static final String ATTRIBUTE_RENDER_PUBLIC_CONTENT = "renderPublicContent";
    public static final String ATTRIBUTE_AUDIENCE = "audience";

    /** {@code sectionMatchMode}: render every matching section rather than only the first. */
    private static final String MATCH_MODE_ALL = "all-matches";

    /** {@code publicContentMode}: render public content regardless of what matched. */
    private static final String PUBLIC_CONTENT_ALWAYS = "always";

    /**
     * The property holding a section's expression, relative to the section bean. Override
     * {@link #getExpressionPropertyName()} if a project stores it under a different name.
     */
    private static final String DEFAULT_EXPRESSION_PROPERTY = "facetseg:faceted_segmentation";

    /**
     * The child-node name of the sections on the document. Override {@link #getSectionNodeName()} for
     * a project whose document composes the compound under a different name.
     */
    private static final String DEFAULT_SECTION_NODE_NAME = "facetseg:FacetedSegmentationSection";

    @SuppressWarnings("SpellCheckingInspection")
    @Override
    public void doBeforeRender(final HstRequest request, final HstResponse response)
            throws HstComponentException {
        super.doBeforeRender(request, response);

        final FacetedSegmentationInfo info = getComponentParametersInfo(request);
        if (info == null || info.getDocument() == null) {
            log.debug("No document configured on this component instance — nothing to render.");
            return;
        }

        final HstRequestContext context = request.getRequestContext();
        // The picker stores an absolute JCR path (e.g. "/content/documents/..."), so it must be
        // resolved via the object bean manager rather than HippoBean#getBean, which treats its
        // argument as relative to the bean it is called on.
        final HippoBean document;
        try {
            document = (HippoBean) context.getObjectBeanManager().getObject(info.getDocument());
        } catch (final ObjectBeanManagerException e) {
            log.warn("Could not resolve document at '{}' — check the component's configuration.",
                    info.getDocument(), e);
            return;
        }
        if (document == null) {
            log.warn("No document found at '{}' — check the component's configuration.", info.getDocument());
            return;
        }
        request.setAttribute(ATTRIBUTE_DOCUMENT, document);

        final ValueListCatalogReader.Facets facets;
        try {
            facets = getCatalogReader().read(context, false);
        } catch (final RuntimeException e) {
            // Without the catalog we cannot map request values onto expression tokens, so no rule can
            // be satisfied. Fail closed: no section can win, so the caller falls back to public
            // content — set the attribute to null explicitly rather than leaving it unset, so a
            // template's null-check behaves identically whether nothing matched or the catalog failed.
            log.error("Could not read the facet catalog; falling back to public content.", e);
            request.setAttribute(ATTRIBUTE_MATCHED_SECTIONS, List.of());
            request.setAttribute(ATTRIBUTE_MATCHED_SECTION, null);
            // Nothing can match, so public content must render whichever mode is configured —
            // otherwise a catalog outage would render an empty component.
            request.setAttribute(ATTRIBUTE_RENDER_PUBLIC_CONTENT, true);
            return;
        }

        final Audience audience = resolveAudience(request, facets);
        final EvaluateOptions options = EvaluateOptions.builder()
                .absentFacetPolicy(resolvePolicy(info))
                .build();

        // A document's sections are hipposysedit:ordered, so a CMS user reordering them changes the
        // outcome under either mode — which one wins under first-match, what order they appear in
        // under all-matches.
        final List<HippoBean> sections = document.getChildBeansByName(getSectionNodeName());
        final boolean stopAtFirst = !MATCH_MODE_ALL.equalsIgnoreCase(trimmed(info.getSectionMatchMode()));

        final List<HippoBean> matched = new ArrayList<>();
        for (final HippoBean section : sections) {
            if (matches(section, audience, options)) {
                matched.add(section);
                if (stopAtFirst) {
                    break;
                }
            }
        }

        final boolean publicAlways =
                PUBLIC_CONTENT_ALWAYS.equalsIgnoreCase(trimmed(info.getPublicContentMode()));

        request.setAttribute(ATTRIBUTE_MATCHED_SECTIONS, List.copyOf(matched));
        // The single-section attribute stays populated so a template written against it keeps
        // working; under all-matches it is the first of several, not the only one.
        request.setAttribute(ATTRIBUTE_MATCHED_SECTION, matched.isEmpty() ? null : matched.get(0));
        request.setAttribute(ATTRIBUTE_RENDER_PUBLIC_CONTENT, publicAlways || matched.isEmpty());
        request.setAttribute(ATTRIBUTE_AUDIENCE, audience);

        if (log.isDebugEnabled()) {
            log.debug("{} of {} section(s) matched for document '{}' (mode={}, publicContent={}).",
                    matched.size(), sections.size(), info.getDocument(),
                    stopAtFirst ? "first-match" : "all-matches",
                    publicAlways || matched.isEmpty() ? "rendered" : "suppressed");
        }
    }

    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Turns the request into the set of facet values it carries.
     * <p>Override this to source values from somewhere other than query parameters.
     */
    protected Audience resolveAudience(final HstRequest request,
                                       final ValueListCatalogReader.Facets facets) {
        final Audience.Builder builder = Audience.builder();

        final Map<String, String[]> parameters = resolveRequestParameters(request);
        for (final Map.Entry<String, String[]> entry : parameters.entrySet()) {
            final String facetId = entry.getKey();
            final String displayName = facets.displayNameForId(facetId);
            if (displayName == null) {
                // Not a facet at all — an unrelated query parameter, or a typo. Ignore it rather than
                // failing: a caller must be free to put anything in a query string.
                log.debug("Ignoring request parameter '{}': no value list with that id.", facetId);
                continue;
            }

            final List<String> labels = new ArrayList<>();
            for (final String raw : entry.getValue()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                // Accept a comma-separated list too, so ?size=S,M works as well as ?size=S&size=M.
                for (final String candidate : raw.split(",")) {
                    final String trimmed = candidate.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    final String label = facets.labelForKey(facetId, trimmed);
                    if (label == null) {
                        log.debug("Ignoring value '{}' for facet '{}': not in that value list.",
                                trimmed, facetId);
                        continue;
                    }
                    if (!labels.contains(label)) {
                        labels.add(label);
                    }
                }
            }

            if (!labels.isEmpty()) {
                builder.putAll(displayName, labels);
            }
        }

        return builder.build();
    }

    /**
     * The facet parameters to evaluate sections against.
     * <p>In the Experience Manager's preview, the page is rendered inside an iframe whose navigation
     * the CMS controls — there is no way for a content author to attach query parameters to it — so
     * a live request there never carries real facet values. In that case this returns the
     * component's {@code previewModeQueryParams} parameter instead, letting an author preview each
     * section by editing that parameter. Everywhere else it returns the actual request's query
     * parameters, as {@link #resolveAudience} always did.
     */
    private Map<String, String[]> resolveRequestParameters(final HstRequest request) {
        final HstRequestContext context = request.getRequestContext();
        if (!context.isPreview()) {
            // Use HttpServletRequest instead of HstRequest as query parameters are one per request, not one per component.
            return context.getServletRequest().getParameterMap();
        }

        final FacetedSegmentationInfo info = getComponentParametersInfo(request);
        final String rawQueryParams = info == null ? null : info.getPreviewModeQueryParams();
        return parseQueryParams(rawQueryParams);
    }

    /**
     * Parses a {@code key=value&key=value} string — the same shape as a URL query string — into a
     * parameter map, without needing a real request to attach it to.
     */
    private Map<String, String[]> parseQueryParams(final String rawQueryParams) {
        final Map<String, List<String>> collected = new LinkedHashMap<>();
        if (rawQueryParams == null || rawQueryParams.isBlank()) {
            return Collections.emptyMap();
        }

        for (final String pair : rawQueryParams.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            final String rawKey = eq == -1 ? pair : pair.substring(0, eq);
            final String rawValue = eq == -1 ? "" : pair.substring(eq + 1);
            final String key = decode(rawKey);
            if (key.isBlank()) {
                continue;
            }
            collected.computeIfAbsent(key, k -> new ArrayList<>()).add(decode(rawValue));
        }

        final Map<String, String[]> result = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : collected.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        return result;
    }

    private static String decode(final String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Evaluates one section's rule.
     * A section with no expression is never a match — authoring requires a facet definition (see
     * the {@code non-empty} validator on {@code facetseg:faceted_segmentation}), so a blank
     * expression here means a pre-existing document that predates that requirement, or a section
     * mid-edit. Either way it must not win over public content or another section by defaulting to
     * "always matches" the way an empty (truly ungated) expression does elsewhere in this library.
     * A section whose expression is malformed is likewise hidden.
     */
    protected boolean matches(final HippoBean section, final Audience audience,
                              final EvaluateOptions options) {
        final String expression = section.getSingleProperty(getExpressionPropertyName());
        if (expression == null || expression.isBlank()) {
            return false;
        }

        final ExpressionNode parsed;
        try {
            parsed = ExpressionParser.parse(expression);
        } catch (final ExpressionParseException e) {
            log.error("Section '{}' has an unparseable segmentation rule, so it will NOT be rendered. "
                            + "{}", section.getPath(), e.describe());
            return false;
        }

        return ExpressionEvaluator.evaluate(parsed, audience, options);
    }

    private AbsentFacetPolicy resolvePolicy(final FacetedSegmentationInfo info) {
        final String configured = info.getAbsentFacetPolicy();
        if (configured == null || configured.isBlank()) {
            return AbsentFacetPolicy.STRICT;
        }
        try {
            return AbsentFacetPolicy.valueOf(configured.trim().toUpperCase());
        } catch (final IllegalArgumentException e) {
            // An editor cannot fix a mistyped component parameter from the delivery tier, so fall back
            // to the safe option rather than failing the page.
            log.warn("Unrecognised absentFacetPolicy '{}'; falling back to STRICT.", configured);
            return AbsentFacetPolicy.STRICT;
        }
    }

    /**
     * The shared catalog reader. Looked up from the HST component manager so the endpoint and the
     * delivery tier share one cache; override in a subclass to supply one directly.
     */
    protected ValueListCatalogReader getCatalogReader() {
        final ValueListCatalogReader configured = CatalogReaderHolder.get();
        if (configured != null) {
            return configured;
        }
        throw new IllegalStateException(
                "No ValueListCatalogReader is configured. The plugin's HST addon module should define "
                        + "one — check that bloomreach-facetedsegmentation-hst is on the site's "
                        + "classpath so META-INF/hst-assembly/addon/module.xml is picked up.");
    }

    protected String getExpressionPropertyName() {
        return DEFAULT_EXPRESSION_PROPERTY;
    }

    protected String getSectionNodeName() {
        return DEFAULT_SECTION_NODE_NAME;
    }
}
