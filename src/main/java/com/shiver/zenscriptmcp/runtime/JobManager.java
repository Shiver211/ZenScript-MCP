package com.shiver.zenscriptmcp.runtime;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 管理作业状态、取消请求和修改权限。
 * 作业元数据由本实例的锁保护；游戏回调和资源清理必须在锁外执行。
 */
public final class JobManager {

    private static final int MAX_ACTIVE_JOBS = 128;
    private static final int MAX_WAITING_MUTATIONS = 32;
    private static final long CLEANUP_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(3000);

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final SnapshotStore snapshots;
    private final GameExecutor executor;
    private final LongSupplier nanoClock;
    private final int completedJobsRetained;

    private String exclusiveJobId;
    private boolean closed;

    public JobManager(SnapshotStore snapshots, GameExecutor executor, int completedJobsRetained) {
        this(snapshots, executor, completedJobsRetained, System::nanoTime);
    }

    public JobManager(
            SnapshotStore snapshots,
            GameExecutor executor,
            int completedJobsRetained,
            LongSupplier nanoClock) {
        this.snapshots = snapshots;
        this.executor = executor;
        this.completedJobsRetained = completedJobsRetained;
        this.nanoClock = nanoClock;
    }

    public synchronized JsonObject submit(
            String toolName,
            JsonObject arguments,
            GameExecutor.Side side,
            boolean modifiesGameState,
            boolean worldBound,
            int timeoutMillis,
            String operationId,
            Task task) {
        if (closed) {
            throw new DomainException("source_unavailable", "运行时已关闭");
        }

        Job existingJob = findOperation(operationId, toolName, arguments);
        if (existingJob != null) {
            return result(existingJob);
        }

        ensureCapacity(modifiesGameState);
        long worldEpoch = snapshots.worldEpoch();
        long deadlineNanos = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Job job = new Job(
                toolName, arguments, side, task, modifiesGameState, worldBound, worldEpoch,
                deadlineNanos, operationId, timeoutMillis);

        jobs.put(job.id, job);
        if (!modifiesGameState) {
            scheduleNewJob(job);
        } else if (exclusiveJobId == null) {
            exclusiveJobId = job.id;
            scheduleNewJob(job);
        }
        return snapshots.result("accepted", "job", toJson(job));
    }

    public synchronized JsonObject status(String jobId) {
        maintain();
        return result(requireJob(jobId));
    }

