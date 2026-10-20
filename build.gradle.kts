import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    `maven-publish`
    signing
}

defaultTasks("clean", "build")

val verifyDesignTripwires = tasks.register<Exec>("verifyDesignTripwires") {
    description = "Checks the doc layer: AGENTS.md and design/"
    commandLine("design/verify_design_tripwires.sh")
}
tasks.check { dependsOn(verifyDesignTripwires) }

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
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget = JvmTarget.JVM_17
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependencies {
        // Gradle 9 no longer provides the launcher; the version comes from the junit-bom DynaTest pulls in
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
