package com.fersaiyan.cyanbridge.media

import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.DeviceManager
import java.util.Locale

/** Selects the paired HeyCyan device from Wi-Fi Direct discovery results. */
internal object HeyCyanP2pPeerSelector {
    private fun currentBleMacNoColonUpper(): String? {
        return try {
            DeviceManager.getInstance().deviceAddress
                ?.replace(":", "")
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun likelyGlassesPeerStrength(device: WifiP2pDevice, bleMacNoColon: String?): Int {
        val name = (device.deviceName ?: "").uppercase(Locale.US)
        if (name.isBlank()) return -1

        if (!bleMacNoColon.isNullOrBlank() && name.contains(bleMacNoColon)) {
            return 100
        }

        if (
            name.contains("HEYCYAN") ||
            name.contains("CYAN") ||
            name.startsWith("O_") ||
            name.startsWith("Q_")
        ) {
            return 80
        }

        // Known glasses model prefixes/brands from glasses_models.txt
        val glassesPrefixes = arrayOf(
            "AIM", "CR-", "SG", "GL-", "ST-AG", "BW-AG", "ABG-", "RG-",
            "ZIC-GLA", "QK-SG", "TF-GL", "HY-G", "NLB", "DM-", "ES-",
            "GS", "BV", "XC", "CG", "WL-", "ID-", "AZBV", "PW-", "GV3",
            "AX01", "ER0S", "VEU", "FIRELENS", "VIBELENS", "AIWR", "ROLBATCH",
            "DPVR", "VIZO", "SMARTVIEW", "KSIX", "VISO", "MD02", "WAGA",
            "BROOKLYN", "KATLOS", "FASTRACK", "HUGUR", "NILOX", "VEYRA",
            "AHENOD", "BOMANLON", "TRUSMI", "FABRIKA", "MICROWEAR",
            "WANDERTH", "PANGBOLIN", "SEEVA", "ASTR", "LENYES", "BLISBOND",
            "MEEEGOU", "NEOSEE", "SOBAST"
        )

        if (glassesPrefixes.any { name.startsWith(it) || name.contains(it) }) {
            return 70
        }

        if (name.contains("AIMB-") || name.contains("GLASS")) {
            return 70
        }

        // Weak fallback only when nothing else looks like the glasses.
        if (Regex("[A-F0-9]{12}").containsMatchIn(name)) {
            return 30
        }

        return -1
    }

    fun selectBestLikelyGlassesPeer(peers: Collection<WifiP2pDevice>): WifiP2pDevice? {
        if (peers.isEmpty()) return null

        val bleMacNoColon = currentBleMacNoColonUpper()
        val scored = peers
            .map { peer -> peer to likelyGlassesPeerStrength(peer, bleMacNoColon) }
            .filter { (_, score) -> score >= 0 }
        if (scored.isEmpty()) return null

        val bestScore = scored.maxOf { it.second }
        val bestPeers = scored.filter { it.second == bestScore }.map { it.first }

        // Do not guess among multiple weak hex-only matches; keep waiting for a stronger signal.
        if (bestScore <= 30 && bestPeers.size > 1) {
            Log.i(
                "DataDownload",
                "Ambiguous weak glasses peer candidates; waiting for a stronger match: ${bestPeers.map { "${it.deviceName}/${it.deviceAddress}" }}"
            )
            return null
        }

        return bestPeers.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
            ?: bestPeers.firstOrNull()
    }

    fun selectOfficialLikelyGlassesPeer(peers: Collection<WifiP2pDevice>): WifiP2pDevice? {
        if (peers.isEmpty()) return null

        val pairedName = try {
            DeviceManager.getInstance().deviceName
        } catch (_: Exception) {
            null
        }
        val pairedAddress = try {
            DeviceManager.getInstance().deviceAddress
        } catch (_: Exception) {
            null
        }

        fun matches(peer: WifiP2pDevice): Boolean {
            return HeyCyanP2pPolicy.matchesOfficialPeer(peer.deviceName, pairedName, pairedAddress)
        }

        return peers.firstOrNull { matches(it) && it.status == WifiP2pDevice.AVAILABLE }
            ?: peers.firstOrNull(::matches)
    }

    fun expectedOfficialP2pName(): String {
        return try {
            HeyCyanP2pPolicy.officialWifiDirectName(
                DeviceManager.getInstance().deviceName,
                DeviceManager.getInstance().deviceAddress,
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
