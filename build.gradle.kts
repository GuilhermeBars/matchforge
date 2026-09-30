plugins {
    java
    id("org.springframework.boot") version "3.5.13"
    jacoco
    id("me.champeau.jmh") version "0.7.3"
}

group = "io.github.guilhermebars"
version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    // Only the optional publisher adapter will create producers (a later session).
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
    maxParallelForks = 1
    systemProperty("jqwik.database", layout.buildDirectory.file("jqwik-database").get().asFile.path)
    finalizedBy(tasks.jacocoTestReport)
}
jacoco { toolVersion = "0.8.13" }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}
jmh { threads.set(1); fork.set(1) }
