import java.io.File
import java.security.MessageDigest
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    id("com.android.library") version "8.10.1" apply false
}

group = "io.github.leon-jakob-schneider.kpal"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()

val repoUrl = "https://github.com/leon-jakob-schneider/kpal"
val developerId = "leon-jakob-schneider"
val developerName = "Leon Jakob Schneider"
val developerProfileUrl = "https://github.com/leon-jakob-schneider"
val pomLicenseName = providers.gradleProperty("pomLicenseName").orElse("Proprietary")
val pomLicenseUrl = providers.gradleProperty("pomLicenseUrl").orElse(repoUrl)
val signingKey = providers.environmentVariable("GPG_PRIVATE_KEY")
    .orElse(providers.gradleProperty("signingKey"))
val signingPassword = providers.environmentVariable("GPG_PASSPHRASE")
    .orElse(providers.gradleProperty("signingPassword"))
val centralRepoDir = layout.buildDirectory.dir("central-staging")

data class ModuleSpec(
    val artifactId: String,
    val description: String,
    val namespace: String,
)

val moduleSpecs = mapOf(
    "audio" to ModuleSpec(
        artifactId = "audio",
        description = "Shared Kotlin Multiplatform audio diagnostics and audio I/O abstractions.",
        namespace = "app.miso.audio",
    ),
    "device" to ModuleSpec(
        artifactId = "device",
        description = "Shared Kotlin Multiplatform device facade built on top of the audio module.",
        namespace = "app.miso.device",
    ),
)

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    val spec = moduleSpecs.getValue(name)

    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "com.android.library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    extensions.configure<KotlinMultiplatformExtension> {
        androidTarget {
            publishLibraryVariants("release")
        }
        iosArm64()
        iosSimulatorArm64()
        iosX64()

        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }

        sourceSets {
            val commonMain by getting {
                kotlin.srcDir(projectDir.resolve("src"))
            }
            val androidMain by getting {
                kotlin.srcDir(projectDir.resolve("src@android"))
            }
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(commonMain)
                kotlin.srcDir(projectDir.resolve("src@ios"))
            }
            named("iosArm64Main") {
                dependsOn(iosMain)
            }
            named("iosSimulatorArm64Main") {
                dependsOn(iosMain)
            }
            named("iosX64Main") {
                dependsOn(iosMain)
            }
        }
    }

    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = spec.namespace
        compileSdk = 36

        defaultConfig {
            minSdk = 23
        }

        sourceSets.named("main") {
            manifest.srcFile(projectDir.resolve("src@android/AndroidManifest.xml"))
        }
    }

    val javadocJar = tasks.register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
        from(rootProject.file("../README.md"))
    }

    extensions.configure<org.gradle.api.publish.PublishingExtension> {
        repositories {
            maven {
                name = "staging"
                url = centralRepoDir.get().asFile.toURI()
            }
        }

        publications.withType(MavenPublication::class.java).configureEach {
            if (artifactId == "kotlinMultiplatform") {
                artifactId = spec.artifactId
            }

            artifact(javadocJar)

            pom {
                name.set("${project.group}:${this@configureEach.artifactId}")
                description.set(spec.description)
                url.set(repoUrl)

                licenses {
                    license {
                        name.set(pomLicenseName)
                        url.set(pomLicenseUrl)
                    }
                }

                developers {
                    developer {
                        id.set(developerId)
                        name.set(developerName)
                        url.set(developerProfileUrl)
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/leon-jakob-schneider/kpal.git")
                    developerConnection.set("scm:git:ssh://git@github.com/leon-jakob-schneider/kpal.git")
                    url.set(repoUrl)
                }
            }
        }
    }

    extensions.configure<SigningExtension> {
        val key = signingKey.orNull
        val password = signingPassword.orNull
        if (!key.isNullOrBlank()) {
            useInMemoryPgpKeys(key, password)
            sign(extensions.getByType<org.gradle.api.publish.PublishingExtension>().publications)
        }
    }
}

fun File.hexDigest(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val publishToStaging = tasks.register("publishToCentralStaging") {
    dependsOn(subprojects.map { it.tasks.named("publishAllPublicationsToStagingRepository") })
}

val generateCentralChecksums = tasks.register("generateCentralChecksums") {
    dependsOn(publishToStaging)

    doLast {
        val repoRoot = centralRepoDir.get().asFile
        repoRoot.walkTopDown()
            .filter(File::isFile)
            .filterNot { file ->
                file.name.endsWith(".md5") ||
                    file.name.endsWith(".sha1") ||
                    file.name.endsWith(".sha256") ||
                    file.name.endsWith(".sha512")
            }
            .forEach { file ->
                file.resolveSibling("${file.name}.md5").writeText("${file.hexDigest("MD5")}\n")
                file.resolveSibling("${file.name}.sha1").writeText("${file.hexDigest("SHA-1")}\n")
            }
    }
}

val verifyCentralSigning = tasks.register("verifyCentralSigning") {
    dependsOn(generateCentralChecksums)

    doLast {
        val repoRoot = centralRepoDir.get().asFile
        val unsigned = repoRoot.walkTopDown()
            .filter(File::isFile)
            .filterNot { file ->
                file.name.endsWith(".asc") ||
                    file.name.endsWith(".md5") ||
                    file.name.endsWith(".sha1") ||
                    file.name.endsWith(".sha256") ||
                    file.name.endsWith(".sha512")
            }
            .filter { file -> !file.resolveSibling("${file.name}.asc").exists() }
            .map { it.relativeTo(repoRoot).path }
            .toList()

        if (unsigned.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Missing .asc signatures for published files:")
                    unsigned.forEach { appendLine(" - $it") }
                    appendLine("Set GPG_PRIVATE_KEY and GPG_PASSPHRASE before preparing the Central bundle.")
                },
            )
        }
    }
}

tasks.register<Zip>("zipCentralBundle") {
    dependsOn(verifyCentralSigning)
    destinationDirectory.set(layout.buildDirectory.dir("central-bundle"))
    archiveFileName.set("central-bundle.zip")
    from(centralRepoDir)
}

tasks.register("prepareCentralBundle") {
    dependsOn("zipCentralBundle")
}
