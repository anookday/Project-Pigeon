package com.anookday.rpistream.stream

import android.app.Application
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.anookday.rpistream.R
import com.anookday.rpistream.UserViewModel
import com.anookday.rpistream.chat.*
import com.anookday.rpistream.extensions.addNewItem
import com.anookday.rpistream.oauth.TwitchManager
import com.pedro.rtplibrary.util.BitrateAdapter
import com.pedro.rtplibrary.view.OpenGlView
import com.serenegiant.usb.USBMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ossrs.rtmp.ConnectCheckerRtmp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import timber.log.Timber

enum class UsbConnectStatus { ATTACHED, DETACHED }
enum class RtmpConnectStatus { SUCCESS, FAIL, DISCONNECT }
enum class RtmpAuthStatus { SUCCESS, FAIL }
enum class ChatStatus { CONNECTED, DISCONNECTED }
enum class CurrentFragmentName {
    STREAM,
    ACCOUNT,
    SETTINGS,
    VIDEO_CONFIG,
    VIDEO_CONFIG_RESOLUTION,
    VIDEO_CONFIG_FPS,
    VIDEO_CONFIG_BITRATE,
    VIDEO_CONFIG_IFRAME,
    VIDEO_CONFIG_ROTATION,
    AUDIO_CONFIG,
    AUDIO_CONFIG_BITRATE,
    AUDIO_CONFIG_SAMPLERATE,
    DARK_MODE
}

/**
 * ViewModel for [StreamActivity].
 */
class StreamViewModel(app: Application) : UserViewModel(app) {
    // name of currently visible fragment
    private val _currentFragment = MutableLiveData<CurrentFragmentName>()
    val currentFragment: LiveData<CurrentFragmentName>
        get() = _currentFragment

    // twitch oauth manager
    private val twitchManager = TwitchManager(app.applicationContext, database)

    // twitch chat variables
    private var chatWebSocket: WebSocket? = null

    // usb manager
    var usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    // USB monitor object used to control connected USB devices
    private var usbMonitor: USBMonitor? = null

    // USB device status
    private val _usbStatus = MutableLiveData<UsbConnectStatus>()
    val usbStatus: LiveData<UsbConnectStatus>
        get() = _usbStatus

    // stream connection status
    private val _connectStatus = MutableLiveData<RtmpConnectStatus>()
    val connectStatus: LiveData<RtmpConnectStatus>
        get() = _connectStatus

    // stream authentication status
    private val _authStatus = MutableLiveData<RtmpAuthStatus>()
    val authStatus: LiveData<RtmpAuthStatus>
        get() = _authStatus

    // video connection status
    private val _videoStatus = MutableLiveData<String?>()
    val videoStatus: LiveData<String?>
        get() = _videoStatus

    // audio connection status
    private val _audioStatus = MutableLiveData<String?>()
    val audioStatus: LiveData<String?>
        get() = _audioStatus

    private val _chatStatus = MutableLiveData<ChatStatus>()
    val chatStatus: LiveData<ChatStatus>
        get() = _chatStatus

    // chat messages
    private val _chatMessages = MutableLiveData<MutableList<TwitchChatItem>>()
    val chatMessages: LiveData<MutableList<TwitchChatItem>>
        get() = _chatMessages

    private val _videoBitrate = MutableLiveData<Long?>()
    val videoBitrate: LiveData<Long?>
        get() = _videoBitrate

    /**
     * Initialize required LiveData variables.
     *
     * @param context Activity context
     * @param cameraView OpenGL surface view that displays the camera
     */
    fun init(context: Context, cameraView: OpenGlView) {
        usbMonitor = USBMonitor(context, onDeviceConnectListener)
        StreamService.init(cameraView, connectCheckerRtmp)
        registerUsbMonitor()
    }

    /**
     * Update live data with current fragment's name.
     */
    fun setCurrentFragment(fragmentName: CurrentFragmentName) {
        _currentFragment.value = fragmentName
    }

    /**
     * Register USB monitor.
     */
    fun registerUsbMonitor() {
        viewModelScope.launch {
            usbMonitor?.register()
        }
    }

    /**
     * Unregister USB monitor.
     */
    fun unregisterUsbMonitor() {
        viewModelScope.launch {
            usbMonitor?.unregister()
        }
    }

    /**
     * Start camera preview if there is no preview.
     */
    fun startPreview(width: Int?, height: Int?) {
        viewModelScope.launch {
            StreamService.startPreview(width, height)
        }
    }

    /**
     * Stop camera preview if there is a preview.
     */
    fun stopPreview() {
        viewModelScope.launch {
            StreamService.stopPreview()
        }
    }

    /**
     * Stop the current stream and preview. Destroy camera instance if initialized.
     */
    fun disableCamera() {
        viewModelScope.launch {
            StreamService.disableCamera()
            _videoStatus.value = null
        }
    }

    /**
     * Connect to UVCCamera if video is disabled. Otherwise, disable video.
     */
    fun toggleVideo() {
        if (!StreamService.isStreaming) {
            if (videoStatus.value == null) {
                val deviceList = usbManager.deviceList
                if (deviceList.isNotEmpty()) {
                    val device: UsbDevice = deviceList.values.elementAt(0)
                    usbMonitor?.requestPermission(device)
                }
            } else {
                disableCamera()
            }
        }
    }

