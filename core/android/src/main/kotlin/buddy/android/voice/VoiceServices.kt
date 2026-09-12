package buddy.android.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import buddy.android.BuddyApp

/**
 * Voice interaction role holders. Phase 0 claims the role and does nothing with it;
 * hotword detection (tier 0) and sessions arrive with the audio pipeline in Phase 1.
 */
class BuddyVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(BuddyApp.TAG, "voice interaction service ready")
    }
}

class BuddyVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            Log.i(BuddyApp.TAG, "assist session shown; nothing to do in this phase")
            hide()
        }
    }
}
