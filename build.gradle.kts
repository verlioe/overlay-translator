plugins {
    // 注意：AGP 9.0 之後 Kotlin 支援已經內建，
    // 不能再套用 org.jetbrains.kotlin.android，否則建置會直接失敗。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
