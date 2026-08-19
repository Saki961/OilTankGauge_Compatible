package com.oilterminal.tankcalc.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class OriginalFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String =
        mimeTypeForName(resolveFile(uri).name)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFile(uri)
        val columns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        )
        return MatrixCursor(columns).apply {
            val row = newRow()
            columns.forEach { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME ->
                        row.add(file.name)
                    OpenableColumns.SIZE ->
                        row.add(file.length())
                    else ->
                        row.add(null)
                }
            }
        }
    }

    override fun openFile(
        uri: Uri,
        mode: String
    ): ParcelFileDescriptor {
        if (!mode.startsWith("r")) {
            throw FileNotFoundException("只允许读取")
        }
        val file = resolveFile(uri)
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? =
        throw UnsupportedOperationException("只读 Provider")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int =
        throw UnsupportedOperationException("只读 Provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int =
        throw UnsupportedOperationException("只读 Provider")

    private fun resolveFile(uri: Uri): File {
        val ctx = context
            ?: throw FileNotFoundException(
                "Provider 未初始化"
            )
        val segments = uri.pathSegments
        val scope: String
        val fileName: String

        when {
            segments.size == 2 &&
                segments[0] == "file" -> {
                scope = "originals"
                fileName = segments[1]
            }

            segments.size == 3 &&
                segments[0] == "file" -> {
                scope = segments[1]
                fileName = segments[2]
            }

            else ->
                throw FileNotFoundException(
                    "无效文件地址"
                )
        }

        if (scope !in ALLOWED_SCOPES) {
            throw FileNotFoundException(
                "不允许访问该目录"
            )
        }

        val directory = File(
            ctx.filesDir,
            scope
        ).canonicalFile
        val file = File(
            directory,
            fileName
        ).canonicalFile

        if (
            file.parentFile != directory ||
            !file.isFile
        ) {
            throw FileNotFoundException(
                "文件不存在"
            )
        }
        return file
    }

    companion object {
        private val ALLOWED_SCOPES =
            setOf("originals", "reports")

        fun uriFor(
            context: Context,
            file: File
        ): Uri {
            val scope =
                file.parentFile?.name.orEmpty()
            require(scope in ALLOWED_SCOPES) {
                "只允许共享原始表格或计算报表。"
            }

            return Uri.Builder()
                .scheme("content")
                .authority(
                    "${context.packageName}.originals"
                )
                .appendPath("file")
                .appendPath(scope)
                .appendPath(file.name)
                .build()
        }

        fun mimeTypeForName(
            fileName: String
        ): String =
            when (
                fileName
                    .substringAfterLast('.', "")
                    .lowercase()
            ) {
                "xlsx" ->
                    "application/vnd.openxmlformats-" +
                        "officedocument.spreadsheetml.sheet"
                "pdf" ->
                    "application/pdf"
                "txt" ->
                    "text/plain"
                "png" ->
                    "image/png"
                "jpg", "jpeg" ->
                    "image/jpeg"
                else ->
                    "application/octet-stream"
            }
    }
}
