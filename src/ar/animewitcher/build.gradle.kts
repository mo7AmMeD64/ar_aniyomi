import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "AnimeWitcher"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
}
