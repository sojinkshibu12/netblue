package com.example.meshtalk.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.recyclerview.widget.RecyclerView

import com.example.meshtalk.R


data class Device(val name: String, val address: String)

class DeviceAdapter(
    private val devices: MutableList<Device> ,
    private val context: Context
//    private val onDeviceClicked: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {



    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.deviceName)
        private val addressTextView: TextView = itemView.findViewById(R.id.deviceAddress)

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun bind(device: Device) {
            nameTextView.text = device.name
            addressTextView.text = device.address
            itemView.setOnClickListener {

                //val bluetoothDevice: BluetoothDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(device.address)

            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun getItemCount(): Int = devices.size

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    fun addDevice(name: String, address: String) {
        devices.add(Device(name, address))
        notifyItemInserted(devices.size - 1)

    }


    private fun MutableList<BluetoothDevice>.add(element: Device) {
        devices.add(element)
    }
}