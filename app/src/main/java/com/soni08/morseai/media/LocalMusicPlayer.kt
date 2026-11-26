package com.soni08.morseai.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore

object LocalMusicPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun playLocalMusic(context: Context, query: String): Boolean {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE
        )
        val selection = if (query.isNotBlank()) {
            "${MediaStore.Audio.Media.TITLE} LIKE ?"
        } else {
            null
        }
        val args = if (query.isNotBlank()) arrayOf("%$query%") else null

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            if (idIdx >= 0 && cursor.moveToFirst()) {
                val id = cursor.getLong(idIdx)
                val trackUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                stop()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, trackUri)
                    prepare()
                    start()
                }
                return true
            }
        }
        return false
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
