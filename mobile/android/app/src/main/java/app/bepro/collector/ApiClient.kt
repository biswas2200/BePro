package app.bepro.collector

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** Talks to the Spring Boot backend. Owns the JWT: re-authenticates with the pairing secret on 401/expiry. */
class ApiClient(context: Context) {

  private val settings = Settings(context)
  private val secure = SecureStore(context)

  fun isPaired() = settings.serverUrl != null && secure.get(KEY_SECRET) != null

  data class Pairing(val userId: String)

  fun pair(serverUrl: String, secret: String): Pairing {
    val base = normalize(serverUrl)
    val (token, expiresAt, userId) = authenticate(base, secret)
    settings.serverUrl = base
    settings.userId = userId
    settings.tokenExpiresAt = expiresAt
    secure.put(KEY_SECRET, secret)
    secure.put(KEY_TOKEN, token)
    return Pairing(userId)
  }

  fun unpair() {
    secure.clear()
    settings.clearPairing()
  }

  @Synchronized
  fun accessToken(forceRefresh: Boolean = false): String {
    val base = settings.serverUrl ?: throw IOException("Not paired")
    val cached = secure.get(KEY_TOKEN)
    if (!forceRefresh && cached != null && settings.tokenExpiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS) {
      return cached
    }
    val secret = secure.get(KEY_SECRET) ?: throw IOException("Not paired")
    val (token, expiresAt, userId) = authenticate(base, secret)
    secure.put(KEY_TOKEN, token)
    settings.tokenExpiresAt = expiresAt
    settings.userId = userId
    return token
  }

  fun uploadEvents(events: List<Event>) {
    val array = JSONArray()
    events.forEach { e ->
      array.put(
        JSONObject()
          .put("category", e.category)
          .put("subCategory", e.subCategory)
          .put("value", e.value)
          .put("unit", e.unit)
          .put("startedAt", Instant.ofEpochMilli(e.startedAt).toString())
          .put("endedAt", e.endedAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
          .put("source", e.source)
          .put("metadata", JSONObject(e.metadata)),
      )
    }
    authorized("/v1/events/batch") { it.post(JSONObject().put("events", array).toString().toRequestBody(JSON)) }
      .use { /* body not needed */ }
  }

  data class InsightSummary(val generatedAt: String, val kind: String, val pattern: String, val action: String)

  /** Insights generated after [since] (ISO instant), newest first. */
  fun insightsSince(since: String?): List<InsightSummary> {
    val query = "/v1/insights?limit=5" + (since?.let { "&since=" + URLEncoder.encode(it, "UTF-8") } ?: "")
    val body = authorized(query) { it.get() }.use { it.body!!.string() }
    val array = JSONArray(body)
    return (0 until array.length()).map { i ->
      val o = array.getJSONObject(i)
      InsightSummary(o.getString("generatedAt"), o.getString("kind"), o.getString("pattern"), o.getString("action"))
    }
  }

  fun downloadExport(target: File) {
    authorized("/v1/export/events.csv") { it.get() }.use { response ->
      target.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
    }
  }

  private fun authorized(path: String, method: (Request.Builder) -> Request.Builder): Response {
    val base = settings.serverUrl ?: throw IOException("Not paired")
    fun call(token: String) = http.newCall(
      method(Request.Builder().url(base + path).header("Authorization", "Bearer $token")).build(),
    ).execute()

    var response = call(accessToken())
    if (response.code == 401) {
      response.close()
      response = call(accessToken(forceRefresh = true))
    }
    if (!response.isSuccessful) {
      val detail = response.body?.string()?.take(300).orEmpty()
      response.close()
      throw ApiException(response.code, "HTTP ${response.code} on $path $detail")
    }
    return response
  }

  private data class Auth(val token: String, val expiresAt: Long, val userId: String)

  private fun authenticate(base: String, secret: String): Auth {
    val body = JSONObject().put("deviceId", settings.deviceId).put("secret", secret).toString()
    val request = Request.Builder().url("$base/v1/auth/token").post(body.toRequestBody(JSON)).build()
    http.newCall(request).execute().use { response ->
      if (response.code == 401) throw ApiException(401, "The server rejected the pairing secret")
      if (!response.isSuccessful) throw ApiException(response.code, "Pairing failed: HTTP ${response.code}")
      val json = JSONObject(response.body!!.string())
      return Auth(
        json.getString("accessToken"),
        Instant.parse(json.getString("expiresAt")).toEpochMilli(),
        json.getString("userId"),
      )
    }
  }

  companion object {
    private const val KEY_SECRET = "pairing_secret"
    private const val KEY_TOKEN = "access_token"
    private const val REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000L
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    }

    fun normalize(url: String): String {
      val trimmed = url.trim().trimEnd('/')
      require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        "Server URL must start with http:// or https://"
      }
      return trimmed
    }
  }
}
