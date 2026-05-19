plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.facefinder"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val djlVersion = "0.33.0"
val commonsIoVersion = "2.18.0"
val mxnetNativeVersion = "1.9.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // DJL (Deep Java Library) — face detection & recognition
    implementation(platform("ai.djl:bom:${djlVersion}"))
    implementation("ai.djl:api")

    // MXNet engine + model zoo — provides RetinaFace (face detection)
    // and face_feature (ArcFace embeddings) from the DJL model zoo.
    // DJL auto-downloads the MXNet native binary (~200 MB) on first run
    // and caches it in ~/.djl.ai/cache.
    implementation("ai.djl.mxnet:mxnet-engine")
    implementation("ai.djl.mxnet:mxnet-model-zoo")
    // Optional: pre-bundle native lib for a specific platform
    // runtimeOnly("ai.djl.mxnet:mxnet-native-mkl:${mxnetNativeVersion}:linux-x86_64")
    // runtimeOnly("ai.djl.mxnet:mxnet-native-mkl:${mxnetNativeVersion}:win-x86_64")

    // Apache Commons IO — file move/copy helpers
    implementation("commons-io:commons-io:${commonsIoVersion}")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

