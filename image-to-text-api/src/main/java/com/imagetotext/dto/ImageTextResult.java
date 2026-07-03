package com.imagetotext.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageTextResult {

    private String source;
    private String extractedText;
    private int characterCount;
}
