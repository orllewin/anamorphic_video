package oppen.anamorphicvideo

import android.app.*
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.util.concurrent.TimeUnit


class FFMpegService: Service() {

    companion object{
        const val BROADCAST_EVENTS = "oppen.anamorphicvideo.BROADCAST_EVENTS"
        const val BROADCAST_LOG = "oppen.anamorphicvideo.BROADCAST_LOG"
        const val BROADCAST_PROCESS_COMPLETE = "oppen.anamorphicvideo.BROADCAST_PROCESS_COMPLETE"
        const val BROADCAST_OUTPUT_URI = "oppen.anamorphicvideo.BROADCAST_OUTPUT_URI"
        const val ACTION_START_PROCESSING = "oppen.anamorphicvideo.ACTION_START_PROCESSING"
        const val EXTRA_SCALE_FACTOR = "oppen.anamorphicvideo.EXTRA_SCALE_FACTOR"
        const val EXTRA_SOURCE_URI = "oppen.anamorphicvideo.EXTRA_SOURCE_URI"

        const val LINE = "----------------------------"
    }

    private val notificationId = 10

    private var onLog: (log: String) -> Unit = { log ->
        println("FFMpegService: $log")
        Intent(BROADCAST_EVENTS).run {
            putExtra(BROADCAST_LOG, log)
            LocalBroadcastManager.getInstance(this@FFMpegService).sendBroadcast(this)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val intent = Intent(this@FFMpegService, FFMpegService::class.java)
        ContextCompat.startForegroundService(this@FFMpegService, intent)

        val notification = getNotification()
        startForeground(notificationId, notification)
    }

    private fun getNotification() : Notification {
        val notifyIntent = Intent(this, MainActivity::class.java)
        val notifyPendingIntent = PendingIntent.getActivity(
            this,
            0,
            notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channelId = "oppen.anamorphicvideo.PROCESSING"
        val channel = NotificationChannel(channelId, "Oppen Anamorphic Video", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Video processing channel"
        nm.createNotificationChannel(channel)

        val title = "Processing video"

        return NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText("processing video...")
            .setSmallIcon(R.drawable.vector_icon)
            .setContentIntent(notifyPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.run {
            when(action){
                ACTION_START_PROCESSING -> {
                    val sourceUri = intent.getParcelableExtra<Uri>(EXTRA_SOURCE_URI)
                    val scale = intent.getFloatExtra(EXTRA_SCALE_FACTOR, -1f)
                    processVideo(sourceUri, scale)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun processVideo(sourceUri: Uri?, scale: Float){
        if(sourceUri == null) throw IllegalStateException("No source URI to process")

        /**
         * Get source video dimensions
         */
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, sourceUri)
        val width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "")
        val height = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "")
        retriever.release()

        onLog("Source width: $width")
        onLog("Source height: $height")
        onLog("Source ratio: ${ratio(width, height)}")
        onLog("Source uri: $sourceUri")

        /**
         * Create output Uri, and convert in and out Uris to Storage Access Framework format
         */
        val inputVideoPath = FFmpegKitConfig.getSafParameterForRead(this, sourceUri)
        val outputUri = FileIO.createWriteFile(contentResolver, "test_video_out")
        val outputVideoPath = FFmpegKitConfig.getSafParameterForWrite(this, outputUri)

        onLog("Source uri saf: $inputVideoPath")
        onLog(LINE)

        val outputWidth = (width * scale).toInt()
        onLog("Output width: $outputWidth")
        onLog("Output height: $height")
        onLog("Output ratio: ${ratio(outputWidth, height)}")
        onLog("Output uri: $outputUri")
        onLog("Output uri saf: $outputVideoPath")
        onLog(LINE)

        val command = generateCommand(inputVideoPath, outputVideoPath, width, height, scale)

        onLog("FFMpeg command: $command")
        onLog(LINE)

        FFmpegKit.executeAsync(command, { session ->
            val state = session.state
            val stackTrace = session.failStackTrace
            val returnCode = session.returnCode
            println("Process exit return code: $returnCode")
            finished(returnCode.value, outputUri)
        }, { log ->
            //onLog(log.message)
        }) { stats ->
            val time = getTimeLabel(stats.time.toLong())
            val speed = String.format("%.2f", stats.speed)
            val bitrate = String.format("%.1f", stats.bitrate)
            val frame = stats.videoFrameNumber
            onLog("$time Frame: $frame Speed: ${speed}x Bitrate: ${bitrate}kbits/s")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun finished(exitCode: Int, outputUri: Uri?){
        onLog(LINE)
        when (exitCode) {
            0 -> {
                onLog("Video processed successfully")
                FileIO.releaseFile(contentResolver, outputUri)
            }
            else -> onLog("Error processing video")
        }

        Intent(BROADCAST_EVENTS).run {
            putExtra(BROADCAST_PROCESS_COMPLETE, exitCode)
            putExtra(BROADCAST_OUTPUT_URI, outputUri)
            LocalBroadcastManager.getInstance(this@FFMpegService).sendBroadcast(this)
        }

        stopSelf()
    }

    private fun generateCommand(inputPath: String, outputPath: String, sourceWidth: Int, sourceHeight: Int, scale: Float): String{
        var targetWidth = (sourceWidth * scale).toInt()
        if(targetWidth % 2 != 0) targetWidth -= 1

        val targetHeight = when {
            sourceHeight % 2 == 0 -> sourceHeight
            else -> sourceHeight -1
        }

        return "-i $inputPath -vf \"scale=$targetWidth:$targetHeight,setsar=1/1\" $outputPath"
    }

    private fun getTimeLabel(ms: Long): String{
        return String.format("%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(ms),
            TimeUnit.MILLISECONDS.toMinutes(ms) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(ms)),
            TimeUnit.MILLISECONDS.toSeconds(ms) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(ms)))
    }

    private fun ratio(width: Int, height: Int): String {
        val den = greatestCommonDenominator(width, height)
        return "${width/den}:${height/den}"
    }

    private fun greatestCommonDenominator(a: Int, b: Int): Int{
        var aa = a
        var bb = b
        while (aa != bb) {
            when {
                aa > bb -> aa -= bb
                else -> bb -= aa
            }
        }

        return aa
    }
}