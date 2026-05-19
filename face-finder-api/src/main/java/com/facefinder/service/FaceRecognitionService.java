package com.facefinder.service;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import com.facefinder.exception.FaceRecognitionException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the DJL face-detection and face-feature models and exposes helpers for
 * extracting face embeddings and computing cosine similarity.
 *
 * <p>On first run the models are downloaded automatically from the DJL model zoo
 * and cached in ~/.djl.ai/cache.  Subsequent runs use the local cache.
 */
@Slf4j
@Service
public class FaceRecognitionService {

    /** Minimum detector confidence to keep a bounding box. */
    @Value("${face-finder.detection.confidence-threshold:0.85}")
    private float confidenceThreshold;

    private ZooModel<Image, DetectedObjects> detectionModel;
    private ZooModel<Image, float[]> featureModel;

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    @PostConstruct
    public void initialize() {
        try {
            loadDetectionModel();
            loadFeatureModel();
            log.info("✅ Face recognition models ready.");
        } catch (ModelException | IOException e) {
            log.error("❌ Failed to initialise face recognition models: {}", e.getMessage(), e);
            throw new FaceRecognitionException("Face recognition service could not be initialised", e);
        }
    }

    private void loadDetectionModel() throws ModelException, IOException {
        log.info("Loading face detection model (RetinaFace / MXNet) …");
        // RetinaFace is registered in the MXNet model zoo under OBJECT_DETECTION
        // with backbone=mobilenet1.0 and dataset=widerface.
        Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                .optApplication(Application.CV.OBJECT_DETECTION)
                .setTypes(Image.class, DetectedObjects.class)
                .optFilter("backbone", "mobilenet1.0")
                .optFilter("dataset", "widerface")
                .optEngine("MXNet")
                .optProgress(new ProgressBar())
                .build();
        detectionModel = ModelZoo.loadModel(criteria);
        log.info("Face detection model loaded.");
    }

    private void loadFeatureModel() throws ModelException, IOException {
        log.info("Loading face feature model (ArcFace / MXNet) …");
        // The face_feature model is registered in the DJL MXNet model zoo
        // and produces a 512-dimensional ArcFace embedding vector per face crop.
        Criteria<Image, float[]> criteria = Criteria.builder()
                .setTypes(Image.class, float[].class)
                .optGroupId("ai.djl.mxnet")
                .optArtifactId("face_feature")
                .optEngine("MXNet")
                .optProgress(new ProgressBar())
                .build();
        featureModel = ModelZoo.loadModel(criteria);
        log.info("Face feature model loaded.");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Detects every face in the given image file, crops each one, and returns
     * the ArcFace embedding vector for each detected face.
     *
     * @param imagePath path to the image file (JPEG, PNG, BMP, …)
     * @return list of embedding vectors; empty list if no face is detected
     */
    public List<float[]> extractFaceFeatures(Path imagePath)
            throws IOException, ModelException, TranslateException {

        List<float[]> features = new ArrayList<>();
        Image img = ImageFactory.getInstance().fromFile(imagePath);

        try (Predictor<Image, DetectedObjects> detector = detectionModel.newPredictor()) {
            DetectedObjects detections = detector.predict(img);
            List<DetectedObjects.DetectedObject> items = detections.items();

            if (items.isEmpty()) {
                log.warn("No faces detected in: {}", imagePath.getFileName());
                return features;
            }

            log.debug("Detected {} face(s) in: {}", items.size(), imagePath.getFileName());

            try (Predictor<Image, float[]> extractor = featureModel.newPredictor()) {
                for (DetectedObjects.DetectedObject item : items) {
                    if (item.getProbability() < confidenceThreshold) {
                        log.debug("Skipping low-confidence face ({:.2f}) in: {}",
                                item.getProbability(), imagePath.getFileName());
                        continue;
                    }

                    Image face = cropFace(img, item.getBoundingBox());
                    if (face != null) {
                        float[] embedding = extractor.predict(face);
                        features.add(embedding);
                    }
                }
            }
        }
        return features;
    }

    /**
     * Cosine similarity between two embedding vectors – range [−1, 1].
     * A score ≥ 0.6 typically indicates the same person.
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Returns the highest similarity score between {@code candidate} and any
     * vector in {@code referenceFeatures}.
     */
    public double bestSimilarity(List<float[]> referenceFeatures, float[] candidate) {
        return referenceFeatures.stream()
                .mapToDouble(ref -> cosineSimilarity(ref, candidate))
                .max()
                .orElse(0.0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Crops a face sub-image from {@code source} using the bounding-box provided
     * by the detector.  Returns {@code null} when the box is degenerate.
     *
     * <p>DJL bounding-box coordinates are relative (0–1); we convert to pixels
     * and clamp to valid image bounds before cropping.
     */
    private Image cropFace(Image source, BoundingBox bbox) {
        Rectangle rect = bbox.getBounds();
        int imgW = source.getWidth();
        int imgH = source.getHeight();

        int x = clamp((int) (rect.getX() * imgW), 0, imgW - 1);
        int y = clamp((int) (rect.getY() * imgH), 0, imgH - 1);
        int w = clamp((int) (rect.getWidth()  * imgW), 1, imgW - x);
        int h = clamp((int) (rect.getHeight() * imgH), 1, imgH - y);

        if (w <= 0 || h <= 0) {
            log.warn("Degenerate bounding box – skipping face crop.");
            return null;
        }
        return source.getSubImage(x, y, w, h);
    }

    private static int clamp(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PreDestroy
    public void shutdown() {
        if (detectionModel != null) detectionModel.close();
        if (featureModel    != null) featureModel.close();
        log.info("Face recognition models closed.");
    }
}

