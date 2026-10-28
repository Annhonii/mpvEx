package app.marlboroadvance.mpvex.utils.download

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * OkHttp based [Downloader] required by NewPipeExtractor.
 */
class NewPipeDownloader(
  private val client: OkHttpClient,
) : Downloader() {
  override fun execute(request: Request): Response {
    val httpMethod = request.httpMethod()
    val url = request.url()
    val headers = request.headers()
    val dataToSend = request.dataToSend()

    val body = dataToSend?.toRequestBody()

    val builder =
      okhttp3.Request
        .Builder()
        .method(httpMethod, body)
        .url(url)
        .addHeader("User-Agent", USER_AGENT)

    headers.forEach { (name, values) ->
      if (values.size > 1) {
        builder.removeHeader(name)
        values.forEach { builder.addHeader(name, it) }
      } else if (values.size == 1) {
        builder.header(name, values[0])
      }
    }

    client.newCall(builder.build()).execute().use { response ->
      if (response.code == 429) {
        throw ReCaptchaException("reCaptcha Challenge requested", url)
      }

      val responseBody = response.body.string()
      val latestUrl = response.request.url.toString()

      return Response(
        response.code,
        response.message,
        response.headers.toMultimap(),
        responseBody,
        latestUrl,
      )
    }
  }

  companion object {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
  }
}
