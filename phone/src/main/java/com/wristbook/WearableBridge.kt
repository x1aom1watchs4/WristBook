package com.wristbook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.Node
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class WearableBridge(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var connectedNode: Node? = null

    fun connect(onStatus: (String) -> Unit) {
        findNode(onStatus) { node ->
            authorizeAndOpen(node, onStatus) {}
        }
    }

    fun sendBook(name: String, text: String, onStatus: (String) -> Unit) {
        val node = connectedNode
        if (node != null) {
            onStatus("Часы подключены: ${node.name}. Отправляем книгу…")
            transferBook(node, name, text, onStatus)
            return
        }

        onStatus("Ищем часы и настраиваем связь…")
        findNode(onStatus) { found ->
            authorizeAndOpen(found, onStatus) {
                onStatus("Отправляем книгу…")
                transferBook(found, name, text, onStatus)
            }
        }
    }

    private fun findNode(onStatus: (String) -> Unit, onFound: (Node) -> Unit) {
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                onStatus("Часы не ответили за 12 секунд. Проверь подключение в Notify for Xiaomi.")
            }
        }
        handler.postDelayed(timeout, 12_000)

        try {
            Wearable.getNodeApi(appContext).connectedNodes
                .addOnSuccessListener { nodes ->
                    if (!completed.compareAndSet(false, true)) return@addOnSuccessListener
                    handler.removeCallbacks(timeout)
                    val node = nodes.firstOrNull()
                    if (node == null) {
                        connectedNode = null
                        onStatus("Часы не найдены. Проверь, что они подключены в Notify for Xiaomi.")
                    } else {
                        connectedNode = node
                        onStatus("Нашёл часы: ${node.name}. Проверяю разрешение…")
                        onFound(node)
                    }
                }
                .addOnFailureListener { error ->
                    if (!completed.compareAndSet(false, true)) return@addOnFailureListener
                    handler.removeCallbacks(timeout)
                    connectedNode = null
                    onStatus("Не удалось найти часы: ${error.message ?: "неизвестная ошибка"}")
                }
        } catch (error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                onStatus("Ошибка поиска часов: ${error.message ?: "неизвестная ошибка"}")
            }
        }
    }

    private fun authorizeAndOpen(node: Node, onStatus: (String) -> Unit, onReady: () -> Unit) {
        try {
            Wearable.getAuthApi(appContext)
                .checkPermissions(node.id, arrayOf(Permission.DEVICE_MANAGER))
                .addOnSuccessListener { grants ->
                    if (grants.any { !it }) {
                        onStatus("Запрашиваю разрешение на связь с часами…")
                        try {
                            Wearable.getAuthApi(appContext)
                                .requestPermission(node.id, Permission.DEVICE_MANAGER)
                                .addOnSuccessListener {
                                    onStatus("Запрос разрешения отправлен. Если Notify покажет запрос — подтверди его.")
                                    openWatchApp(node, onStatus, onReady)
                                }
                                .addOnFailureListener { error ->
                                    onStatus("Не удалось запросить разрешение: ${error.message ?: "ошибка"}")
                                }
                        } catch (error: Throwable) {
                            onStatus("Ошибка запроса разрешения: ${error.message ?: "ошибка"}")
                        }
                    } else {
                        openWatchApp(node, onStatus, onReady)
                    }
                }
                .addOnFailureListener { error ->
                    onStatus("Не удалось проверить разрешение часов: ${error.message ?: "ошибка"}")
                }
        } catch (error: Throwable) {
            onStatus("Ошибка проверки разрешения: ${error.message ?: "ошибка"}")
        }
    }

    private fun openWatchApp(node: Node, onStatus: (String) -> Unit, onReady: () -> Unit) {
        try {
            Wearable.getNodeApi(appContext)
                .launchWearApp(node.id, "/pages/index")
                .addOnSuccessListener {
                    onStatus("WristBook открыт на часах. Можно передавать книгу.")
                    onReady()
                }
                .addOnFailureListener { error ->
                    onStatus("Не удалось открыть WristBook на часах: ${error.message ?: "ошибка"}")
                }
        } catch (error: Throwable) {
            onStatus("Ошибка запуска WristBook: ${error.message ?: "ошибка"}")
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
        try {
            Wearable.getMessageApi(appContext)
                .sendMessage(nodeId, payload.toByteArray(StandardCharsets.UTF_8))
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { error ->
                    onStatus("Ошибка передачи: ${error.message ?: "неизвестно"}")
                }
        } catch (error: Throwable) {
            onStatus("Ошибка отправки: ${error.message ?: "неизвестно"}")
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
