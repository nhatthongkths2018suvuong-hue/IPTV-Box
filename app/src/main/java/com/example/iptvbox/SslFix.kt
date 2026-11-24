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
 * Android 6 thiếu nhiều chứng chỉ gốc mới (Let's Encrypt, Google Trust Services...) nên nhiều trang https không tải được.
 * Bổ sung bộ chứng chỉ gốc hiện hành (Mozilla) vào danh sách tin cậy (vẫn kiểm tra chứng chỉ đầy đủ, KHÔNG tắt bảo mật).
 */
object SslFix {
    fun install(ctx: Context) {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ctx.resources.openRawResource(R.raw.cacerts).use { ins ->
                var i = 0
                for (c in cf.generateCertificates(ins)) ks.setCertificateEntry("ca" + (i++), c)
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
