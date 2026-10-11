import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "AnimeWitcher"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
}
