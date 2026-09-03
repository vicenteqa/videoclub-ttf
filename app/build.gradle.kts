import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    // Through a UTF-8 reader, not the byte-stream overload: `Properties.load(InputStream)` is
    // specified to decode ISO-8859-1, which turns an accented value into mojibake by the time it
    // reaches BuildConfig.
    localPropertiesFile.reader(Charsets.UTF_8).use(localProperties::load)
}

/**
 * A `local.properties` value escaped for embedding in a generated `BuildConfig` string literal.
 * Passwords and User-Agent strings routinely contain quotes and backslashes, which would otherwise
 * produce a `BuildConfig.java` that does not compile.
 */
fun localProp(key: String, fallback: String = ""): String =
    localProperties.getProperty(key, fallback)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

/**
 * The households, read out of `local.properties`.
 *
 * One flavour per `casa.<id>.remoteConfig.url`, generated rather than written down. The list of
 * households lives on the server — it is the panel that creates them — and `./sync-casas.sh` brings
 * it here. Anything hand-written in this file would be a second copy of that list, and second
 * copies of lists go stale.
 *
 * A household with no URL is not skipped quietly: it compiles to an APK that says «Error de
 * credenciales» on screen, and saying so at build time is the only chance anybody gets to notice
 * before carrying it to a television.
 */
val casas: List<String> = localProperties.stringPropertyNames()
    .mapNotNull { Regex("""^casa\.([^.]+)\.remoteConfig\.url$""").find(it)?.groupValues?.get(1) }
    .sorted()

/** Gradle wants an identifier; the panel hands out slugs, which can carry hyphens. */
fun flavourOf(casa: String): String = casa
    .split(Regex("[^A-Za-z0-9]+"))
    .filter { it.isNotEmpty() }
    .mapIndexed { index, part -> if (index == 0) part.lowercase() else part.replaceFirstChar(Char::uppercase) }
    .joinToString("")

/**
 * La marca de esta compilación, `AAMMDDHHm`, que es a la vez el `versionCode`.
 *
 * Cabe de sobra en el entero con signo que Android exige (`260902146` contra un tope de 2.100
 * millones) y crece con el tiempo, que es lo único que el sistema mira para decidir si una
 * actualización es más nueva que lo instalado.
 *
 * El último dígito es el minuto de esa hora dividido entre 6 (0-9): suficiente para que dos
 * compilaciones seguidas durante una sesión de pruebas no choquen, sin tocar el resto del formato
 * que ya se lee a simple vista en el panel y en los logs. Meter el minuto entero (`yyMMddHHmm`, diez
 * dígitos) se pasa del tope en cuanto el año es "22" o mayor.
 */
val now = LocalDateTime.now()
val buildStamp: String = now.format(DateTimeFormatter.ofPattern("yyMMddHH")) + (now.minute / 6)

/** El descodificador FFmpeg compilado aparte, comprobado aquí para que el fallo se lea. */
val ffmpegDecoder: File = file("libs/media3-decoder-ffmpeg.aar").also {
    if (!it.exists()) {
        throw GradleException(
            "Falta app/libs/media3-decoder-ffmpeg.aar.\n\n" +
                "  export JAVA_HOME=~/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=~/Android/Sdk\n" +
                "  ./build-ffmpeg-decoder.sh\n"
        )
    }
}

android {
    namespace = "com.videoclub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videoclub.app"
        // Same floor as the sibling projects, so the same Gradle cache and the same tooling apply.
        minSdk = 25
        targetSdk = 36
        // Sube solo, a partir de la fecha de compilación: `AAMMDDHHm` como número. Android sólo
        // acepta una actualización cuyo `versionCode` sea mayor que el instalado, así que dejarlo
        // clavado en 1 —como estaba— convierte cada build en una que ningún televisor querrá
        // instalar.
        //
        // De la fecha y no de un contador en un fichero: un contador hay que acordarse de subirlo, y
        // el día que se olvide el fallo no se ve al compilar, se ve semanas después en un aparato que
        // no se actualiza. Y no del minuto exacto: dos builds seguidas con menos de 6 minutos entre
        // sí son el mismo intento, no dos versiones.
        versionCode = buildStamp.toInt()
        versionName = "1.0.$buildStamp"

        // Nothing about the account is compiled in any more: it arrives from the hosted config and
        // nowhere else, so a rotated password is fixed by editing one file on a server rather than
        // by carrying an APK to a television in another house.
        //
        // The one value that remains lives on the flavours below, because which hosted config to
        // ask is the single thing two households do not share.
    }

    // One flavour per household, differing in exactly one string, and deliberately sharing an
    // `applicationId`.
    //
    // The by-the-book move would be `applicationIdSuffix`, so several could sit on one device at
    // once. It is refused on purpose: a box that already holds this package would treat a suffixed
    // id as an unrelated application, turning every future update into a physical visit to
    // uninstall the old one first.
    //
    // With no households configured there are no flavours at all, and the plain `assembleRelease`
    // still works — which is what a fresh checkout, before anybody has run `./sync-casas.sh`, has.
    if (casas.isNotEmpty()) {
        flavorDimensions += "casa"
        productFlavors {
            casas.forEach { casa ->
                create(flavourOf(casa)) {
                    dimension = "casa"
                    val url = localProp("casa.$casa.remoteConfig.url")
                    if (url.isBlank()) {
                        logger.warn("Videoclub: la casa '$casa' no tiene remoteConfig.url — ese APK dirá \"Error de credenciales\".")
                    }
                    buildConfigField("String", "REMOTE_CONFIG_URL", "\"$url\"")
                }
            }
        }
    }

    signingConfigs {
        // Android only accepts an update whose signature matches the installed app. The debug key is
        // regenerated per machine, so shipping release builds with it would tie every future remote
        // update to one laptop's `~/.android/debug.keystore`. Back this keystore up.
        create("release") {
            val path = localProperties.getProperty("keystore.path")
            val keystore = path?.let { rootProject.file(it) }
            if (keystore != null && keystore.exists()) {
                storeFile = keystore
                storePassword = localProperties.getProperty("keystore.password")
                keyAlias = localProperties.getProperty("keystore.alias")
                keyPassword = localProperties.getProperty("keystore.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded onto a phone and a television, so it must always be installable: fall back
            // to the debug key rather than producing an unsigned artefact. A build without the
            // release keystore still runs, but it cannot update a box holding a release-signed one.
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            // `android.util.Log` throws in a JVM test unless stubbed, and the provider parser is
            // exactly where a log line earns its keep: a malformed hand edit on the server is
            // otherwise silent.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)

    implementation(libs.media3.exoplayer)
    // El proveedor sirve MPEG-TS y con eso bastaba, pero un canal que añade la casa —una televisión
    // local— es casi siempre HLS. `DefaultMediaSourceFactory` busca esta fábrica por reflexión, así
    // que sin la dependencia no hay error de compilación: hay `ClassNotFoundException` al reproducir.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    // Google no publica el descodificador FFmpeg en Maven; sale de `./build-ffmpeg-decoder.sh`.
    // Sin él la app se instala y funciona, pero reproduce en silencio todo lo que lleve DTS o AC3
    // en cualquier aparato que no sea un descodificador de televisión — que es casi todo el
    // catálogo en el móvil y en la tablet. Por eso falla aquí en vez de dejarlo pasar: un APK mudo
    // no se distingue de uno bueno hasta que alguien le da al play en el salón.
    implementation(files(ffmpegDecoder))

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
}

