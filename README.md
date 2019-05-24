# Library [![CircleCI](https://circleci.com/gh/PaulWoitaschek/ExoPlayer-Extensions.svg?style=shield)](https://circleci.com/gh/PaulWoitaschek/ExoPlayer-Extensions)
This is not related to ExoPlayer. 

This builds some of the extensions from source code.
This is primarily used by [Voice](https://github.com/PaulWoitaschek/Voice)

# Install

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    // important: add the core dependency and set transitive to false on the extensions
    implementation 'com.google.android.exoplayer:exoplayer-core:2.X.X'
    implementation "com.github.PaulWoitaschek.ExoPlayer-Extensions:extension-opus:X.Y.Z" {
        transitive = false
    }
    implementation "com.github.PaulWoitaschek.ExoPlayer-Extensions:extension-flac:X.Y.Z" {
        transitive = false
    }
}
```
