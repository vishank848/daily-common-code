package com.facefinder.exception;

import com.facefinder.dto.FaceSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<FaceSearchResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(FaceSearchResponse.builder()
                        .success(false)
                        .message("File size exceeds the allowed limit. Check spring.servlet.multipart.max-file-size.")
                        .warnings(List.of(ex.getMessage()))
                        .build());
    }

    @ExceptionHandler(FaceRecognitionException.class)
    public ResponseEntity<FaceSearchResponse> handleFaceRecognition(FaceRecognitionException ex) {
        log.error("Face recognition error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(FaceSearchResponse.builder()
                        .success(false)
                        .message("Face recognition service error: " + ex.getMessage())
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<FaceSearchResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(FaceSearchResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FaceSearchResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(FaceSearchResponse.builder()
                        .success(false)
                        .message("Internal server error: " + ex.getMessage())
                        .build());
    }
}

