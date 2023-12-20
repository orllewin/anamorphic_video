package oppen.anamorphicvideo

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.time.OffsetDateTime


object FileIO {

    fun createWriteFile(contentResolver: ContentResolver, filename: String): Uri? {
        val values = ContentValues()

        val now = OffsetDateTime.now()
        val exportFilename = "${filename.replace(".", "_")}_${now.toEpochSecond()}"

        values.put(MediaStore.Video.Media.TITLE, exportFilename)
        values.put(MediaStore.Video.Media.DISPLAY_NAME, exportFilename)
        values.put(MediaStore.Video.Media.DATE_ADDED, now.toEpochSecond())
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Oppen")
            values.put(MediaStore.Video.Media.IS_PENDING, true)
        }

        val collection = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        return contentResolver.insert(collection, values)
    }

    fun releaseFile(contentResolver: ContentResolver, uri: Uri?){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uri?.let{
                val values = ContentValues()
                values.put(MediaStore.Video.Media.IS_PENDING, false)
                contentResolver.update(uri, values, null, null)
            }
        }
    }

    fun queryName(resolver: ContentResolver, uri: Uri): String? {
        val returnCursor: Cursor = resolver.query(uri, null, null, null, null)!!
        val nameIndex: Int = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        returnCursor.moveToFirst()
        val name: String = returnCursor.getString(nameIndex)
        returnCursor.close()
        return name
    }
}