package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic graph analysis over explicitly recorded UPKM dependencies. */
public final class DependencyGraphAnalyzer {

    public GraphReport analyze(ProjectKnowledgeModel project) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        project.components().stream().map(ProjectKnowledgeModel.ApplicationComponent::id)
                .sorted().forEach(id -> graph.put(id, new TreeSet<>()));

        int externalEdges = 0;
        for (ProjectKnowledgeModel.Dependency dependency : project.dependencies()) {
            if (!graph.containsKey(dependency.from())) continue;
            if (graph.containsKey(dependency.to())) graph.get(dependency.from()).add(dependency.to());
            else externalEdges++;
        }

        Tarjan tarjan = new Tarjan(graph);
        List<DependencyCycle> cycles = tarjan.cycles();
        int internalEdges = graph.values().stream().mapToInt(Set::size).sum();
        return new GraphReport(graph.size(), internalEdges, externalEdges, cycles);
    }

    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final List<String> stack = new ArrayList<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<DependencyCycle> cycles = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private List<DependencyCycle> cycles() {
            for (String node : graph.keySet()) if (!index.containsKey(node)) visit(node);
            cycles.sort((left, right) -> String.join("|", left.componentIds())
                    .compareTo(String.join("|", right.componentIds())));
            return List.copyOf(cycles);
        }

        private void visit(String node) {
            index.put(node, nextIndex);
            lowLink.put(node, nextIndex);
            nextIndex++;
            stack.add(node);
            onStack.add(node);

            for (String target : graph.get(node)) {
                if (!index.containsKey(target)) {
                    visit(target);
                    lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(target)));
                } else if (onStack.contains(target)) {
                    lowLink.put(node, Math.min(lowLink.get(node), index.get(target)));
                }
            }

            if (lowLink.get(node).equals(index.get(node))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.remove(stack.size() - 1);
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(node));
                component.sort(String::compareTo);
                boolean selfLoop = component.size() == 1 && graph.get(node).contains(node);
                if (component.size() > 1 || selfLoop) cycles.add(new DependencyCycle(component));
            }
        }
    }

    public record GraphReport(int nodeCount, int internalEdgeCount,
                              int externalEdgeCount, List<DependencyCycle> cycles) {
        public GraphReport {
            cycles = List.copyOf(cycles);
        }
    }

    public record DependencyCycle(List<String> componentIds) {
        public DependencyCycle {
            componentIds = List.copyOf(componentIds);
        }
    }
}
