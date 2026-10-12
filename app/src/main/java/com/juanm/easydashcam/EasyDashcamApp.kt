package com.juanm.easydashcam

import android.app.Application
import com.juanm.easydashcam.core.session.CameraSession

class EasyDashcamApp : Application() {
    val session by lazy { CameraSession(this) }
}
