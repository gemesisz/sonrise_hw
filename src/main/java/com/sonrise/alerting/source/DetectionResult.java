package com.sonrise.alerting.source;

/**
 * Outcome of one detection run of one source.
 *
 * @param found      candidates the source parsed
 * @param created    new events stored
 * @param duplicates candidates already stored earlier
 * @param rejected   candidates that could not be stored (e.g. unknown category)
 */
public record DetectionResult(String source, int found, int created, int duplicates, int rejected) {
}
