package io.github.queuepriority;

import hudson.Extension;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/** Settings stored on each agent (or the built-in node), not on each job. */
public final class WorkerPriorityProperty extends NodeProperty<Node> {
    private final int priority;
    private final int degradation;

    @DataBoundConstructor
    public WorkerPriorityProperty(int priority, int degradation) {
        if (degradation < 0) {
            throw new IllegalArgumentException("Degradation must be zero or greater");
        }
        this.priority = priority;
        this.degradation = degradation;
    }

    public int getPriority() {
        return priority;
    }

    public int getDegradation() {
        return degradation;
    }

    /** Use long arithmetic: int priorities and large busy counts must not wrap around. */
    public long score(int activeJobs) {
        return (long) priority - (long) degradation * activeJobs;
    }

    @Extension
    public static final class DescriptorImpl extends NodePropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Worker queue priority";
        }

        @Override
        public boolean isApplicableAsGlobal() {
            return false;
        }

        public FormValidation doCheckDegradation(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                return Integer.parseInt(value) < 0
                        ? FormValidation.error("Must be zero or greater") : FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Must be an integer");
            }
        }
    }
}
