package com.facefinder.exception;

/**
 * Thrown when a DJL face-recognition model fails to load or when inference
 * encounters an unrecoverable error.
 */
public class FaceRecognitionException extends RuntimeException {

    public FaceRecognitionException(String message) {
        super(message);
    }

    public FaceRecognitionException(String message, Throwable cause) {
        super(message, cause);
    }
}

