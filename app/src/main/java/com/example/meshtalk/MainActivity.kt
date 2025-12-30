package com.example.meshtalk


import BleScanner
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.dualroleble.DualRoleBleManager
import com.example.meshtalk.ble.BleAdvertiser
import com.example.meshtalk.ui.Device
import com.example.meshtalk.ui.DeviceAdapter

object BleManagerHolder {
    lateinit var dualRole: DualRoleBleManager
}

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val deviceList = mutableListOf<Device>()
    private val bldevices  = mutableListOf<BluetoothDevice>()

    private lateinit var bleScanner: BleScanner
    private lateinit var bleAdvertiser: BleAdvertiser
//    private val gattconnection = GATTconnection()// <-- advertiser

    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private lateinit var adapter: ArrayAdapter<String>

    private val REQUEST_PERMISSIONS = 1
    private val REQUEST_ENABLE_BT = 2
    private val REQUEST_DISCOVERABLE_BT = 3
    private val REQUEST_ADVERT_PERMS = 2002
    private val deviceAdapter = DeviceAdapter(deviceList,this)
    lateinit var dualrole: DualRoleBleManager





    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listDevices)
        statusText = findViewById(R.id.tvStatus)
        val btnDiscover = findViewById<Button>(R.id.btnDiscover)
        val btnAdvertise = findViewById<Button>(R.id.btnWaitForConnection)


        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        listView.adapter = adapter
        dualrole = DualRoleBleManager(this)

        fun checkPermissionsAndEnableBluetooth() {

            val permissionsToRequest = mutableListOf<String>()

            // Android 12+ (S, API 31)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionsToRequest += Manifest.permission.BLUETOOTH_SCAN
                permissionsToRequest += Manifest.permission.BLUETOOTH_CONNECT
                permissionsToRequest += Manifest.permission.BLUETOOTH_ADVERTISE  // NEW (for advertiser)
                permissionsToRequest += Manifest.permission.ACCESS_FINE_LOCATION
            } else {
                // Older Android (needs location permission for BLE scanning)
                permissionsToRequest += Manifest.permission.ACCESS_FINE_LOCATION
            }

            // Check which permissions are missing
            val missingPermissions = permissionsToRequest.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingPermissions.isNotEmpty()) {
                // Request missing permissions
                ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toTypedArray(),
                    REQUEST_PERMISSIONS
                )
                return
            }

            // Permissions granted — now check Bluetooth status
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }

        checkPermissionsAndEnableBluetooth()

        // Initialize BleScanner
        bleScanner = BleScanner(
            context = this,
            bluetoothAdapter = bluetoothAdapter,
            deviceList = bldevices,
            adapter = adapter,
            statusText = statusText,
            deviceadapter = deviceAdapter
        )

        // Initialize BleAdvertiser AFTER bluetoothAdapter is available
        bleAdvertiser = BleAdvertiser(this)



        // Discover (BLE scan)
        btnDiscover.setOnClickListener {
            // permission guard - handle BLUETOOTH_SCAN or ACCESS_FINE_LOCATION based on SDK
            val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }

            if (ContextCompat.checkSelfPermission(this, scanPermission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(scanPermission), REQUEST_PERMISSIONS)
            } else {
                bleScanner.startScan()
            }
        }

        // Advertise start / stop (if you have buttons)
        btnAdvertise?.setOnClickListener {
            // Request BLUETOOTH_ADVERTISE at runtime for Android S+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val advPerm = Manifest.permission.BLUETOOTH_ADVERTISE
                if (ContextCompat.checkSelfPermission(this, advPerm) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(advPerm, Manifest.permission.BLUETOOTH_CONNECT), REQUEST_ADVERT_PERMS)
                    return@setOnClickListener
                }
            }
            // Start advertising (BleAdvertiser handles its own permission checks too)
            dualrole.startGattServer()
            bleAdvertiser.startAdvertising()

            statusText.text = "Status: Advertising..."
        }





        listView.setOnItemClickListener { _, _, position, _ ->
            if (this::bleScanner.isInitialized && bleScanner.isScanning()) {
                bleScanner.stopScan()
            }
            val device = bldevices[position]
            Log.d("item click", "device-name :${device.name}")

            // set a one-shot connected callback
            dualrole.onClientConnected = { connectedDevice ->
                Log.i("MainActivity", "onClientConnected callback for ${connectedDevice.address}")
                BleManagerHolder.dualRole = dualrole

                // send hello once connected
                dualrole.clientWrite("hi i am motorola")
                dualrole.clientWrite("my name is sojin")
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("device_address", connectedDevice.address)
                }
                startActivity(intent)

                // clear callback if not needed again
                dualrole.onClientConnected = null
            }

// now start connection
            dualrole.connectToDevice(device)



            // Optionally open ChatActivity when connection established (in the callback)
        }


    }

    // ... keep your existing onActivityResult, checkPermissionsAndEnableBluetooth, onRequestPermissionsResult ...

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                statusText.text = "Status: Missing required permissions"
            } else {
                statusText.text = "Status: Permissions granted"
            }
        } else if (requestCode == REQUEST_ADVERT_PERMS) {
            // If advertise perms were requested, and granted, start advertising
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                bleAdvertiser.startAdvertising()
                statusText.text = "Status: Advertising..."
            } else {
                statusText.text = "Status: Advertise permission denied"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dualrole.stopGattServer()
        if (this::bleScanner.isInitialized && bleScanner.isScanning()) {
            bleScanner.stopScan()
        }
        if (this::bleAdvertiser.isInitialized && bleAdvertiser.isRunning()) {
            bleAdvertiser.stopAdvertising()
        }
    }
}


