package com.discountscreener.android.data.portfolio

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

const val DEFAULT_BOOK_CSV_MAX_BYTES: Long = 4L * 1024L * 1024L

internal class BookCsvReadGeneration {
    private var currentToken = 0L

    fun begin(): Long {
        currentToken++
        return currentToken
    }

    fun cancel() {
        currentToken++
    }

    fun isCurrent(token: Long): Boolean = token == currentToken
}

sealed class BookCsvInputException(message: String) : Exception(message) {
    class Unreadable(uri: Uri) : BookCsvInputException("The chosen file refused to open for reading: $uri")

    class TooLarge(val maxBytes: Long) :
        BookCsvInputException("The chosen file is larger than ${maxBytes} bytes.")
}

/** Reads a selected book file on IO and stops before an unbounded allocation. */
class BookCsvInputReader(
    private val openStream: (Uri) -> InputStream?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Long = DEFAULT_BOOK_CSV_MAX_BYTES,
) {
    init {
        require(maxBytes > 0L) { "The book CSV size bound must be positive." }
    }

    constructor(
        contentResolver: ContentResolver,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        maxBytes: Long = DEFAULT_BOOK_CSV_MAX_BYTES,
    ) : this(
        openStream = { uri -> contentResolver.openInputStream(uri) },
        ioDispatcher = ioDispatcher,
        maxBytes = maxBytes,
    )

    suspend fun read(source: Uri): String = withContext(ioDispatcher) {
        currentCoroutineContext().ensureActive()
        val stream = openStream(source) ?: throw BookCsvInputException.Unreadable(source)
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = readChunk(input, buffer)
                if (count < 0) break
                total += count.toLong()
                if (total > maxBytes) throw BookCsvInputException.TooLarge(maxBytes)
                output.write(buffer, 0, count)
            }
            currentCoroutineContext().ensureActive()
            output.toString(Charsets.UTF_8.name())
        }
    }

    private suspend fun readChunk(input: InputStream, buffer: ByteArray): Int = try {
        runInterruptible { input.read(buffer) }
    } catch (error: InterruptedIOException) {
        currentCoroutineContext().ensureActive()
        throw error
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 8 * 1024
    }
}
