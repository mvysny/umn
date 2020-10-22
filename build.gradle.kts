import com.jfrog.bintray.gradle.BintrayExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

plugins {
    kotlin("jvm") version "1.4.10"
    id("com.jfrog.bintray") version "1.8.3"
    `maven-publish`
    id("org.jetbrains.dokka") version "1.4.0"
}

defaultTasks("clean", "build")

allprojects {
    group = "com.gitlab.mvysny.umn"
    version = "0.16-SNAPSHOT"
    repositories {
        jcenter()
        maven(url = "https://dl.bintray.com/mvysny/github")
    }
}

subprojects {

    apply {
        plugin("maven-publish")
        plugin("kotlin")
        plugin("com.jfrog.bintray")
        plugin("org.jetbrains.dokka")
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.6"  // retain compatibility with Android
        if (System.getProperty("kotlin.jdkHome") != null) {
            kotlinOptions.jdkHome = System.getProperty("kotlin.jdkHome")
        }
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

        val local = Properties()
        val localProperties: File = rootProject.file("local.properties")
        if (localProperties.exists()) {
            localProperties.inputStream().use { local.load(it) }
        }

        val sourceJar = task("sourceJar", Jar::class) {
            dependsOn(tasks["classes"])
            archiveClassifier.set("sources")
            from(sourceSets.main.get().allSource)
        }

        val javadocJar = task("javadocJar", Jar::class) {
            from(tasks["dokkaJavadoc"])
            archiveClassifier.set("javadoc")
        }

        publishing {
            publications {
                create("mavenJava", MavenPublication::class.java).apply {
                    groupId = project.group.toString()
                    this.artifactId = artifactId
                    version = project.version.toString()
                    pom.withXml {
                        val root = asNode()
                        root.appendNode("description", description)
                        root.appendNode("name", artifactId)
                        root.appendNode("url", "https://gitlab.com/mvysny/umn")
                    }
                    from(components.findByName("java")!!)
                    artifact(sourceJar) {
                        classifier = "sources"
                    }
                }
            }
        }

        bintray {
            user = local.getProperty("bintray.user")
            key = local.getProperty("bintray.key")
            pkg(closureOf<BintrayExtension.PackageConfig> {
                repo = "gitlab"
                name = "com.gitlab.mvysny.umn"
                setLicenses("MIT")
                vcsUrl = "https://gitlab.com/mvysny/umn"
                publish = true
                setPublications("mavenJava")
                version(closureOf<BintrayExtension.VersionConfig> {
                    this.name = project.version.toString()
                    released = Date().toString()
                })
            })
        }
    }
}
