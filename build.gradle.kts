plugins {
    java
    id("org.springframework.boot") version "3.5.13"
    jacoco
    id("me.champeau.jmh") version "0.7.3"
    id("com.diffplug.spotless") version "8.10.3"
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
    // Only the optional publisher adapter creates producers.
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
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
jmh {
    threads.set(1)
    fork.set(1)
    warmupIterations.set(2)
    iterations.set(3)
    warmup.set("1s")
    timeOnIteration.set("1s")
    jvmArgs.set(listOf("-Xms256m", "-Xmx256m"))
    resultFormat.set("JSON")
    includeTests.set(false)
    failOnError.set(true)
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
}

val loadtest = sourceSets.create("loadtest")
tasks.register<JavaExec>("loadTest") {
    description = "Drive an existing REST server: -PbaseUrl=... -Pclients=8 -Pseconds=30"
    group = "verification"
    classpath = loadtest.runtimeClasspath
    mainClass.set("io.github.guilhermebars.matchforge.LoadGenerator")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    args(providers.gradleProperty("baseUrl").getOrElse("http://localhost:8080"),
        providers.gradleProperty("clients").getOrElse("8"),
        providers.gradleProperty("seconds").getOrElse("30"))
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target("src/*/java/**/*.java")
        forbidWildcardImports()
        palantirJavaFormat("2.80.0")
        removeUnusedImports()
    }
}
tasks.check { dependsOn("spotlessCheck", "compileJmhJava", "loadtestClasses") }
tasks.bootJar { archiveFileName.set("matchforge.jar") }
