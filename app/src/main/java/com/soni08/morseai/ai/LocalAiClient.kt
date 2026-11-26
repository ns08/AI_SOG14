package com.soni08.morseai.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object LocalAiClient {

    fun generateHiraganaReply(
        endpointUrl: String,
        modelName: String,
        userPrompt: String
    ): String? {
        val trimmed = endpointUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val targetUrl = if (trimmed.endsWith("/v1/chat/completions")) {
            trimmed
        } else {
            "$trimmed/v1/chat/completions"
        }

        return try {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "あなたは音声アシスタントです。おんせいごうせいでよみあげやすいように、みじかくしぜんなにほんごでこたえてください。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }

            val payload = JSONObject().apply {
                put("model", modelName.ifBlank { "local-model" })
                put("messages", messages)
                put("temperature", 0.5)
                put("max_tokens", 180)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code !in 200..299 || body.isBlank()) return null

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msg = choices.getJSONObject(0).optJSONObject("message") ?: return null
            msg.optString("content", "").trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
