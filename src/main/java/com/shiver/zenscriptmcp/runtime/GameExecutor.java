package com.shiver.zenscriptmcp.runtime;

import com.shiver.zenscriptmcp.model.DomainException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 由对应端的 Tick 事件驱动；每个任务每 Tick 最多执行一步。 */
public final class GameExecutor {

    private static final int QUEUE_CAPACITY = 128;

    private final ArrayBlockingQueue<Runnable> clientTasks = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ArrayBlockingQueue<Runnable> serverTasks = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final int maxTasksPerTick;
    private final long tickBudgetNanos;

    public GameExecutor(McpConfig.RuntimeSettings settings) {
        maxTasksPerTick = settings.maxReadTasksPerTick;
        tickBudgetNanos = TimeUnit.MILLISECONDS.toNanos(settings.readBudgetMillis);
    }

    public void submit(Side side, Runnable task) {
        if (!queueFor(side).offer(task)) {
            throw new DomainException("busy", "游戏线程队列已满");
        }
    }

    public void tick(Side side) {
        ArrayBlockingQueue<Runnable> queue = queueFor(side);
        int tasksToRun = Math.min(queue.size(), maxTasksPerTick);
        long startedNanos = System.nanoTime();

        // 固定本轮数量，避免重新入队的任务在同一 Tick 再次执行。
        for (int index = 0; index < tasksToRun; index++) {
            Runnable task = queue.poll();
            if (task == null) {
                break;
            }

            task.run();
            if (System.nanoTime() - startedNanos >= tickBudgetNanos) {
                break;
            }
        }
    }

    /** 仅在该端停止事件中调用；先标记取消，再清理剩余任务。 */
    public void drain(Side side) {
        ArrayBlockingQueue<Runnable> queue = queueFor(side);
        int tasksToDrain = queue.size();

        for (int index = 0; index < tasksToDrain; index++) {
            Runnable task = queue.poll();
            if (task != null) {
                task.run();
            }
        }
    }

    private ArrayBlockingQueue<Runnable> queueFor(Side side) {
        return side == Side.CLIENT ? clientTasks : serverTasks;
    }

    public enum Side {
        CLIENT,
        SERVER
    }
}
