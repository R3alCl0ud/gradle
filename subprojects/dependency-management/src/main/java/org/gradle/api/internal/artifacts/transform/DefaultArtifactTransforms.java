/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import org.gradle.api.Buildable;
import org.gradle.api.Nullable;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.DefaultResolvedArtifact;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.component.local.model.ComponentFileArtifactIdentifier;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.IvyArtifactName;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class DefaultArtifactTransforms implements ArtifactTransforms {
    private final ArtifactAttributeMatchingCache matchingCache;
    private final ImmutableAttributesFactory attributesFactory;

    public DefaultArtifactTransforms(ImmutableAttributesFactory attributesFactory, ArtifactAttributeMatchingCache matchingCache) {
        this.attributesFactory = attributesFactory;
        this.matchingCache = matchingCache;
    }

    public Transformer<ResolvedArtifactSet, Collection<? extends ResolvedVariant>> variantSelector(AttributeContainerInternal target) {
        return new AttributeMatchingVariantSelector(target.asImmutable());
    }

    private class AttributeMatchingVariantSelector implements Transformer<ResolvedArtifactSet, Collection<? extends ResolvedVariant>> {
        private final AttributeContainerInternal target;

        private AttributeMatchingVariantSelector(AttributeContainerInternal target) {
            this.target = target;
        }

        @Override
        public String toString() {
            return "Variant selector for " + target;
        }

        @Override
        public ResolvedArtifactSet transform(Collection<? extends ResolvedVariant> variants) {
            // Note: This algorithm is a placeholder only. Should deal with ambiguous matches
            if (target.isEmpty()) {
                return variants.iterator().next().getArtifacts();
            }

            // Note: This algorithm is a placeholder only. Should deal with ambiguous matches
            ResolvedVariant canTransform = null;
            Transformer<List<File>, File> transform = null;
            for (ResolvedVariant variant : variants) {
                AttributeContainerInternal variantAttributes = ((AttributeContainerInternal) variant.getAttributes()).asImmutable();
                if (matchingCache.areMatchingAttributes(variantAttributes, target)) {
                    return variant.getArtifacts();
                }
                Transformer<List<File>, File> candidateTransform = matchingCache.getTransform(variantAttributes, target);
                if (candidateTransform != null) {
                    canTransform = variant;
                    transform = candidateTransform;
                }
            }
            return canTransform == null ? ResolvedArtifactSet.EMPTY : new TransformingArtifactSet(canTransform.getArtifacts(), target, transform);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AttributeMatchingVariantSelector)) {
                return false;
            }
            AttributeMatchingVariantSelector that = (AttributeMatchingVariantSelector) o;
            return Objects.equal(target, that.target);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(target);
        }
    }

    private class TransformingArtifactSet implements ResolvedArtifactSet {
        private final ResolvedArtifactSet delegate;
        private final AttributeContainerInternal target;
        private final Transformer<List<File>, File> transform;

        TransformingArtifactSet(ResolvedArtifactSet delegate, AttributeContainerInternal target, Transformer<List<File>, File> transform) {
            this.delegate = delegate;
            this.target = target;
            this.transform = transform;
        }

        @Override
        public Set<ResolvedArtifact> getArtifacts() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void collectBuildDependencies(Collection<? super TaskDependency> dest) {
            delegate.collectBuildDependencies(dest);
        }

        @Override
        public void visit(ArtifactVisitor visitor) {
            delegate.visit(new ArtifactTransformingVisitor(visitor, target, transform, attributesFactory));
        }
    }

    private class ArtifactTransformingVisitor implements ArtifactVisitor {
        private final ArtifactVisitor visitor;
        private final AttributeContainerInternal target;
        private final Transformer<List<File>, File> transform;
        private final ImmutableAttributesFactory attributesFactory;

        private ArtifactTransformingVisitor(ArtifactVisitor visitor, AttributeContainerInternal target, Transformer<List<File>, File> transform, ImmutableAttributesFactory attributesFactory) {
            this.visitor = visitor;
            this.target = target;
            this.transform = transform;
            this.attributesFactory = attributesFactory;
        }

        @Override
        public void visitArtifact(AttributeContainer variant, ResolvedArtifact artifact) {
            List<ResolvedArtifact> transformResults = matchingCache.getTransformedArtifacts(artifact, target);
            if (transformResults != null) {
                for (ResolvedArtifact resolvedArtifact : transformResults) {
                    visitor.visitArtifact(target, resolvedArtifact);
                }
                return;
            }

            List<File> transformedFiles;
            try {
                transformedFiles = transform.transform(artifact.getFile());
            } catch (Throwable t) {
                visitor.visitFailure(t);
                return;
            }

            TaskDependency buildDependencies = ((Buildable) artifact).getBuildDependencies();
            transformResults = Lists.newArrayListWithCapacity(transformedFiles.size());
            for (File output : transformedFiles) {
                ComponentArtifactIdentifier newId = new ComponentFileArtifactIdentifier(artifact.getId().getComponentIdentifier(), output.getName());
                IvyArtifactName artifactName = DefaultIvyArtifactName.forAttributeContainer(output.getName(), this.target);
                ResolvedArtifact resolvedArtifact = new DefaultResolvedArtifact(artifact.getModuleVersion().getId(), artifactName, newId, buildDependencies, output, this.target, attributesFactory);
                transformResults.add(resolvedArtifact);
                visitor.visitArtifact(target, resolvedArtifact);
            }

            matchingCache.putTransformedArtifact(artifact, this.target, transformResults);
        }

        @Override
        public void visitFailure(Throwable failure) {
            visitor.visitFailure(failure);
        }

        @Override
        public boolean includeFiles() {
            return visitor.includeFiles();
        }

        @Override
        public void visitFiles(@Nullable ComponentIdentifier componentIdentifier, AttributeContainer variant, Iterable<File> files) {
            List<File> result = new ArrayList<File>();
            try {
                for (File file : files) {
                    try {
                        List<File> transformResults = matchingCache.getTransformedFile(file, target);
                        if (transformResults != null) {
                            result.addAll(transformResults);
                            continue;
                        }

                        transformResults = transform.transform(file);
                        matchingCache.putTransformedFile(file, target, transformResults);
                        result.addAll(transformResults);
                    } catch (Throwable t) {
                        visitor.visitFailure(t);
                    }
                }
            } catch (Throwable t) {
                visitor.visitFailure(t);
                return;
            }
            if (!result.isEmpty()) {
                visitor.visitFiles(componentIdentifier, target, result);
            }
        }
    }

}
