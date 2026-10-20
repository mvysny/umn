import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    `maven-publish`
    signing
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
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
    version = "0.16-SNAPSHOT"
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

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget = JvmTarget.JVM_17
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

    // creates a reusable function which configures proper deployment to Bintray
    ext["publish"] = { artifactId: String, description: String ->
        java {
            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<Javadoc> {
            isFailOnError = false
        }
        publishing {
	    repositories {
		maven {
		    setUrl("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
		    credentials {
		        username = project.findProperty("ossrhUsername") as String? ?: "Unknown user"
		        password = project.findProperty("ossrhPassword") as String? ?: "Unknown user"
		    }
		}
	    }
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
