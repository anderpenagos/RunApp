// Arquivo de build da RAIZ do projeto.
// Apenas declara os plugins disponíveis para os subprojetos (apply false).
// A configuração real do app fica em app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
