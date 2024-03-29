package com.dailymotion.android.player.sdk.iab

import android.content.Context
import com.dailymotion.android.player.sdk.PlayerWebView
import com.dailymotion.android.player.sdk.events.*
import com.iab.omid.library.dailymotion.Omid
import com.iab.omid.library.dailymotion.adsession.*
import com.iab.omid.library.dailymotion.adsession.media.*
import timber.log.Timber
import java.net.URL
import java.util.regex.Pattern


object OMHelper {

    const val PARTNER_NAME = "Dailymotion"
    /** This represents the version of our SDK that has been validated by IAB */
    const val PARTNER_VERSION = "0.2.8"

    private var omidSession: AdSession? = null
    private var omidAdEvents: AdEvents? = null
    private var omidMediaEvents: MediaEvents? = null
    private var omidCurrentPosition: Quartile? = null
    private var adDuration = 1f
    private var isAdPaused = false
    private var mVolume = 1f

    private var omErrorListener: OMErrorListener? = null

    /** Indicate the current player state. It's STRONGLY recommended to
     *  update at all time this field if the core app has other states than NORMAL or FULLSCREEN */
    var playerState: PlayerState? = null
        set(value) {
            if (value != field && value != null) {
                onPlayerStateChanged(value)
            }
            field = value
        }

    private enum class Quartile(
        val progress: Float,
        val nextStep: Quartile? = null,
        val action: ((MediaEvents) -> Unit)? = null
    ) {
        Q3(0.75f, null, { it.thirdQuartile(); logOmidAction("thirdQuartile") }),
        Q2(0.50f, Q3, { it.midpoint(); logOmidAction("midpoint") }),
        Q1(0.25f, Q2, { it.firstQuartile(); logOmidAction("firstQuartile") }),
        START(0f, Q1, { it.start(adDuration, 1f); logOmidAction("start duration=$adDuration") }),
        INIT(0f, START)
    }

    /**
     * Ensure Omid SDK is initialized
     */
    internal fun ensureInitialized(context: Context) {
        if (!Omid.isActive()) {
            Omid.activate(context.applicationContext)
        }
    }

