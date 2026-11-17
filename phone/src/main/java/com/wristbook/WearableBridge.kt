package com.wristbook

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.node.Node
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class WearableBridge(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var connectedNode: Node? = null

    fun connect(onStatus: (String) -> Unit) {
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                onStatus("Проверка часов заняла больше 12 секунд. Проверь Mi Fitness и Bluetooth.")
            }
        }
        handler.postDelayed(timeout, 12_000)

        Wearable.getNodeApi(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                if (!completed.compareAndSet(false, true)) return@addOnSuccessListener
                handler.removeCallbacks(timeout)
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
                if (!completed.compareAndSet(false, true)) return@addOnFailureListener
                handler.removeCallbacks(timeout)
                connectedNode = null
                onStatus("Ошибка связи: ${error.message ?: "неизвестно"}")
            }
    }

    fun sendBook(name: String, text: String, onStatus: (String) -> Unit) {
        // Reuse the node discovered when the app opened instead of querying it again.
        val knownNode = connectedNode
        if (knownNode != null) {
            onStatus("Часы подключены: ${knownNode.name}. Отправляем книгу…")
            transferBook(knownNode, name, text, onStatus)
            return
        }

        onStatus("Проверяем подключение к часам…")
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                onStatus("Часы не ответили на проверку за 12 секунд. Проверь Mi Fitness и попробуй ещё раз.")
            }
        }
        handler.postDelayed(timeout, 12_000)

        Wearable.getNodeApi(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                if (!completed.compareAndSet(false, true)) return@addOnSuccessListener
                handler.removeCallbacks(timeout)
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
                if (!completed.compareAndSet(false, true)) return@addOnFailureListener
                handler.removeCallbacks(timeout)
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
