/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.metaai

import app.crimera.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.crimera.patches.instagram.utils.addFlags
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Neutralizes the `iget-boolean <reg>, <obj>, LX/5Bu;->A08:Z` read ("compose
 * the Meta AI search-bar button/icon") in a search-bar composable: when the
 * toggle is on the gate register is zeroed right after the stock read, so the
 * composable takes the stock no-Meta-AI path exactly as it does for users the
 * feature is disabled for. Off = stock behavior untouched.
 */
private fun MutableMethod.forceNoMetaAiButton() {
    val igetIndex =
        instructions.indexOfFirst {
            it.opcode == Opcode.IGET_BOOLEAN &&
                it.getReference<FieldReference>()?.let { ref ->
                    ref.definingClass == "LX/5Bu;" && ref.name == "A08"
                } == true
        }
    if (igetIndex == -1) error("5Bu.A08 read not found in ${this.name}")

    val gateReg = getInstruction(igetIndex).registersUsed[0]
    val scratch = findFreeRegister(igetIndex + 1)
    if (scratch < 0) error("no free register in ${this.name}")

    addInstructionsWithLabels(
        igetIndex + 1,
        """
        $PREF_CALL_DESCRIPTOR->disableMetaAi()Z
        move-result v$scratch
        if-eqz v$scratch, :piko_no_mai_keep
        const/4 v$gateReg, 0x0
        """.trimIndent(),
        ExternalLabel("piko_no_mai_keep", getInstruction(igetIndex + 1)),
    )
}

@Suppress("unused")
val disableMetaAiPatch =
    bytecodePatch(
        name = "Disable Meta AI",
        description = "Disables Meta AI entry points across the app (DMs, search, feed, reels, share sheet). Toggled in piko settings.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(
            settingsPatch,
            hookFlagsPatch,
        )
        execute {
            // Entry-point flag kills (int MobileConfig flags via HookFlags).
            addFlags("metaAiFlags")

            // DM inbox search bar: the Meta AI button/icon variant is gated by
            // 64-bit MobileConfig params (not the flags above), so the button
            // survives the kills. Zero the A08 gate in the three composables
            // that read it. Existing Meta AI chat threads stay openable.
            SearchBarContentFingerprint.method.apply { forceNoMetaAiButton() }
            SearchBarIconFingerprint.method.apply { forceNoMetaAiButton() }
            MetaAiCustomActionButtonFingerprint.method.apply { forceNoMetaAiButton() }

            enableSettings("disableMetaAi")
        }
    }
