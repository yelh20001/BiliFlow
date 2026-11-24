package app.biliflow

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only this app can read the key. Session values are never included in diagnostics. */
class SessionVault(context: Context, name: String = "account_session") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("biliflow.session.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("biliflow.session.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String? = runCatching {
        val bytes = Base64.decode(prefs.getString("sealed", null) ?: return null, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("sealed", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}

class AccountCookies(private val vault: SessionVault) : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    init {
        runCatching {
            val saved = JSONArray(vault.read() ?: "[]")
            for (index in 0 until saved.length()) {
                val record = saved.optJSONObject(index)
                Cookie.parse((record?.optString("url") ?: "https://www.bilibili.com/").toHttpUrl(), record?.getString("cookie") ?: saved.getString(index))?.let {
                    if (isBilibili(it.domain) && it.expiresAt > System.currentTimeMillis()) cookies.add(it)
                }
            }
        }
    }
    override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
        values.filter { isBilibili(it.domain) }.forEach { value ->
            cookies.removeAll { it.name == value.name && it.domain == value.domain && it.path == value.path }
            if (value.expiresAt > System.currentTimeMillis()) cookies.add(value)
        }
        persist()
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
        cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }
    private fun persist() { vault.write(JSONArray(cookies.map { JSONObject().put("url", "https://${it.domain}${it.path}").put("cookie", it.toString()) }).toString()) }
    fun importAppCookies(items: JSONArray) {
        val values = (0 until items.length()).mapNotNull { index ->
            runCatching {
                val item = items.getJSONObject(index)
                Cookie.Builder().name(item.getString("name")).value(item.getString("value")).domain("bilibili.com").path("/")
                    .secure().expiresAt(item.optLong("expires", System.currentTimeMillis() / 1000 + 15_552_000) * 1000)
                    .apply { if (item.optInt("http_only") == 1) httpOnly() }.build()
            }.getOrNull()
        }
        saveFromResponse("https://www.bilibili.com/".toHttpUrl(), values)
    }
    /** Called on the UI thread, including after official login pages navigate. */
    fun importWebSession() {
        val manager = CookieManager.getInstance()
        val host = "https://www.bilibili.com/".toHttpUrl()
        val headers = listOf("https://www.bilibili.com", "https://passport.bilibili.com", "https://api.bilibili.com")
            .mapNotNull { manager.getCookie(it) }
        val incoming = headers.flatMap { it.split(';') }.mapNotNull { raw ->
            val pair = raw.trim().split('=', limit = 2)
            if (pair.size != 2) null else runCatching {
                Cookie.Builder().name(pair[0]).value(pair[1]).domain("bilibili.com").path("/")
                    .secure().expiresAt(System.currentTimeMillis() + 180L * 24 * 60 * 60 * 1000).build()
            }.getOrNull()
        }
        if (incoming.isNotEmpty()) saveFromResponse(host, incoming)
    }
    fun exportToWeb() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        synchronized(cookies) { cookies.toList() }.forEach { cookie ->
            manager.setCookie("https://${cookie.domain}${cookie.path}", cookie.toString())
        }
        manager.flush()
    }
    fun clear() {
        synchronized(cookies) { cookies.clear(); vault.clear() }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
    private fun isBilibili(domain: String) = domain == "bilibili.com" || domain.endsWith(".bilibili.com")
}
