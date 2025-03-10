package com.example.campower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.round


// Data class to hold a single CPU core’s snapshot.
data class CpuCoreSnapshot(
    val cpufreq: Map<Int, Long>,  // Map from frequency (kHz) to cumulative time (µs)
    val cpuidle: Map<String, Long> // Map from idle state name to cumulative time (µs)
)

// Overall snapshot holding stats for all CPUs and the timestamp.
data class Snapshot(
    val cpuStats: Map<Int, CpuCoreSnapshot>,
    val timestamp: Long  // Recorded in nanoseconds
)

fun getTimeString() : String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val currentDateTime = LocalDateTime.now()
    val friendlyTime = currentDateTime.format(formatter)
    return friendlyTime
}

// Helper function: Read a snapshot for one CPU core.
fun readCpuCoreSnapshot(cpuId: Int): CpuCoreSnapshot {
    val cpufreqStats = mutableMapOf<Int, Long>()
    // Read the cpufreq stats. Each line is "freq time" where time is in ms.
    val cpufreqFile = File("/sys/devices/system/cpu/cpu$cpuId/cpufreq/stats/time_in_state")
    if (cpufreqFile.exists()) {
        cpufreqFile.forEachLine { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                try {
                    val freq = parts[0].toInt()
                    val timeMs = parts[1].toLong()
                    // Convert 10ms to µs so that units match the cpuidle stats.
                    cpufreqStats[freq] = timeMs * 10000
                } catch (e: NumberFormatException) {
                    // Skip malformed line.
                }
            }
        }
    }
    val cpuidleStats = mutableMapOf<String, Long>()
    // Read cpuidle stats: list directories like /sys/devices/system/cpu/cpuX/cpuidle/stateY.
    val cpuidleDir = File("/sys/devices/system/cpu/cpu$cpuId/cpuidle")
    if (cpuidleDir.exists() && cpuidleDir.isDirectory) {
        cpuidleDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("state") }?.forEach { stateDir ->
            val nameFile = File(stateDir, "name")
            val timeFile = File(stateDir, "time")
            if (nameFile.exists() && timeFile.exists()) {
                val stateName = nameFile.readText().trim()
                try {
                    val timeUs = timeFile.readText().trim().toLong()
                    cpuidleStats[stateName] = timeUs
                } catch (e: NumberFormatException) {
                    // Skip if the value isn’t a number.
                }
            }
        }
    }
    return CpuCoreSnapshot(cpufreqStats, cpuidleStats)
}

// This function takes a snapshot of all CPU cores along with the current timestamp.
fun cpu_analyse_start(): Snapshot {
    val cpuStats = mutableMapOf<Int, CpuCoreSnapshot>()
    // List directories matching "cpu[0-9]+" under /sys/devices/system/cpu.
    val cpuDir = File("/sys/devices/system/cpu")
    cpuDir.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("cpu\\d+")) }?.forEach { cpu ->
        val cpuId = cpu.name.substring(3).toIntOrNull() ?: return@forEach
        cpuStats[cpuId] = readCpuCoreSnapshot(cpuId)
    }
    // Record the timestamp in nanoseconds.
    return Snapshot(cpuStats, System.nanoTime())
}

// This function takes a starting snapshot and, when called later,
// computes the differences and returns a formatted string with percentages,
// using the actual elapsed time as the denominator.
fun cpu_analyse_stop(startSnapshot: Snapshot): String {
    // Take a new snapshot.
    val endSnapshot = cpu_analyse_start()
    val sb = StringBuilder()

    // Compute actual elapsed time in microseconds.
    val elapsedTimeUs = (System.nanoTime() - startSnapshot.timestamp) / 1000.0

    // Process each CPU core found in the start snapshot.
    for ((cpuId, startCore) in startSnapshot.cpuStats) {
        val endCore = endSnapshot.cpuStats[cpuId] ?: continue
        sb.append("CPU$cpuId:\n")

        // Compute differences for active (cpufreq) states.
        val freqDiffs = mutableMapOf<Int, Long>()
        for ((freq, startTime) in startCore.cpufreq) {
            val endTime = endCore.cpufreq[freq] ?: 0L
            val diff = endTime - startTime
            if (diff >= 0) {
                freqDiffs[freq] = diff
            }
        }

        // Compute differences for idle (cpuidle) states.
        val idleDiffs = mutableMapOf<String, Long>()
        for ((state, startTime) in startCore.cpuidle) {
            val endTime = endCore.cpuidle[state] ?: 0L
            val diff = endTime - startTime
            if (diff >= 0) {
                idleDiffs[state] = diff
            }
        }

        // Format active state percentages.
        for ((freq, diff) in freqDiffs) {
            val percentage = diff * 100.0 / elapsedTimeUs
            sb.append("  Active @ ${freq}kHz: ${"%.2f".format(percentage)}%\n")
        }

        // Format idle state percentages.
        for ((state, diff) in idleDiffs) {
            val percentage = diff * 100.0 / elapsedTimeUs
            sb.append("  Idle ($state): ${"%.2f".format(percentage)}%\n")
        }
        sb.append("\n")
    }
    return sb.toString()
}


