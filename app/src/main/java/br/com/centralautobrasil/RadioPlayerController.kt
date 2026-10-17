package br.com.centralautobrasil

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer

object RadioPlayerController {
    private var exoPlayer: ExoPlayer? = null
    var currentStation: RadioStation? = null
        private set

    var onStateChanged: (() -> Unit)? = null

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            exoPlayer = ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
        }
        return exoPlayer!!
    }

    fun playStation(context: Context, station: RadioStation) {
        val player = getOrCreatePlayer(context)
        currentStation = station

        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setArtist(station.region)
            .setGenre(station.category)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(station.streamUrl)
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        onStateChanged?.invoke()
    }

    fun togglePlayPause(context: Context) {
        val player = getOrCreatePlayer(context)
        if (player.isPlaying) {
            player.pause()
        } else {
            if (currentStation == null) {
                val first = AppRepository.getRadios(context).firstOrNull()
                if (first != null) {
                    playStation(context, first)
                    return
                }
            }
            player.play()
        }
        onStateChanged?.invoke()
    }

    fun stop() {
        exoPlayer?.stop()
        onStateChanged?.invoke()
    }

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true
}
