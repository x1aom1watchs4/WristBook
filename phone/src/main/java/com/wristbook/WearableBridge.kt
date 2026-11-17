package com.wristbook

import android.content.Context
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.node.Node
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class WearableBridge(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var connectedNode: Node? = null

    fun connect(onStatus: (String) -> Unit) {
        Wearable.getNodeApi(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    connectedNode = null
                    onStatus("Часы не найдены. Проверь Mi Fitness.")
                } else {
                    connectedNode = node
                    onStatus("Часы подключены: ${node.name}")
                }
            }
            .addOnFailureListener { error ->
                connectedNode = null
                onStatus("Ошибка связи: ${error.message ?: "неизвестно"}")
            }
    }

    fun sendBook(name: String, text: String, onStatus: (String) -> Unit) {
        // Always refresh the node list at send time. The startup lookup may still be
        // running, or its cached node may have gone stale while the app was open.
        onStatus("Проверяем подключение к часам…")
        Wearable.getNodeApi(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    connectedNode = null
                    onStatus("Часы не найдены. Открой Mi Fitness и проверь Bluetooth.")
                } else {
                    connectedNode = node
                    onStatus("Часы подключены: ${node.name}. Отправляем книгу…")
                    transferBook(node, name, text, onStatus)
                }
            }
            .addOnFailureListener { error ->
                connectedNode = null
                onStatus("Не удалось проверить подключение: ${error.message ?: "неизвестная ошибка"}")
            }
    }

    private fun transferBook(node: Node, name: String, text: String, onStatus: (String) -> Unit) {
        val chunks = splitSafely(text, 1400)
        sendMessage(node.id, JSONObject()
            .put("type", "book_start")
            .put("name", name)
            .put("totalChunks", chunks.size)
            .toString(), onStatus) {
            sendChunk(node.id, chunks, 0, onStatus)
        }
    }

    private fun sendChunk(nodeId: String, chunks: List<String>, index: Int, onStatus: (String) -> Unit) {
        if (index >= chunks.size) {
            sendMessage(nodeId, JSONObject().put("type", "book_end").toString(), onStatus) {
                onStatus("Книга отправлена на часы")
            }
            return
        }
        val payload = JSONObject()
            .put("type", "book_chunk")
            .put("index", index)
            .put("text", chunks[index])
            .toString()
        sendMessage(nodeId, payload, onStatus) {
            onStatus("Передача: ${index + 1}/${chunks.size}")
            sendChunk(nodeId, chunks, index + 1, onStatus)
        }
    }

    private fun sendMessage(nodeId: String, payload: String, onStatus: (String) -> Unit, onSuccess: () -> Unit) {
        Wearable.getMessageApi(appContext)
            .sendMessage(nodeId, payload.toByteArray(StandardCharsets.UTF_8))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { error ->
                onStatus("Ошибка передачи: ${error.message ?: "неизвестно"}")
            }
    }

    private fun splitSafely(value: String, maxChars: Int): List<String> {
        if (value.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        var start = 0
        while (start < value.length) {
            var end = minOf(start + maxChars, value.length)
            if (end < value.length && end > start &&
                Character.isHighSurrogate(value[end - 1]) &&
                Character.isLowSurrogate(value[end])) end--
            result += value.substring(start, end)
            start = end
        }
        return result
    }
}