    internal fun onPlayerEvent(playerWebView: PlayerWebView, playerEvent: PlayerEvent) {
        when (playerEvent) {
            is AdLoadedEvent -> {
                if (omidSession != null) {
                    endOmidSession()
                }
                createOmidSession(playerWebView, playerEvent.payload)

                try {
                    omidAdEvents?.apply {
                        impressionOccurred()
                        logOmidAction("Impression occured")
                    }
                } catch (e: Exception) {
                    omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                    logError("Error with adSession : Impression", e)
                }

                val position = try {
                    Position.valueOf(playerEvent.position?.uppercase().orEmpty())
                } catch (e: Exception) {
                    logError("Incorrect Position", e)
                    return
                }

                val properties = if(playerEvent.skipOffset > 0) VastProperties.createVastPropertiesForSkippableMedia(
                    playerEvent.skipOffset,
                    playerEvent.autoPlay,
                    position
                )else{
                    VastProperties.createVastPropertiesForNonSkippableMedia(playerEvent.autoPlay, position)
                }

                try {
                    omidAdEvents?.apply {
                        loaded(properties)
                        logOmidAction("Loaded ${properties.isAutoPlay}/${properties.isSkippable}/${properties.position}/${properties.skipOffset}")
                    }
                } catch (e: Exception) {
                    omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                    logError("Error with adSession : Load Properties", e)
                }
            }
            is AdStartEvent -> {
                adDuration = playerEvent.adDuration.takeIf { it != 0f } ?: 1f
                isAdPaused = false
                startOmidSession()
                sendVolumeEvent()
            }
            is AdEndEvent -> {
                try {
                    when (playerEvent.reason) {
                        "AD_STOPPED" -> {
                            omidMediaEvents?.apply {
                                complete()
                                logOmidAction("Complete")
                            }
                        }
                        "AD_SKIPPED" -> {
                            omidMediaEvents?.apply {
                                skipped()
                                logOmidAction("Skipped")
                            }
                        }
                        "AD_ERROR" -> {
                            omidSession?.error(ErrorType.VIDEO, playerEvent.error ?: "AD_ERROR")
                            logError("Error with adSession : AD_ERROR", Exception("Received an AD_ERROR"))
                        }
                    }
                    endOmidSession()
                } catch (e: Exception) {
                    omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                    logError("Error with adSession : AdEndEvent", e)
                }
            }
            is AdPauseEvent -> {
                isAdPaused = true
                try {
                    omidMediaEvents?.apply {
                        pause()
                        logOmidAction("pause")
                    }
                } catch (e: Exception) {
                    omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                    logError("Error with adSession : AdPauseEvent", e)
                }
            }
            is AdPlayEvent -> {
                if(isAdPaused) {
                    isAdPaused = false
                    try {
                        omidMediaEvents?.apply {
                            resume()
                            logOmidAction("resume")
                        }
                    } catch (e: Exception) {
                        omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                        logError("Error with adSession : AdPlayEvent", e)
                    }
                }
            }
            is AdBufferStartEvent -> {
                try {
                    omidMediaEvents?.apply {
                        bufferStart()
                        logOmidAction("bufferStart")
                    }
                } catch (e: Exception) {
                    omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                    logError("Error with adSession : AdBufferStartTime", e)
                }
            }
            is AdBufferEndEvent -> {
                try {
                    omidMediaEvents?.apply {
                        bufferFinish()
                        logOmidAction("bufferEnd")
                    }
                } catch (e: Exception) {
                    omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                    logError("Error with adSession : AdBufferEndEvent", e)
                }
            }
            is AdClickEvent -> {
                try {
                    omidMediaEvents?.apply {
                        adUserInteraction(InteractionType.CLICK)
                        logOmidAction("adUserInteraction Click")
                    }
                } catch (e: Exception) {
                    omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                    logError("Error with adSession : AdClickEvent", e)
                }
            }
            is VolumeChangeEvent -> {
                mVolume = if (playerEvent.isMuted) 0f else 1f
                sendVolumeEvent()
            }
            is FullScreenChangeEvent -> {
                if (playerState == null) {
                    /** Provided playerState is null, so we rely on events coming from player
                     * to set the player state **/
                    onPlayerStateChanged(if (playerEvent.fullscreen) PlayerState.FULLSCREEN else PlayerState.NORMAL)
                }
            }
            is AdTimeUpdateEvent -> {
                val progress = (playerEvent.time?.toDoubleOrNull() ?: 0.0) / adDuration
                val nextPosition = omidCurrentPosition?.nextStep ?: return
                if (progress > nextPosition.progress) {
                    omidCurrentPosition = nextPosition
                    try {
                        omidMediaEvents?.let { omidCurrentPosition?.action?.invoke(it) }
                    } catch (e: Exception) {
                        omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
                        logError("Error with adSession : AdTimeUpdateEvent", e)
                    }
                }
            }
        }
    }

    private fun sendVolumeEvent() {
        try {
            omidMediaEvents?.apply {
                volumeChange(mVolume)
                logOmidAction("volumeChange $mVolume")
            }
        } catch (e: Exception) {
            omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
            logError("Error with adSession : VolumeChangeEvent", e)
        }
    }

