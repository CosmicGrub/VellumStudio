# Add project specific ProGuard rules here.

-keep class org.nanohttpd.** { *; }

# =====================================================================================
# kotlinx.serialization
# =====================================================================================
# Every @Serializable class Json.decodeFromString/encodeToString touches gets its field-level
# (de)serialization logic from a generated `serializer()` function and a companion `$$serializer`
# inner class -- neither is called from a normal, R8-visible code path (the call is made
# reflectively by the Json engine at runtime), so both are exactly the kind of thing that gets
# silently stripped/renamed by minification without an explicit keep, breaking save/load at
# runtime rather than at compile time. This is kotlinx.serialization's own documented ProGuard
# rule set (see https://github.com/Kotlin/kotlinx.serialization#android), scoped to this app's
# own package rather than left as a wildcard across all of com.vellum.studio.**:
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.vellum.studio.**$$serializer { *; }
-keepclassmembers class com.vellum.studio.** {
    *** Companion;
}
-keepclasseswithmembers class com.vellum.studio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-suspenders: keep the actual @Serializable data classes/enum this app persists to disk
# today, named individually rather than as a package-wide blanket, so the exact on-disk JSON field
# names/types of every real save format survive shrinking+obfuscation even if the generic rule
# above is ever loosened. These are the complete, current serialization surface -- verified by
# grepping the source tree for every @Serializable-annotated declaration and the repository that
# (de)serializes it:
#   - Brush / BrushCategory (canvas/Brush.kt)             -- custom_brushes.json, via CustomBrushRepository
#   - Palette (model/Palette.kt)                          -- palettes.json, via PaletteRepository
#   - LayerMeta / ProjectMeta (model/Project.kt)          -- <project>/metadata.json, via ProjectRepository
#   - UserPhotoTemplate (model/UserPhotoTemplateRepository.kt) -- photo_templates/index.json
# Note these are NOT all under com.vellum.studio.model -- Brush/BrushCategory live in
# com.vellum.studio.canvas, which the codebase's old blanket `-keep class
# com.vellum.studio.model.** { *; }` rule never actually covered (see below).
-keep @kotlinx.serialization.Serializable class com.vellum.studio.canvas.Brush { *; }
-keep @kotlinx.serialization.Serializable class com.vellum.studio.canvas.BrushCategory { *; }
-keep @kotlinx.serialization.Serializable class com.vellum.studio.model.Palette { *; }
-keep @kotlinx.serialization.Serializable class com.vellum.studio.model.LayerMeta { *; }
-keep @kotlinx.serialization.Serializable class com.vellum.studio.model.ProjectMeta { *; }
-keep @kotlinx.serialization.Serializable class com.vellum.studio.model.UserPhotoTemplate { *; }

# The rest of com.vellum.studio.model (ProjectRepository, PaletteRepository, CustomBrushRepository,
# SettingsRepository, ProjectSchemaMigrator, plain non-serialized data classes like ProjectSummary/
# CanvasSizePreset, etc.) intentionally has NO blanket keep any more -- replacing the codebase's old
# `-keep class com.vellum.studio.model.** { *; }` with the two targeted rule sets above. That old
# rule was both too narrow (it never reached canvas.Brush/BrushCategory, this app's other real
# on-disk JSON format, leaving custom brush save/load completely unprotected -- see above) and too
# broad (it kept every member of every ordinary class in the package verbatim -- repository classes,
# helper functions, private implementation details -- none of which any reflection or JNI path ever
# touches by name, so shrinking+obfuscation could never actually apply to any of it). Verified via a
# real :app:assembleRelease install + manual smoke pass (save/load a project, save/load a custom
# brush, custom palette) on R52X101MB6W that the narrower scope still keeps everything that's
# actually reflection-reachable.

# =====================================================================================
# OpenCV (org.opencv:opencv:4.14.0) -- JNI bindings
# =====================================================================================
# libopencv_java4.so resolves Java classes/methods/fields by name via JNI (FindClass/GetMethodID/
# GetFieldID) from native code -- a call path invisible to R8's static analysis, so an obfuscated
# or field-stripped org.opencv.core.Mat (etc.) fails at the native boundary with an
# UnsatisfiedLinkError/NoSuchMethodError at runtime, not at compile time. Confirmed the AAR ships
# no consumer ProGuard rules of its own (its .aar has no proguard.txt at all, unlike the ML Kit
# artifacts below), so this app must supply an explicit keep or get no protection whatsoever.
# canvas/PhotoConverter.kt (the photo-import pipeline) is the only call site, using
# org.opencv.android.{OpenCVLoader,Utils} and org.opencv.core/imgproc -- keeping the whole
# org.opencv package rather than hand-picking classes, since new OpenCV calls added later to that
# file must not silently regain this same JNI-reflection hole.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# =====================================================================================
# ML Kit pose detection (com.google.mlkit:pose-detection-accurate:17.0.0, bundled model)
# =====================================================================================
# canvas/PoseOverlay.kt calls the public com.google.mlkit.vision.pose.* / .common.* API directly
# (PoseDetection.getClient, InputImage, Pose, PoseLandmark, AccuratePoseDetectorOptions) -- normal
# method calls R8 can already see and would keep correctly on its own. The real risk is beneath
# that public surface: ML Kit's bundled runtime resolves its own on-device model/detector
# implementation via Play-Services-style internal component + protobuf-reflection loading (its
# pose-detection-common AAR already ships a consumer proguard.txt keeping <fields> on its internal
# zzgd-derived proto classes for exactly this reason -- AGP merges that automatically, so it's not
# duplicated here). These explicit keeps cover the rest of that same internal surface plus the
# public API app code calls directly, so a bad rename can't reach either from a
# different direction.
-keep class com.google.mlkit.vision.pose.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_pose_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_pose_accurate.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_pose_**
