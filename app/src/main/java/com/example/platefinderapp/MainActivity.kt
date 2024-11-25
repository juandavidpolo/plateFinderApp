package com.example.platefinderapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.View
import androidx.core.content.ContextCompat
import com.example.platefinderapp.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Base64
import java.io.ByteArrayOutputStream
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import android.os.AsyncTask


class MainActivity : AppCompatActivity() {

    lateinit var labels:List<String>
    var colors = listOf<Int>(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED)
    val paint = Paint()
    lateinit var imageProcessor: ImageProcessor
    lateinit var bitmap:Bitmap
    lateinit var imageView: ImageView
    lateinit var cameraDevice: CameraDevice
    lateinit var handler: Handler
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    lateinit var model:SsdMobilenetV11Metadata1

    lateinit var recordAdapter: RecordAdapter
    lateinit var recyclerView: RecyclerView
    private val appRecords: MutableList<AppRecord> = mutableListOf()

    // Track recently detected objects to avoid multiple requests for the same one
    private val recentlyDetectedObjects = mutableMapOf<String, Long>()
    private val objectPositions = mutableMapOf<String, RectF>()
    private val requestInterval: Long = 500  // 2 seconds interval between requests

    // Threshold to determine if the object has moved enough to trigger a new request
    private val positionChangeThreshold = 50f  // in pixels

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        get_permission()

        // Set bottom Navigation bar
        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        val homeView: FrameLayout = findViewById(R.id.nav_home)
        val historyView: FrameLayout = findViewById(R.id.nav_history)
        val historyList: RecyclerView = findViewById(R.id.recyclerView)

        recordAdapter = RecordAdapter(appRecords)
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = recordAdapter

        homeView.visibility = View.VISIBLE
        historyView.visibility = View.GONE

        // Function to toggle visibility between the views
        fun toggleView(isHome: Boolean) {
            if (isHome) {
                homeView.visibility = View.VISIBLE
                historyView.visibility = View.GONE
            } else {
                homeView.visibility = View.GONE
                historyView.visibility = View.VISIBLE
            }
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Show Home view and hide History view
                    toggleView(true)
                    true
                }

                R.id.nav_history -> {
                    // Show History view and hide Home view
                    toggleView(false)
                    true
                }

