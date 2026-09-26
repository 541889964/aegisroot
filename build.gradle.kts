plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val androidMinSdkVersion by extra(27)
val androidTargetSdkVersion by extra(34)
val androidCompileSdkVersion by extra(34)
val androidCompileSdkVersionMinor by extra(0)
val androidBuildToolsVersion by extra("34.0.0")
val androidSourceCompatibility by extra(JavaVersion.VERSION_17)
val androidTargetCompatibility by extra(JavaVersion.VERSION_17)
val managerVersionCode by extra(1)
val managerVersionName by extra("1.0")
