# Face Finder API

A Spring Boot REST API that uses **Deep Java Library (DJL)** with a **PyTorch** backend to:

1. Accept one or more **reference photos** of a target person (uploaded files or server-side paths).
2. Scan a set of **search images or directories** for images containing that same person.
3. **Move** (or copy) every matching image into an output folder.

---

## Tech Stack

| Component        | Version  |
|-----------------|----------|
| Java            | 21       |
| Spring Boot     | 3.5.3    |
| Gradle          | 8.13     |
| DJL (ai.djl)   | 0.33.0   |
| MXNet Engine    | auto (1.9.1 native, downloaded on first run) |

---

## How It Works

```
Reference images  ──►  Face detection  ──►  ArcFace embedding vectors
                                                     │
                                                     ▼
Search images/dirs ──►  Face detection  ──►  ArcFace embedding  ──►  Cosine similarity
                                                                          │
                                              similarity ≥ threshold? ──► Move/copy to output
```

- **Face detection**: RetinaFace via MXNet (downloaded automatically from the DJL MXNet model zoo — filter: backbone=`mobilenet1.0`, dataset=`widerface`).
- **Face recognition**: ArcFace (`face_feature` model) from the DJL MXNet model zoo — produces a 512-d embedding vector per face.
- **Matching**: cosine similarity between every reference embedding and every candidate face embedding.
- **Threshold**: default `0.6` (configurable per request or in `application.yml`).

> **First-run note**: DJL will download the MXNet native library (~200 MB) and the model files for
> RetinaFace + ArcFace on first start and cache them in `~/.djl.ai/cache`.  Subsequent starts use the local cache.
> You can pre-bundle the native lib in `build.gradle.kts` by uncommenting the platform-specific line.

---

## Quick Start

### Prerequisites
- Java 21 installed and on `PATH`
- Internet access (first run only, for model download)

### Run

```bash
# Windows
gradlew.bat bootRun

# macOS / Linux
./gradlew bootRun
```

The API starts on `http://localhost:8080`.

---

## API Reference

### `GET /api/faces/health`

Liveness probe.

**Response**:
```json
{ "status": "UP", "service": "face-finder-api" }
```

---

### `POST /api/faces/search` — multipart/form-data

| Field                | Type       | Required | Description |
|---------------------|-----------|----------|-------------|
| `referenceImages`   | file[]    | *        | Uploaded reference photos of the target person |
| `referencePaths`    | string[]  | *        | Server-side file paths to reference photos |
| `searchImages`      | file[]    | †        | Uploaded images to scan |
| `searchPaths`       | string[]  | †        | Server-side file or directory paths to scan |
| `outputDirectory`   | string    | No       | Where to move matched files (auto-generated if omitted) |
| `similarityThreshold` | double  | No       | Cosine similarity cut-off 0–1 (default `0.6`) |
| `moveFiles`         | boolean   | No       | `true` = move (default), `false` = copy |

\* At least one reference source is required.  
† At least one search source is required.

**Example (curl)**:
```bash
curl -X POST http://localhost:8080/api/faces/search \
  -F "referenceImages=@/photos/john_doe.jpg" \
  -F "searchImages=@/photos/party1.jpg" \
  -F "searchImages=@/photos/party2.jpg" \
  -F "searchPaths=/photos/vacation/" \
  -F "outputDirectory=/photos/john_matches" \
  -F "similarityThreshold=0.65" \
  -F "moveFiles=false"
```

---

### `POST /api/faces/search-by-path` — application/json

For purely server-side operations (no file upload).

```json
{
  "referencePaths":      ["/photos/person/ref1.jpg", "/photos/person/ref2.jpg"],
  "searchPaths":         ["/photos/album/2024/", "/photos/album/2025/"],
  "outputDirectory":     "/photos/matches",
  "similarityThreshold": 0.65,
  "moveFiles":           true
}
```

---

### Response Schema

```json
{
  "success":                   true,
  "message":                   "Search complete – 3 matching image(s) found.",
  "outputDirectory":           "/photos/matches",
  "referenceImagesProcessed":  2,
  "totalScanned":              50,
  "totalMatched":              3,
  "processingTimeMs":          4213,
  "warnings":                  [],
  "matchedFiles": [
    {
      "originalPath":  "/photos/album/2024/group_photo.jpg",
      "outputPath":    "/photos/matches/group_photo.jpg",
      "similarity":    0.812,
      "facesDetected": 4,
      "moved":         true
    }
  ]
}
```

---

## Configuration (`application.yml`)

```yaml
face-finder:
  similarity-threshold: 0.6      # global default; override per request
  output:
    base-dir: output             # parent for auto-generated output dirs
  detection:
    confidence-threshold: 0.85  # min detector confidence to keep a bounding box

spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 500MB
```

---

## Project Structure

```
face-finder-api/
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/java/com/facefinder/
    ├── FaceFinderApplication.java
    ├── config/
    │   └── WebConfig.java               # CORS configuration
    ├── controller/
    │   └── FaceFinderController.java    # REST endpoints
    ├── dto/
    │   ├── FaceSearchResponse.java
    │   └── FileMatchResult.java
    ├── exception/
    │   └── GlobalExceptionHandler.java  # unified error responses
    └── service/
        ├── FaceRecognitionService.java  # DJL model loading & inference
        └── FaceFinderService.java       # orchestration & file I/O
```

---

## Tips

- **Multiple reference photos of the same person** improve accuracy.  
  All embeddings from all reference images are collected and the best match wins.
- **Lower threshold** (e.g. `0.5`) = more permissive / more false positives.  
  **Higher threshold** (e.g. `0.75`) = stricter / fewer false positives.
- **Directories are walked recursively**, so pointing `searchPaths` at a top-level album folder will scan all sub-directories.
- Supported image formats: `.jpg`, `.jpeg`, `.png`, `.bmp`, `.gif`, `.tiff`, `.tif`, `.webp`.