                else -> false
            }
        }

        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageView = findViewById(R.id.imageView)
        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
            }
            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                bitmap = textureView.bitmap!!
                var image = TensorImage.fromBitmap(bitmap)
                image = imageProcessor.process(image)

                val outputs = model.process(image)
                val locations = outputs.locationsAsTensorBuffer.floatArray
                val classes = outputs.classesAsTensorBuffer.floatArray
                val scores = outputs.scoresAsTensorBuffer.floatArray
                val numberOfDetections = outputs.numberOfDetectionsAsTensorBuffer.floatArray

                val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutable)

                val h = mutable.height
                val w = mutable.width
                paint.textSize = h/30f
                paint.strokeWidth = h/250f
                var x = 0

                scores.forEachIndexed { index, fl ->
                    x = index
                    x *= 4
                    if(fl > 0.60){
                        val label = labels[classes[index].toInt()]
                        val includeLabels = setOf("car", "bus", "truck");
                        if (includeLabels.contains(label)){
                            paint.setColor(colors.get(index))
                            paint.style = Paint.Style.STROKE
                            val left = locations[x + 1] * w
                            val top = locations[x] * h
                            val right = locations[x + 3] * w
                            val bottom = locations[x + 2] * h
                            val rect = RectF(left, top, right, bottom)

                            val objectKey = "${label}-${locations[x + 1]}-${locations[x]}-${locations[x + 3]}-${locations[x + 2]}"
                            val currentTime = System.currentTimeMillis()

                            // Check if object position has changed significantly (threshold for movement)
                            val previousPosition = objectPositions[objectKey]
                            if (previousPosition == null || rect.isMovedEnough(previousPosition)) {
                                // Only send request if enough time has passed or if the object is new
                                if (currentTime - (recentlyDetectedObjects[objectKey] ?: 0) >= requestInterval) {
                                    // Save the timestamp for this object
                                    recentlyDetectedObjects[objectKey] = currentTime
                                    objectPositions[objectKey] = rect

                                    val base64Image = bitmapToBase64(bitmap)
                                    val plate = generateRandomString()  // Simulated
                                    val isReported = getRandomBoolean()  // Simulated

                                    // Use AsyncTask to handle network request on a background thread
                                    RequestImageAsyncTask(historyList).execute(base64Image, plate, isReported.toString())
                                }
                            }
                            canvas.drawRect(RectF(locations.get(x+1)*w, locations.get(x)*h, locations.get(x+3)*w, locations.get(x+2)*h), paint)
                            canvas.drawText(label, locations.get(x+1)*w, locations.get(x)*h, paint)
                        }
                    }
                }
                imageView.setImageBitmap(mutable)
            }
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
    }

    @SuppressLint("MissingPermission")
    fun open_camera(){
        cameraManager.openCamera(cameraManager.cameraIdList[0], object:CameraDevice.StateCallback(){
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0

                val surfaceTexture = textureView.surfaceTexture
                val surface = Surface(surfaceTexture)

                val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(listOf(surface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(p0: CameraCaptureSession) {
                        p0.setRepeatingRequest(captureRequest.build(), null, null)
                    }
                    override fun onConfigureFailed(p0: CameraCaptureSession) {
                    }
                }, handler)
            }

            override fun onDisconnected(p0: CameraDevice) {

            }

            override fun onError(p0: CameraDevice, p1: Int) {

            }
        }, handler)
    }

    /*Request*/
    inner class RequestImageAsyncTask(val recyclerView: RecyclerView) : AsyncTask<String, Void, Pair<String?, Int?>>() {
        override fun doInBackground(vararg params: String):  Pair<String?, Int?> {
            val base64Image = params[0]
            val plate = params[1]
            val isReported = params[2].toBoolean()

            val url = "https://jsonplaceholder.typicode.com/posts"
            val client = OkHttpClient()
            val jsonObject = JSONObject()
            jsonObject.put("img", base64Image)
            jsonObject.put("plate", plate)
            jsonObject.put("isReported", isReported)

            val mediaType = "application/json".toMediaType()
            val body = RequestBody.create(mediaType, jsonObject.toString())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            return try {
                val response = client.newCall(request).execute()
                Pair(response.body?.string(), response.code)
            } catch (e: IOException) {
                Pair(null, null)
            }
        }

        override fun onPostExecute(result: Pair<String?, Int?>) {
            val (responseBody, statusCode) = result
            if (responseBody != null && statusCode == 201) {
                val records: MutableList<AppRecord> = mutableListOf()

                try {
                    // Try parsing as a single object first
                    val record = Gson().fromJson(responseBody, AppRecord::class.java)
                    records.add(record)
                } catch (e: Exception) {
                    // If it's not a single object, try parsing as an array
                    try {
                        val recordsArray = Gson().fromJson(responseBody, Array<AppRecord>::class.java)
                        records.addAll(recordsArray.toList()) // Add all the records from the array
                    } catch (e: Exception) {
                        Log.e("Response", "Error parsing response: ${e.message}")
                    }
                }

                // Update the main thread UI with new records and notify adapter
                runOnUiThread {
                    appRecords.addAll(records)  // Add the new records to the existing list
                    recordAdapter.notifyDataSetChanged()  // Notify the adapter to update the RecyclerView
                }
                Log.d("Response", "Response: $responseBody, Status Code: $statusCode")
            } else {
                Log.d("Response", "Request failed with Status Code: $statusCode")
            }
        }
    }

    fun get_permission(){
        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    private fun RectF.isMovedEnough(previous: RectF): Boolean {
        val deltaX = Math.abs(this.left - previous.left)
        val deltaY = Math.abs(this.top - previous.top)
        return deltaX > positionChangeThreshold || deltaY > positionChangeThreshold
    }

    /*Random plate generator*/
    fun generateRandomString(): String {
        val letters = ('a'..'z').toList()
        val numbers = ('0'..'9').toList()
        val randomLetters = List(3) { letters.random() }.joinToString("")
        val randomNumbers = List(3) { numbers.random() }.joinToString("")
        return randomLetters + randomNumbers
    }
    /*Random bool generator*/
    fun getRandomBoolean(): Boolean {
        return (0..1).random() == 1
    }
    /*convert img to b64*/
    fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)  // Use PNG or JPEG
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }
}