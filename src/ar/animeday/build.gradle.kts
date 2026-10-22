import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "AnimeDay"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
