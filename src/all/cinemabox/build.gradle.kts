import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "CinemaBox"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
}
