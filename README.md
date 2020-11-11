Project-Pigeon
==========
Connect a Raspberry Pi w/ a camera module to an Android device and stream the ivideo output to your favorite streaming site.

Raspberry Pi Setup
-------

### 1. Enabling OTG
We need to edit some files on Raspberry Pi's boot disk. Add the following line at the bottom of /boot/config.txt:
```
dtoverlay=dwc2
```
Add the following at the end of the line of /boot/cmdline.txt:
```
modules-load=dwc2,libcomposite
```
If you own a Raspberry Pi Zero W and would like to debug over a WiFi connection, run the following command in the /boot directory:
```
touch ssh
```

### 2. Enable Camera
Connect your camera module to the Raspberry Pi if you already haven't done so. Then, you can find the menu to enable the camera by running the command:
```
sudo raspi-config
```

### 3. Configure Pi as a UVC Gadget
Check out a forked version of uvc-gadget:
```
cd /home/pi
git clone https://github.com/climberhunt/uvc-gadget.git
```
This repository provides a script that creates a systemd service. To install this, copy the file to the specified location and enable it.
```
cd uvc-gadget
sudo cp piwebcam.service /etc/systemd/system/
sudo systemctl enable piwebcam
```
Now, we build the uvc-gadget app. While you are in the /uvc-gadget directory, compile the repository code:
```
make
```
Enable serial connection (for app-to-pi communication):
```
sudo ln -s /lib/systemd/system/getty@.service /etc/systemd/system/getty.target.wants/getty@ttyGS0.service
sudo systemctl enable getty@ttyGS0.service
```
Restart your Raspberry Pi. Upon next boot, your Raspberry Pi should be set up as a USB camera gadget.

More information can be found at the following links:
https://www.raspberrypi.org/forums/viewtopic.php?t=148361
http://www.davidhunt.ie/raspberry-pi-zero-with-pi-camera-as-usb-webcam/

Android Streaming App
-------
Our Android app needs to find the connected UVC gadget at the USB port and retrieve the video feed from the camera module. In order to achieve this, we will use the following library:
https://github.com/saki4510t/UVCCamera

### How to Compile the App

##### 1. Download NDK
This app has been tested with NDK version 14 which you cannot donwload directly from Android Studio. Google provides download mirrors for older versions of NDK (<16) in the following link: 
https://developer.android.com/ndk/downloads/older_releases

##### 2. Compile in Android Studio
Clone this repository to your desired location and open the "rpistream" directory in Android Studio. Then, add the following line at the botoom of file "local.properties":
```
ndk.dir={NDK_LOCATION}
```
Make sure to substitute {NDK_LOCATION} with the correct path to the NDK directory.
Then, in Android Studio, go to Build > Rebuild Project and wait for the project to finish building. After, Run the "App" module on your Android device.

Trouble Shooting
-------
##### 1. Service Not Running
On your Raspberry Pi check that the piwebcam service is running by using the command `systemctl | grep piwebcam`

If it's not then you can view the service logs using the command `journalctl -u piwebcam.service` to find the root problem.

A common problem may be that `dev/video0` is not being detected as a video device. A fix for this may be to run the command `echo "bcm2835-v4l2" | sudo tee -a /etc/modules`.
