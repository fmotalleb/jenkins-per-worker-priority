package io.github.queuepriority;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.LoadBalancer;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.MappingWorksheet;
import hudson.slaves.DumbSlave;
import hudson.tasks.Builder;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import hudson.model.Descriptor;

import static org.junit.Assert.*;

public class WorkerPriorityTest {
    @Rule public JenkinsRule j = new JenkinsRule();

    @Test public void degradationRedistributesFourConcurrentJobs() throws Exception {
        DumbSlave a = agent("a", 3, 100, 60, "pool");
        DumbSlave b = agent("b", 3, 1, 60, "pool");
        j.jenkins.setNumExecutors(0);

        BlockedJob first = blockedJob("first", "pool");
        BlockedJob second = blockedJob("second", "pool");
        BlockedJob third = blockedJob("third", "pool");
        BlockedJob fourth = blockedJob("fourth", "pool");
        try {
            assertEquals(a.getNodeName(), first.start());  // A: 100 -> 40
            assertEquals(a.getNodeName(), second.start()); // A: 40 -> -20
            assertEquals(b.getNodeName(), third.start());  // B: 1 -> -59
            assertEquals(a.getNodeName(), fourth.start()); // -20 beats -59
            assertEquals(3, a.toComputer().countBusy());
            assertEquals(1, b.toComputer().countBusy());
            assertEquals(-80L, WorkerPriorityLoadBalancer.score(a, 3));
            assertEquals(-59L, WorkerPriorityLoadBalancer.score(b, 1));
        } finally {
            first.finish(); second.finish(); third.finish(); fourth.finish();
        }
        first.result(); second.result(); third.result(); fourth.result();
        // No persistent counter: freeing executors restores the original priority.
        assertEquals(100L, WorkerPriorityLoadBalancer.score(a, 0));
    }

    @Test public void fullPreferredWorkerFallsBackToAvailableWorker() throws Exception {
        agent("a", 1, 100, 0, "pool");
        agent("b", 1, 1, 0, "pool");
        j.jenkins.setNumExecutors(0);
        BlockedJob first = blockedJob("first", "pool");
        BlockedJob second = blockedJob("second", "pool");
        try {
            assertEquals("a", first.start());
            assertEquals("b", second.start());
        } finally {
            first.finish(); second.finish();
        }
        first.result(); second.result();
    }

    @Test public void jobLabelsStillConstrainPlacement() throws Exception {
        agent("a", 1, 100, 0, "fast");
        agent("b", 1, 1, 0, "slow");
        j.jenkins.setNumExecutors(0);
        BlockedJob job = blockedJob("labelled", "slow");
        try {
            assertEquals("b", job.start());
        } finally {
            job.finish();
        }
        job.result();
    }

    @Test public void settingsPersistThroughNodeConfigurationGui() throws Exception {
        DumbSlave agent = agent("a", 1, -20, 60, "pool");
        Node reloaded = j.configRoundtrip(agent);
        WorkerPriorityProperty property = reloaded.getNodeProperties().get(WorkerPriorityProperty.class);
        assertNotNull(property);
        assertEquals(-20, property.getPriority());
        assertEquals(60, property.getDegradation());
        assertEquals(-80L, property.score(1));
    }

    @Test public void arithmeticAndValidation() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerPriorityProperty(100, -1));
        assertEquals(-4294967294L, new WorkerPriorityProperty(Integer.MAX_VALUE, Integer.MAX_VALUE).score(3));
        WorkerPriorityProperty.DescriptorImpl descriptor = new WorkerPriorityProperty.DescriptorImpl();
        assertFalse(descriptor.isApplicableAsGlobal());
        assertEquals(hudson.util.FormValidation.Kind.ERROR, descriptor.doCheckDegradation("-1").kind);
        assertEquals(hudson.util.FormValidation.Kind.ERROR, descriptor.doCheckDegradation("oops").kind);
    }

    @Test public void unconfiguredNodesUsePreviousBalancer() throws Exception {
        DumbSlave node = j.createSlave("plain", "pool", null);
        j.waitOnline(node);
        j.jenkins.setNumExecutors(0);
        AtomicInteger calls = new AtomicInteger();
        j.jenkins.getQueue().setLoadBalancer(new WorkerPriorityLoadBalancer(new LoadBalancer() {
            @Override public MappingWorksheet.Mapping map(Queue.Task task, MappingWorksheet ws) {
                calls.incrementAndGet();
                return LoadBalancer.CONSISTENT_HASH.map(task, ws);
            }
        }));
        BlockedJob job = blockedJob("plain-job", "pool");
        try {
            assertEquals("plain", job.start());
            assertTrue("Unconfigured nodes must use the original balancer", calls.get() > 0);
        } finally {
            job.finish();
        }
        job.result();
    }

    private DumbSlave agent(String name, int executors, int priority, int degradation, String labels) throws Exception {
        DumbSlave node = j.createSlave(name, labels, null);
        node.setNumExecutors(executors);
        node.getNodeProperties().add(new WorkerPriorityProperty(priority, degradation));
        node.save();
        j.waitOnline(node);
        return node;
    }

    private BlockedJob blockedJob(String name, String label) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        p.setAssignedLabel(j.jenkins.getLabel(label));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        p.getBuildersList().add(new HoldingBuilder(started, release));
        return new BlockedJob(p, started, release);
    }

    private static class BlockedJob {
        final FreeStyleProject project;
        final CountDownLatch started;
        final CountDownLatch release;
        Future<FreeStyleBuild> future;
        BlockedJob(FreeStyleProject project, CountDownLatch started, CountDownLatch release) {
            this.project = project;
            this.started = started;
            this.release = release;
        }
        String start() throws Exception {
            future = project.scheduleBuild2(0);
            assertNotNull("Build was not scheduled", future);
            assertTrue("Build did not start", started.await(60, TimeUnit.SECONDS));
            // The builder signals only after its build actually starts on an executor.
            return project.getLastBuild().getBuiltOnStr();
        }
        void finish() { release.countDown(); }
        void result() throws Exception { assertEquals(hudson.model.Result.SUCCESS, future.get(60, TimeUnit.SECONDS).getResult()); }
    }

    /** In-memory blocking step to keep executors busy without wall-clock sleeps. */
    public static class HoldingBuilder extends Builder {
        private final transient CountDownLatch started;
        private final transient CountDownLatch release;
        public HoldingBuilder(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }
        @Override public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            started.countDown();
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release test build");
            }
            return true;
        }
        @TestExtension
        public static final class DescriptorImpl extends Descriptor<Builder> { }
    }
}
