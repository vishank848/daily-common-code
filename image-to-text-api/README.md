# Image to Text API

A Spring Boot REST API that converts images into text using OCR.

## Endpoints

- `GET /api/ocr/health`
- `POST /api/ocr/convert`
- `POST /api/ocr/convert-by-path`

## OCR engine

This module uses **Tesseract** through **Tess4J**.

Set `image-to-text.ocr.datapath` to the folder that contains `tessdata`, and
use `image-to-text.ocr.language` to select the OCR language (default: `eng`).
