package com.imagetotext.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImageTextResponse {

    private boolean success;
    private String message;
    private String aggregatedText;
    private List<ImageTextResult> results;
    private List<String> warnings;
    private long processingTimeMs;
}
