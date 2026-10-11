import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Oscar TV"
    qname = "OscarTv"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
}
