package oppen.anamorphicvideo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import oppen.anamorphicvideo.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    val showSourceVideo = false
    private val viewModel: AnamorphViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var resultLauncher: ActivityResultLauncher<Intent>
    private var requestMultiplePermissions: ActivityResultLauncher<Array<String>>? = null

    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.also { uri ->
                sourceUri = uri
                showPreview()
            }
        }

        var openFromIntent = false
        var intentUri: Uri? = null

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("video/") == true) {
                    (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
                        openFromIntent = true
                        intentUri = uri
                    }
                }
            }
        }

        val showChooserOnLaunch = getBooleanPref("open_chooser_on_launch", true)

        when{
            openFromIntent -> {
                sourceUri = intentUri
                showPreview()
            }
            hasPermissions() -> if(showChooserOnLaunch) chooseVideo()
            else -> {
                requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                    when {
                        permissions.entries.all { entry ->
                            entry.value == true
                        } -> chooseVideo()
                        else -> snack("External Storage permission required")
                    }
                }
                launchPermissions()
            }
        }

        binding.chooseButton.setOnClickListener {
            chooseVideo()
        }

        binding.saveButton.setOnClickListener {
            startService()
        }
    }

    private val br = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.run{
                when {
                    hasExtra(FFMpegService.BROADCAST_LOG) -> {
                        runOnUiThread { log(intent.getStringExtra(FFMpegService.BROADCAST_LOG)) }
                    }
                    hasExtra(FFMpegService.BROADCAST_PROCESS_COMPLETE) -> {
                        when (getIntExtra(FFMpegService.BROADCAST_PROCESS_COMPLETE, 100)) {
                            0 -> {
                                intent.getParcelableExtra<Uri>(FFMpegService.BROADCAST_OUTPUT_URI)?.let{ uri ->
                                    Snackbar.make(binding.root, "Video exported", Snackbar.LENGTH_SHORT).setAction(
                                        "Share"
                                    ){
                                        Intent(Intent.ACTION_SEND).run{
                                            type = "video/*"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            startActivity(Intent.createChooser(this, "Share"))
                                        }
                                    }.show()
                                }
                            }
                            100 -> {
                                //NOOP
                            }
                            else -> {
                                snack("Video did not process successfully")
                            }
                        }
                    }
                    else -> {
                        //NOOP
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        LocalBroadcastManager.getInstance(this).registerReceiver(br, IntentFilter(FFMpegService.BROADCAST_EVENTS))
    }

    override fun onPause() {
        super.onPause()

        LocalBroadcastManager.getInstance(this).unregisterReceiver(br)
    }

    private fun showPreview(){
        when {
            showSourceVideo -> {
                binding.videoView.visibility = View.VISIBLE
                binding.videoView.setVideoURI(sourceUri)
                binding.videoView.start()
            }
            else -> binding.videoView.visibility = View.GONE
        }

        val name = FileIO.queryName(contentResolver, sourceUri!!)
        log("$name ready, tap process to start")
    }

    private fun startService(){

        if(sourceUri == null){
            snack("No video loaded")
            return
        }

        val horizontalScaleFactor = getStringPref("horizontal_scale_factor", "1.33")?.toFloat() ?: 1.33f
        log("Horizontal scale factor: $horizontalScaleFactor")

        Intent(this, FFMpegService::class.java).also { intent ->
            intent.action = FFMpegService.ACTION_START_PROCESSING
            intent.putExtra(FFMpegService.EXTRA_SCALE_FACTOR, horizontalScaleFactor)
            intent.putExtra(FFMpegService.EXTRA_SOURCE_URI, sourceUri)
            startService(intent)
        }
    }

    private fun hasPermissions(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun launchPermissions() {
        requestMultiplePermissions?.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    private fun chooseVideo() = when {
        hasPermissions() -> {
            Intent().run {
                type = "video/*"
                action = Intent.ACTION_GET_CONTENT
                resultLauncher.launch(this)
            }
        }
        else -> launchPermissions()
    }

    private fun log(log: String?) {
        if(log == null) return
        binding.log.append("${log.trim()}\n")
        binding.logScroll.fullScroll(View.FOCUS_DOWN)
    }

    private fun snack(message: String) = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

}