package com.wristbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Book(val name: String, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val bridge = remember { WearableBridge(context) }
            var transferStatus by remember { mutableStateOf("Проверяем подключение к часам…") }
            var books by remember { mutableStateOf(listOf<Book>()) }
            var selected by remember { mutableStateOf<Book?>(null) }

            LaunchedEffect(Unit) {
                bridge.connect { transferStatus = it }
            }

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val name = DocumentFile.fromSingleUri(this, it)?.name ?: "Книга.txt"
                    if (!name.endsWith(".txt", ignoreCase = true)) {
                        transferStatus = "Пока поддерживаются только TXT-файлы"
                    } else {
                        lifecycleScope.launch {
                            val content = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(it)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                                    reader.readText()
                                }.orEmpty()
                            }
                            val book = Book(name, content)
                            books = books + book
                            selected = book
                            transferStatus = "Книга добавлена"
                        }
                    }
                }
            }

            MaterialTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                        Text("WristBook", style = MaterialTheme.typography.headlineMedium)
                        Text(transferStatus, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { picker.launch(arrayOf("text/plain")) }) {
                            Text("Добавить TXT-книгу")
                        }
                        Spacer(Modifier.height(12.dp))
                        selected?.let { book ->
                            Text(book.name, style = MaterialTheme.typography.titleLarge)
                            Text(book.text.take(2500), maxLines = 8)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                transferStatus = "Отправляем книгу…"
                                bridge.sendBook(book.name, book.text) { transferStatus = it }
                            }) {
                                Text("Передать на часы")
                            }
                            OutlinedButton(onClick = { selected = null }) {
                                Text("К библиотеке")
                            }
                        } ?: LazyColumn {
                            items(books) { book ->
                                TextButton(onClick = { selected = book }) { Text(book.name) }
                            }
                        }
                    }
                }
            }
        }
    }
}
