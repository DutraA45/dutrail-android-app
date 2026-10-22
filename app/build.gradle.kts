import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Configuração por máquina (fora do repo): dutrail.api.url, a URL base da API do ambiente de desenvolvimento, terminada em /api/.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun String.withTrailingSlash() = if (endsWith("/")) this else "$this/"

// Nenhuma URL de ambiente tem valor padrão no repo: sem a propriedade, o APK não é gerado.
val debugApiUrl: String? = (providers.gradleProperty("dutrail.api.url").orNull ?: localProperties.getProperty("dutrail.api.url"))
    ?.trim()?.withTrailingSlash()

// URL de produção: -PDUTRAIL_API_URL=..., ~/.gradle/gradle.properties ou variável de ambiente. Nunca no repo.
val releaseApiUrl = providers.gradleProperty("DUTRAIL_API_URL")
    .orElse(providers.environmentVariable("DUTRAIL_API_URL"))
    .map { it.trim().withTrailingSlash() }

/** Mensagem de erro, ou null se a URL serve como base da API (HTTPS, terminada em /api/). */
fun apiUrlProblem(name: String, url: String?): String? = when {
    url.isNullOrBlank() -> "Defina $name"
    !url.startsWith("https://") -> "$name precisa ser HTTPS (o app não permite HTTP puro)"
    !url.endsWith("/api/") -> "$name precisa terminar em /api/"
    else -> null
}

// Provedor de tiles do mapa. O padrão é o tile server do OSM, que só serve para uso leve
// (ver README); em produção troque por um provedor com SLA sem mexer no código.
val mapTileUrl = providers.gradleProperty("DUTRAIL_TILE_URL")
    .getOrElse("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
val mapTileAttribution = providers.gradleProperty("DUTRAIL_TILE_ATTRIBUTION")
    .getOrElse("© OpenStreetMap contributors")

android {
    namespace = "com.dutrail.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dutrail.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAP_TILE_URL", "\"$mapTileUrl\"")
        buildConfigField("String", "MAP_TILE_ATTRIBUTION", "\"$mapTileAttribution\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${debugApiUrl.orEmpty()}\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"${releaseApiUrl.getOrElse("")}\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // android.util.Log e afins retornam valores padrão nos testes de JVM.
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// As mensagens são calculadas na configuração: o configuration cache só guarda as strings.
val verifyReleaseApiUrl by tasks.registering {
    val problem = apiUrlProblem("DUTRAIL_API_URL", releaseApiUrl.orNull)
    doLast {
        problem?.let { error("$it (propriedade Gradle ou variável de ambiente) para gerar o release.") }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseApiUrl) }

// Debug: verificado só ao empacotar, para os testes de unidade rodarem sem a URL configurada.
val verifyDebugApiUrl by tasks.registering {
    val problem = apiUrlProblem("dutrail.api.url", debugApiUrl)
    // Só pega o erro se a URL de produção estiver configurada nesta máquina; ela nunca está no repo.
    val pointsToProduction = debugApiUrl != null && debugApiUrl == releaseApiUrl.orNull
    doLast {
        problem?.let { error("$it no local.properties para gerar o build debug.") }
        check(!pointsToProduction) { "O build debug não pode apontar para produção (dutrail.api.url = DUTRAIL_API_URL)." }
    }
}
tasks.matching { it.name == "packageDebug" || it.name == "packageDebugBundle" }.configureEach { dependsOn(verifyDebugApiUrl) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.play.services.location)
    implementation(libs.maplibre.android)
    implementation(libs.garmin.fit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Smoke test contra a API do ambiente de desenvolvimento (pulado se ausente):
// ./gradlew :app:testDebugUnitTest --tests '*BackendSmokeTest*' -PdutrailSmokeUrl=<URL base da API, terminada em /api/>
tasks.withType<Test>().configureEach {
    providers.gradleProperty("dutrailSmokeUrl").orNull?.trim()?.withTrailingSlash()?.let { url ->
        check(url != releaseApiUrl.orNull) { "O smoke test não pode rodar contra produção (dutrailSmokeUrl = DUTRAIL_API_URL)." }
        systemProperty("dutrail.smoke.url", url)
    }
    systemProperty("dutrail.test.output", layout.buildDirectory.dir("test-output").get().asFile.absolutePath)
}
