package com.example.bluetoothautopair

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private val bt: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (bt == null) {
            append("هذا الهاتف لا يدعم البلوتوث.")
            startButton.isEnabled = false
        } else if (hasPermission()) loadPairedDevices()
        else if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "Bluetooth Retry Helper"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "يعيد محاولة الاتصال بجهاز مقترن مسبقًا. لا ينشئ نوافذ اقتران متكررة ولا يتجاوز PIN."
            textSize = 15f; setPadding(0, 12, 0, 12)
        })
        root.addView(Button(this).apply { text = "تحديث الأجهزة المقترنة"; setOnClickListener { loadPairedDevices() } })
        spinner = Spinner(this)
        root.addView(spinner, ViewGroup.LayoutParams(-1, -2))
        attemptsInput = EditText(this).apply { hint = "عدد المحاولات (1 إلى 20)"; setText("5"); inputType = 2 }
        root.addView(attemptsInput)
        delayInput = EditText(this).apply { hint = "الفاصل بالثواني (5 إلى 120)"; setText("10"); inputType = 2 }
        root.addView(delayInput)
        startButton = Button(this).apply { text = "بدء محاولات الاتصال"; setOnClickListener { startRetries() } }
        root.addView(startButton)
        root.addView(Button(this).apply { text = "إيقاف"; setOnClickListener { stopRetries("تم الإيقاف يدويًا.") } })
        log = TextView(this).apply { textSize = 14f; setPadding(0, 16, 0, 0); text = "اختر جهازًا مقترنًا مسبقًا." }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun loadPairedDevices() {
        if (!hasPermission()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
            return
        }
        val adapter = bt ?: return
        if (!adapter.isEnabled) { append("شغّل البلوتوث من إعدادات الهاتف أولًا."); return }
        devices = try { adapter.bondedDevices.toList().sortedBy { it.name ?: it.address } }
        catch (_: SecurityException) { emptyList() }
        val labels = devices.map { try { "${it.name ?: "جهاز بلا اسم"} (${it.address})" } catch (_: Exception) { "جهاز بلوتوث" } }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("لا توجد أجهزة مقترنة") })
        append(if (devices.isEmpty()) "لا توجد أجهزة مقترنة. اقترن بالجهاز يدويًا من إعدادات أندرويد أولًا." else "تم تحميل ${devices.size} جهاز مقترن.")
    }

    private fun startRetries() {
        if (running) return
        if (!hasPermission()) { loadPairedDevices(); return }
        if (devices.isEmpty() || spinner.selectedItemPosition !in devices.indices) { append("اختار جهازًا مقترنًا من القائمة."); return }
        val count = attemptsInput.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 5
        val delaySec = delayInput.text.toString().toIntOrNull()?.coerceIn(5, 120) ?: 10
        val device = devices[spinner.selectedItemPosition]
        running = true
        startButton.isEnabled = false
        append("بدء اختبار الاتصال بـ ${safeName(device)}.")
        work = scope.launch {
            try {
                for (i in 1..count) {
                    if (!running) break
                    append("المحاولة $i من $count...")
                    val result = withContext(Dispatchers.IO) { tryConnect(device) }
                    append("النتيجة: $result")
                    if (i < count && running) delay(delaySec * 1000L)
                }
                append("انتهت المحاولات.")
            } finally { running = false; startButton.isEnabled = true }
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
        } finally { try { socket?.close() } catch (_: IOException) {} }
    }

    private fun safeName(device: BluetoothDevice) = try { device.name ?: device.address } catch (_: Exception) { "الجهاز المحدد" }
    private fun stopRetries(message: String) {
        running = false
        work?.cancel()
        work = null
        if (::startButton.isInitialized) startButton.isEnabled = true
        append(message)
    }
    private fun append(message: String) { if (::log.isInitialized) log.append("\n$message") }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadPairedDevices()
        else if (requestCode == 101) append("اسمح بصلاحية البلوتوث لعرض الأجهزة.")
    }
    override fun onDestroy() { stopRetries("تم إغلاق التطبيق."); scope.cancel(); super.onDestroy() }
}
