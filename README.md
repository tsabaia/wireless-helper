<p align="center">
  <img src="app/src/main/res/ic_launcher-playstore.png" width="200" alt="Wireless Helper Logo">
</p>

# Wireless Helper

A lightweight launcher utility for **Headunit Revived**.

This app acts as a trigger to start Android Auto Wireless on your phone. It automatically detects your tablet running Headunit Revived via NSD (mDNS) or a passive TCP trigger, ensuring a seamless wireless connection experience.

## Features
- **Auto-Trigger:** Fires the Android Auto wireless intent as soon as the Headunit is detected.
- **NSD Support:** Finds your tablet in the same WiFi network automatically.
- **Passive Mode:** Waits for a trigger from the Headunit (ideal for Tablet-Hotspot setups).
- **Zero-Config:** No manual IP entry required.

## Auto-Start Setup
To ensure reliable background auto-start (Bluetooth/WiFi):
1. **Disable Battery Optimization:** Go to App Info -> Battery -> select **"Unrestricted"**.
2. **Permissions:** Grant Bluetooth and Location permissions when prompted.
3. **Paired Devices:** Select your car's Bluetooth or WiFi network in the settings.
