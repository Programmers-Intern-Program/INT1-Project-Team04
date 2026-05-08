package com.back.domain.application.result;

public record PromoteBaselineMcpResult(
        boolean promoted,
        int rowsUpdated,
        String skippedReason
) {

    public static PromoteBaselineMcpResult failed(String reason) {
        return new PromoteBaselineMcpResult(false, 0, reason);
    }
}
