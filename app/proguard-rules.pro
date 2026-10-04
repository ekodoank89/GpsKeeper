# ============================================================
# AYA GPS — Aturan R8/ProGuard (build release)
# ============================================================

# --- Google Play Services (Location & Maps) ---
# Library sudah membawa aturan consumer sendiri; ini lapisan
# pengaman tambahan agar tidak ada crash runtime di HP vendor mana pun.
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# --- Peringatan kelas yang tidak dipakai di Android (aman diabaikan) ---
-dontwarn org.apache.http.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**

# --- Kotlin Coroutines ---
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Simpan info generik & anotasi (dibutuhkan beberapa library) ---
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# --- Baris kode tetap tercatat agar crash log mudah dibaca ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
