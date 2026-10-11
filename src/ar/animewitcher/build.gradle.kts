import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "AnimeWitcher"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
}
