# 🔵 NetBlue

NetBlue is an Android Bluetooth Low Energy (BLE) project that implements **dual-role communication**, where a single app can function as both a **GATT Server** and a **GATT Client**.  
The project focuses on **reliable BLE messaging** using MTU-aware chunking and message reassembly, making it suitable for peer-to-peer and mesh-style communication.

---

## 🚀 Features

- Dual-role BLE (GATT Server + GATT Client)
- MTU-aware message chunking
- Length-prefixed message framing
- Automatic message reassembly
- TX / RX characteristic communication model
- Notification-based data transfer
- Write queue handling for reliability

---

## 🧠 Architecture Overview

Client Device Server Device

Write → RX Characteristic ---> GATT Server
Notify ← TX Characteristic <--- GATT Server


- **RX Characteristic**: Used for incoming writes  
- **TX Characteristic**: Used for outgoing notifications  
- **CCCD**: Enables notifications on the client side  

---

## 🛠️ Tech Stack

- **Language:** Kotlin
- **Platform:** Android
- **Bluetooth:** BLE (GATT)
- **Minimum Android Version:** Android 8.0 (API 26+)

### Required Permissions
BLUETOOTH_CONNECT,
BLUETOOTH_SCAN,
BLUETOOTH_ADVERTISE

## 📂 Project Structure

netblue/
│
├── ble/
│ ├── DualRoleBleManager.kt
│ ├── BleScanner.kt
│ ├── BleAdvertiser.kt
│
├── ui/
│ ├── MainActivity.kt
│ ├── ChatActivity.kt
│
└── README.md



---

## ⚙️ How It Works

1. One device starts a **GATT Server** and advertises the BLE service.
2. Another device scans and connects as a **GATT Client**.
3. MTU is negotiated to maximize payload size.
4. Messages are:
   - Length-prefixed
   - Split into MTU-safe chunks
   - Reassembled on the receiver
5. Responses are sent using **notifications** via the TX characteristic.

---

## 🧪 Use Cases

- BLE chat applications
- Peer-to-peer communication
- Offline device messaging
- IoT device interaction
- Foundations for BLE mesh networking

---

## 🔮 Future Enhancements

- End-to-end encryption
- Multi-device mesh routing
- Automatic reconnection logic
- Improved error handling
- Performance monitoring tools

---

## 👤 Author

**Sojin K Shibu**  
Android & Networking Enthusiast  
Kerala, India  

GitHub: https://github.com/sojinkshibu12

---

## 📄 License

This project is licensed under the **MIT License**.  
You are free to use, modify, and distribute this project with proper attribution.

---

⭐ If you find this project useful, please consider starring the repository.