    /** 仅供 HTTP 调用；等待通知时不持有作业锁。 */
    public JsonObject await(String jobId, int waitMillis) {
        Job job;
        synchronized (this) {
            job = requireJob(jobId);
        }
        try {
            job.completion.await(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            return result(job);
        }
    }

    public synchronized JsonObject cancel(String jobId) {
        Job job = requireJob(jobId);
        boolean cancelAccepted = !job.isTerminal();

        if (cancelAccepted) {
            requestStop(job, "cancelled");
        }
        maintain();

        JsonObject response = result(job);
        response.getAsJsonObject("job").addProperty("cancelAccepted", cancelAccepted);
        return response;
    }

    public synchronized JsonObject cancelMutation(String toolName) {
        if (exclusiveJobId == null) {
            return snapshots.result("completed", "data", Json.object("cancelAccepted", false));
        }
        Job job = jobs.get(exclusiveJobId);
        if (toolName != null && !toolName.equals(job.toolName)) {
            throw new DomainException("busy", "当前运行的是另一类修改作业");
        }
        return cancel(exclusiveJobId);
    }

    public synchronized JsonObject summary() {
        maintain();
        JsonObject activeMutation = exclusiveJobId == null ? null : toJson(jobs.get(exclusiveJobId));

        return Json.object(
                "activeCount", countActiveJobs(),
                "waitingMutations", waitingMutations(),
                "mutation", activeMutation);
    }

    public synchronized void worldChanged() {
        for (Job job : jobs.values()) {
            if (job.worldBound && !job.isTerminal()) {
                requestStop(job, "stale_reference");
            }
        }
    }

    public synchronized void cancelSide(GameExecutor.Side side) {
        for (Job job : jobs.values()) {
            if (job.side == side && !job.isTerminal()) {
                requestStop(job, "source_unavailable");
            }
        }
    }

    public synchronized void maintain() {
        for (Job job : jobs.values()) {
            checkDeadline(job);

            // 尚未执行的作业没有游戏资源，可以由后台结束。
            // 已执行的作业必须由所属游戏线程完成清理后再结束。
            if (job.startedAt == null && job.stopCode != null && !job.isTerminal()) {
                job.state = stateForStopCode(job.stopCode);
                job.error = createError(job.stopCode, "作业在执行前停止");
                job.cleanupComplete = true;
                releaseCompletedJob(job);
            }
        }

        trimCompletedJobs();
    }

    public synchronized void close() {
        closed = true;

        for (Job job : jobs.values()) {
            if (!job.isTerminal()) {
                job.stopCode = "cancelled";
            }
        }

        maintain();
    }

    private Job findOperation(String operationId, String toolName, JsonObject arguments) {
        if (operationId == null) {
            return null;
        }

        for (Job job : jobs.values()) {
            if (!operationId.equals(job.operationId)) {
                continue;
            }

            if (!toolName.equals(job.toolName) || !operationArguments(arguments).equals(job.arguments)) {
                throw new IllegalArgumentException("operationId 已用于其他参数");
            }

            return job;
        }

        return null;
    }

    private void ensureCapacity(boolean modifiesGameState) {
        if (modifiesGameState && exclusiveJobId != null && waitingMutations() >= MAX_WAITING_MUTATIONS) {
            throw new DomainException("queue_full", "修改作业等待队列已满（最多 32 个）");
        }

        if (countActiveJobs() >= MAX_ACTIVE_JOBS) {
            throw new DomainException("busy", "活动作业已达上限");
        }
    }

    private long waitingMutations() {
        return jobs.values().stream().filter(job -> job.modifiesGameState && !job.isTerminal()
                && !job.id.equals(exclusiveJobId)).count();
    }

    private long countActiveJobs() {
        return jobs.values().stream().filter(job -> !job.isTerminal()).count();
    }

    private void scheduleNewJob(Job job) {
        try {
            schedule(job);
        } catch (RuntimeException exception) {
            jobs.remove(job.id);
            releaseMutationLock(job);
            throw exception;
        }
    }

    private void advance(Job job) {
        if (!prepareForStep(job)) {
            return;
        }

        if (job.finishing) {
            advanceCleanup(job);
            return;
        }
        JsonObject result = null;
        Exception failure = null;

        if (!isStopRequested(job)) {
            try {
                result = job.task.step();
            } catch (Exception exception) {
                failure = exception;
            }
            recordStep(job);
        }

        if (isStopRequested(job) || result != null || failure != null) {
            beginCleanup(job, result, failure);
        } else {
            try {
                schedule(job);
            } catch (RuntimeException exception) {
                beginCleanup(job, null, exception);
            }
        }
    }

    private synchronized boolean prepareForStep(Job job) {
        if (job.isTerminal()) {
            return false;
        }

        if (job.finishing) {
            return true;
        }
        checkDeadline(job);
        if (job.worldBound && job.worldEpoch != snapshots.worldEpoch()) {
            job.stopCode = "stale_reference";
        }

        if (job.startedAt == null && job.stopCode == null) {
            job.startedAt = Instant.now().toString();
            job.state = JobState.RUNNING;
        }

        return true;
    }

    private void schedule(Job job) {
        GameExecutor.Side requestedSide = job.task.executionSide();
        GameExecutor.Side side = requestedSide == null ? job.side : requestedSide;
        if (job.modifiesGameState) {
            executor.submitAction(side, () -> advance(job));
        } else {
            executor.submit(side, () -> advance(job));
        }
    }

    private synchronized boolean isStopRequested(Job job) {
        return job.stopCode != null;
    }

    private synchronized void recordStep(Job job) {
        job.executedSteps++;
        checkDeadline(job);
    }

    private void beginCleanup(Job job, JsonObject result, Exception failure) {
        synchronized (this) {
            job.finishing = true;
            job.finishStartedNanos = nanoClock.getAsLong();
            job.finishResult = result;
            job.finishFailure = failure;
            job.state = JobState.FINISHING;
        }
        advanceCleanup(job);
    }

    private void advanceCleanup(Job job) {
        boolean expired = nanoClock.getAsLong() - job.finishStartedNanos >= CLEANUP_BUDGET_NANOS;
        String reason = "succeeded";
        if (job.stopCode != null) {
            reason = job.stopCode;
        } else if (job.finishFailure != null) {
            reason = "failed";
        }
        try {
            if (job.startedAt != null && !job.task.finishStep(reason, expired)) {
                if (expired) {
                    throw new DomainException("cleanup_failed", "收尾阶段超过 3000 ms 独立预算");
                }
                schedule(job);
                return;
            }
        } catch (Exception exception) {
            finishJob(job, job.finishResult, job.finishFailure, exception);
            return;
        }
        finishJob(job, job.task.lastResult() == null ? job.finishResult : job.task.lastResult(),
                job.finishFailure, null);
    }

    private void finishJob(Job job, JsonObject result, Exception failure, Exception finishFailure) {
        // 不持有查询锁：即使模组清理回调卡住，HTTP 状态查询仍然可以响应。
        Exception cleanupFailure = cleanupTask(job);
        Exception effectiveFailure = failure == null ? finishFailure : failure;

        synchronized (this) {
            job.cleanupComplete = cleanupFailure == null;
            applyOutcome(job, result, effectiveFailure, cleanupFailure);
            if (finishFailure != null && job.error != null) {
                job.error.getAsJsonObject("details").addProperty("recoveryError", finishFailure.toString());
            }
            releaseCompletedJob(job);
            trimCompletedJobs();
        }
    }

    private Exception cleanupTask(Job job) {
        if (job.startedAt == null) {
            return null;
        }

        try {
            job.task.cleanup();
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }

    private void applyOutcome(Job job, JsonObject result, Exception failure, Exception cleanupFailure) {
        JsonObject evidence = result;
        if (evidence == null && job.task != null) {
            evidence = job.task.lastResult();
        }
        if (evidence != null) {
            job.result = Json.copy(evidence);
        }
        if (cleanupFailure != null) {
            job.state = JobState.FAILED;
            job.error = createError("cleanup_failed", cleanupFailure.toString());
        } else if (job.stopCode != null) {
            job.state = stateForStopCode(job.stopCode);
            job.error = createError(job.stopCode, "作业已在安全边界停止");
        } else if (failure != null) {
            String errorCode = failure instanceof DomainException
                    ? ((DomainException) failure).getCode()
                    : "action_rejected";
            job.state = JobState.FAILED;
            job.error = failure instanceof DomainException ? ((DomainException) failure).toJson()
                    : createError(errorCode, failure.toString());
        } else {
            job.state = isUnverified(result) ? JobState.UNVERIFIED : JobState.SUCCEEDED;
            job.result = Json.copy(result);
        }
    }

    /** 未确认是正常终态，仍须释放修改权限；不能把无异常结束当成效果已验证。 */
    public static boolean isUnverified(JsonObject result) {
        JsonObject data = result.has("data") ? result.getAsJsonObject("data") : result;
        return data.has("outcome") && "unverified".equals(data.get("outcome").getAsString())
                || data.has("confirmation") && "unconfirmed".equals(data.get("confirmation").getAsString())
                || data.has("verificationRequired") && data.get("verificationRequired").getAsBoolean();
    }

    private void releaseCompletedJob(Job job) {
        job.endedAt = Instant.now().toString();
        job.task = null; // 只保留结果 DTO，释放回调闭包中的游戏对象引用。

        if (job.cleanupComplete) {
            releaseMutationLock(job);
        }
        job.completion.countDown();
        startNextMutation();
    }

    private void releaseMutationLock(Job job) {
        if (job.id.equals(exclusiveJobId)) {
            exclusiveJobId = null;
        }
    }

    private void startNextMutation() {
        if (exclusiveJobId != null || closed) {
            return;
        }
        for (Job next : jobs.values()) {
            if (!next.modifiesGameState || next.isTerminal() || next.startedAt != null || next.stopCode != null) {
                continue;
            }
            checkDeadline(next);
            if (next.stopCode != null) {
                continue;
            }
            exclusiveJobId = next.id;
            try {
                schedule(next);
                return;
            } catch (RuntimeException exception) {
                next.state = JobState.FAILED;
                next.error = createError("source_unavailable", exception.toString());
                next.cleanupComplete = true;
                next.endedAt = Instant.now().toString();
                next.task = null;
                next.completion.countDown();
                exclusiveJobId = null;
            }
        }
    }

    private void checkDeadline(Job job) {
        if (job.finishing || job.isTerminal() || nanoClock.getAsLong() - job.deadlineNanos < 0) {
            return;
        }

        job.deadlineExceeded = true;
        if (job.stopCode == null) {
            job.stopCode = "deadline_exceeded";
        }
    }

    private void requestStop(Job job, String stopCode) {
        job.stopCode = stopCode;
        if (job.startedAt != null && !job.finishing) {
            job.state = JobState.CANCEL_REQUESTED;
        }
    }

    private JobState stateForStopCode(String stopCode) {
        switch (stopCode) {
            case "deadline_exceeded":
                return JobState.TIMED_OUT;
            case "cancelled":
                return JobState.CANCELLED;
            default:
                return JobState.FAILED;
        }
    }

    private Job requireJob(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw new DomainException("stale_reference", "作业不存在、已过期或来自其他游戏实例");
        }

        return job;
    }

    private JsonObject toJson(Job job) {
        return Json.object(
                "jobId", job.id,
                "tool", job.toolName,
                "state", job.state.protocolValue,
                "source", job.side.name().toLowerCase(Locale.ROOT),
                "worldEpoch", job.worldEpoch,
                "createdAt", job.createdAt,
                "startedAt", job.startedAt,
                "endedAt", job.endedAt,
                "steps", job.executedSteps,
                "timeoutMillis", job.timeoutMillis,
                "deadlineExceeded", job.deadlineExceeded,
                "cleanupComplete", job.cleanupComplete);
    }

    private JsonObject result(Job job) {
        String status = job.isTerminal() ? "completed" : "accepted";
        if (job.state == JobState.UNVERIFIED) {
            status = "unverified";
        }
        JsonObject result = snapshots.result(status, "job", toJson(job));
        if (job.result != null) {
            if (job.result.has("data")) {
                result.add("data", Json.copy(job.result.getAsJsonObject("data")));
                if (job.result.has("observation")) {
                    result.add("observation", job.result.get("observation"));
                }
                if (job.result.has("pagination")) {
                    result.add("pagination", job.result.get("pagination"));
                }
            } else {
                result.add("data", Json.copy(job.result));
            }
        }
        if (job.error != null) {
            result.addProperty("status", "error");
            result.add("error", Json.copy(job.error));
        }
        return result;
    }

    private static JsonObject operationArguments(JsonObject arguments) {
        JsonObject value = Json.copy(arguments);
        for (String key : new String[] {"waitMs", "view", "fields", "limit", "cursor"}) {
            value.remove(key);
        }
        return value;
    }

    private JsonObject createError(String code, String message) {
        return Json.object(
                "code", code,
                "message", message,
                "stage", "execution",
                "details", new JsonObject());
    }

    private void trimCompletedJobs() {
        long completedCount = jobs.values().stream().filter(Job::isTerminal).count();
        Iterator<Job> iterator = jobs.values().iterator();

        while (completedCount > completedJobsRetained && iterator.hasNext()) {
            Job job = iterator.next();
            if (job.isTerminal() && !job.id.equals(exclusiveJobId)) {
                iterator.remove();
                completedCount--;
            }
        }
    }

    public interface Task {

        /** 返回 null 表示下一 Tick 继续；回调在所属游戏线程运行。 */
        JsonObject step() throws Exception;

        /** 跨线程任务只返回下一回调所属端，不在此读取游戏对象。 */
        default GameExecutor.Side executionSide() {
            return null;
        }

        /** 结束路径可跨 tick 补偿；到期必须停止补偿并保存剩余证据。 */
        default boolean finishStep(String reason, boolean expired) throws Exception {
            return true;
        }

        /** 作业开始后，所有结束路径都会在所属游戏线程调用清理。 */
        default void cleanup() throws Exception {
        }

        /** 取消或失败时保留最后一次 DTO 证据；不得在此跨线程读取游戏。 */
        default JsonObject lastResult() {
            return null;
        }
    }

    private enum JobState {
        QUEUED("queued", false),
        RUNNING("running", false),
        CANCEL_REQUESTED("cancel_requested", false),
        FINISHING("finishing", false),
        SUCCEEDED("succeeded", true),
        UNVERIFIED("unverified", true),
        FAILED("failed", true),
        CANCELLED("cancelled", true),
        TIMED_OUT("timed_out", true);

        private final String protocolValue;
        private final boolean terminal;

        JobState(String protocolValue, boolean terminal) {
            this.protocolValue = protocolValue;
            this.terminal = terminal;
        }
    }

    /** 字段均由外层 JobManager 的锁保护。 */
    private static final class Job {

        private final String id = UUID.randomUUID().toString();
        private final String createdAt = Instant.now().toString();
        private final String toolName;
        private final String operationId;
        private final JsonObject arguments;
        private final GameExecutor.Side side;
        private final boolean worldBound;
        private final boolean modifiesGameState;
        private final long worldEpoch;
        private final long deadlineNanos;
        private final int timeoutMillis;
        private final CountDownLatch completion = new CountDownLatch(1);

        private Task task;
        private JobState state = JobState.QUEUED;
        private String startedAt;
        private String endedAt;
        private String stopCode;
        private JsonObject result;
        private JsonObject error;
        private boolean cleanupComplete;
        private boolean deadlineExceeded;
        private boolean finishing;
        private long finishStartedNanos;
        private JsonObject finishResult;
        private Exception finishFailure;
        private long executedSteps;

        private Job(
                String toolName,
                JsonObject arguments,
                GameExecutor.Side side,
                Task task,
                boolean modifiesGameState,
                boolean worldBound,
                long worldEpoch,
                long deadlineNanos,
                String operationId,
                int timeoutMillis) {
            this.toolName = toolName;
            this.arguments = operationArguments(arguments);
            this.side = side;
            this.task = task;
            this.modifiesGameState = modifiesGameState;
            this.worldBound = worldBound;
            this.worldEpoch = worldEpoch;
            this.deadlineNanos = deadlineNanos;
            this.operationId = operationId;
            this.timeoutMillis = timeoutMillis;
        }

        private boolean isTerminal() {
            return state.terminal;
        }
    }
}
