/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.languagetool.markup.AnnotatedText
import org.languagetool.markup.TextPart
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers

// Thrown when the server cannot be reached: the laptop has no route to the
// server (see checkRouteToServer), or a new connection fails
// (java.net.ConnectException, which Java's HttpClient throws both for a host
// name that cannot be resolved and for a refused connection). Any HTTP
// response, whatever its status, means the server was reached. DocumentChecker
// logs the failure or, with the offline fallback on, checks with the bundled
// LanguageTool.
class LanguageToolHttpServerUnreachableException(
  val reason: IOException,
) : RuntimeException(reason)

class LanguageToolHttpInterface(
  uriString: String,
  private val languageShortCode: String,
  private val motherTongueShortCode: String,
  private val preferredVariants: List<String> = emptyList(),
) : LanguageToolInterface() {
  private val enabledRules: MutableList<String> = ArrayList()
  private var httpClient: HttpClient = HttpClient.newHttpClient()
  private val uri: URI?

  init {
    var exception: Exception? = null
    this.uri =
      try {
        if (uriString.last().toString() == "/") {
          URI(uriString + "v2/check").toURL().toURI()
        } else {
          URI(uriString + "/v2/check").toURL().toURI()
        }
      } catch (e: MalformedURLException) {
        exception = e
        null
      } catch (e: URISyntaxException) {
        exception = e
        null
      }

    if (exception != null) {
      Logging.LOGGER.severe(I18n.format("couldNotParseHttpServerUri", exception, uriString))
    }
  }

  fun getURIString(): String = this.uri.toString()

  override fun isInitialized(): Boolean = (this.uri != null)

  override fun checkInternal(
    annotatedTextFragment: AnnotatedTextFragment,
  ): List<LanguageToolRuleMatch> {
    if (!isInitialized()) return emptyList()

    val requestBody: String = createRequestBody(annotatedTextFragment) ?: return emptyList()
    val httpResponse: HttpResponse<String> =
      sendCheck(requestBody) ?: return emptyList()

    val statusCode: Int = httpResponse.statusCode()

    if (statusCode != STATUS_CODE_SUCCESS) {
      Logging.LOGGER.severe(I18n.format("languageToolFailedWithStatusCode", statusCode))
      return emptyList()
    }

    val responseBody: String = httpResponse.body()
    val jsonResponse: JsonObject = JsonParser.parseString(responseBody).asJsonObject

    // When language=auto, the server reports the language it picked as
    // jsonResponse.language.code. Record it on the CodeFragment so downstream consumers —
    // LanguageToolRuleMatch.fromLanguageTool, CodeActionProvider (add-to-dictionary,
    // disable-rule, hide-false-positive) — key per-language correctly instead of under
    // the literal "auto". The public endpoints (api.languagetool.org /
    // api.languagetoolplus.com) ship with the ngram/fasttext detector and always return
    // a concrete variant for Category 1 bases. A self-hosted languagetool-server without
    // the ngram language-data download falls back to a weaker detector and may return
    // the bare base "en"/"de"/"pt" — that would re-introduce the Category 1 silent-
    // disable. promoteToPreferredVariant is a no-op when the returned code already has
    // a region (every public-endpoint response) and otherwise promotes via the user's
    // preferredVariants, mirroring what the Java backend does at detection time.
    if (annotatedTextFragment.codeFragment.languageShortCode == "auto") {
      jsonResponse
        .get("language")
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.get("code")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.let {
          annotatedTextFragment.codeFragment.languageShortCode =
            Settings.promoteToPreferredVariant(it, this.preferredVariants)
        }
    }

    val jsonMatches: JsonArray = jsonResponse.get("matches").asJsonArray
    val result = ArrayList<LanguageToolRuleMatch>()

    for (jsonElement: JsonElement in jsonMatches) {
      result.add(
        LanguageToolRuleMatch.fromLanguageTool(jsonElement.asJsonObject, annotatedTextFragment),
      )
    }

    return result
  }

  // Sends the check and returns the response, or logs the failure and returns
  // null. Throws LanguageToolHttpServerUnreachableException when the laptop
  // has no route to the server or a new connection fails.
  private fun sendCheck(requestBody: String): HttpResponse<String>? {
    val httpRequest: HttpRequest =
      HttpRequest
        .newBuilder(this.uri)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(BodyPublishers.ofString(requestBody))
        .build()

    checkRouteToServer()

    return try {
      this.httpClient.send(httpRequest, BodyHandlers.ofString())
    } catch (e: ConnectException) {
      throw LanguageToolHttpServerUnreachableException(e)
    } catch (e: InterruptedException) {
      Logging.LOGGER.severe(I18n.format("couldNotSendHttpRequestToLanguageTool", e))
      null
    } catch (e: IOException) {
      Logging.LOGGER.severe(I18n.format("couldNotSendHttpRequestToLanguageTool", e))
      null
    }
  }

  // Detects a laptop that is not connected to the internet, before the check
  // is sent. The client keeps its connection to the server open between
  // checks, and when the laptop goes offline that connection reports no
  // error: a check sent over it would wait until the network is back.
  // "Connecting" a UDP socket asks the operating system for a route to the
  // server without sending anything; it takes about a millisecond. Offline,
  // it fails with SocketException ("Network is unreachable"), or the host
  // name cannot be resolved (UnknownHostException). A host name can have
  // several addresses (IPv4 and IPv6); the laptop counts as offline only if
  // none of them has a route. This does not test whether the server works.
  private fun checkRouteToServer() {
    val uri: URI = this.uri ?: return
    val port: Int =
      when {
        uri.port >= 0 -> uri.port
        uri.scheme == "https" -> HTTPS_PORT
        else -> HTTP_PORT
      }

    var failure: IOException? = null
    val addresses: Array<InetAddress> =
      try {
        InetAddress.getAllByName(uri.host)
      } catch (e: IOException) {
        failure = e
        emptyArray()
      }

    // Stops at the first address with a route.
    for (address: InetAddress in addresses) failure = routeFailure(address, port) ?: return

    // The open connection is unusable: close it, so the next check opens a new one.
    this.httpClient.shutdownNow()
    this.httpClient = HttpClient.newHttpClient()
    throw LanguageToolHttpServerUnreachableException(failure ?: IOException(uri.host))
  }

  private fun createRequestBody(annotatedTextFragment: AnnotatedTextFragment): String? {
    val jsonData = JsonObject()
    jsonData.add("annotation", convertAnnotatedTextToJson(annotatedTextFragment.annotatedText))

    val requestEntries = HashMap<String, String>()
    requestEntries["language"] = this.languageShortCode
    if (this.languageShortCode == "auto" && this.preferredVariants.isNotEmpty()) {
      requestEntries["preferredVariants"] = this.preferredVariants.joinToString(",")
    }
    requestEntries["data"] = jsonData.toString()
    Logging.LOGGER.finer("requestEntries[\"data\"].length = " + requestEntries["data"]!!.length)

    if (this.languageToolOrgUsername.isNotEmpty()) {
      requestEntries["username"] = this.languageToolOrgUsername
    }

    if (this.languageToolOrgApiKey.isNotEmpty()) {
      requestEntries["apiKey"] = this.languageToolOrgApiKey
    }

    if (annotatedTextFragment.codeFragment.settings.enablePickyRules) {
      requestEntries["level"] = "picky"
    }

    if (this.motherTongueShortCode.isNotEmpty()) {
      requestEntries["motherTongue"] = this.motherTongueShortCode
    }

    if (this.enabledRules.isNotEmpty()) {
      requestEntries["enabledRules"] = this.enabledRules.joinToString(",")
    }

    val builder = StringBuilder()

    for ((requestKey: String, requestValue: String) in requestEntries) {
      if (builder.isNotEmpty()) builder.append("&")

      try {
        builder
          .append(URLEncoder.encode(requestKey, "utf-8"))
          .append("=")
          .append(URLEncoder.encode(requestValue, "utf-8"))
      } catch (e: UnsupportedEncodingException) {
        Logging.LOGGER.severe(I18n.format(e))
        return null
      }
    }

    return builder.toString()
  }

  override fun activateDefaultFalseFriendRules() {
    // handled by LanguageTool HTTP server
  }

  override fun activateLanguageModelRules(languageModelRulesDirectory: String) {
    // handled by LanguageTool HTTP server
  }

  override fun enableRules(ruleIds: Set<String>) {
    this.enabledRules.addAll(ruleIds)
  }

  override fun enableEasterEgg() {
    // not possible with LanguageTool HTTP server
  }

  companion object {
    private const val STATUS_CODE_SUCCESS = 200
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443

    // Null if the operating system has a route to the address, otherwise why not.
    private fun routeFailure(
      address: InetAddress,
      port: Int,
    ): IOException? =
      try {
        DatagramSocket().use { it.connect(InetSocketAddress(address, port)) }
        null
      } catch (e: IOException) {
        e
      }

    private fun convertAnnotatedTextToJson(annotatedText: AnnotatedText): JsonElement {
      val jsonDataAnnotation = JsonArray()
      val parts: List<TextPart> = annotatedText.parts
      var i = 0

      while (i < parts.size) {
        val jsonPart = JsonObject()

        if (parts[i].type == TextPart.Type.TEXT) {
          jsonPart.addProperty("text", parts[i].part)
        } else if (parts[i].type == TextPart.Type.MARKUP) {
          jsonPart.addProperty("markup", parts[i].part)

          if ((i < parts.size - 1) && (parts[i + 1].type == TextPart.Type.FAKE_CONTENT)) {
            i++
            jsonPart.addProperty("interpretAs", parts[i].part)
          }
        } else {
          // should not happen
          i++
          continue
        }

        jsonDataAnnotation.add(jsonPart)
        i++
      }

      return jsonDataAnnotation
    }
  }
}
