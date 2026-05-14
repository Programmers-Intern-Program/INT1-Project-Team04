package com.back.domain.adapter.out.ai;

import java.util.ArrayList;
import java.util.List;

// ChatClient 호출 1회 안에서 실제 MCP tool 호출 여부를 확인하기 위한 가벼운 실행 기록.
// 모델 최종 응답이 비어도 이 기록으로 데이터 조회/비교/알림 발송 증빙을 검증한다.
final class McpToolExecutionRecorder {

    private static final ThreadLocal<List<Execution>> EXECUTIONS = new ThreadLocal<>();

    private McpToolExecutionRecorder() {
    }

    static void start() {
        EXECUTIONS.set(new ArrayList<>());
    }

    static List<Execution> stop() {
        List<Execution> executions = EXECUTIONS.get();
        EXECUTIONS.remove();
        if (executions == null) {
            return List.of();
        }
        return List.copyOf(executions);
    }

    static void record(String toolName, String input, String output, boolean failed) {
        List<Execution> executions = EXECUTIONS.get();
        if (executions != null) {
            executions.add(new Execution(toolName, input, output, failed));
        }
    }

    static List<Execution> snapshot() {
        List<Execution> executions = EXECUTIONS.get();
        if (executions == null) {
            return List.of();
        }
        return List.copyOf(executions);
    }

    record Execution(String toolName, String input, String output, boolean failed) {
    }
}
