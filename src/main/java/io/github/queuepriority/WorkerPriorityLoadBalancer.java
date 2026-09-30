package io.github.queuepriority;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.Initializer;
import hudson.model.LoadBalancer;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.MappingWorksheet;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.MappingWorksheet.Mapping;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Choose the available node with the highest effective priority for each work chunk.
 * Only idle executors are offered in the worksheet; the score uses *all* busy regular
 * executors on the node, including executors assigned earlier in this queue pass.
 */
public final class WorkerPriorityLoadBalancer extends LoadBalancer {
    private final LoadBalancer fallback;

    public WorkerPriorityLoadBalancer(LoadBalancer fallback) {
        this.fallback = fallback;
    }

    // A LoadBalancer is installed on Queue, NOT registered as a Jenkins @Extension.
    @Initializer
    public static void install() {
        Queue queue = Jenkins.get().getQueue();
        // Queue wraps load balancers in a sanitizing decorator. Retain the previous
        // implementation as the opt-out fallback; do not assume getLoadBalancer()
        // returns the raw instance.
        queue.setLoadBalancer(new WorkerPriorityLoadBalancer(queue.getLoadBalancer()));
    }

    @CheckForNull
    @Override
    public Mapping map(Queue.Task task, MappingWorksheet worksheet) {
        // No opt-in settings: leave Jenkins' default placement/affinity behavior unchanged.
        boolean configured = worksheet.works.stream()
                .anyMatch(work -> work.applicableExecutorChunks().stream()
                        .anyMatch(ec -> ec.node.getNodeProperties().get(WorkerPriorityProperty.class) != null));
        if (!configured) {
            return fallback.map(task, worksheet);
        }

        Mapping mapping = worksheet.new Mapping();
        int[] assigned = new int[worksheet.executors.size()];
        int[] busy = new int[worksheet.executors.size()];
        // Executor state can change as other jobs finish; capture it once so the
        // comparator remains consistent throughout this placement decision.
        for (ExecutorChunk ec : worksheet.executors) {
            busy[ec.index] = ec.computer.countBusy();
        }
        if (assign(worksheet, mapping, assigned, busy, 0) && mapping.isCompletelyValid()) {
            return mapping;
        }
        return null; // no legal placement: let Jenkins retry when executors become free
    }

    private boolean assign(MappingWorksheet worksheet, Mapping mapping, int[] assigned, int[] busy, int index) {
        if (index == mapping.size()) {
            return true;
        }
        MappingWorksheet.WorkChunk work = worksheet.works(index);
        List<ExecutorChunk> choices = new ArrayList<>(work.applicableExecutorChunks());
        // Add provisional assignments within this mapping to the busy-count snapshot.
        // Sort a copy, never mutate Jenkins' worksheet.
        choices.sort(Comparator
                .<ExecutorChunk>comparingLong(ec -> score(ec.node,
                        busy[ec.index] + assigned[ec.index]))
                .reversed()
                .thenComparing(ExecutorChunk::getName));

        for (ExecutorChunk ec : choices) {
            if (assigned[ec.index] + work.size() > ec.capacity()) {
                continue;
            }
            mapping.assign(index, ec);
            assigned[ec.index] += work.size();
            if (mapping.isPartiallyValid() && assign(worksheet, mapping, assigned, busy, index + 1)) {
                return true;
            }
            assigned[ec.index] -= work.size();
            mapping.assign(index, null);
        }
        return false;
    }

    static long score(Node node, int activeJobs) {
        WorkerPriorityProperty property = node.getNodeProperties().get(WorkerPriorityProperty.class);
        return property == null ? 0L : property.score(activeJobs);
    }
}
