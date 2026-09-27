package com.sonrise.alerting.admin.dto;

import com.sonrise.alerting.detection.SourceRun;

/**
 * Counts are null unless the run completed.
 */
public record SourceRunResponse(String source, SourceRun.Status status, Integer found, Integer created,
                                Integer duplicates, Integer rejected, String error) {

    public static SourceRunResponse of(SourceRun run) {
        var result = run.result();
        return new SourceRunResponse(run.source(), run.status(),
                result == null ? null : result.found(),
                result == null ? null : result.created(),
                result == null ? null : result.duplicates(),
                result == null ? null : result.rejected(),
                run.error());
    }
}
