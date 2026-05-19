package com.facefinder.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Top-level response returned by POST /api/faces/search.
 */
@Data
@Builder
public class FaceSearchResponse {

    private boolean success;
    private String message;

    /** Directory where matched files were moved/copied. */
    private String outputDirectory;

    /** Number of reference images that yielded at least one detectable face. */
    private int referenceImagesProcessed;

    /** Total candidate images scanned (uploads + path-based). */
    private int totalScanned;

    /** How many candidate images matched the reference person. */
    private int totalMatched;

    /** Details for every matched image. */
    private List<FileMatchResult> matchedFiles;

    /** Non-fatal warnings / per-file errors collected during processing. */
    private List<String> warnings;

    /** Wall-clock processing time in milliseconds. */
    private long processingTimeMs;
}

