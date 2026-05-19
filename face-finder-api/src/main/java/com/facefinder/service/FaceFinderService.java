package com.facefinder.service;

import com.facefinder.dto.FaceSearchResponse;
import com.facefinder.dto.FileMatchResult;
import ai.djl.ModelException;
import ai.djl.translate.TranslateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Orchestrates the full face-search pipeline:
 * <pre>
 *   1. Save uploaded reference/search images to a temp directory.
 *   2. Extract face embeddings for every reference image.
 *   3. Walk all candidate images (uploaded files + file-system paths).
 *   4. For each candidate, detect faces and compare against reference embeddings.
 *   5. When a match is found, move (or copy) the file to the output directory.
 *   6. Return a structured {@link FaceSearchResponse}.
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaceFinderService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".tiff", ".tif", ".webp");

    private final FaceRecognitionService recognitionService;

    @Value("${face-finder.output.base-dir:output}")
    private String outputBaseDir;

    @Value("${face-finder.similarity-threshold:0.6}")
    private double defaultSimilarityThreshold;

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Runs the full face-find-and-move pipeline.
     *
     * @param referenceImages   uploaded photos of the target person (required if {@code referencePaths} is empty)
     * @param referencePaths    file-system paths to reference photos (alternative / supplement to uploads)
     * @param searchImages      uploaded images to search through (optional)
     * @param searchPaths       file or directory paths to search (optional)
     * @param outputDirectory   destination dir for matched files; auto-generated when blank
     * @param similarityThreshold cosine-similarity cut-off (0–1); uses config default when null
     * @param moveFiles         move files when true, copy when false
     */
    public FaceSearchResponse search(
            List<MultipartFile> referenceImages,
            List<String>        referencePaths,
            List<MultipartFile> searchImages,
            List<String>        searchPaths,
            String              outputDirectory,
            Double              similarityThreshold,
            boolean             moveFiles) {

        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        List<FileMatchResult> matchedFiles = new ArrayList<>();
        double threshold = (similarityThreshold != null) ? similarityThreshold : defaultSimilarityThreshold;

        Path tempDir = createTempDir();
        try {
            // ---- 1 & 2. Collect reference image paths -----------------------
            List<Path> referencePaths2 = new ArrayList<>();
            referencePaths2.addAll(saveUploads(referenceImages, tempDir, "ref_", warnings));
            referencePaths2.addAll(resolveFilePaths(referencePaths, warnings));

            if (referencePaths2.isEmpty()) {
                return errorResponse("No reference images were provided or could be read.", startTime);
            }

            // ---- 3. Extract embeddings from all reference images ------------
            EmbeddingExtraction extraction = extractReferenceEmbeddings(referencePaths2, warnings);
            if (extraction.embeddings().isEmpty()) {
                return errorResponse(
                        "No faces could be extracted from any reference image. " +
                        "Ensure the images contain clearly visible faces.", startTime);
            }
            log.info("Reference embeddings ready: {} vector(s) from {} image(s)",
                    extraction.embeddings().size(), extraction.imagesProcessed());

            // ---- 4. Collect candidate image paths ---------------------------
            List<Path> candidates = new ArrayList<>();
            candidates.addAll(saveUploads(searchImages, tempDir, "search_", warnings));
            candidates.addAll(collectFromPaths(searchPaths, warnings));

            if (candidates.isEmpty()) {
                return errorResponse(
                        "No search images were provided. Supply 'searchImages' files or 'searchPaths' path(s).",
                        startTime);
            }

            // ---- 5. Resolve output directory --------------------------------
            Path outDir = resolveOutputDir(outputDirectory);
            log.info("Output directory: {}", outDir);

            // ---- 6. Compare each candidate ----------------------------------
            for (Path candidate : candidates) {
                processCandidate(candidate, extraction.embeddings(), threshold, outDir, moveFiles, matchedFiles, warnings);
            }

            return FaceSearchResponse.builder()
                    .success(true)
                    .message(matchedFiles.isEmpty()
                            ? "Search complete – no matching images found."
                            : "Search complete – " + matchedFiles.size() + " matching image(s) found.")
                    .outputDirectory(outDir.toAbsolutePath().toString())
                    .referenceImagesProcessed(extraction.imagesProcessed())
                    .totalScanned(candidates.size())
                    .totalMatched(matchedFiles.size())
                    .matchedFiles(matchedFiles)
                    .warnings(warnings)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } finally {
            deleteQuietly(tempDir);
        }
    }

    /**
     * Iterates over reference image paths, runs face detection + embedding on each,
     * and returns the collected embeddings alongside a count of successfully processed images.
     */
    private EmbeddingExtraction extractReferenceEmbeddings(List<Path> paths, List<String> warnings) {
        List<float[]> embeddings = new ArrayList<>();
        int processed = 0;
        for (Path refPath : paths) {
            try {
                List<float[]> embs = recognitionService.extractFaceFeatures(refPath);
                if (embs.isEmpty()) {
                    warnings.add("No face detected in reference image: " + refPath.getFileName());
                } else {
                    embeddings.addAll(embs);
                    processed++;
                }
            } catch (IOException | ModelException | TranslateException e) {
                log.warn("Could not process reference image {}: {}", refPath, e.getMessage());
                warnings.add("Reference image error [" + refPath.getFileName() + "]: " + e.getMessage());
            }
        }
        return new EmbeddingExtraction(embeddings, processed);
    }

    /**
     * Runs face detection + similarity check on one candidate image and, when the
     * best score meets the threshold, moves/copies it to the output directory.
     */
    private void processCandidate(Path candidate, List<float[]> referenceEmbeddings,
                                   double threshold, Path outDir, boolean moveFiles,
                                   List<FileMatchResult> matchedFiles, List<String> warnings) {
        try {
            List<float[]> faceEmbeddings = recognitionService.extractFaceFeatures(candidate);
            if (faceEmbeddings.isEmpty()) {
                log.debug("No face in candidate image: {}", candidate.getFileName());
                return;
            }

            double best = faceEmbeddings.stream()
                    .mapToDouble(fe -> recognitionService.bestSimilarity(referenceEmbeddings, fe))
                    .max()
                    .orElse(0.0);

            log.debug("Candidate {} — best similarity: {:.4f}", candidate.getFileName(), best);

            if (best >= threshold) {
                Path dest = transferFile(candidate, outDir, moveFiles, warnings);
                matchedFiles.add(FileMatchResult.builder()
                        .originalPath(candidate.toAbsolutePath().toString())
                        .outputPath(dest != null ? dest.toAbsolutePath().toString() : null)
                        .similarity(best)
                        .facesDetected(faceEmbeddings.size())
                        .moved(moveFiles && dest != null)
                        .build());
            }
        } catch (IOException | ModelException | TranslateException e) {
            log.warn("Could not process candidate {}: {}", candidate, e.getMessage());
            warnings.add("Candidate error [" + candidate.getFileName() + "]: " + e.getMessage());
        }
    }

    /** Carries the result of the reference-embedding phase. */
    private record EmbeddingExtraction(List<float[]> embeddings, int imagesProcessed) {}

    // -------------------------------------------------------------------------
    // Helpers – file I/O
    // -------------------------------------------------------------------------

    /** Saves each uploaded MultipartFile to {@code dir} and returns their paths. */
    private List<Path> saveUploads(List<MultipartFile> files, Path dir, String prefix, List<String> warnings) {
        List<Path> paths = new ArrayList<>();
        if (files == null || files.isEmpty()) return paths;

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "upload");
            if (!isImageFile(originalName)) {
                warnings.add("Skipped non-image upload: " + originalName);
            } else {
                try {
                    Path dest = dir.resolve(prefix + UUID.randomUUID() + "_" + originalName);
                    file.transferTo(dest);
                    paths.add(dest);
                } catch (IOException e) {
                    log.warn("Could not save upload {}: {}", originalName, e.getMessage());
                    warnings.add("Failed to save upload [" + originalName + "]: " + e.getMessage());
                }
            }
        }
        return paths;
    }

    /** Resolves explicit file-path strings (files only, non-recursive). */
    private List<Path> resolveFilePaths(List<String> pathStrings, List<String> warnings) {
        List<Path> result = new ArrayList<>();
        if (pathStrings == null) return result;
        for (String ps : pathStrings) {
            if (ps == null || ps.isBlank()) continue;
            Path p = Path.of(ps.trim());
            if (!Files.exists(p)) {
                warnings.add("Path not found: " + ps);
            } else if (Files.isRegularFile(p) && isImageFile(p.getFileName().toString())) {
                result.add(p);
            } else {
                warnings.add("Path is not a supported image file: " + ps);
            }
        }
        return result;
    }

    /**
     * Expands a list of file/directory paths into individual image file paths.
     * Directories are walked recursively.
     */
    private List<Path> collectFromPaths(List<String> pathStrings, List<String> warnings) {
        List<Path> result = new ArrayList<>();
        if (pathStrings == null) return result;

        for (String ps : pathStrings) {
            if (ps != null && !ps.isBlank()) {
                collectFromSinglePath(ps.trim(), result, warnings);
            }
        }
        return result;
    }

    /** Resolves one raw path string and appends matching image paths to {@code result}. */
    private void collectFromSinglePath(String ps, List<Path> result, List<String> warnings) {
        Path p = Path.of(ps);
        if (!Files.exists(p)) {
            warnings.add("Search path not found: " + ps);
        } else if (Files.isRegularFile(p)) {
            collectSingleFile(p, ps, result, warnings);
        } else if (Files.isDirectory(p)) {
            walkDirectory(p, ps, result, warnings);
        }
    }

    /** Adds {@code file} to {@code result} if it is a supported image, otherwise warns. */
    private void collectSingleFile(Path file, String original, List<Path> result, List<String> warnings) {
        if (isImageFile(file.getFileName().toString())) {
            result.add(file);
        } else {
            warnings.add("Not an image file: " + original);
        }
    }

    /** Recursively walks {@code dir} and collects all supported image files. */
    private void walkDirectory(Path dir, String original, List<Path> result, List<String> warnings) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isImageFile(file.getFileName().toString())) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    warnings.add("Cannot access: " + file + " – " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("Directory walk failed for " + original + ": " + e.getMessage());
        }
    }

    /** Moves or copies a file to the output directory. Returns the destination path, or null on failure. */
    private Path transferFile(Path source, Path outDir, boolean move, List<String> warnings) {
        try {
            Path dest = uniqueDest(outDir, source.getFileName().toString());
            if (move) {
                Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
                log.info("Moved  {} → {}", source.getFileName(), dest);
            } else {
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied {} → {}", source.getFileName(), dest);
            }
            return dest;
        } catch (IOException e) {
            log.warn("File transfer failed for {}: {}", source, e.getMessage());
            warnings.add("Could not transfer [" + source.getFileName() + "]: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers – paths / directories
    // -------------------------------------------------------------------------

    private Path resolveOutputDir(String outputDirectory) {
        Path dir;
        if (outputDirectory != null && !outputDirectory.isBlank()) {
            dir = Path.of(outputDirectory.trim());
        } else {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            dir = Path.of(outputBaseDir, "faces_" + ts);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create output directory: " + dir, e);
        }
        return dir;
    }

    private Path createTempDir() {
        try {
            return Files.createTempDirectory("face-finder-");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create temp directory", e);
        }
    }

    /** Returns a path inside {@code dir} that does not yet exist, appending a counter if needed. */
    private Path uniqueDest(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) return candidate;

        String name = fileName;
        String ext  = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            name = fileName.substring(0, dot);
            ext  = fileName.substring(dot);
        }
        int counter = 1;
        do {
            candidate = dir.resolve(name + "_" + counter++ + ext);
        } while (Files.exists(candidate));
        return candidate;
    }

    private boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private void deleteQuietly(Path dir) {
        try {
            FileUtils.deleteDirectory(dir.toFile());
        } catch (IOException e) {
            log.warn("Could not delete temp dir {}: {}", dir, e.getMessage());
        }
    }

    private FaceSearchResponse errorResponse(String message, long startTime) {
        return FaceSearchResponse.builder()
                .success(false)
                .message(message)
                .matchedFiles(List.of())
                .warnings(List.of())
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }
}