    /**
     * Enable or disable audio recording.
     */
    fun toggleAudio() {
        if (!StreamService.isStreaming) {
            if (_audioStatus.value == null) {
                _audioStatus.value = app.getString(R.string.audio_on_text)
                StreamService.enableAudio()
            } else {
                _audioStatus.value = null
                StreamService.disableAudio()
            }
        }
    }

    fun destroyUsbMonitor() {
        usbMonitor?.destroy()
    }

    /**
     * Start streaming to registered URI address if not currently streaming.
     * Otherwise, stop the current stream.
     */
    fun toggleStream() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // only logged in users can toggle stream
                user.value?.let {
                    val intent = Intent(app.applicationContext, StreamService::class.java)
                    if (StreamService.isStreaming) {
                        app.stopService(intent)
                        _connectStatus.postValue(RtmpConnectStatus.DISCONNECT)
                    } else if (StreamService.prepareStream(it.settings.videoConfig, it.settings.audioConfig)) {
                        val streamEndpoint = twitchManager.getIngestEndpoint(it.id, it.auth.accessToken)
                        if (streamEndpoint != null) {
                            intent.putExtra("endpoint", streamEndpoint)
                            app.startService(intent)
                            _connectStatus.postValue(RtmpConnectStatus.SUCCESS)
                        }
                    } else {
                        _connectStatus.postValue(RtmpConnectStatus.FAIL)
                    }
                }
            }
        }
    }

    /**
     * Disable streaming and video preview. Called before navigating to another activity/fragment.
     */
    fun prepareNavigation() {
        if (StreamService.isPreview) {
            toggleVideo()
        }
        if (StreamService.isStreaming) {
            toggleStream()
        }
        _audioStatus.value = null
        StreamService.disableAudio()
    }

    /**
     * Connect to user's chat web socket.
     */
    fun connectToChat() {
        user.value?.let {
            val client = OkHttpClient()
            val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
            val twitchChatListener =
                TwitchChatListener(
                    it.auth.accessToken,
                    it.profile.displayName
                ) { message: Message ->
                    _chatMessages.addNewItem(TwitchChatItem(message))

                    viewModelScope.launch {
                        routeMessageToPi(message)
                    }
                }
            chatWebSocket = client.newWebSocket(request, twitchChatListener)
            _chatStatus.value = ChatStatus.CONNECTED
        }
    }

    /**
     * Send a message to the chat web socket.
     */
    fun sendMessage(text: String) {
        user.value?.let {
            chatWebSocket?.apply {
                send("PRIVMSG #${it.profile.displayName} :$text")
                val message =
                    UserMessage(UserMessageType.VALID, it.profile.displayName, text)
                _chatMessages.addNewItem(TwitchChatItem(message))
            }
        }
    }

    /**
     * Safely disconnect from user's chat web socket.
     */
    fun disconnectFromChat() {
        chatWebSocket?.close(NORMAL_CLOSURE_STATUS, null)
        chatWebSocket = null
        _chatStatus.value = ChatStatus.DISCONNECTED
    }

    /**
     * Send message string over usb connection to pi device.
     *
     * @param message object containing message string
     */
    private suspend fun routeMessageToPi(message: Message) {
        withContext(Dispatchers.IO) {
            if (message is UserMessage) {
                val deviceList = usbManager.deviceList
                if (deviceList.isNotEmpty()) {
                    val device: UsbDevice = deviceList.values.elementAt(0)
                    if (usbManager.hasPermission(device)) {
                        for (i in 0 until device.interfaceCount) {
                            val intf: UsbInterface = device.getInterface(i)
                            for (j in 0 until intf.endpointCount) {
                                val ep = intf.getEndpoint(j)
                                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                                    val buffer = message.toString().toByteArray()
                                    usbManager.openDevice(device)?.apply {
                                        claimInterface(intf, true)
                                        bulkTransfer(ep, buffer, buffer.size, 0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Update video resolution in user settings.
     * @param width resolution width in pixels
     * @param height resolution height in pixels
     */
    fun updateVideoResolution(width: Int, height: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateVideoResolution(id, width, height)
                }
            }
        }
    }

    /**
     * Update max frames-per-second in user settings.
     * @param fps frames-per-second threshold
     */
    fun updateVideoFps(fps: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateVideoFps(id, fps)
                }
            }
        }
    }

    /**
     * Update maximum video bitrate in user settings.
     * @param bitrate maximum bitrate in bytes
     */
    fun updateVideoBitrate(bitrate: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateVideoBitrate(id, bitrate)
                }
            }
        }
    }

    /**
     * Update i-frame limit in user settings.
     * @param iframe keyframe (i-frame) count
     */
    fun updateVideoIFrame(iframe: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateVideoIFrame(id, iframe)
                }
            }
        }
    }

    /**
     * Update video rotation angle in user settings.
     * @param rotation rotation angle in degrees
     */
    fun updateVideoRotation(rotation: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateVideoRotation(id, rotation)
                }
            }
        }
    }

    /**
     * Update maximum audio bitrate in user settings.
     * @param bitrate audio bitrate in bytes
     */
    fun updateAudioBitrate(bitrate: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateAudioBitrate(id, bitrate)
                }
            }
        }
    }

    /**
     * Update audio sample rate in user settings.
     * @param sampleRate sample rate in hertz
     */
    fun updateAudioSampleRate(sampleRate: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateAudioSampleRate(id, sampleRate)
                }
            }
        }
    }

    /**
     * Update stereo toggle in user settings.
     * @param stereo if true then enable stereo
     */
    fun updateAudioStereo(stereo: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateAudioStereo(id, stereo)
                }
            }
        }
    }

    /**
     * Update echo canceler toggle in user settings.
     * @param echoCanceler if true then enable echo canceler
     */
    fun updateAudioEchoCanceler(echoCanceler: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateAudioEchoCanceler(id, echoCanceler)
                }
            }
        }
    }

    /**
     * Update noise suppressor toggle in user settings.
     * @param noiseSuppressor if true then enable noise suppressor
     */
    fun updateAudioNoiseSuppressor(noiseSuppressor: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateAudioNoiseSuppressor(id, noiseSuppressor)
                }
            }
        }
    }

    /**
     * Update dark mode toggle in user settings.
     * @param darkMode if true then enable dark mode
     */
    fun updateDarkMode(darkMode: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateDarkMode(id, darkMode)
                }
            }
        }
    }

    /**
     * Update developer mode toggle in user settings.
     * @param developerMode if true then enable developer mode
     */
    fun updateDeveloperMode(developerMode: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                user.value?.id?.let { id ->
                    database.userDao.updateDeveloperMode(id, developerMode)
                }
            }
        }
    }


    /**
     * deviceConnectListener object
     */
    private var onDeviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        private val ACTION_USB_PERMISSION = "com.anookday.rpistream.USB_PERMISSION"

        private val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.apply {
                                //call method to set up device communication
                            }
                        } else {
                            Timber.d("permission denied for device $device")
                        }
                    }
                }
            }
        }

        override fun onConnect(
            device: UsbDevice?,
            ctrlBlock: USBMonitor.UsbControlBlock?,
            createNew: Boolean
        ) {
            Timber.v("onDeviceConnectListener: Device connected")
            user.value?.let {
                viewModelScope.launch {
                    try {
                        _videoStatus.postValue(
                            StreamService.enableCamera(
                                ctrlBlock,
                                it.settings.videoConfig
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        disableCamera()
                    }
                }
            }
        }

        override fun onCancel(device: UsbDevice?) {
            //
        }

        override fun onAttach(device: UsbDevice?) {
            Timber.v("onDeviceConnectListener: Device attached")
            _usbStatus.postValue(UsbConnectStatus.ATTACHED)
            val permissionIntent = PendingIntent.getBroadcast(
                app.applicationContext, 0, Intent(
                    ACTION_USB_PERMISSION
                ), 0
            )
            val intentFilter = IntentFilter(ACTION_USB_PERMISSION)
            app.registerReceiver(usbReceiver, intentFilter)
            usbManager.requestPermission(device, permissionIntent)
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            Timber.v("onDeviceConnectListener: Device disconnected")
            viewModelScope.launch {
                StreamService.disableCamera()
                _videoStatus.postValue(null)
            }
        }

        override fun onDettach(device: UsbDevice?) {
            Timber.v("onDeviceConnectListener: Device detached")
            _usbStatus.postValue(UsbConnectStatus.DETACHED)
        }

    }

    /**
     * RTMP connection notification object
     */
    private var connectCheckerRtmp = object : ConnectCheckerRtmp {
        private var bitrateAdapter: BitrateAdapter? = null

        override fun onConnectionSuccessRtmp() {
            bitrateAdapter = BitrateAdapter(BitrateAdapter.Listener { bitrate ->
                StreamService.videoBitrate = bitrate
            })
            bitrateAdapter?.setMaxBitrate(StreamService.videoBitrate)
            _connectStatus.postValue(RtmpConnectStatus.SUCCESS)
        }

        override fun onConnectionFailedRtmp(reason: String) {
            _connectStatus.postValue(RtmpConnectStatus.FAIL)
            StreamService.stopStream()
        }

        override fun onAuthSuccessRtmp() {
            _authStatus.postValue(RtmpAuthStatus.SUCCESS)
        }

        override fun onNewBitrateRtmp(bitrate: Long) {
            user.value?.settings?.let { settings ->
                if (settings.developerMode) {
                    _videoBitrate.postValue(bitrate)
                }
            }
            bitrateAdapter?.adaptBitrate(bitrate)
        }

        override fun onAuthErrorRtmp() {
            _authStatus.postValue(RtmpAuthStatus.FAIL)
        }

        override fun onDisconnectRtmp() {
            _videoBitrate.postValue(null)
            _connectStatus.postValue(RtmpConnectStatus.DISCONNECT)
        }
    }
}