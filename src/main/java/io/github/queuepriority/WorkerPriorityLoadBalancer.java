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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Choose the available node with the highest effective priority for each work chunk.
 * Only idle executors are offered in the worksheet; the score uses *all* busy regular
 * executors on the node, including executors assigned earlier in this queue pass.
 */
public final class WorkerPriorityLoadBalancer extends LoadBalancer {
    /** Enable a Log Recorder on this name to see placement decisions. */
    private static final Logger LOGGER = Logger.getLogger(WorkerPriorityLoadBalancer.class.getName());

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
        LOGGER.log(Level.INFO, "Worker priority load balancer installed");
    }

    @CheckForNull
    @Override
    public Mapping map(Queue.Task task, MappingWorksheet worksheet) {
        // No opt-in settings: leave Jenkins' default placement/affinity behavior unchanged.
        boolean configured = worksheet.works.stream()
                .anyMatch(work -> work.applicableExecutorChunks().stream()
                        .anyMatch(ec -> ec.node.getNodeProperties().get(WorkerPriorityProperty.class) != null));
        if (!configured) {
            LOGGER.log(Level.FINE,
                    "No offered agent carries a worker priority; deferring {0} to the default load balancer",
                    task.getFullDisplayName());
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
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Mapped {0} to [{1}]",
                        new Object[] {task.getFullDisplayName(), describe(worksheet, mapping, busy, assigned)});
            }
            return mapping;
        }
        LOGGER.log(Level.FINE,
                "No legal placement for {0} on prioritized agents; deferring until executors free up",
                task.getFullDisplayName());
        return null; // no legal placement: let Jenkins retry when executors become free
    }

    /** One entry per assigned work chunk: work id, target node, and the node's score at decision time. */
    private static String describe(MappingWorksheet worksheet, Mapping mapping, int[] busy, int[] assigned) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mapping.size(); i++) {
            ExecutorChunk ec = mapping.assigned(i);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(worksheet.works(i).id).append(" -> ").append(ec.getName())
                    .append(" (score=").append(score(ec.node, busy[ec.index] + assigned[ec.index]))
                    .append(", busy=").append(busy[ec.index]).append(')');
        }
        return sb.toString();
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
