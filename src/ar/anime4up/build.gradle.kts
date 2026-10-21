import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Anime4Up"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
}

dependencies {
    implementation("org.jsoup:jsoup:1.17.1")
}

android {
    buildTypes {
        named("release") {
            proguardFile("proguard-rules.pro")
        }
    }
}
