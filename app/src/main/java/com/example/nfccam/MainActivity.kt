package com.example.nfccam

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    // BLE UUID phải khớp với ESP32
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHAR_NOTIFY_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false

    private val serverUrl = "http://YOUR_SERVER:8000/upload" // Đổi URL

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val ok = hasAllPerms()
        if (ok) initAfterPermission() else Toast.makeText(this, "Thiếu quyền", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)
        requestPerms()
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun hasAllPerms(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun initAfterPermission() {
        setupCamera()
        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = mgr.adapter
        startScan()
    }

    private fun setupCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    // ===== BLE scan/connect =====
    private fun startScan() {
        if (scanning) return
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner.startScan(filters, settings, scanCb)
        previewView.postDelayed({ stopScan() }, 10000)
    }

    private fun stopScan() {
        if (!scanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCb)
        scanning = false
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, res: ScanResult) { stopScan(); connectGatt(res.device) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.firstOrNull()?.device?.let { stopScan(); connectGatt(it) }
        }
        override fun onScanFailed(code: Int) { Log.e("BLE","Scan failed $code") }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            else if (state == BluetoothProfile.STATE_DISCONNECTED) startScan()
        }
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SERVICE_UUID) ?: return
            val ch = svc.getCharacteristic(CHAR_NOTIFY_UUID) ?: return
            gatt.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            cccd?.let { it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(it) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == CHAR_NOTIFY_UUID) {
                val uid = ch.value?.toString(Charsets.UTF_8)?.trim().orEmpty()
                runOnUiThread { captureAndUpload(uid) }
            }
        }
    }

    // ===== Camera + Upload =====
    private fun captureAndUpload(uid: String) {
        val cap = imageCapture ?: return
        val file = createPhotoFile()
        val out = ImageCapture.OutputFileOptions.Builder(file).build()
        cap.takePicture(out, ContextCompat.getMainExecutor(this),
            object: ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Chụp lỗi: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(res: ImageCapture.OutputFileResults) { upload(file, uid) }
            })
    }

    private fun createPhotoFile(): File {
        val dir = externalCacheDir ?: cacheDir
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "IMG_${ts}.jpg")
    }

    private fun upload(file: File, uid: String) {
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            .build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("uid", uid)
            .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url(serverUrl).post(body).build()
        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Upload lỗi: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onResponse(call: Call, resp: Response) {
                val ok = resp.isSuccessful; resp.close()
                runOnUiThread { Toast.makeText(this@MainActivity, if (ok) "Upload OK" else "Upload FAIL", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
