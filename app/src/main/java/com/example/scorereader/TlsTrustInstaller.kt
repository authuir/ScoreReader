package com.example.scorereader

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Installs an [SSLContext] that trusts both the system CA store *and* a
 * handful of bundled root certificates (`res/raw/isrg_root_x1.pem`,
 * `res/raw/isrg_root_x2.pem`).
 *
 * Why this exists:
 *   `android:networkSecurityConfig` was introduced in API 24 (Android 7.0).
 *   On API 23 (Android 6.0), which we still support, that manifest entry
 *   is ignored. Many TV/STB boxes shipped with Android 6 never received a
 *   CA update and don't trust the ISRG Root X1 that `*.github.io` chains
 *   to, so any HTTPS request to GitHub Pages fails with
 *   `CertPathValidatorException: Trust anchor for certification path not found`.
 *
 *   We patch that by combining the platform's default trust anchors with
 *   our own bundled ones and pushing the resulting socket factory into
 *   [HttpsURLConnection.setDefaultSSLSocketFactory], so every plain
 *   `URL(...).openConnection()` HTTPS call (including the ones in
 *   [OnlineLibraryRepository]) is covered.
 */
object TlsTrustInstaller {

    private const val TAG = "TlsTrustInstaller"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                val sslContext = buildContext(context.applicationContext)
                HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                installed = true
                Log.i(TAG, "Custom TLS trust store installed (system + bundled ISRG roots).")
            } catch (t: Throwable) {
                // Don't crash the app; HTTPS will simply fall back to the
                // platform default and old devices will keep failing.
                Log.e(TAG, "Failed to install custom TLS trust store", t)
            }
        }
    }

    private fun buildContext(context: Context): SSLContext {
        val factory = CertificateFactory.getInstance("X.509")

        // 1. Seed a KeyStore with the platform's default trust anchors so
        //    we don't accidentally narrow trust on modern devices.
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
        var index = 0
        systemTmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
            .forEach { cert ->
                keyStore.setCertificateEntry("system-${index++}", cert)
            }

        // 2. Layer in our bundled extras.
        for ((alias, resId) in BUNDLED) {
            try {
                context.resources.openRawResource(resId).use { stream ->
                    addCertificates(keyStore, factory, alias, stream)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not load bundled cert $alias", t)
            }
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }

        return SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }
    }

    private fun addCertificates(
        keyStore: KeyStore,
        factory: CertificateFactory,
        alias: String,
        stream: InputStream,
    ) {
        // generateCertificates() handles both single-cert and concatenated
        // PEM bundles transparently.
        var i = 0
        for (cert in factory.generateCertificates(stream)) {
            if (cert is X509Certificate) {
                keyStore.setCertificateEntry("$alias-${i++}", cert)
            }
        }
    }

    private val BUNDLED: List<Pair<String, Int>> = listOf(
        "isrg_root_x1" to R.raw.isrg_root_x1,
        "isrg_root_x2" to R.raw.isrg_root_x2,
    )
}
