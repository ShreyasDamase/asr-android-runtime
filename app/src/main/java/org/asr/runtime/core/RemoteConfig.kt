package org.asr.runtime.core

/** Values accepted on the config/set topic. */
object RemoteConfig {
    /** 640x480 is the default because it holds a steady 30 FPS at a low data rate. */
    val CAMERA_RESOLUTIONS = listOf("320x240", "640x480", "1280x960")
}
