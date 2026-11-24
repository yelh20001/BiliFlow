# HEVC configuration compatibility patch

The app uses Media3 1.6.1. A real initialization segment from `BV1aQn96fEan`
triggers an `IndexOutOfBoundsException` in `HevcConfig.parseImpl`: prefix SEI
contains 3D display information while the parsed VPS has an empty `layerInfos`.
The upstream code accesses `layerInfos.get(0)` without checking the list.

`src/main/java/androidx/media3/extractor/HevcConfig.java` is the unmodified
Apache-2.0 upstream 1.6.1 source, except for a guard on that one empty list.
It retains all video initialization bytes, Dolby/HDR metadata, color transfer,
bit depth and codec configuration. Only the unavailable stereo-view hint is
ignored. This avoids degrading a playable HEVC/Dolby source to AVC.

`prepareExtractorCompat` reads the pinned original AAR and copies its classes
to a generated JAR, excluding only `HevcConfig.class`. The app compiles the
replacement source, so D8/R8 receives exactly one class with that name.
`media3-extractor-consumer-rules.pro` preserves the original AAR consumer rules;
`media3-container` is declared explicitly to retain the extractor dependency.
No Media3 binaries or stream URLs are checked into this patch.

When upgrading Media3, check the upstream condition and all constructor/field
signatures. Remove this override only once the regression fixtures and real
video playback pass with the upstream class.

Source: https://github.com/androidx/media/blob/1.6.1/libraries/extractor/src/main/java/androidx/media3/extractor/HevcConfig.java
