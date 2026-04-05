package com.youki.dex.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * CastOptionsProvider
 *
 * Required by the Cast SDK as an entry point.
 * We use DEFAULT_MEDIA_RECEIVER_APPLICATION_ID so it works on any Chromecast
 * without needing to register a custom app in the Google Cast Developer Console.
 *
 * If a custom Cast app is needed in the future, change the ID here.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val mediaOptions = CastMediaOptions.Builder()
            .build()

        return CastOptions.Builder()
            // DEFAULT_MEDIA_RECEIVER_APPLICATION_ID = "CC1AD845"
            // Works on any Chromecast without registration
            .setReceiverApplicationId(
                com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