class TestService : Service() {

    private val CHANNEL_ID = "VideoPowerTestChannel"
    private val NOTIFICATION_ID = 1

    // Camera and recording variables
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private lateinit var toneGen : ToneGenerator
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wakeLock2: PowerManager.WakeLock

    // Battery monitoring
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var batteryManager: BatteryManager? = null
    private var logFile: File? = null
    private var logFile2: File? = null

    private var total_faces : Int =0
    private var total_frame_callbacks : Int =0

    private var outputDir: File? = null

    private val frameSemaphore = Semaphore(0)

    private var bm_scale = 0
    // Define threshold as a 5% drop from initial level
    private var bm_thresholdCharge = 0
    private var bm_prevCharge: Int = 0
    private var bm_accumulatedEnergy: Double = 0.0
    private var bm_monitoringStartTime: Long = 0
    private var bm_accumulatedEnergy2=0.0
    private var bm_started : Boolean = false
    private var bm_lastTime : Long = 0
    private var bm_runpercent : Int = 0
    private var bm_slow_iter : Int = 0
    private var bm_total_iter : Int = 0
    private var bm_teststr : String = ""
    private val doneSemaphore = Semaphore(0)


    fun playDefaultBing(durationMs: Int, volume: Int, tone : Int = ToneGenerator.TONE_PROP_BEEP) {
        // ToneGenerator.TONE_PROP_NACK
        // ToneGenerator.TONE_PROP_ACK
        // ToneGenerator.TONE_SUP_ERROR
        toneGen.startTone(tone, durationMs) // 200ms duration
    }

    fun createOutputDirectory(): File? {
        // Access the public Downloads directory
        // /storage/emulated/0/Download
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        // Format the current date and time
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // Construct the new directory name
        val newDirName = "powertest_$timestamp"
        val newDir = File(downloadsDir, newDirName)

        // Create the directory if it doesn't exist
        if (!newDir.exists()) {
            val created = newDir.mkdirs()
            if (!created) {
                // Log or handle directory creation failure
                return null
            }
        }

        return newDir
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Video Power Test Service Channel",
                NotificationManager.IMPORTANCE_MAX
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            log( "startForeground failed")
        }

        Log.d("CamPower", "Testservice onCreate...")

        toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) // 100 is the volume (0-100)
        // create unique output dir in "downloads"
        outputDir = createOutputDirectory()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        // Start background thread for camera operations
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
        Log.d("CamPower", "Init log...")
        // Initialize log file
        logFile = File(outputDir, "power_test_log.txt")
        if (!logFile!!.exists()) logFile!!.createNewFile()
        logFile2 = File(outputDir, "results.txt")
        if (!logFile2!!.exists()) logFile2!!.createNewFile()
        Log.d("CamPower", "Testservice... onCreate done")

        playDefaultBing(2000, 100, ToneGenerator.TONE_SUP_RINGTONE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MDB : running in backgroundhandler seems to give permissions issues
        //backgroundHandler.post { runTests() }
        val handlerThread = HandlerThread("BackgroundHandlerThread2").apply { start() }
        val handler = Handler(handlerThread.looper)
        handler.post { runTests() }

        //Handler(Looper.getMainLooper()).post { runTests()}
        return START_STICKY
    }

    private fun runTests() {

        // print some camera info etc

        Log.d("CamPower", "Current thread: ${Thread.currentThread().name}")

        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing != null) {
                    if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                        log("Front Camera ID: $cameraId")
                    } else if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        log("Back Camera ID: $cameraId")
                    }
                    // Check if the camera is disabled
                    val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    if (hardwareLevel == null) {
                        log("Camera $cameraId hardwareLevel is null.")
                    }
                    if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) {
                        log("Camera $cameraId is disabled or limited by policy.")
                    }
                    val desc=getCameraDescription(this, cameraId)
                    log(desc)
                }
            }
        } catch (e: CameraAccessException) {
            Log.e("CamPower", "Error accessing camera: ${e.reason}", e)
        }

        // dump some video codec info
        logVideoCodecs(this)

        // run the tests....
        log2("Running all tests...")
        val runPercents = listOf(3, 3)
        // crop means the processed/encoded region will be a sub-rectangle of the cam_wxcam_h whole area
        // the crop rect is centered and will be 1920x1080 for 4K full image, 720p for 1080p, 640x360 for 720p

        while(true) {
            //performTest(false, 1920, 1080, 30,  false, false, true, false, false, false, 10)

            performTest(true, 1920, 1080, 30,  false, false, true, false, true, false, 3)
            //performTest(false, 1920, 1080, 30,  false, false, true, false, false, false, 10)
            //performTest(true, 1920, 1080, 30,  false, false, true, false, false, false, 10)
            //performTest(true, 1920, 1080, 30,  true, false, true, false, false, false, 10)
        }

        for (runpercent in runPercents) {

            performTest(true, 1920, 1080, 30,  false, false, true, false, true, false, runpercent)
            performTest(true, 1920, 1080, 30,  false, false, true, false, false, false, runpercent)
            performTest(true, 1920, 1080, 15,  false, false, true, false, false, false, runpercent)
            performTest(true, 1920, 1080, 5, false,  false, true, false, false, false, runpercent)
            performTest(true, 1920, 1080, 30, false,  false, true, false, false, false, runpercent)
            performTest(true, 640, 480, 5,  false, false, true, false, false, false, runpercent)
            performTest(true, 1280, 720, 15, false,  false, true, false, false, false, runpercent)
            performTest(true, 1280, 720, 30, false,  false, true, false, false, false, runpercent)
            performTest(false, 3840, 2160, 15,  false, false, true, false, false, false, runpercent)
        }

        log2("Finished!")
        stopSelf()
    }

    private fun performTest(front: Boolean, cam_w: Int, cam_h: Int,
                            fps: Int,
                            zoom: Boolean,
                            crop: Boolean,
                            noiseReduction: Boolean,
                            LDC: Boolean,
                            faceDetection : Boolean,
                            EIS : Boolean,
                            runpercent: Int) {

        // Reset face counts
        total_faces = 0
        total_frame_callbacks = 0

        playDefaultBing(200, 100)

        // Prepare a unique file name for video and log for this test condition
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val teststr = "${if(front) "Frnt" else "Back"}"+
                "_${cam_w}x${cam_h}x${fps}"+
                "_Zm_${if(zoom) "Y" else "N"}"+
                "_Crp_${if(crop) "Y" else "N"}"+
                "_R$runpercent"+
                "_NR_${if(noiseReduction) "Y" else "N"}"+
                "_LDC_${if(LDC) "Y" else "N"}"+
                "_FD_${if(faceDetection) "Y" else "N"}"+
                "_EIS_${if(EIS) "Y" else "N"}"+
                "_${timestamp}"
        val videoFile = File(
            outputDir,
            teststr+".mp4"
        )

        log("start test $teststr")

        var crop_w : Int = cam_w
        var crop_h : Int = cam_h
        if (crop) {
            if (cam_w > 1920 && cam_h > 1080) {
                crop_w = 1920
                crop_h = 1080
            } else if (cam_w > 1280 && cam_h > 720) {
                crop_w = 1280
                crop_h = 720
            } else {
                crop_w = 640
                crop_h = 360
            }
        }


        // Set up MediaRecorder
        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(4_000_000) // 4Mbps
            setVideoFrameRate(fps)
            setVideoSize(crop_w, crop_h)
            prepare()
        }

        val startSnapshot = cpu_analyse_start()

        // Open camera and start recording
        openCameraAndStartRecording(cam_w, cam_h, crop_w, crop_h, front, fps, zoom, crop, noiseReduction, LDC, faceDetection, EIS)

        log("waiting for camera")
        if (frameSemaphore.tryAcquire(20, TimeUnit.SECONDS)) {
            // Camera is initialized and first frame received
            Log.d("CamPower", "camera running ok")
        } else {
            log("camera never started!")
            playDefaultBing(2000, 100, ToneGenerator.TONE_SUP_ERROR)
            return
        }

        Log.d("CamPower", "Running.... ")
        // Monitor battery every 10 seconds for 15 minutes (900 seconds)
        doBatteryMonitoring(teststr, runpercent)

        playDefaultBing(500, 100, ToneGenerator.TONE_PROP_ACK)

        Log.d("CamPower", "Test done.... ")
        val report = cpu_analyse_stop(startSnapshot)
        log("CPU state")
        log("${report}")

        stopRecording()
    }

    fun logVideoCodecs(context: Context) {
        // Log supported codecs
        log("Supported Video Codecs:")
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        codecList.codecInfos.forEach { codecInfo ->
            if (!codecInfo.isEncoder) return@forEach
            codecInfo.supportedTypes.forEach { mimeType ->
                if (mimeType.startsWith("video/")) {
                    val capabilities = codecInfo.getCapabilitiesForType(mimeType)
                    val videoCapabilities = capabilities.videoCapabilities
                    val supportedWidths = videoCapabilities.supportedWidths
                    val supportedHeights = videoCapabilities.supportedHeights
                    val supportedFrameRates = videoCapabilities.supportedFrameRates
                    log("Codec: ${codecInfo.name} MIME Type: $mimeType"+
                            " Supported Resolutions: ${supportedWidths.lower}x${supportedHeights.lower} to ${supportedWidths.upper}x${supportedHeights.upper} "+
                            "Supported Frame Rates: ${supportedFrameRates.lower} to ${supportedFrameRates.upper} fps")
                }
            }
        }
    }

    fun getCameraDescription(context: Context, cameraId: String): String {
        // dump info about the specified camera

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        val description = StringBuilder()

        description.append("=== CameraID $cameraId ===")
        // Lens facing
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val lensFacingDescription = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front-Facing Camera"
            CameraCharacteristics.LENS_FACING_BACK -> "Rear-Facing Camera"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External Camera"
            else -> "Unknown Facing"
        }
        description.append("Lens Facing: $lensFacingDescription\n")

        val physicalCameraIds = characteristics.physicalCameraIds.toTypedArray<String>()

        if (physicalCameraIds.size > 0) {
            description.append("Physical Camera IDs: " + physicalCameraIds.contentToString()+"\n")
        } else {
            description.append("No physical cameras listed. The device may use internal switching.\n")
        }

        // Sensor information
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (sensorSize != null) {
            description.append("Sensor Size: ${sensorSize.width} x ${sensorSize.height} mm\n")
        }

        val maxResolution = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (maxResolution != null) {
            description.append("Max Resolution: ${maxResolution.width} x ${maxResolution.height} pixels\n")
        }

        // Aperture (if available)
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        if (apertures != null && apertures.isNotEmpty()) {
            description.append("Aperture: f/${apertures.joinToString(", ")}\n")
        }

        // Focal lengths
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        if (focalLengths != null && focalLengths.isNotEmpty()) {
            description.append("Focal Length: ${focalLengths.joinToString(", ")} mm\n")
        }

        val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        if (zoomRange != null) {
            description.append("Supported Zoom Ratio Range: ${zoomRange.lower}x to ${zoomRange.upper}x\n")
        } else {
            description.append("Zoom ratio range not supported, use crop region instead.\n")
        }

        // Optical Stabilization
        val opticalStabilizationModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (opticalStabilizationModes != null) {
            description.append("Optical Stabilization: Supported\n")
        } else {
            description.append("Optical Stabilization: Not Supported\n")
        }

        // Flash support
        val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        description.append("Flash Available: $flashAvailable\n")

        // List supported sizes
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = map?.getOutputSizes(MediaRecorder::class.java)
        description.append("Supported video sizes: ")
        videoSizes?.forEach {
            description.append("${it.width}x${it.height},")
        }
        description.append("\n")

        // Face detect
        val max_count = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT)
        description.append("Face detect MAX_FACES $max_count \n")

        // List keys
        val availableKeys = characteristics.availableCaptureRequestKeys
        description.append("availableCaptureRequestKeys: ")
        for (key in availableKeys) {
            description.append("${key.name},")
        }
        description.append("\n")
        return description.toString()
    }

    fun chooseCamera(context: Context, front : Boolean, cam_w: Int, cam_h: Int): String {

        // try and find the best matching camera
        // pick the one that matches front/back and has the lowest sensor resolution
        // larger than the specified cam_w x cam_h

        val cameraManager = this.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var best="None"
        var best_diff : Int = 100000000
        var highest_res : String = "none"
        var max_pixels : Int =0

        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                // Check if the camera is disabled
                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                if (hardwareLevel == null || hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) {
                    Log.d("CamPower", "Camera $cameraId is disabled or limited by policy.")
                }

                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (front == true && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    Log.d("CamPower", "Skip $cameraId as back")
                    continue
                }
                if (front == false && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    Log.d("CamPower", "Skip $cameraId as front")
                    continue
                }

                val maxResolution = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                if (maxResolution != null) {
                    val diff=(cam_w-maxResolution.width)*(cam_h-maxResolution.height)
                    Log.d("CamPower", "ID ${cameraId} Max Resolution: ${maxResolution.width} x ${maxResolution.height} score $diff")

                    val pixels=maxResolution.width*maxResolution.height
                    if (pixels>max_pixels) {
                        max_pixels=pixels
                        highest_res=cameraId
                    }
                    if (diff<0) {
                        Log.d("CamPower", "Skip $cameraId as too low resolution")
                        continue
                    }
                    if (diff < best_diff) {
                        best_diff=diff
                        best=cameraId
                        Log.d("CamPower", "New best camera ${cameraId}")
                    }

                }
            }
        } catch (e: Exception) {
            Log.e("CamPower", "Error camera", e)
        }

        if (best=="None") {
            log("Could not find high enough resolution choosing highest $highest_res instead")
            best=highest_res
        }
        return best
    }

    private fun openCameraAndStartRecording(cam_w: Int,
                                            cam_h: Int,
                                            crop_w: Int,
                                            crop_h: Int,
                                            front: Boolean,
                                            fps: Int,
                                            zoom: Boolean,
                                            crop: Boolean,
                                            noiseReduction: Boolean,
                                            LDC: Boolean,
                                            faceDetection : Boolean,
                                            EIS: Boolean) {
        try {
            // For simplicity, use the first rear or front-facing camera.
            // Adjust selection logic to choose the front camera if needed.
            val cameraId = chooseCamera(this, front, cam_w, cam_h) //cameraManager.cameraIdList.first()
            Log.d("CamPower", "Chosen $cameraId")
            log("Chosen $cameraId")

            if (!backgroundThread.isAlive) {
                Log.e("CamPower", "Background thread is not alive!")
            }
            val devicePolicyManager = this.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val isCameraDisabled = devicePolicyManager.getCameraDisabled(null)
            Log.d("CamPower", "Camera disabled by policy: $isCameraDisabled")

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d("CamPower", "onOpened startCameraSession")
                    cameraDevice = device
                    startCameraSession(cam_w, cam_h, crop_w, crop_h, fps, zoom, crop, noiseReduction, LDC, faceDetection, EIS)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.d("CamPower", "camera disconnected")
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CamPower", "camera error")
                    camera.close()
                }
            }, backgroundHandler)
            Log.d("CamPower", "Camera opened")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startCameraSession(cam_w: Int,
                                   cam_h: Int,
                                   crop_w: Int,
                                   crop_h: Int,
                                   fps: Int,
                                   zoom: Boolean,
                                   crop: Boolean,
                                   noiseReduction: Boolean,
                                   LDC: Boolean,
                                   faceDetection : Boolean,
                                   EIS: Boolean) {
        Log.d("CamPower", "startCameraSession")
        try {
            val recorderSurface = mediaRecorder.surface
            cameraDevice?.createCaptureSession(
                listOf(recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(recorderSurface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))

                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                                if (crop) {
                                    val left: Int = (cam_w - crop_w) / 2
                                    val right: Int = left + crop_w
                                    val top: Int = (cam_h - crop_h) / 2
                                    val bottom: Int = top + crop_h
                                    val cropRegion = Rect(left, top, right, bottom)
                                    set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                                }

                                if(noiseReduction)
                                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                                else
                                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)

                                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                                set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)

                                if (EIS)
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                                else
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

                                if (faceDetection) {
                                    //set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL)
                                    set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                                } else
                                    set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)

                                if (LDC)
                                    set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
                                else
                                    set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)

                                if (zoom) {
                                    // Ultrawide (for macro)	0.5x to 0.7x
                                    // Dedicated Macro Lens	1.0x (via lens switch, not zoom)
                                    // Telephoto (macro capable)	2.0x to 3.0x

                                    set(CaptureRequest.CONTROL_ZOOM_RATIO, 10f);
                                    /*set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_MACRO
                                    );

                                    val possibleKeys = listOf(
                                        "org.codeaurora.qcamera3.lens_type",
                                        "com.xiaomi.camera.lensType",
                                        "xiaomi.camera.lensType",
                                        "xiaomi.lens.type",
                                        "vendor.qti.camera.lens.type"
                                    )*/

                                    //0: Main (Wide)
                                    //1: Ultra-Wide
                                    //2: Telephoto
                                    //3: Macro

                                    /*for (keyName in possibleKeys) {
                                        try {
                                            val customKey =
                                                CaptureRequest.Key<Int>(keyName, Int::class.java)
                                            set(customKey, 2) // Telephoto
                                            Log.d(
                                                "VendorTag",
                                                "Successfully set $keyName to Telephoto."
                                            )
                                            break
                                        } catch (e: IllegalArgumentException) {
                                            Log.d("VendorTag", "Failed with $keyName: ${e.message}")
                                        }
                                    }*/
                                }
                            }
                            // submit single capture to check things are working
                            val captureRequest=builder.build()
                            session.capture(
                                captureRequest,
                                object : CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        log("First frame capture completed.")
                                        val focalLength = result[CaptureResult.LENS_FOCAL_LENGTH]
                                        if (focalLength != null) {log("Focal Length used: $focalLength mm")}
                                        val zoomRatio = result[CaptureResult.CONTROL_ZOOM_RATIO]
                                        if (zoomRatio != null) {log("Zoom ratio used: $zoomRatio")}
                                        val cropRegion: Rect? = result[CaptureResult.SCALER_CROP_REGION]
                                        if (cropRegion != null) { log("check: Crop ${cropRegion.width()} x ${cropRegion.height()}")}
                                        val aeFpsRange: Range<Int>? = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                                        aeFpsRange?.let {
                                            log("check: Auto-Exposure Frame Rate Range: ${it.lower} - ${it.upper} fps") }
                                        val eis = result[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]
                                        if (eis != null) {  log("check: EIS: $eis") }
                                        val ldc = result[CaptureResult.DISTORTION_CORRECTION_MODE]
                                        if (ldc != null) {  log("check: LDC: $ldc") }
                                        val nr = result[CaptureResult.NOISE_REDUCTION_MODE]
                                        if (nr != null) {  log("check: NR: $nr") }
                                        val fd = result[CaptureResult.STATISTICS_FACE_DETECT_MODE]
                                        if (fd != null) {  log("check: Face detection: $fd") }
                                        frameSemaphore.release()
                                    }
                                },
                                null
                            )
                            // now the repeating request
                            session.setRepeatingRequest(captureRequest, object : CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    total_frame_callbacks += 1
                                    if (faceDetection) {
                                        if ((total_frame_callbacks % 30) == 0) {
                                            val faces: Array<Face>? =
                                                result[CaptureResult.STATISTICS_FACES]
                                            if (faces != null) {
                                                val faceCount = faces.size
                                                total_faces += faceCount
                                                Log.d("CamPower", "${faces.size} faces")
                                            }
                                        }
                                    }
                                }
                            }, backgroundHandler)

                            Log.d("CamPower", "Starting media recorder")
                            mediaRecorder.start()
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CamPower", "CameraCaptureSession onConfigureFailed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        try {
            captureSession?.stopRepeating()
            mediaRecorder.stop()
            mediaRecorder.reset()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        captureSession?.close()
        cameraDevice?.close()
    }

    fun roundDouble(number: Double, decimals: Int): Double {
        require(decimals >= 0) { "Decimal places must be non-negative" }
        val factor = 10.0.pow(decimals)
        return round(number * factor) / factor
    }

    private fun bm_iter(): Int {
        val batteryStatus: Intent? =
            applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltage =
            batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0 // in mV
        // current is in uA on most devices but mA on some :(
        val currentMilliAmps = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        // get temperature
        val batteryTemperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temperatureCelsius = batteryTemperature / 10.0

        // charge stuff - charge is in uAh
        val currentCharge = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: bm_prevCharge
        var deltaCharge = bm_prevCharge - currentCharge

        // how annoying
        // in order to improve accuracy we only start/stop the test /accumulate energy
        // when we see a drop in charge (which can be infrequent).

        var timeToStop=false
        var nearlyTimeToStop= false

        if (deltaCharge>0) {
            if (bm_started==false)
            {
                bm_monitoringStartTime = System.currentTimeMillis() // reset start time to now
                bm_lastTime=bm_monitoringStartTime
                bm_thresholdCharge = currentCharge-45000*bm_runpercent // set charge stop point, assumes approx 4.5Mah battery
                bm_started=true
                deltaCharge=0
            }
            timeToStop=currentCharge<bm_thresholdCharge
            nearlyTimeToStop=(currentCharge-deltaCharge)<bm_thresholdCharge // within one drop of ending
            bm_accumulatedEnergy += ((deltaCharge * voltage.toDouble())/1000000000.0)
        }
        bm_prevCharge = currentCharge

        val friendlyTime = getTimeString()
        if (!bm_started) {
            log("Time: ${getTimeString()}, Charge: $currentCharge WAITING FOR CHARGE DROP")
            playDefaultBing(50, 45)
            return 2000
        }

        // measure time since last iteration
        val currentTime = System.currentTimeMillis()
        val iter_elapsed_seconds=(currentTime-bm_lastTime)/1000.0
        val iter_elapsed_hours= (currentTime-bm_lastTime) / (1000.0 * 60.0 * 60.0)
        bm_lastTime=currentTime
        bm_total_iter+=1
        if (iter_elapsed_seconds>60) {
            bm_slow_iter += 1000
        }
        if (bm_slow_iter!=0) {
            playDefaultBing(50, 100, ToneGenerator.TONE_SUP_ERROR)
        }
        /*else {
            if ((bm_total_iter % 2)==0) {
                playDefaultBing(50, 100, ToneGenerator.TONE_PROP_ACK)
            }
        }*/

        // Calculate elapsed time in hours for average power calculation
        val elapsedTimeMillis = currentTime - bm_monitoringStartTime
        val elapsedTimeHours = elapsedTimeMillis / (1000.0 * 60.0 * 60.0)
        val averagePower = bm_accumulatedEnergy /(elapsedTimeHours+0.00001)

        // stats using current current
        var power = currentMilliAmps*voltage/-1000000.0
        bm_accumulatedEnergy2 += power*iter_elapsed_hours
        val averagePower2=bm_accumulatedEnergy2/(elapsedTimeHours+0.00001)

        // Get current battery level
        val currentBatteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val currentBatteryLevelPct=currentBatteryLevel * 100 / bm_scale.toDouble()

        log(
            "Time: $friendlyTime,  Slow $bm_slow_iter NrlyStp: $nearlyTimeToStop "+
                    "BTemp: $temperatureCelsius " +
                    "Voltage: $voltage mV, Current: $currentMilliAmps, "+
                    "Power(cur): ${roundDouble(power,3)} "+
                    "Charge: $currentCharge, ThrCharge: $bm_thresholdCharge " +
                    "AccEnergy(cur): ${roundDouble(bm_accumulatedEnergy2,3)} " +
                    "AccEnergy(chg): ${roundDouble(bm_accumulatedEnergy,3)} " +
                    "Hours: ${roundDouble(elapsedTimeHours,2)} " +
                    "AvgPower(cur): ${roundDouble(averagePower2,3)} " +
                    "AvgPower(chg): ${roundDouble(averagePower,3)} " +
                    "Bat: ${currentBatteryLevelPct}% " +
                    "Faces: $total_faces CamFr: $total_frame_callbacks "
        )

        // Check threshold and handle stopping if needed...
        if (timeToStop) {
            Log.d("CamPower", "Threshold reached with handler.")
            log2("$friendlyTime $bm_teststr "+
                    "Bat: ${currentBatteryLevelPct}% "+
                    "Hours: ${roundDouble(elapsedTimeHours,2)} "+
                    "Energy(cur): ${roundDouble(bm_accumulatedEnergy2,3)} "+
                    "Energy(chg): ${roundDouble(bm_accumulatedEnergy,3)} "+
                    "AvgPower(cur): ${roundDouble(averagePower2,3)} "+
                    "AvgPower(chg): ${roundDouble(averagePower,3)} "+
                    "SlowIter: ${bm_slow_iter} CamFr: $total_frame_callbacks")
            return -1
        }

        // we run the loop more frequently close to the stop point
        // want to end up as close to the charge drop as possible
        if (nearlyTimeToStop) {
            return 5000
        }
        else {
            return 30000
        }
    }

    private fun doBatteryMonitoring(teststr: String, runpercent: Int) {
        // Retrieve initial battery status
        val initialStatusIntent: Intent? =
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        bm_scale = initialStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        // Define threshold as a 5% drop from initial level
        bm_thresholdCharge = 0
        bm_prevCharge= batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        bm_accumulatedEnergy = 0.0
        bm_monitoringStartTime = System.currentTimeMillis()
        bm_accumulatedEnergy2=0.0
        bm_started = false
        bm_lastTime = System.currentTimeMillis()
        bm_runpercent=runpercent
        bm_teststr=teststr
        bm_slow_iter=0
        bm_total_iter=0

        // jump through hoops here to try and choose a reliable mechanism that
        // works in the background - who knows....
        val handlerThread = HandlerThread("BackgroundHandlerThread").apply { start() }
        val handler = Handler(handlerThread.looper)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Campower::BatteryMonitor")
        wakeLock.acquire(10 * 60 * 1000L)

        val task: Runnable = object : Runnable {
            override fun run() {
                log("hello from background run wl:${wakeLock.isHeld}")

                if (::wakeLock.isInitialized && wakeLock.isHeld) {
                    try {
                        //log("${getTimeString()} Releasing wakelock")
                        wakeLock.release()
                    } catch (e: RuntimeException) {
                        //log("WakeLock already released or not held: ${e.message}")
                    }
                }

                wakeLock.acquire(10 * 60 * 1000L)
                //log("${getTimeString()} Wakelock re-acquired")

                val delay=bm_iter()

                if (delay>0) {
                    handler.postDelayed(this, delay.toLong()) // reschedule
                }
                else {
                    doneSemaphore.release()
                    handlerThread.quitSafely()
                }
            }
        }
        handler.post(task)
        log("task started; waiting for done semaphore")
        if (doneSemaphore.tryAcquire(30*60*60, TimeUnit.SECONDS)) {
            // Camera is initialized and first frame received
            log("Done semaphore ok")
        } else {
            log("Done semaphore timeout")
        }
        wakeLock.release()
    }

    private fun log(message: String) {
        // Append message to log file
        Log.d("CamPower", message)

        try {
            FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                writer.appendLine(message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun log2(message: String) {
        // Append message to log file
        Log.d("CamPower", message)

        try {
            FileOutputStream(logFile2, true).bufferedWriter().use { writer ->
                writer.appendLine(message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Power Test Running")
            .setContentText("Recording and analyzing video power consumption")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundThread.quitSafely()
        scheduler.shutdownNow()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
