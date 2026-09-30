# Worker Priority — Jenkins plugin

A Jenkins load balancer that prefers the **highest effective-priority eligible worker with free executors**. Configure each worker at **Manage Jenkins → Nodes → [worker] → Configure → Node Properties → Worker queue priority**:

- **Base priority**: signed integer; higher wins.
- **Priority degradation per active job**: nonnegative integer.

Before placing a job, the worker's effective priority is:

```text
base priority − (degradation × occupied regular executors)
```

The count includes currently running jobs and executor slots already assigned by Jenkins but not yet started. The priority recovers automatically when an executor is free again. An unconfigured worker scores 0 with no degradation; when *no eligible worker* has this property, scheduling delegates to the balancer previously installed in Jenkins. Ties between scored workers are resolved by node name. Jenkins' label, permission, same-node, executor-capacity, offline and other queue constraints still apply. Negative effective priorities remain eligible; a fully occupied worker is not eligible.

## Example

Give A three executors, base 100, degradation 60. Give B at least one executor, base 1, degradation 60. If each queued job remains running, placement is:

| Job | A before | B before | Chosen |
| --- | ---: | ---: | --- |
| 1 | 100 | 1 | A |
| 2 | 40 | 1 | A |
| 3 | −20 | 1 | B |
| 4 | −20 | −59 | A |

If A has only one executor, once it is occupied the next job runs on B (if B is eligible and free). Jobs are **not** preempted or moved after they start; this plugin selects execution nodes, not the ordering of items within Jenkins' queue.

## Build and test

Requires JDK 17+ and Maven 3.9+. Targets Jenkins 2.479.3+.

```bash
mvn test
mvn package
```

The tests use the Jenkins test harness to verify the four-job example with live concurrent builds, full-worker fallback, label restrictions, node-configuration GUI round-trip, integer validation/overflow, and unconfigured-node delegation.

## Installation

Download the prebuilt plugin binary from the [releases page](https://github.com/fmotalleb/jenkins-per-worker-priority/releases) — every release tag builds and publishes a `worker-priority-<version>.hpi` artifact there. Install it through **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**, then restart Jenkins. To build the binary yourself instead, run `mvn package` and use the generated `target/worker-priority.hpi`.

**Compatibility:** This installs a `Queue` load balancer at startup. Jenkins supports one installed load balancer at a time; another load-balancing plugin installed later can replace it, so avoid running competing load balancer plugins together. The built-in controller can also be configured as a worker by adding the same Node Property to its node configuration, if it has regular executors. Pipeline flyweight tasks (which do not occupy regular executors) are outside this balancer's placement decisions.

## Logging

The plugin logs through `java.util.logging` under the logger name `io.github.queuepriority.WorkerPriorityLoadBalancer`. By default nothing appears beyond startup, so the scheduling hot path stays quiet.

To inspect placement decisions, add a Log Recorder (**Manage Jenkins → System Log → Add new log recorder**) for that logger name with level `FINE` (or `ALL`). Logged events:

| Level | Event |
| --- | --- |
| `INFO` | Load balancer installed at startup. |
| `FINE` | Task deferred to the previous balancer because no offered worker has the Node Property. |
| `FINE` | Placement decision: each work chunk's target node, effective score, and busy-executor count at decision time. |
| `FINE` | No legal placement found; the task waits until executors free up. |

## Versions

- **Minimum Jenkins:** 2.479.3 (Jakarta servlet line)
- **Java:** 17+
- **Build tools:** Maven 3.9+ with JDK 17+

Releases are cut from `v`-prefixed Git tags (e.g. `v1.2.0`). CI strips the `v`, sets the plugin version in the build from the tag, and publishes the resulting `worker-priority-<version>.hpi` as a [release artifact](https://github.com/fmotalleb/jenkins-per-worker-priority/releases). The repository itself stays on `1.0-SNAPSHOT` between releases.
