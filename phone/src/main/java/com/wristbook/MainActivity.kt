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
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

data class Book(val name: String, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var books by remember { mutableStateOf(listOf<Book>()) }
            var selected by remember { mutableStateOf<Book?>(null) }
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(
                        it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val name = DocumentFile.fromSingleUri(this, it)?.name ?: "Книга"
                    val content = contentResolver.openInputStream(it)?.bufferedReader()?.use { r ->
                        r.readText()
                    }.orEmpty()
                    val book = Book(name, content)
                    books = books + book
                    selected = book
                }
            }

            MaterialTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                        Text("WristBook", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/pdf")) }) {
                            Text("Добавить книгу")
                        }
                        Spacer(Modifier.height(12.dp))
                        selected?.let { book ->
                            Text(book.name, style = MaterialTheme.typography.titleLarge)
                            Text(book.text.take(5000))
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { selected = null }) { Text("К библиотеке") }
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
