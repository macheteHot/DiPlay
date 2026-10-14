package com.diplay.carprobe;

import android.app.Activity;
import android.os.Bundle;

/**
 * Runs the crash-prone part of the HUD probe (the vendor {@code HUDInteractionImpl} and
 * {@code ECarXCarVfhudManager}) in its own process, declared as {@code :hudimpl} in the manifest.
 *
 * <p>On this head unit the vendor signal callback throws {@code ClassCastException} (Float to
 * Integer) and takes the whole process down; isolating it keeps the main probe alive and still
 * captures the crash in logcat.
 */
public final class HudImplProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProbeLog.i("HudImplProbeActivity pid=" + android.os.Process.myPid());
        HudApi.readRisky(this);
        finish();
    }
}
