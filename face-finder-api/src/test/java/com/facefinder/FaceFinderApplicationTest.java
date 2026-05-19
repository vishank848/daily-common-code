package com.facefinder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test – verifies the application context loads without errors.
 *
 * NOTE: DJL model downloads happen on first run. This test is intentionally
 * skipped in CI environments that have no network access or GPU/CPU native libs.
 * Remove/adjust @SpringBootTest if you only want unit tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class FaceFinderApplicationTest {

    @Test
    void contextLoads() {
        // If the context starts without exception, the test passes.
    }
}

