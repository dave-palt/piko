/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.videoQuality

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction11x

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/instagram/patches/videoQuality/Av1DecoderFilter;"

/**
 * True when the method body references every anchor string (any const-string
 * opcode form — the "video/av01" literal sits in a const-string/jumbo on 439).
 */
private fun referencesStrings(method: Method, vararg anchors: String): Boolean {
    val body = method.implementation?.instructions ?: return false
    return anchors.all { anchor ->
        body.any {
            (it as? ReferenceInstruction)?.reference?.toString() == anchor
        }
    }
}

/**
 * The codec selector behind IG's player: Bp8(mime, Z, Z)List over decoder-info
 * wrappers (X/00PC on 439). Logs through "MediaCodecSelectorHelper" and has an
 * AV1 special case ("video/av01" + hw-first sort). The MV-HEVC twin selector
 * (classes19) shares the signature and log tag but never references video/av01.
 */
internal object Av1CodecSelectorFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf("Ljava/lang/String;", "Z", "Z"),
    custom = { method, _ -> referencesStrings(method, "video/av01", "MediaCodecSelectorHelper") },
)

/**
 * Instagram serves reels as AV1-only DASH ladders; some hardware AV1 decoders
 * (Qualcomm c2.qti.av1.decoder) drop hundreds of frames per seek on these
 * streams. When the toggle is on, the extension strips hardware AV1 entries
 * from the selector's result so playback falls back to software dav1d.
 */
@Suppress("unused")
val av1DecoderPatch =
    bytecodePatch(
        name = "Software AV1 decoding",
        description =
            "Decode AV1 video (new reels pipeline) with the software decoder. " +
                "Fixes stuck scrubbing and frozen frames after seeking on devices whose " +
                "hardware AV1 decoder mishandles Instagram's AV1 streams",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)
        execute {
            Av1CodecSelectorFingerprint.method.apply {
                val returnIndices =
                    instructions
                        .withIndex()
                        .filter { (_, ins) -> ins.opcode == Opcode.RETURN_OBJECT }
                        .map { (i, _) -> i }
                        .sortedDescending()

                for (index in returnIndices) {
                    val reg = (getInstruction(index) as Instruction11x).registerA
                    addInstructions(
                        index,
                        """
                        invoke-static {p1, v$reg}, $EXTENSION_CLASS_DESCRIPTOR->filter(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$reg
                        check-cast v$reg, Ljava/util/List;
                        """.trimIndent(),
                    )
                }
            }
            enableSettings("videoQuality")
        }
    }
