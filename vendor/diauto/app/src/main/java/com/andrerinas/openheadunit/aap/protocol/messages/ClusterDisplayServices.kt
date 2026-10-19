package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.hud.ClusterMapConfig
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * The extra services that declare the BYD dashboard to the phone as a second display.
 *
 * Android Auto renders a navigation app's cluster map on a video sink whose display_type is
 * CLUSTER (Waze and Google Maps both support it), declared with an input service of its own.
 */
internal object ClusterDisplayServices {


    fun profile(context: android.content.Context, settings: Settings): ClusterMapConfig.Profile = ClusterMapConfig.profile(
        ClusterMapConfig.Size.of(settings.clusterMapSize),
        ClusterMapConfig.Detail.of(settings.clusterMapDetail),
        settings.clusterMarkerX,
        settings.clusterMarkerY,
        ClusterMapConfig.Width.of(settings.clusterMapWidth),
        window(context, settings),
    )

    /**
     * The car's cluster theme: as the cluster itself reports it when DiAuto has Usage access (as
     * DiPlay follows it), otherwise the theme picked in Settings. A wrong guess puts the map on a
     * layer the cluster shows over everything, so the reported theme always wins.
     */
    fun theme(context: android.content.Context, settings: Settings): ClusterMapConfig.Theme {
        val reported = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
            ClusterMapConfig.measured(android.os.Build.FINGERPRINT)
        ) ClusterMapConfig.reportedTheme
            ?: com.andrerinas.openheadunit.hud.DiLink51ClusterMonitor.snapshotNow(context)?.theme
        else null
        return reported ?: ClusterMapConfig.Theme.of(settings.clusterTheme)
    }

    /** The map window of the car's cluster theme, on measured DiLink 5.1 firmware only. */
    fun window(context: android.content.Context, settings: Settings): ClusterMapConfig.Window? =
        if (ClusterMapConfig.measured(android.os.Build.FINGERPRINT)) ClusterMapConfig.window(theme(context, settings))
        else null

    fun build(context: android.content.Context, settings: Settings): List<Control.Service> {
        if (!settings.clusterMapEnabled) return emptyList()
        val p = profile(context, settings)
        AppLog.i("[ServiceDiscovery] Announcing dashboard display: theme=${theme(context, settings)} " +
            "codec=${p.codecWidth}x${p.codecHeight} content=${p.contentWidth}x${p.contentHeight} " +
            "density=${p.density} insets=${p.contentInsets}")

        fun insets(i: ClusterMapConfig.Insets) = Media.Insets.newBuilder()
            .setTop(i.top).setBottom(i.bottom).setLeft(i.left).setRight(i.right).build()

        val video = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType.forNumber(p.codecResolution)
            )
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(p.widthMargin)
            .setMarginHeight(p.heightMargin)
            .setDensity(p.density)
            .setRealDensity(p.density)
            .setDecoderAdditionalDepth(0)
            .setViewingDistance(VIEWING_DISTANCE_MM)
            .setPixelAspectRatioE4(10000)
            .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setUiConfig(
                Media.UiConfig.newBuilder()
                    .setMargins(insets(p.margins))
                    .setContentInsets(insets(p.contentInsets))
                    .setStableContentInsets(insets(p.contentInsets))
                    .setUiTheme(0) // automatic: follow the phone's day/night
                    .build()
            )
            .build()

        val sink = Control.Service.MediaSinkService.newBuilder()
            .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setAudioType(Media.AudioStreamType.NONE)
            .setAvailableWhileInCall(true)
            .addVideoConfigs(video)
            .setDisplayId(ClusterMapConfig.DISPLAY_ID)
            .setDisplayType(Control.Service.MediaSinkService.DisplayType.forNumber(ClusterMapConfig.DISPLAY_TYPE_CLUSTER))

        val services = mutableListOf(
            Control.Service.newBuilder().setId(Channel.ID_CLUSTER_VID).setMediaSinkService(sink).build()
        )
        // Every extra display gets an input service bound to it, as LIVI declares its cluster.
        val input = Control.Service.InputSourceService.newBuilder().setDisplayId(ClusterMapConfig.DISPLAY_ID)
        services += Control.Service.newBuilder().setId(Channel.ID_CLUSTER_INP).setInputSourceService(input).build()
        return services
    }

    /** Roughly the driver's distance to an instrument cluster. */
    private const val VIEWING_DISTANCE_MM = 750
}
