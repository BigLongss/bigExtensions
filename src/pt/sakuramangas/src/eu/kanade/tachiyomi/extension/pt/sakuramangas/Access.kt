package eu.kanade.tachiyomi.extension.pt.sakuramangas

import androidx.webkit.CustomHeader
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import keiyoushi.network.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

internal class Access {
    private val mutex = Mutex()

    suspend fun getPage(client: OkHttpClient, url: String, headers: Headers, baseUrl: String): Response {
        if (!isAndroid) return client.get(url, headers)

        mutex.withLock {
            withContext(Dispatchers.Main) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS) &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                ) {
                    val profile = ProfileStore.getInstance().getProfile(Profile.DEFAULT_PROFILE_NAME)
                        ?: throw IOException("Não foi possível abrir a sessão do WebView.")
                    val previous = profile.getCustomHeaders(HEADER, VALUE)
                    profile.addCustomHeader(CustomHeader(HEADER, VALUE, setOf(baseUrl)))
                    try {
                        val ajaxHeaders = headers.newBuilder().set(HEADER, VALUE).build()
                        client.get(
                            "$baseUrl/dist/sakura/models/home/__.home_adicionados.php",
                            ajaxHeaders,
                            cacheControl = CacheControl.FORCE_NETWORK,
                        ).use { }
                    } finally {
                        withContext(NonCancellable) {
                            profile.clearCustomHeader(HEADER, VALUE)
                            previous.forEach(profile::addCustomHeader)
                        }
                    }
                } else {
                    throw IOException("Atualize o Android System WebView para acessar o Sakura Mangás.")
                }
            }
        }
        return client.get(url, headers)
    }

    companion object {
        val isAndroid = System.getProperty("java.vm.name") == "Dalvik"

        private const val HEADER = "X-Requested-With"
        private const val VALUE = "XMLHttpRequest"
    }
}
