package com.myweb.workflow;

import java.util.Collection;

/**
 * 节点执行的触发规则.
 */
public enum TaskTriggerRule {

    /**
     * （默认）仅当所有上游任务都成功时，任务才会运行。
     * 一旦任何上游任务处于失败或 upstream_failed 状态，下游任务就会设置为状态 upstream_failed 并且不会运行。
     * 同样，一旦任何上游任务处于跳过状态，下游任务就会设置为跳过状态并且不运行。
     * 有一个跳过的上游任务和一个失败的上游任务，则下游任务是设置为跳过还是 upstream_failed 取决于哪个上游任务先完成。
     */
    ALL_SUCCESS,

    /**
     * 仅在所有上游任务都处于失败或 upstream_failed 状态时运行。
     * 一旦任何上游任务处于成功状态，下游任务就会设置为跳过状态并且不运行。
     * 类似地，一旦任何上游任务处于跳过状态 ，下游任务就会设置为跳过状态并且不运行。
     */
    ALL_FAILED,

    /**
     * 所有上游任务都执行完毕(无论其状态如何)，任务就会运行。
     * all_done 触发规则将使任务等待，直到所有上游任务都完成其执行。
     * 一旦所有任务完成，无论其状态如何，下游任务都会运行。
     */
    ALL_DONE,

    /**
     * 仅当跳过所有上游任务时，任务才会运行。
     * 一旦任何上游任务处于成功、失败或 upstream_failed 状态，
     * 触发规则 all_skipped 的下游任务就会设置为跳过状态，并且不会运行。
     */
    ALL_SKIPPED,

    /**
     * 至少一个上游任务失败（不等待所有上游任务完成）。
     * one_failed 触发器规则将在任务的至少一个上游任务处于失败或 upstream_failed 状态时立即运行。
     * 如果所有上游任务都已完成，并且没有一个任务处于失败或 upstream_failed 状态，则下游任务将设置为跳过状态 。
     */
    ONE_FAILED,

    /**
     * 至少有一个上游任务已成功（不等待所有上游任务完成）。
     * one_success 触发规则将在任务的至少一个上游任务处于成功状态时立即运行。
     * 如果跳过了所有上游任务，则具有 one_success 触发规则的下游任务也将设置为跳过状态。
     * 如果所有上游任务都已完成，并且其中至少有一个任务处于失败或 upstream_failed 状态，
     * 则下游任务将设置为状态 upstream_failed。
     */
    ONE_SUCCESS,

    /**
     * 当至少一个上游任务成功或失败时，任务将运行。
     * one_done 触发器规则会在任务的至少一个上游任务处于成功或失败状态时立即运行。
     * 不考虑具有跳过或 upstream_failed 状态的上游任务。
     * 一旦一个上游任务完成（ 处于成功或失败状态），下游任务就会运行。
     * 如果所有上游任务都处于跳过或 upstream_failed 状态，则具有 one_done 触发规则的下游任务将设置为跳过状态 。
     */
    ONE_DONE,

    /**
     * 仅当所有上游任务都成功或被跳过时，任务才会运行。
     * none_failed 触发器规则仅在所有上游任务成功或跳过时才运行任务。
     */
    NONE_FAILED,

    /**
     * 仅当所有上游任务均未失败，且至少有一个上游任务成功时，任务才会运行。
     * 仅当满足三个条件时，使用 none_failed_min_one_success 触发器规则的任务才会运行：
     * 1. 所有上游任务均已完成。
     * 2. 没有上游任务处于失败或 upstream_failed 状态。
     * 3. 至少有一个上游任务处于成功状态。
     * 如果任何上游任务处于失败或 upstream_failed 状态，则下游任务将设置为状态 upstream_failed，并且不会运行。
     * 如果所有上游任务都处于跳过状态，则下游任务将设置为跳过状态 ，不运行。
     */
    NONE_FAILED_MIN_ONE_SUCCESS,

    /**
     * 仅当没有上游任务处于跳过状态时，任务才会运行。
     * 使用 none_skipped 触发规则的任务仅在没有上游任务处于跳过状态时运行。
     * 上游任务可以处于任何其他状态： 成功、失败或 upstream_failed。
     */
    NONE_SKIPPED,

    /**
     * DAG 运行启动后立即运行，而不管其上游任务的状态如何。
     */
    ALWAYS;


    /**
     * 根据收集到的上游状态计算出当前节点是否满足触发运行的条件.
     *
     * @param upstreamStates 上游运行状态
     * @return true - 满足触发条件
     */
    public boolean evaluate(Collection<TaskState> upstreamStates) {
        // 是否所有上游都已结束
        boolean areAllDone = upstreamStates.stream()
                .noneMatch(s -> s == TaskState.PENDING || s == TaskState.RUNNING);

        switch (this) {
            // ==========================================
            // 1. 不需要等待所有上游结束的规则 (Eager Rules)
            // ==========================================

            // 只要有一个满足条件立刻触发
            case ONE_SUCCESS:
                return upstreamStates.stream().anyMatch(s -> s == TaskState.SUCCESS);
            case ONE_FAILED:
                return upstreamStates.stream().anyMatch(s -> s == TaskState.FAILED || s == TaskState.UPSTREAM_FAILED);
            case ONE_DONE:
                return upstreamStates.stream().anyMatch(s -> s == TaskState.SUCCESS || s == TaskState.FAILED || s == TaskState.UPSTREAM_FAILED);
            case ALWAYS:
                return true;

            // ==========================================
            // 2. 必须等待所有上游结束的规则 (Blocking Rules)
            // ==========================================

            // 正向匹配逻辑 (allMatch 在遇到 PENDING 时会自动返回 false，这是安全的)
            case ALL_SUCCESS:
                return upstreamStates.stream().allMatch(s -> s == TaskState.SUCCESS);
            case ALL_FAILED:
                return upstreamStates.stream().allMatch(s -> s == TaskState.FAILED || s == TaskState.UPSTREAM_FAILED);
            case ALL_SKIPPED:
                return upstreamStates.stream().allMatch(s -> s == TaskState.SKIPPED);
            case ALL_DONE:
                return areAllDone;

            // ==========================================
            // 3. 否定逻辑规则 (必须要加 !areAllDone 判断)
            // ==========================================

            case NONE_FAILED:
                if (!areAllDone) {
                    return false;
                }
                return upstreamStates.stream().noneMatch(s -> s == TaskState.FAILED || s == TaskState.UPSTREAM_FAILED);
            case NONE_SKIPPED:
                if (!areAllDone) {
                    return false;
                }
                return upstreamStates.stream().noneMatch(s -> s == TaskState.SKIPPED);
            case NONE_FAILED_MIN_ONE_SUCCESS:
                if (!areAllDone) {
                    return false;
                }
                return upstreamStates.stream().noneMatch(s -> s == TaskState.FAILED || s == TaskState.UPSTREAM_FAILED)
                        && upstreamStates.stream().anyMatch(s -> s == TaskState.SUCCESS);
            default:
                throw new IllegalArgumentException("Unknown trigger rule: " + this);
        }
    }

}
