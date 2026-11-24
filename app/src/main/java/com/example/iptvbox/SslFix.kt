package com.example.iptvbox

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 6 không có chứng chỉ gốc ISRG (Let's Encrypt) nên nhiều trang https mới không tải được.
 * Bổ sung 2 chứng chỉ gốc đó vào danh sách tin cậy (vẫn kiểm tra chứng chỉ đầy đủ, KHÔNG tắt bảo mật).
 */
object SslFix {
    fun install(ctx: Context) {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            val ids = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)
            for ((i, id) in ids.withIndex()) {
                ctx.resources.openRawResource(id).use { ks.setCertificateEntry("isrg$i", cf.generateCertificate(it)) }
            }
            val extraF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            extraF.init(ks)
            val extra = extraF.trustManagers.filterIsInstance<X509TrustManager>().first()
            val defF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            defF.init(null as KeyStore?)
            val def = defF.trustManagers.filterIsInstance<X509TrustManager>().first()
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    def.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        def.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        extra.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = def.acceptedIssuers + extra.acceptedIssuers
            }
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<TrustManager>(tm), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        } catch (e: Throwable) {
        }
    }
}
