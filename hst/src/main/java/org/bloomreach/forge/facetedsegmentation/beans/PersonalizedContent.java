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
package org.bloomreach.forge.facetedsegmentation.beans;

import java.util.List;

import org.hippoecm.hst.content.beans.Node;
import org.hippoecm.hst.content.beans.standard.HippoDocument;
import org.hippoecm.hst.content.beans.standard.HippoHtml;

/**
 * HST content bean for the plugin's {@code facetseg:PersonalizedContent} document type.
 * <p>
 * {@code FacetedSegmentationComponent} itself is bean-type agnostic — it reads
 * {@code facetseg:FacetedSegmentationSection} children via the generic {@code HippoBean} API, so
 * this class is not required for the component to function. It exists so the plugin's own
 * bundled template (and any project that uses the generic document type as-is, without
 * subclassing) has a typed bean to render against instead of falling back to generic property
 * lookups.
 * <p>
 * Standard archetype bean scanning (the default {@code classpath*:org/onehippo/**&#47;*.class}
 * entry in {@code hst-beans-annotated-classes}) picks this class up with no project-side
 * configuration.
 */
@Node(jcrType = "facetseg:PersonalizedContent")
public class PersonalizedContent extends HippoDocument {

    public HippoHtml getPublic() {
        return getHippoHtml("facetseg:public");
    }

    public List<FacetedSegmentationSection> getFacetedSegmentationSection() {
        return getChildBeansByName("facetseg:FacetedSegmentationSection", FacetedSegmentationSection.class);
    }
}
