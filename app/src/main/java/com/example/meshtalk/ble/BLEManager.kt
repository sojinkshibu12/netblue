import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.ArrayAdapter
import androidx.annotation.RequiresPermission
import com.example.meshtalk.ui.DeviceAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

import java.util.UUID

class BleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val deviceList: MutableList<BluetoothDevice>,
    private val adapter: ArrayAdapter<String>,
    private val statusText: TextView,
    private val scanPeriodMs: Long = 10_000L, // default 10s
    private val deviceadapter : DeviceAdapter
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private val scanner by lazy { BluetoothLeScannerCompat.getScanner() }



    private val DEFAULT_MTU = 23
    private val DEFAULT_PAYLOAD_OVERHEAD = 3


    private val writeChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private var writerJob: Job? = null

    @Volatile
    private var negotiatedMtu: Int = DEFAULT_MTU
    private val payloadSize: Int
        get() = (negotiatedMtu - DEFAULT_PAYLOAD_OVERHEAD).coerceAtLeast(20)


    // Simple listener for incoming messages (UI can poll or hook into this)
//    var onMessageReceived: ((String) -> Unit)? = null
    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onScanResult(callbackType: Int, result: ScanResult) {


            val record = result.scanRecord ?: return

            val uuid = try {
                ParcelUuid.fromString("0000abcd-0000-1000-8000-00805f9b34fb") // example: Heart Rate service (0x180D)
            } catch (e: IllegalArgumentException) {
                Log.w("BLE_SCN", "Invalid service UUID string")
                return
            }

            val serviceData: ByteArray? = record.getServiceData(uuid)

            if ( serviceData.contentEquals(byteArrayOf(0x01, 0x02, 0x03))) {
                Log.d("BLE_SCN","servicdata : ${serviceData}")
                val device = result.device
                val advertisedName = result.scanRecord?.deviceName
                val name = device.name ?: advertisedName


                device.let {
                    // Only add named devices and avoid duplicates by address
                    if (name != null && deviceList.none { d -> d.address == it.address }) {

                        deviceList.add(it)
                        // Update UI on main thread
                        mainHandler.post {
                            adapter.add("${name}\n${it.address}")
                            deviceadapter.addDevice(name,it.address)
                        }
                    }
                }
            }

        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                statusText.text = "Status: Scan failed (code=$errorCode)"
            }
            stopScanInternal()
        }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (isScanning) return

        if (!bluetoothAdapter.isEnabled) {
            mainHandler.post { statusText.text = "Status: Bluetooth disabled" }
            return
        }
        if (scanner == null) {
            mainHandler.post { statusText.text = "Status: BLE scanner not available" }
            return
        }

        // Optional: clear previous results if you want fresh list
        // deviceList.clear()
        // adapter.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters: List<ScanFilter> = listOf() // add ScanFilter if you want to filter devices

        scanner.startScan(filters, settings, scanCallback)
        isScanning = true
        mainHandler.post { statusText.text = "Status: Scanning for BLE devices..." }

        // emulate ACTION_DISCOVERY_FINISHED after scanPeriodMs
        mainHandler.postDelayed({ stopScan() }, scanPeriodMs)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        mainHandler.post { stopScanInternal() }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScanInternal() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // ignore if already stopped
        }
        isScanning = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { statusText.text = "Status: Discovery Finished" }
    }

    fun isScanning(): Boolean = isScanning
}
