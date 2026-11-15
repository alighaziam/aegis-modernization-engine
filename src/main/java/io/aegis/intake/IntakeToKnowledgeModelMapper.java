package io.aegis.intake;

import io.aegis.domain.ProjectKnowledgeModel;
import java.util.ArrayList;
import java.util.List;

/** Maps user-declared technology profiles into the canonical UPKM without inventing discoveries. */
public final class IntakeToKnowledgeModelMapper {
    public ProjectKnowledgeModel map(IntakeService.IntakeRequest request) {
        IntakeService.ProjectInput p = request.project();
        List\u003cProjectKnowledgeModel.SourceArtifact\u003e artifacts = new ArrayList\u003e();
        for (IntakeService.ArtifactDescriptor a : request.intake().artifacts()) {
            artifacts.add(new ProjectKnowledgeModel.SourceArtifact(a.artifactId(), a.category(), a.format(),
                    a.origin(), a.location(), a.sha256(), a.sizeBytes() == null ? 0 : a.sizeBytes()));
        }
        List\u003cProjectKnowledgeModel.Technology\u003e technologies = new ArrayList\u003e();
        add(technologies, request.technology() == null ? null : request.technology().source(), ProjectKnowledgeModel.Layer.SOURCE);
        add(technologies, request.technology() == null ? null : request.technology().target(), ProjectKnowledgeModel.Layer.TARGET);
        return new ProjectKnowledgeModel(new ProjectKnowledgeModel.Project(p.id(), p.name(), p.description(), p.businessDomain()),
                artifacts, technologies, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "1.0");
    }

    private void add(List\u003cProjectKnowledgeModel.Technology\u003e out, IntakeService.TechnologyProfile profile,
                     ProjectKnowledgeModel.Layer layer) {
        if (profile == null) return;
        addItems(out, profile.languages(), "language", layer);
        addItems(out, profile.frameworks(), "framework", layer);
        addItems(out, profile.databases(), "database", layer);
        addItems(out, profile.platforms(), "platform", layer);
        addItems(out, profile.buildTools(), "build-tool", layer);
    }

    private void addItems(List\u003cProjectKnowledgeModel.Technology\u003e out, List\u003cIntakeService.TechnologyItem\u003e items,
                          String role, ProjectKnowledgeModel.Layer layer) {
        if (items == null) return;
        for (IntakeService.TechnologyItem item : items) if (item != null && item.name() != null)
            out.add(new ProjectKnowledgeModel.Technology(item.name(), item.version(), role, layer));
    }
}
