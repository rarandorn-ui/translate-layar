package com.bosmuda.translatelayar

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Terjemahan lewat Google Cloud Translation API (v2).
 * Harus dipanggil dari background thread, bukan main thread.
 */
object Translator {

    private const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"

    /**
     * @param source kode bahasa asal, atau null untuk deteksi otomatis
     * @param target kode bahasa tujuan
     * @return hasil terjemahan dengan urutan yang sama dengan [texts]
     */
    @Throws(Exception::class)
    fun translate(apiKey: String, texts: List<String>, source: String?, target: String): List<String> {
        val body = JSONObject().apply {
            put("q", JSONArray(texts))
            put("target", target)
            if (source != null) put("source", source)
            put("format", "text")
        }

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Goog-Api-Key", apiKey)
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(200)}")
            }

            val arr = JSONObject(text).getJSONObject("data").getJSONArray("translations")
            return List(arr.length()) { i -> arr.getJSONObject(i).getString("translatedText") }
        } finally {
            conn.disconnect()
        }
    }
}
