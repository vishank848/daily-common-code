package com.facefinder.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Represents one image that matched the reference person.
 */
@Data
@Builder
public class FileMatchResult {

    /** Absolute path of the original image on disk. */
    private String originalPath;

    /** Absolute path after the file was moved/copied (null if moveFiles=false). */
    private String outputPath;

    /** Highest cosine similarity score found between any reference face and any face in this image (0–1). */
    private double similarity;

    /** Number of faces detected in this image. */
    private int facesDetected;

    /** Whether the file was moved (true) or copied (false). */
    private boolean moved;
}

