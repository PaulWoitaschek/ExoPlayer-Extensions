# Library
This is not related to ExoPlayer. 

This builds some of the extensions from source code.
This is primarily used by [Voice](https://github.com/PaulWoitaschek/Voice)

# Install

```gradle
repositories {
  maven { url "https://jitpack.io" }
}
  
dependencies {
  // all extensions
  implementation "com.github.PaulWoitaschek.ExoPlayer-Extensions:X.Y.Z"

  // import only the extension you need
  implementation "com.github.PaulWoitaschek.ExoPlayer-Extensions:opus:X.Y.Z"
  implementation "com.github.PaulWoitaschek.ExoPlayer-Extensions:flac:X.Y.Z"
 
  // where X.Y.Z is the latest exoplayer version
}
```
