package com.imagetotext.controller;

import com.imagetotext.dto.ImageTextResponse;
import com.imagetotext.service.ImageToTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
public class ImageToTextController {

    private final ImageToTextService imageToTextService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "image-to-text-api"));
    }

    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageTextResponse> convert(
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "imagePaths", required = false) List<String> imagePaths,
            @RequestParam(value = "language", required = false) String language) {

        validate(images, imagePaths);

        log.info("OCR request — {} upload(s), {} path(s)", size(images), size(imagePaths));
        return ResponseEntity.ok(imageToTextService.convert(images, imagePaths, language));
    }

    @PostMapping(value = "/convert-by-path", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImageTextResponse> convertByPath(@RequestBody ImagePathRequest body) {
        if (isEmpty(body.imagePaths())) {
            throw new IllegalArgumentException("'imagePaths' must contain at least one path.");
        }

        return ResponseEntity.ok(imageToTextService.convert(null, body.imagePaths(), body.language()));
    }

    private void validate(List<MultipartFile> images, List<String> imagePaths) {
        if (isEmpty(images) && isEmpty(imagePaths)) {
            throw new IllegalArgumentException(
                    "Provide at least one image via 'images' (upload) or 'imagePaths' (path).");
        }
    }

    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    public record ImagePathRequest(List<String> imagePaths, String language) {
    }
}
