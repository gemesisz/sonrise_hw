package com.sonrise.alerting.detection;

import com.sonrise.alerting.source.DetectionResult;

/**
 * What happened when the scheduler (or an admin) tried to run one source.
 *
 * @param result only for {@link Status#COMPLETED}
 * @param error  only for {@link Status#FAILED}
 */
public record SourceRun(String source, Status status, DetectionResult result, String error) {

    public enum Status {
        COMPLETED,
        FAILED,
        /** A run of the same source was already in progress; this one did nothing. */
        SKIPPED_ALREADY_RUNNING
    }

    static SourceRun completed(DetectionResult result) {
        return new SourceRun(result.source(), Status.COMPLETED, result, null);
    }

    static SourceRun failed(String source, String error) {
        return new SourceRun(source, Status.FAILED, null, error);
    }

    static SourceRun skipped(String source) {
        return new SourceRun(source, Status.SKIPPED_ALREADY_RUNNING, null, null);
    }
}
