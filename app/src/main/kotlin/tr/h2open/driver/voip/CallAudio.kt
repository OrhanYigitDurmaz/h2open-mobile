package tr.h2open.driver.voip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

enum class AudioRoute { Earpiece, Speaker, Bluetooth }

/**
 * Puts the phone in call mode for the length of a call and moves the
 * audio between earpiece, speaker and a Bluetooth headset or car kit.
 */
class CallAudio(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL

    var route: AudioRoute = AudioRoute.Earpiece
        private set

    fun start() {
        if (focus != null) return
        previousMode = audio.mode
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audio.requestAudioFocus(request)
        focus = request
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        // A driver on the road most likely wants the car kit.
        select(if (AudioRoute.Bluetooth in available()) AudioRoute.Bluetooth else AudioRoute.Earpiece)
    }

    fun stop() {
        val request = focus ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            audio.stopBluetoothSco()
        }
        audio.mode = previousMode
        audio.abandonAudioFocusRequest(request)
        focus = null
        route = AudioRoute.Earpiece
    }

    fun available(): List<AudioRoute> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val bluetooth = audio.isBluetoothScoAvailableOffCall &&
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            return listOfNotNull(AudioRoute.Earpiece, AudioRoute.Speaker, AudioRoute.Bluetooth.takeIf { bluetooth })
        }
        val types = audio.availableCommunicationDevices.map { it.type }
        return listOfNotNull(
            AudioRoute.Earpiece.takeIf { AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types || AudioDeviceInfo.TYPE_WIRED_HEADSET in types },
            AudioRoute.Speaker,
            AudioRoute.Bluetooth.takeIf { types.any(::isBluetooth) },
        )
    }

    fun select(target: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val device = when (target) {
                AudioRoute.Speaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                AudioRoute.Bluetooth -> devices.firstOrNull { isBluetooth(it.type) }
                AudioRoute.Earpiece -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (device == null || !audio.setCommunicationDevice(device)) return
        } else {
            @Suppress("DEPRECATION")
            when (target) {
                AudioRoute.Bluetooth -> {
                    audio.isSpeakerphoneOn = false
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                }
                AudioRoute.Speaker, AudioRoute.Earpiece -> {
                    if (audio.isBluetoothScoOn) {
                        audio.isBluetoothScoOn = false
                        audio.stopBluetoothSco()
                    }
                    audio.isSpeakerphoneOn = target == AudioRoute.Speaker
                }
            }
        }
        route = target
    }

    // Only asked on Android 12+, where every one of these types exists.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_HEARING_AID
}
