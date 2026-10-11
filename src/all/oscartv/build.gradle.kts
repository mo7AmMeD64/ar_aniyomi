import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Oscar TV"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
}
