package com.example.platefinderapp

import com.example.platefinderapp.AppRecord

import com.example.platefinderapp.RecordAdapter
import android.Manifest
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
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.android.volley.RequestQueue
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

    // Track recently detected objects to avoid multiple requests for the same one
    private val recentlyDetectedObjects = mutableMapOf<String, Long>()
    private val requestInterval: Long = 2000  // 2 seconds interval between requests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        get_permission()

        // Set bottom Navigation bar
        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        val homeView: FrameLayout = findViewById(R.id.nav_home)
        val historyView: FrameLayout = findViewById(R.id.nav_history)
        val historyList: RecyclerView = findViewById(R.id.recyclerView)

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

        // Sample data for history view
        val records = listOf(
            AppRecord("Record 1"),
            AppRecord("Record 2"),
            AppRecord("Record 3"),
            AppRecord("Record 4"),
            AppRecord("Record 5")
        )
        // Set up RecyclerView
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = RecordAdapter(records)

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

                var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutable)

                val h = mutable.height
                val w = mutable.width
                paint.textSize = h/30f
                paint.strokeWidth = h/250f
                var x = 0

                scores.forEachIndexed { index, fl ->
                    x = index
                    x *= 4
                    if(fl > 0.75){
                        var label = labels[classes[index].toInt()]
                        val includeLabels = setOf("laptop");
                        if (includeLabels.contains(label)){
                            paint.setColor(colors.get(index))
                            paint.style = Paint.Style.STROKE
                            paint.style = Paint.Style.FILL
                            canvas.drawRect(RectF(locations.get(x+1)*w, locations.get(x)*h, locations.get(x+3)*w, locations.get(x+2)*h), paint)
                            canvas.drawText(label, locations.get(x+1)*w, locations.get(x)*h, paint)

                            val objectKey = "${label}-${locations[x + 1]}-${locations[x]}-${locations[x + 3]}-${locations[x + 2]}"
                            val currentTime = System.currentTimeMillis()

                            // Only send request if enough time has passed or if the object is new
                            if (currentTime - (recentlyDetectedObjects[objectKey] ?: 0) >= requestInterval) {
                                // Save the timestamp for this object
                                recentlyDetectedObjects[objectKey] = currentTime

                                val base64Image = bitmapToBase64(bitmap)
                                val plate = generateRandomString()  // Simulated
                                val isReported = getRandomBoolean()  // Simulated

                                // Use AsyncTask to handle network request on a background thread
                                RequestImageAsyncTask().execute(base64Image, plate, isReported.toString())
                            }
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

                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)

                var captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
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
    inner class RequestImageAsyncTask : AsyncTask<String, Void, Pair<String?, Int?>>() {
        override fun doInBackground(vararg params: String):  Pair<String?, Int?> {
            val base64Image = params[0]
            val plate = params[1]
            val isReported = params[2].toBoolean()

            val url = ""
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
            if (responseBody != null) {
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