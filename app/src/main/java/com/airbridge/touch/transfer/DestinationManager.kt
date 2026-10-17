package com.airbridge.touch.transfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.airbridge.touch.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DestinationManager: creates non-conflicting target document names under a
 * user-chosen SAF tree. Never overwrites existing files.
 */
class DestinationManager(private val context: Context) {

    /** Returns a Uri for a brand-new, non-existing document named [desiredName]. */
    suspend fun createUniqueDestination(treeUri: Uri, desiredName: String, mimeType: String): Uri =
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw AppError.StorageError("Receive folder is unavailable. Re-select it in Settings.")
            var base = desiredName.substringBeforeLast('.', desiredName)
            var ext = desiredName.substringAfterLast('.', "")
            if (base.isBlank()) base = "received"
            var candidate = if (ext.isNotEmpty()) "$base.$ext" else base
            var n = 1
            while (tree.findFile(candidate)?.exists() == true) {
                n++
                candidate = if (ext.isNotEmpty()) "$base ($n).$ext" else "$base ($n)"
                if (n > 9999) throw AppError.StorageError("Could not find a free file name.")
            }
            val created = if (ext.isNotEmpty()) {
                tree.createFile(mimeType, candidate) ?: throw AppError.StorageError("Could not create the file.")
            } else {
                tree.createFile(mimeType, candidate) ?: throw AppError.StorageError("Could not create the file.")
            }
            created.uri
        }

    fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "apk" -> "application/vnd.android.package-archive"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            else -> "application/octet-stream"
        }
    }
}
