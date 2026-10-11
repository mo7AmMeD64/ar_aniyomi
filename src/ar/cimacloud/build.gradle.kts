import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "CimaCloud"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
}
