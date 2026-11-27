package io.github.hypercar.session

import io.github.hypercar.headunit.HeadUnitProfile
import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.net.CarNetworkReuse
import io.github.hypercar.settings.CarWithDisplay
import io.github.hypercar.settings.SettingsStore

/** Entry lifecycle uses the attempt snapshot; wire protocol is negotiated separately in Rust. */
internal object CarLifeSourcePolicy {
    // "carplus"（OPPO Car+ 车联）是本 fork 新增的 CarLife 源，与 carwith 同构：
    // 自己建屏/编码并开本机七通道，只是拉起对象不同；通话音频沿用 carwith 的默认开启。
    fun isGateway(source: String) = source == "carwith" || source == "carplus" || source == "system_carlife"

    // 上游 v0.1.0.639 新增：BMW 网关源只在 ID85 且对端 MAC 可用时准备入站连接。
    // carplus 不参与这条限制，仍按车机档案判断（ID6/ID85/ID9 都可），避免 Car+ 在 ID6 上被挡。
    fun canPrepareBmwInbound(source: String, profile: HeadUnitProfile, peer: String?): Boolean =
        if (source == "carwith" || source == "system_carlife") profile.id == HeadUnitProfiles.BMW_ID85.id && mac(peer) != null
        else profile.protocolProfileId.startsWith("bmw_")

    fun allowsCallAudio(source: String, systemCallAudio: Boolean) =
        source == "carwith" || source == "carplus" || source == "system_carlife" && systemCallAudio
    fun shouldReopen(source: String, startup: Boolean) =
        source == "carwith" || source == "carplus" || source == "system_carlife" && startup

    fun prewarmSize(systemCarLife: Boolean, cached: CarWithDisplay?, manualSize: Pair<Int, Int>): Pair<Int, Int> =
        cached?.let { it.width to it.height } ?: if (systemCarLife) manualSize else
            SettingsStore.DEFAULT_VIDEO_WIDTH to SettingsStore.DEFAULT_VIDEO_HEIGHT

    fun systemCanvasKey(profile: String, activePeer: String?, hotspotPeer: String?,
                        selectedPeer: String?, aclWakePeer: String?): String? {
        val hotspot = hotspotPeer?.takeIf { it.equals(activePeer, true) && (selectedPeer.isNullOrBlank() || it.equals(selectedPeer, true)) }
        val wake = aclWakePeer?.takeIf {
            it.equals(selectedPeer, true) && (activePeer.isNullOrBlank() || it.equals(activePeer, true))
        }
        return PairingEvidence.canvasKey(profile, hotspot, wake)
    }

    data class HuIdentity(val address: String, val name: String)
    private fun mac(value: String?) = CarNetworkReuse.mac(value)?.uppercase(java.util.Locale.ROOT)
        ?.takeUnless { it == "FF:FF:FF:FF:FF:FF" }

    fun huIdentity(receiver: String?, target: String?, targetName: String?, paired: Set<String>): HuIdentity? {
        val selected=mac(target)
        val address=mac(receiver) ?: selected ?: return null
        val remembered=paired.firstOrNull { mac(it.substringBefore('|')) == address }
            ?.substringAfter('|')?.substringBeforeLast('|')?.takeIf { it.isNotBlank() }
        val name=targetName?.takeIf { selected == address && it.isNotBlank() } ?: remembered ?: address
        return HuIdentity(address,name.trim().take(128))
    }
}
