import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Akwam"
    versionCode = 1
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
