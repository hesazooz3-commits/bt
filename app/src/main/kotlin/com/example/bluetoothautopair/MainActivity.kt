package com.example.bluetoothautopair

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import java.io.IOException
import java.util.UUID

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var devices: List<BluetoothDevice> = emptyList()
    private var running = false
    private var work: Job? = null
    private lateinit var spinner: Spinner
    private lateinit var attemptsInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var log: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var clearButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statsText: TextView
    private var currentAttempt = 0
    private var successCount = 0
    private var failureCount = 0

    private val bt: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (bt == null) {
            append("هذا الهاتف لا يدعم البلوتوث.")
            startButton.isEnabled = false
        } else if (hasPermission()) {
            loadPairedDevices()
        } else {
            requestBluetoothPermission()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "Bluetooth Retry Helper"
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "إعادة محاولة الاتصال بجهاز مقترن مسبقًا — اختر جهازًا، حدد عدد المحاولات والفاصل، ثم اضغط بدء."
            textSize = 14f
            setPadding(0, 8, 0, 12)
        })

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(
            Button(this).apply { text = "تحديث الأجهزة"; setOnClickListener { loadPairedDevices() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(
            Button(this).apply { text = "مسح السجل"; setOnClickListener { clearLogAndStats() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(topRow)

        spinner = Spinner(this)
        root.addView(spinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val inputsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        attemptsInput = EditText(this).apply { hint = "محاولات (1-50)"; setText("5"); inputType = 2 }
        delayInput = EditText(this).apply { hint = "فاصل ثواني (1-300)"; setText("10"); inputType = 2 }
        inputsRow.addView(attemptsInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputsRow.addView(delayInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(inputsRow)

        val buttonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { text = "بدء"; setOnClickListener { startRetries() } }
        stopButton = Button(this).apply { text = "إيقاف"; isEnabled = false; setOnClickListener { stopRetries("تم الإيقاف يدويًا.") } }
        buttonsRow.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonsRow.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttonsRow)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12
        })

        statsText = TextView(this).apply { text = "المحاولة: 0 | نجاحات: 0 | إخفاقات: 0"; setPadding(0, 8, 0, 8) }
        root.addView(statsText)

        log = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 0)
            text = "اختر جهازًا مقترنًا مسبقًا."
        }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT < 31) {
            true
        } else {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
        }
    }

    private fun loadPairedDevices() {
        if (!hasPermission()) {
            requestBluetoothPermission()
            return
        }
        val adapter = bt ?: return
        if (!adapter.isEnabled) {
            append("شغّل البلوتوث من إعدادات الهاتف أولًا.")
            return
        }
        devices = try {
            adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
        } catch (_: SecurityException) {
            emptyList()
        }
        val labels = devices.map {
            try { "${it.name ?: "جهاز بلا اسم"} (${it.address})" } catch (_: Exception) { "جهاز بلوتوث" }
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("لا توجد أجهزة مقترنة") })
        append(if (devices.isEmpty()) "لا توجد أجهزة مقترنة. اقترن بالجهاز من إعدادات أندرويد." else "تم تحميل ${devices.size} جهاز/أجهزة مقترنة.")
    }

    private fun startRetries() {
        if (running) return
        if (!hasPermission()) { loadPairedDevices(); return }
        if (devices.isEmpty() || spinner.selectedItemPosition !in devices.indices) { append("اختَر جهازًا مقترنًا من القائمة."); return }

        val count = attemptsInput.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 5
        val delaySec = delayInput.text.toString().toIntOrNull()?.coerceIn(1, 300) ?: 10
        val device = devices[spinner.selectedItemPosition]

        currentAttempt = 0
        successCount = 0
        failureCount = 0
        progressBar.max = count
        progressBar.progress = 0
        updateStats()

        running = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        append("بدء اختبار الاتصال بـ ${safeName(device)}. محاولات: $count - فاصل: ${delaySec}s")

        work = scope.launch {
            try {
                for (i in 1..count) {
                    if (!running || !isActive) break
                    currentAttempt = i
                    append("المحاولة $i من $count ...")
                    val result = withContext(Dispatchers.IO) { tryConnect(device) }
                    if (result.startsWith("نجح")) successCount++ else failureCount++
                    append("النتيجة: $result")
                    progressBar.progress = i
                    updateStats()
                    if (i < count && running) {
                        val slept = withContext(Dispatchers.Default) {
                            var waited = 0L
                            while (waited < delaySec * 1000L && running && isActive) {
                                delay(200L)
                                waited += 200L
                            }
                            waited
                        }
                        if (!running) break
                    }
                }
                append("انتهت المحاولات.")
            } catch (e: CancellationException) {
                append("تم إلغاء العملية.")
            } finally {
                running = false
                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
    }

    private fun tryConnect(device: BluetoothDevice): String {
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createRfcommSocketToServiceRecord(sppUuid)
            socket.connect()
            "نجح اتصال SPP؛ تم إغلاق اتصال الاختبار."
        } catch (e: Exception) {
            "تعذر الاتصال: ${e.message ?: "قد لا يدعم الجهاز SPP"}"
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    private fun safeName(device: BluetoothDevice) = try { device.name ?: device.address } catch (_: Exception) { "الجهاز المحدد" }

    private fun stopRetries(message: String) {
        if (!running && work == null) {
            append("لا توجد عملية نشطة للإيقاف.")
            return
        }
        running = false
        work?.cancel()
        work = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        append(message)
    }

    private fun clearLogAndStats() {
        log.text = ""
        currentAttempt = 0
        successCount = 0
        failureCount = 0
        progressBar.progress = 0
        updateStats()
    }

    private fun updateStats() {
        statsText.text = "المحاولة: $currentAttempt | نجاحات: $successCount | إخفاقات: $failureCount"
    }

    private fun append(message: String) {
        if (::log.isInitialized) log.append("\n$message")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadPairedDevices()
        } else if (requestCode == 101) {
            append("اسمح بصلاحية البلوتوث لعرض الأجهزة.")
        }
    }

    override fun onDestroy() {
        stopRetries("تم إغلاق التطبيق.")
        scope.cancel()
        super.onDestroy()
    }
}
