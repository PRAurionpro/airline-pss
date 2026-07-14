import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "pss"
version = "0.3.3-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

dependencies {
    // Paved road: tenant context, structured JSON logging, OpenTelemetry tracing, /actuator/ready.
    implementation(project(":libs:paved-road"))
    // Shared singleton-Postgres IT base (com.pss.platform.testsupport.AbstractPostgresIT).
    testImplementation(testFixtures(project(":libs:paved-road")))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Observability: OpenTelemetry tracing (fitness check_otel — every Spring Boot service ships tracing).
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    // L1+: validate emitted Loyalty events against loyalty-events_schema.json in CI.
    testImplementation("com.networknt:json-schema-validator:1.5.2")
}

// L1+: vendor the canonical loyalty-events schema onto the test classpath so schema-conformance
// tests validate against docs/contracts/loyalty-events_schema.json (single source of truth —
// copied each build, never a committed duplicate that could drift).
val copyEventSchemas by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("../docs/contracts/loyalty-events_schema.json"))
    into(layout.buildDirectory.dir("generated/contract-schemas/contracts"))
}
sourceSets.named("test") {
    resources.srcDir(layout.buildDirectory.dir("generated/contract-schemas"))
}
tasks.named("processTestResources") {
    dependsOn(copyEventSchemas)
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "21"
    }
}
tasks.withType<Test> {
    useJUnitPlatform()
}
