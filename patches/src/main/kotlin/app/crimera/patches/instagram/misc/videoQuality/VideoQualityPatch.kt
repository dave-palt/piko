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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/instagram/patches/videoQuality/VideoQuality;"

private const val VIDEO_URL_IMPL = "Lcom/instagram/model/mediasize/VideoUrlImpl;"

/** True when the method reads VideoUrlImpl.A01 (the variant type int) — the picker's tell. */
private fun readsVideoUrlType(method: Method): Boolean =
    method.implementation?.instructions?.any {
        it.opcode == Opcode.IGET &&
            (it as? ReferenceInstruction)?.reference is FieldReference &&
            (it.reference as FieldReference).let { r -> r.definingClass == VIDEO_URL_IMPL && r.name == "A01" }
    } == true

/**
 * X/08iu.A00(LX/03oL;)VideoUrlImpl — playback picker for the 03oL media DTO:
 * prefers type==100 else max-width. Single return site.
 */
internal object VideoUrlPickerA00Fingerprint : Fingerprint(
    returnType = VIDEO_URL_IMPL,
    parameters = listOf("LX/03oL;"),
    custom = { method, _ -> readsVideoUrlType(method) },
)

/**
 * X/08iu.A01(Ljava/util/List;)VideoUrlImpl — playback picker over a VideoUrlImpl list:
 * prefers type in {-2,100,101,102}, else first(). THREE return sites — all hooked.
 */
internal object VideoUrlPickerA01Fingerprint : Fingerprint(
    returnType = VIDEO_URL_IMPL,
    parameters = listOf("Ljava/util/List;"),
    custom = { method, _ -> readsVideoUrlType(method) },
)

@Suppress("unused")
val videoQualityPatch =
    bytecodePatch(
        name = "Video playback quality",
        description = "Select in-app video playback quality (highest, 720p, 480p, 360p)",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)
        execute {
            // Rewrite the picked variant at EVERY return site, so early preferred-type
            // returns are covered too. Descending order keeps earlier indices valid.
            fun hookReturns(
                fingerprint: Fingerprint,
                callFormat: (Int) -> String,
            ) {
                fingerprint.method.apply {
                    val returnIndices =
                        instructions
                            .withIndex()
                            .filter { (i, ins) -> ins.opcode == Opcode.RETURN_OBJECT }
                            .map { (i, _) -> i }
                            .sortedDescending()

                    for (index in returnIndices) {
                        val reg = (getInstruction(index) as Instruction11x).registerA
                        addInstructions(index, callFormat(reg))
                    }
                }
            }

            hookReturns(VideoUrlPickerA00Fingerprint) { reg ->
                """
                invoke-static {v$reg, p0}, $EXTENSION_CLASS_DESCRIPTOR->pick(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$reg
                check-cast v$reg, $VIDEO_URL_IMPL
                """.trimIndent()
            }

            hookReturns(VideoUrlPickerA01Fingerprint) { reg ->
                """
                invoke-static {v$reg, p0}, $EXTENSION_CLASS_DESCRIPTOR->pickFromList(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$reg
                check-cast v$reg, $VIDEO_URL_IMPL
                """.trimIndent()
            }

            enableSettings("videoQuality")
        }
    }
