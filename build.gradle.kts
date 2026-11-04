import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    `maven-publish`
    signing
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

defaultTasks("clean", "build")

val verifyDesignTripwires = tasks.register<Exec>("verifyDesignTripwires") {
    description = "Checks the doc layer: AGENTS.md and design/"
    commandLine("design/verify_design_tripwires.sh")
}
tasks.check { dependsOn(verifyDesignTripwires) }

// `./gradlew koverHtmlReport` merges the coverage of the shipped modules into build/reports/kover/html
dependencies {
    kover(project(":umn-core"))
    kover(project(":umn-sync-tester"))  // its tests exercise umn-core's MVStoreGlobalLog
    kover(project(":umn-proxy-client"))
    kover(project(":umn-proxy-server"))
    kover(project(":umn-proxy-server-adminclient"))
}
kover.reports.filters.excludes {
    packages("com.gitlab.mvysny.umn.sync.tester")  // test batteries, not shipped code
}

allprojects {
    group = "com.gitlab.mvysny.umn"
    version = "0.17"
    repositories {
        mavenCentral()
    }
}

subprojects {

    apply {
        plugin("maven-publish")
        plugin("kotlin")
        plugin("org.gradle.signing")
        plugin("org.jetbrains.kotlinx.kover")
    }

    // the Java 17 API, not the running JDK's; not jvmToolchain(17), which downloads a JDK where none is installed
    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget = JvmTarget.JVM_17
        compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
    }
    tasks.withType<JavaCompile> {
        options.release = 17
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(kotlin("test"))
        // Gradle 9 no longer provides the launcher; the version comes from junit-bom
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            // to see the exceptions of failed tests in CI console.
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    // creates a reusable function which configures proper deployment to Maven Central
    ext["publish"] = { artifactId: String, description: String ->
        java {
            // near-empty on purpose, no Dokka: D_empty_javadoc_jars
            withJavadocJar()
            withSourcesJar()
        }
        // JDK 23+ javadoc bundles web fonts, megabytes per jar; older javadoc rejects the flag
        if (JavaVersion.current() >= JavaVersion.VERSION_23) {
            tasks.withType<Javadoc> {
                (options as StandardJavadocDocletOptions).addBooleanOption("-no-fonts", true)
            }
        }

        publishing {
            publications {
                create("mavenJava", MavenPublication::class.java).apply {
                    groupId = project.group.toString()
                    this.artifactId = artifactId
                    version = project.version.toString()
                    pom {
                        this.description = description
                        name = artifactId
                        url = "https://gitlab.com/mvysny/umn"
                        licenses {
                            license {
                                name = "The Apache Software License, Version 2.0"
                                url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                                distribution = "repo"
                            }
                        }
                        developers {
                            developer {
                                id = "mavi"
                                name = "Martin Vysny"
                                email = "martin@vysny.me"
                            }
                        }
                        scm {
                            url = "https://gitlab.com/mvysny/umn"
                        }
                    }
                    
                    from(components["java"])
                }
            }
        }

        if (project.findProperty("signing.keyId") != null) {
            signing {
                sign(publishing.publications["mavenJava"])
            }
        }
    }
}

nexusPublishing {
    repositories {
        // see https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
