package com.imagetotext.service;

import com.imagetotext.dto.ImageTextResponse;
import com.imagetotext.dto.ImageTextResult;
import com.imagetotext.exception.ImageToTextException;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class ImageToTextService {

    private static final List<String> IMAGE_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".tiff", ".tif", ".webp");

    @Value("${image-to-text.ocr.datapath:}")
    private String dataPath;

    @Value("${image-to-text.ocr.language:eng}")
    private String defaultLanguage;

    public ImageTextResponse convert(
            List<MultipartFile> uploadedImages,
            List<String> imagePaths,
            String language) {

        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        List<ImageTextResult> results = new ArrayList<>();
        StringBuilder aggregatedText = new StringBuilder();

        Path tempDir = createTempDir();
        try {
            List<Path> sources = new ArrayList<>();
            sources.addAll(saveUploads(uploadedImages, tempDir, warnings));
            sources.addAll(resolvePaths(imagePaths, warnings));

            if (sources.isEmpty()) {
                throw new IllegalArgumentException("Provide at least one image via 'images' or 'imagePaths'.");
            }

            for (Path source : sources) {
                String text = extractText(source, resolveLanguage(language));
                results.add(ImageTextResult.builder()
                        .source(source.toAbsolutePath().toString())
                        .extractedText(text)
                        .characterCount(text.length())
                        .build());

                if (aggregatedText.length() > 0) {
                    aggregatedText.append(System.lineSeparator()).append(System.lineSeparator());
                }
                aggregatedText.append("=== ").append(source.getFileName()).append(" ===")
                        .append(System.lineSeparator())
                        .append(text);
            }

            return ImageTextResponse.builder()
                    .success(true)
                    .message("OCR complete.")
                    .aggregatedText(aggregatedText.toString())
                    .results(results)
                    .warnings(warnings)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private String extractText(Path imagePath, String language) {
        try {
            ITesseract tesseract = new Tesseract();
            if (dataPath != null && !dataPath.isBlank()) {
                tesseract.setDatapath(dataPath.trim());
            }
            tesseract.setLanguage(language);
            String text = tesseract.doOCR(imagePath.toFile());
            return text == null ? "" : text.trim();
        } catch (TesseractException e) {
            throw new ImageToTextException("Could not read text from " + imagePath.getFileName(), e);
        }
    }

    private String resolveLanguage(String requestedLanguage) {
        if (requestedLanguage == null || requestedLanguage.isBlank()) {
            return defaultLanguage;
        }
        return requestedLanguage.trim();
    }

    private List<Path> saveUploads(List<MultipartFile> files, Path dir, List<String> warnings) {
        List<Path> paths = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return paths;
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "upload");
            if (!isImageFile(originalName)) {
                warnings.add("Skipped non-image upload: " + originalName);
                continue;
            }

            try {
                Path dest = dir.resolve("upload_" + java.util.UUID.randomUUID() + "_" + originalName);
                file.transferTo(dest);
                paths.add(dest);
            } catch (IOException e) {
                log.warn("Could not save upload {}: {}", originalName, e.getMessage());
                warnings.add("Failed to save upload [" + originalName + "]: " + e.getMessage());
            }
        }
        return paths;
    }

    private List<Path> resolvePaths(List<String> pathStrings, List<String> warnings) {
        List<Path> result = new ArrayList<>();
        if (pathStrings == null) {
            return result;
        }

        for (String rawPath : pathStrings) {
            if (rawPath == null || rawPath.isBlank()) {
                continue;
            }

            Path path = Path.of(rawPath.trim());
            if (!Files.exists(path)) {
                warnings.add("Path not found: " + rawPath);
            } else if (Files.isRegularFile(path)) {
                collectSingleFile(path, rawPath, result, warnings);
            } else if (Files.isDirectory(path)) {
                walkDirectory(path, rawPath, result, warnings);
            }
        }
        return result;
    }

    private void collectSingleFile(Path file, String original, List<Path> result, List<String> warnings) {
        if (isImageFile(file.getFileName().toString())) {
            result.add(file);
        } else {
            warnings.add("Not an image file: " + original);
        }
    }

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
                    warnings.add("Cannot access: " + file + " - " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("Directory walk failed for " + original + ": " + e.getMessage());
        }
    }

    private boolean isImageFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private Path createTempDir() {
        try {
            return Files.createTempDirectory("image-to-text-");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create temp directory", e);
        }
    }

    private void deleteQuietly(Path dir) {
        try {
            FileUtils.deleteDirectory(dir.toFile());
        } catch (IOException e) {
            log.warn("Could not delete temp dir {}: {}", dir, e.getMessage());
        }
    }
}
