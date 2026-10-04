dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Compose (gunakan template "Empty Activity" Compose dari Android Studio)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
}
