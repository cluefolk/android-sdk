import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}
group = "com.cluefolk"
version = "0.3.1"
android {
    namespace = "com.cluefolk.sdk"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // The SDK reports its version to Cluefolk with each conversation.
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
        // Games compile with what their engine allows; Unity 2022.3 ships AGP 7.4.
        aarMetadata { minCompileSdk = 34 }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    // AndroidX versions are pinned on purpose (see dependencies), not stale.
    lint { warningsAsErrors = true; abortOnError = true; disable += "GradleDependency" }
}
// Java 8 bytecode keeps older game toolchains working; javac 21+ only warns about it.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-options") }
kotlin { compilerOptions { allWarningsAsErrors.set(true); jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
// The newest AndroidX releases need AGP 8.9 and compileSdk 36, and WebKit 1.14+ is
// Java 11 bytecode that AGP 7.4's D8 cannot dex. These versions keep the SDK
// buildable from Unity 2022.3 (AGP 7.4) with compileSdk 34; the SDK needs only
// WebViewAssetLoader and web message listeners from WebKit.
dependencies { implementation("androidx.activity:activity:1.9.3"); implementation("androidx.core:core-ktx:1.13.1"); implementation("androidx.webkit:webkit:1.12.1"); testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20260814") }

// Released to Maven Central as com.cluefolk:android-sdk. Central requires signed
// artifacts; without a signing key (local builds, publishToMavenLocal) they stay unsigned.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources(), variant = "release"))
    coordinates("com.cluefolk", "android-sdk", version.toString())
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) signAllPublications()
    pom {
        name.set("Cluefolk Android SDK")
        description.set("In-game player support for Android games: players chat with your support team from inside the game.")
        inceptionYear.set("2026")
        url.set("https://github.com/cluefolk/android-sdk")
        licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit"); distribution.set("repo") } }
        developers { developer { id.set("cluefolk"); name.set("Cluefolk"); url.set("https://cluefolk.com") } }
        scm {
            url.set("https://github.com/cluefolk/android-sdk")
            connection.set("scm:git:https://github.com/cluefolk/android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/cluefolk/android-sdk.git")
        }
    }
}
