package com.example.platefinderapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.location.Location
import android.os.*
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.platefinderapp.ml.SsdMobilenetV11Metadata1
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.ByteArrayOutputStream
import java.io.IOException
import androidx.activity.result.contract.ActivityResultContracts


class MainActivity : AppCompatActivity() {

    private lateinit var labels: List<String>
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var cameraDevice: CameraDevice
    private lateinit var handler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var imageView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var model: SsdMobilenetV11Metadata1
    private val appRecords = mutableListOf<AppRecord>()
    private val recentlyDetectedObjects = mutableMapOf<String, Long>()
    private val objectPositions = mutableMapOf<String, RectF>()
    private val paint = Paint().apply {
        strokeWidth = 4f
        textSize = 40f
    }
    private val requestInterval: Long = 500
    private val positionChangeThreshold = 50f
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null

    private var currentView: String = "home"

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupBottomNavigation()
        initializeModel()
        initializeLocationServices()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = false
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = processFrame()
        }
    }

    private fun initializeViews() {
        imageView = findViewById(R.id.imageView)
        textureView = findViewById(R.id.textureView)
        progressBar = findViewById(R.id.progressBar)
        val historyList: RecyclerView = findViewById(R.id.recyclerView)
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = RecordAdapter(appRecords)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        handler = Handler(Looper.getMainLooper())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun setupBottomNavigation() {
        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        val homeView: FrameLayout = findViewById(R.id.nav_home)
        val historyView: FrameLayout = findViewById(R.id.nav_history)

        fun toggleView(isHome: Boolean) {
            homeView.visibility = if (isHome) View.VISIBLE else View.GONE
            historyView.visibility = if (isHome) View.GONE else View.VISIBLE
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    currentView = "home"
                    toggleView(true)
                    true
                }
                R.id.nav_history -> {
                    currentView = "history"
                    toggleView(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun initializeModel() {
        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        model = SsdMobilenetV11Metadata1.newInstance(this)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
            }
        }, handler)
    }

    private fun startPreview() {
        val surfaceTexture = textureView.surfaceTexture
        val surface = Surface(surfaceTexture)

        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
        }

        cameraDevice.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.setRepeatingRequest(requestBuilder.build(), null, handler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            handler
        )
    }

    private fun processFrame() {
        val bitmap = textureView.bitmap ?: return
        val image = imageProcessor.process(TensorImage.fromBitmap(bitmap))
        val outputs = model.process(image)
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        Canvas(mutableBitmap).apply {
            drawDetections(outputs, bitmap)
        }

        imageView.setImageBitmap(mutableBitmap)
    }

    private fun Canvas.drawDetections(outputs: SsdMobilenetV11Metadata1.Outputs, bitmap: Bitmap) {
        val scores = outputs.scoresAsTensorBuffer.floatArray
        val classes = outputs.classesAsTensorBuffer.floatArray
        val locations = outputs.locationsAsTensorBuffer.floatArray
        val width = bitmap.width
        val height = bitmap.height

        scores.forEachIndexed { index, score ->
            if (score > 0.6) {
                val label = labels[classes[index].toInt()]
                if (label in setOf("car", "bus", "truck")) {
                    val rect = RectF(
                        locations[index * 4 + 1] * width,
                        locations[index * 4] * height,
                        locations[index * 4 + 3] * width,
                        locations[index * 4 + 2] * height
                    )
                    paint.color = Color.RED
                    paint.style = Paint.Style.STROKE
                    drawRect(rect, paint)
                    drawText(label, rect.left, rect.top, paint)

                    handleDetectedObject(rect, bitmap)
                }
            }
        }
    }

    private fun initializeLocationServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
        } else {
            fetchLocation()
        }
    }

    private fun fetchLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                lastLocation = location
            } else {
                Log.e("Location", "Unable to fetch location")
            }
        }.addOnFailureListener {
            Log.e("LocationError", "Failed to fetch location: ${it.message}")
        }
    }

    private fun handleDetectedObject(rect: RectF, bitmap: Bitmap) {
        val objectKey = "${rect.left}-${rect.top}-${rect.right}-${rect.bottom}"
        val currentTime = System.currentTimeMillis()

        if (currentTime - (recentlyDetectedObjects[objectKey] ?: 0) >= requestInterval) {
            recentlyDetectedObjects[objectKey] = currentTime
            objectPositions[objectKey] = rect

            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                rect.left.toInt().coerceAtLeast(0),
                rect.top.toInt().coerceAtLeast(0),
                rect.width().toInt().coerceAtMost(bitmap.width - rect.left.toInt()),
                rect.height().toInt().coerceAtMost(bitmap.height - rect.top.toInt())
            )

            CoroutineScope(Dispatchers.IO).launch {
                val base64Image = bitmapToBase64(croppedBitmap)
                val locationData = lastLocation?.let {
                    "Lat: ${it.latitude}, Lng: ${it.longitude}"
                } ?: "Location unavailable"
                val jsonObject = JSONObject().apply {
                    put("img", croppedBitmap)
                    put("plate", generateRandomString())
                    put("isReported", getRandomBoolean())
                    put("location", locationData)
                }
                appRecords.addAll(listOf(AppRecord(generateRandomString(), getRandomBoolean(), croppedBitmap, locationData)));
                //Add notifications
                sendNotification(generateRandomString());
                //val response = sendObjectData(base64Image, generateRandomString(), getRandomBoolean())
                /*
                withContext(Dispatchers.Main) {
                    if (response != null) {
                        appRecords.addAll(parseResponse(response))
                        findViewById<RecyclerView>(R.id.recyclerView).adapter?.notifyDataSetChanged()
                    }
                }
                */

            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }

    private suspend fun sendObjectData(image: String, plate: String, isReported: Boolean): String? {
        return withContext(Dispatchers.IO) {
            val jsonObject = JSONObject().apply {
                put("img", image)
                put("plate", plate)
                put("isReported", isReported)
            }

            val request = Request.Builder()
                .url("https://jsonplaceholder.typicode.com/posts")
                .post(RequestBody.create("application/json".toMediaType(), jsonObject.toString()))
                .build()

            try {
                OkHttpClient().newCall(request).execute().body?.string()
            } catch (e: IOException) {
                Log.e("NetworkError", e.message.toString())
                null
            }
        }
    }

    private fun parseResponse(response: String): List<AppRecord> {
        return try {
            listOf(Gson().fromJson(response, AppRecord::class.java))
        } catch (e: Exception) {
            Gson().fromJson(response, Array<AppRecord>::class.java).toList()
        }
    }

    private fun generateRandomString(): String = List(3) { ('A'..'Z').random() }.joinToString("") + List(3) { ('0'..'9').random() }.joinToString("")
    private fun getRandomBoolean(): Boolean = (0..1).random() == 1

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "object_detection_channel"
            val channelName = "Object Detection Notifications"
            val channelDescription = "Notifies when a new object is detected and added to the list"
            val importance = NotificationManager.IMPORTANCE_DEFAULT

            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("NotificationPermission")
    private fun sendNotification(plate: String) {
        if (currentView != "history"){
            val channelId = "object_detection_channel"
            val notificationId = System.currentTimeMillis().toInt()

            val notification = NotificationCompat.Builder(this, channelId)
                //.setSmallIcon(R.drawable.notification) // Replace with your app's notification icon
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Placa reportada")
                .setContentText("La placa ${plate} se encuentre reportada.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.areNotificationsEnabled()
    }

    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted
        } else {
            // Permission denied
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
    }
}
