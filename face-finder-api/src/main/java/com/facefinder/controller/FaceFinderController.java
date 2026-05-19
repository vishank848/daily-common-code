package com.facefinder.controller;

import com.facefinder.dto.FaceSearchResponse;
import com.facefinder.service.FaceFinderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the face-finder API.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/faces/health}   — liveness probe</li>
 *   <li>{@code POST /api/faces/search}   — main search endpoint (multipart)</li>
 *   <li>{@code POST /api/faces/search-by-path} — path-only search (JSON body)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/faces")
@RequiredArgsConstructor
public class FaceFinderController {

    private final FaceFinderService faceFinderService;

    // -------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "face-finder-api"));
    }

    // -------------------------------------------------------------------------
    // Main search endpoint  (multipart/form-data)
    // -------------------------------------------------------------------------

    /**
     * Find images that contain the same person as the reference photo(s) and
     * move/copy matched files to an output directory.
     *
     * <h3>Form parameters</h3>
     * <table border="1">
     *   <tr><th>Field</th><th>Type</th><th>Required</th><th>Description</th></tr>
     *   <tr><td>referenceImages</td><td>file[]</td><td>No*</td>
     *       <td>Uploaded reference photos of the target person</td></tr>
     *   <tr><td>referencePaths</td><td>string[]</td><td>No*</td>
     *       <td>Server-side file paths to reference photos (* at least one source is required)</td></tr>
     *   <tr><td>searchImages</td><td>file[]</td><td>No†</td>
     *       <td>Uploaded images to scan</td></tr>
     *   <tr><td>searchPaths</td><td>string[]</td><td>No†</td>
     *       <td>Server-side file/directory paths to scan († at least one source is required)</td></tr>
     *   <tr><td>outputDirectory</td><td>string</td><td>No</td>
     *       <td>Where to move matched files. Auto-generated when omitted.</td></tr>
     *   <tr><td>similarityThreshold</td><td>double</td><td>No</td>
     *       <td>Cosine-similarity cut-off 0–1 (default 0.6)</td></tr>
     *   <tr><td>moveFiles</td><td>boolean</td><td>No</td>
     *       <td>Move matched files (true, default) or copy them (false)</td></tr>
     * </table>
     */
    @PostMapping(value = "/search", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FaceSearchResponse> search(
            @RequestPart(value = "referenceImages", required = false) List<MultipartFile> referenceImages,
            @RequestParam(value = "referencePaths",  required = false) List<String> referencePaths,
            @RequestPart(value = "searchImages",    required = false) List<MultipartFile> searchImages,
            @RequestParam(value = "searchPaths",    required = false) List<String> searchPaths,
            @RequestParam(value = "outputDirectory", required = false) String outputDirectory,
            @RequestParam(value = "similarityThreshold", required = false) Double similarityThreshold,
            @RequestParam(value = "moveFiles", defaultValue = "true") boolean moveFiles) {

        validate(referenceImages, referencePaths, searchImages, searchPaths);

        log.info("Search request — refs: {} uploads + {} paths | candidates: {} uploads + {} paths | threshold: {}",
                size(referenceImages), size(referencePaths),
                size(searchImages), size(searchPaths),
                similarityThreshold);

        FaceSearchResponse response = faceFinderService.search(
                referenceImages, referencePaths,
                searchImages, searchPaths,
                outputDirectory, similarityThreshold, moveFiles);

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Path-only endpoint  (application/json)
    // -------------------------------------------------------------------------

    /**
     * Same logic as {@code /search} but accepts a JSON body for purely server-side
     * path-based operations (no file upload needed).
     *
     * <pre>{@code
     * POST /api/faces/search-by-path
     * {
     *   "referencePaths":       ["/photos/person/ref1.jpg", "/photos/person/ref2.jpg"],
     *   "searchPaths":          ["/photos/album/2024/", "/photos/album/2025/"],
     *   "outputDirectory":      "/photos/matches",
     *   "similarityThreshold":  0.65,
     *   "moveFiles":            true
     * }
     * }</pre>
     */
    @PostMapping(value = "/search-by-path", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FaceSearchResponse> searchByPath(@RequestBody PathSearchRequest body) {

        if (isEmpty(body.referencePaths())) {
            throw new IllegalArgumentException("'referencePaths' must contain at least one path.");
        }
        if (isEmpty(body.searchPaths())) {
            throw new IllegalArgumentException("'searchPaths' must contain at least one path.");
        }

        log.info("Path-search request — {} ref path(s), {} search path(s)",
                body.referencePaths().size(), body.searchPaths().size());

        FaceSearchResponse response = faceFinderService.search(
                null, body.referencePaths(),
                null, body.searchPaths(),
                body.outputDirectory(), body.similarityThreshold(), body.moveFiles());

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private void validate(List<MultipartFile> refImages, List<String> refPaths,
                          List<MultipartFile> searchImages, List<String> searchPaths) {
        if (isEmpty(refImages) && isEmpty(refPaths)) {
            throw new IllegalArgumentException(
                    "Provide at least one reference image via 'referenceImages' (upload) or 'referencePaths' (path).");
        }
        if (isEmpty(searchImages) && isEmpty(searchPaths)) {
            throw new IllegalArgumentException(
                    "Provide at least one search source via 'searchImages' (upload) or 'searchPaths' (path).");
        }
    }

    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    // -------------------------------------------------------------------------
    // Inner record for JSON body
    // -------------------------------------------------------------------------

    /**
     * JSON request body for {@code /search-by-path}.
     */
    public record PathSearchRequest(
            List<String> referencePaths,
            List<String> searchPaths,
            String outputDirectory,
            Double similarityThreshold,
            boolean moveFiles) {
    }
}

