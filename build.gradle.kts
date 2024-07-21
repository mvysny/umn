import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.*

plugins {
    kotlin("jvm") version "2.0.0"
    `maven-publish`
    signing
}

defaultTasks("clean", "build")

allprojects {
    group = "com.gitlab.mvysny.umn"
    version = "0.16-SNAPSHOT"
    repositories {
        mavenCentral()
        //maven(url = "https://dl.bintray.com/mvysny/github")
    }
}

subprojects {

    apply {
        plugin("maven-publish")
        plugin("kotlin")
        plugin("org.gradle.signing")
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget = JvmTarget.JVM_1_8  // retain compatibility with Android
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            // to see the exceptions of failed tests in CI console.
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    // creates a reusable function which configures proper deployment to Bintray
    ext["configureBintray"] = { artifactId: String, description: String ->
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
		        username = project.properties["ossrhUsername"] as String? ?: "Unknown user"
		        password = project.properties["ossrhPassword"] as String? ?: "Unknown user"
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

        if (project.properties["signing.keyId"] != null) {
            signing {
                sign(publishing.publications["mavenJava"])
            }
        }
    }
}