    /**
     * Create and setup an Omid AdSession
     * @param playerWebView the host
     * @param payload coming from the event which allow to setup the Omid session
     */
    private fun createOmidSession(playerWebView: PlayerWebView, payload: String?) {

        val verificationScriptsList = parseVerificationScriptData(payload)
        val verificationScriptResourceList = try {
            verificationScriptsList.map {
                VerificationScriptResource.createVerificationScriptResourceWithParameters(
                    it.vendorKey,
                    URL(it.url),
                    it.parameters
                )
            }
        } catch (e: Exception) {
            logError("Error while creating verificationScriptResourceList with payload", e, payload)
            return
        }

        val partner = try {
            Partner.createPartner(PARTNER_NAME, PARTNER_VERSION)
        } catch (e: java.lang.IllegalArgumentException) {
            logError("Error while creating partner", e)
            return
        }

        val adSessionContext = AdSessionContext.createNativeAdSessionContext(
            partner,
            OmidJsLoader.getOmidJs(playerWebView.context),
            verificationScriptResourceList,
            null,
            null
        )

        val adSessionConfiguration = try {
            AdSessionConfiguration.createAdSessionConfiguration(
                CreativeType.VIDEO, ImpressionType.ONE_PIXEL,
                Owner.NATIVE, Owner.NATIVE, true
            )
        } catch (e: IllegalArgumentException) {
            logError("Error while creating adSessionConfiguration", e)
            return
        }

        omidSession = AdSession.createAdSession(adSessionConfiguration, adSessionContext)
        omidSession?.registerAdView(playerWebView)

        omidAdEvents = AdEvents.createAdEvents(omidSession)
        omidMediaEvents = MediaEvents.createMediaEvents(omidSession)
        omidCurrentPosition = Quartile.INIT
    }

    /**
     * End Omid session
     */
    internal fun endOmidSession() {
        omidSession?.apply {
            finish()
            logOmidAction("Session End")
        }
        omidSession = null
        omidAdEvents = null
        omidMediaEvents = null
        omidCurrentPosition = null
        adDuration = 1f
        isAdPaused = false
    }

    /**
     * Start Omid session
     */
    private fun startOmidSession() {
        omidSession?.apply {
            start()
            logOmidAction("Session Start")
        }
        playerState?.let {
            /** Ensure we have the right state **/
            onPlayerStateChanged(it)
        }
    }

    /**
     * Parse the verificationScriptsList from the payload of AdLoadedEvent
     */
    internal fun parseVerificationScriptData(payload: String?): List<VerificationScriptData> {
        return payload
            ?.split("&")
            ?.groupBy {
                val m = Pattern.compile("verificationScripts\\[([0-9]*)]\\[(.*)]=(.*)").matcher(it)
                if (m.matches()) m.group(1) else null
            }
            ?.filterKeys { it != null }
            ?.map { group ->
                VerificationScriptData(
                    vendorKey = group.value.find { it.contains("verificationScripts[${group.key}][vendor]") }
                        ?.split("=", limit = 2)?.get(1) ?: "",
                    url = group.value.find { it.contains("verificationScripts[${group.key}][resource]") }
                        ?.split("=", limit = 2)?.get(1) ?: "",
                    parameters = group.value.find { it.contains("verificationScripts[${group.key}][parameters]") }
                        ?.split("=", limit = 2)?.get(1) ?: ""
                )
            }
            ?: emptyList()
    }

    private fun onPlayerStateChanged(state: PlayerState) {
        try {
            omidMediaEvents?.apply {
                playerStateChange(state)
                logOmidAction("PlayerState => $state")
            }
        } catch (e: Exception) {
            omidSession?.error(ErrorType.GENERIC, e.localizedMessage)
            logError("Error with adSession : PlayerState", e)
        }
    }

    private fun logError(error: String, exception: Exception, debug: String? = null) {
        Timber.e(exception, "OMSDK: ERROR : $error")
        omErrorListener?.onOMSDKError(error, exception, debug)
    }

    private fun logOmidAction(message: String) {
        Timber.d("OMSDK: $message")
    }

    internal fun getVersion() = Omid.getVersion()

    interface OMErrorListener {
        fun onOMSDKError(description: String, exception: Exception, debug: String?)
    }

    fun setOMErrorListener(errorListener: OMErrorListener?) {
        omErrorListener = errorListener
    }
}