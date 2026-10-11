import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "AnimeWitcher"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
}
