package com.myweb.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskTriggerRuleTest {

    @Test
    void allSuccess_requiresEveryUpstreamSuccess() {
        assertTrue(TaskTriggerRule.ALL_SUCCESS.evaluate(List.of(TaskState.SUCCESS, TaskState.SUCCESS)));
        assertFalse(TaskTriggerRule.ALL_SUCCESS.evaluate(List.of(TaskState.SUCCESS, TaskState.FAILED)));
        assertFalse(TaskTriggerRule.ALL_SUCCESS.evaluate(List.of(TaskState.SUCCESS, TaskState.PENDING)));
        assertFalse(TaskTriggerRule.ALL_SUCCESS.evaluate(List.of(TaskState.SUCCESS, TaskState.SKIPPED)));
    }

    @Test
    void oneSuccess_eager() {
        assertTrue(TaskTriggerRule.ONE_SUCCESS.evaluate(List.of(TaskState.SUCCESS, TaskState.PENDING)));
        assertFalse(TaskTriggerRule.ONE_SUCCESS.evaluate(List.of(TaskState.FAILED, TaskState.PENDING)));
        assertFalse(TaskTriggerRule.ONE_SUCCESS.evaluate(List.of(TaskState.SKIPPED, TaskState.SKIPPED)));
    }

    @Test
    void oneFailed_eager() {
        assertTrue(TaskTriggerRule.ONE_FAILED.evaluate(List.of(TaskState.FAILED, TaskState.PENDING)));
        assertTrue(TaskTriggerRule.ONE_FAILED.evaluate(List.of(TaskState.UPSTREAM_FAILED)));
        assertFalse(TaskTriggerRule.ONE_FAILED.evaluate(List.of(TaskState.SUCCESS, TaskState.PENDING)));
    }

    @Test
    void allDone_waitsUntilNoPendingOrRunning() {
        assertFalse(TaskTriggerRule.ALL_DONE.evaluate(List.of(TaskState.SUCCESS, TaskState.PENDING)));
        assertFalse(TaskTriggerRule.ALL_DONE.evaluate(List.of(TaskState.RUNNING)));
        assertTrue(TaskTriggerRule.ALL_DONE.evaluate(List.of(TaskState.SUCCESS, TaskState.FAILED, TaskState.SKIPPED)));
    }

    @Test
    void allFailed() {
        assertTrue(TaskTriggerRule.ALL_FAILED.evaluate(List.of(TaskState.FAILED, TaskState.UPSTREAM_FAILED)));
        assertFalse(TaskTriggerRule.ALL_FAILED.evaluate(List.of(TaskState.FAILED, TaskState.SUCCESS)));
        assertFalse(TaskTriggerRule.ALL_FAILED.evaluate(List.of(TaskState.FAILED, TaskState.PENDING)));
    }

    @Test
    void allSkipped() {
        assertTrue(TaskTriggerRule.ALL_SKIPPED.evaluate(List.of(TaskState.SKIPPED, TaskState.SKIPPED)));
        assertFalse(TaskTriggerRule.ALL_SKIPPED.evaluate(List.of(TaskState.SKIPPED, TaskState.SUCCESS)));
    }

    @Test
    void noneFailed_requiresAllDoneAndNoFailure() {
        assertFalse(TaskTriggerRule.NONE_FAILED.evaluate(List.of(TaskState.SUCCESS, TaskState.PENDING)));
        assertTrue(TaskTriggerRule.NONE_FAILED.evaluate(List.of(TaskState.SUCCESS, TaskState.SKIPPED)));
        assertFalse(TaskTriggerRule.NONE_FAILED.evaluate(List.of(TaskState.SUCCESS, TaskState.FAILED)));
    }

    @Test
    void noneFailedMinOneSuccess() {
        assertTrue(TaskTriggerRule.NONE_FAILED_MIN_ONE_SUCCESS.evaluate(
                List.of(TaskState.SUCCESS, TaskState.SKIPPED)));
        assertFalse(TaskTriggerRule.NONE_FAILED_MIN_ONE_SUCCESS.evaluate(
                List.of(TaskState.SKIPPED, TaskState.SKIPPED)));
        assertFalse(TaskTriggerRule.NONE_FAILED_MIN_ONE_SUCCESS.evaluate(
                List.of(TaskState.SUCCESS, TaskState.FAILED)));
    }

    @Test
    void noneSkipped() {
        assertTrue(TaskTriggerRule.NONE_SKIPPED.evaluate(List.of(TaskState.SUCCESS, TaskState.FAILED)));
        assertFalse(TaskTriggerRule.NONE_SKIPPED.evaluate(List.of(TaskState.SUCCESS, TaskState.SKIPPED)));
        assertFalse(TaskTriggerRule.NONE_SKIPPED.evaluate(List.of(TaskState.SUCCESS, TaskState.PENDING)));
    }

    @Test
    void always_isAlwaysTrue() {
        assertTrue(TaskTriggerRule.ALWAYS.evaluate(List.of()));
        assertTrue(TaskTriggerRule.ALWAYS.evaluate(List.of(TaskState.PENDING, TaskState.FAILED)));
    }

    @Test
    void oneDone_successOrFailNotSkip() {
        assertTrue(TaskTriggerRule.ONE_DONE.evaluate(List.of(TaskState.SUCCESS, TaskState.PENDING)));
        assertTrue(TaskTriggerRule.ONE_DONE.evaluate(List.of(TaskState.FAILED)));
        assertTrue(TaskTriggerRule.ONE_DONE.evaluate(List.of(TaskState.UPSTREAM_FAILED)));
        assertFalse(TaskTriggerRule.ONE_DONE.evaluate(List.of(TaskState.SKIPPED, TaskState.PENDING)));
    }
}
