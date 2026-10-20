package com.diplay.carprobe;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjectionManager;

/**
 * Read-only look at the audio routes, for the question "can DiPlay read the car's Bluetooth audio?".
 *
 * <p>On this head unit CarPlay audio is played by the car's own Bluetooth player, so a music-reactive
 * lamp envelope would need the A2DP stream. Android only exposes that to system apps
 * ({@code CAPTURE_AUDIO_OUTPUT}) or through {@code MediaProjection} playback capture, so this probe
 * reports what the app can actually see and whether a remote-submix capture is even allowed.
 */
final class AudioApi {

    private AudioApi() {
    }

    static void dump(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        ProbeLog.i("=== audio route dump ===");
        if (manager == null) {
            ProbeLog.i("AudioManager unavailable");
            return;
        }
        ProbeLog.i("isMusicActive=" + manager.isMusicActive()
                + " isBluetoothA2dpOn=" + manager.isBluetoothA2dpOn()
                + " isBluetoothScoOn=" + manager.isBluetoothScoOn()
                + " isSpeakerphoneOn=" + manager.isSpeakerphoneOn());

        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            ProbeLog.i("out type=" + typeName(device.getType())
                    + " id=" + device.getId()
                    + " name=" + device.getProductName()
                    + " addr=" + device.getAddress()
                    + " sink=" + device.isSink()
                    + " rates=" + java.util.Arrays.toString(device.getSampleRates()));
        }
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            ProbeLog.i("in  type=" + typeName(device.getType())
                    + " id=" + device.getId()
                    + " name=" + device.getProductName()
                    + " addr=" + device.getAddress());
        }

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                ProbeLog.i("bluetooth adapter unavailable");
            } else {
                ProbeLog.i("bt enabled=" + adapter.isEnabled()
                        + " name=" + adapter.getName()
                        + " a2dp=" + profileState(adapter, BluetoothProfile.A2DP)
                        + " headset=" + profileState(adapter, BluetoothProfile.HEADSET));
            }
        } catch (Throwable error) {
            ProbeLog.i("bluetooth state unreadable: " + ProbeLog.describe(HudApi.unwrap(error)));
        }

        ProbeLog.i("permission CAPTURE_AUDIO_OUTPUT=" + granted(context, "android.permission.CAPTURE_AUDIO_OUTPUT")
                + " RECORD_AUDIO=" + granted(context, Manifest.permission.RECORD_AUDIO)
                + " MODIFY_AUDIO_ROUTING=" + granted(context, "android.permission.MODIFY_AUDIO_ROUTING"));
        ProbeLog.i("media projection service="
                + (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) instanceof MediaProjectionManager));

        probeRemoteSubmix();
        ProbeLog.i("=== audio route dump done (nothing was changed) ===");
    }

    /** Opens and immediately releases a remote-submix record, to see whether capture is permitted. */
    private static void probeRemoteSubmix() {
        int rate = 48_000;
        int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        ProbeLog.i("remote-submix min buffer=" + minimum);
        AudioRecord record = null;
        try {
            record = new AudioRecord(
                    MediaRecorder.AudioSource.REMOTE_SUBMIX,
                    rate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minimum > 0 ? minimum * 2 : 8192
            );
            ProbeLog.i("remote-submix state=" + record.getState()
                    + " (STATE_INITIALIZED=" + AudioRecord.STATE_INITIALIZED + ")");
        } catch (Throwable error) {
            ProbeLog.i("remote-submix refused: " + ProbeLog.describe(HudApi.unwrap(error)));
        } finally {
            if (record != null) {
                try {
                    record.release();
                } catch (Throwable ignored) {
                    // Nothing to release.
                }
            }
        }
    }

    private static String granted(Context context, String permission) {
        return String.valueOf(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
    }

    private static String profileState(BluetoothAdapter adapter, int profile) {
        try {
            switch (adapter.getProfileConnectionState(profile)) {
                case BluetoothProfile.STATE_CONNECTED:
                    return "connected";
                case BluetoothProfile.STATE_CONNECTING:
                    return "connecting";
                case BluetoothProfile.STATE_DISCONNECTING:
                    return "disconnecting";
                default:
                    return "disconnected";
            }
        } catch (Throwable error) {
            return "unavailable(" + error.getClass().getSimpleName() + ")";
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "builtin_speaker";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "bt_a2dp";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "bt_sco";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "usb_device";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "usb_headset";
            case AudioDeviceInfo.TYPE_BUS:
                return "bus";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:
                return "remote_submix";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "telephony";
            default:
                return String.valueOf(type);
        }
    }
}
