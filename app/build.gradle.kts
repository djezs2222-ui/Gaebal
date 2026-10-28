plugins { id("com.android.application") }
android {
    namespace = "kr.personal.tabletplayer"
    compileSdk = 37
    defaultConfig { applicationId = "kr.personal.tabletplayer"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "0.1.3"; testInstrumentationRunner = "kr.personal.tabletplayer.SmokeRunner" }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("org.videolan.android:libvlc-all:3.7.7")

    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    testImplementation("junit:junit:4.13.2")
}

