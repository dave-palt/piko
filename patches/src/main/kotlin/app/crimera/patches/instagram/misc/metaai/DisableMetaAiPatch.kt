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
import app.crimera.patches.instagram.utils.Constants.META_AI_BLOCK_CLASS
import app.crimera.patches.instagram.utils.addFlags
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Neutralizes the `iget-boolean <gate>, <obj>, LX/5Bu;->A08:Z` read ("compose
 * the Meta AI search-bar button/icon") in a search-bar composable. The gate
 * register is dead right before the stock read (the iget overwrites it), so
 * no scratch register is needed: with the toggle on, the gate register is
 * zeroed and the stock read is skipped, making the following stock branch
 * take the no-Meta-AI path — identical to a user the feature is disabled
 * for. With the toggle off the stock read executes untouched.
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
    if (igetIndex + 1 >= instructions.size) error("no branch after 5Bu.A08 read in ${this.name}")

    val gateReg = getInstruction(igetIndex).registersUsed[0]

    addInstructionsWithLabels(
        igetIndex,
        """
        $PREF_CALL_DESCRIPTOR->disableMetaAi()Z
        move-result v$gateReg
        if-eqz v$gateReg, :piko_mai_stock
        const/4 v$gateReg, 0x0
        goto :piko_mai_after
        """.trimIndent(),
        ExternalLabel("piko_mai_stock", getInstruction(igetIndex)),
        ExternalLabel("piko_mai_after", getInstruction(igetIndex + 1)),
    )
}

@Suppress("unused")
val disableMetaAiPatch =
    bytecodePatch(
        name = "Disable Meta AI",
        description = "Disables Meta AI entry points across the app (DMs, search, feed, reels, share sheet) and blocks Meta AI network requests. Toggled in piko settings.",
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

            // DM inbox search overlay "Ask Meta AI" result row (classic view
            // binder, not compose) — reads the same A08 gate.
            MetaAiSearchRowFingerprint.method.apply { forceNoMetaAiButton() }

            // Network-level block: rewrite the endpoint path at the central
            // REST funnel so Meta AI requests can never be built, regardless
            // of which surface triggered them.
            RestRequestFunnelFingerprint.method.apply {
                // Collect first, then inject in reverse so earlier insertions
                // do not shift the indices of later sites.
                val sites =
                    instructions
                        .filter {
                            it.opcode == Opcode.IGET_OBJECT &&
                                it.getReference<FieldReference>()?.let { ref ->
                                    ref.definingClass == "LX/2tK;" && ref.name == "A0G"
                                } == true
                        }.map { it.location.index to it.registersUsed[0] }

                sites.sortedByDescending { it.first }.forEach { (idx, pathReg) ->
                    addInstructions(
                        idx + 1,
                        """
                        invoke-static {v$pathReg}, $META_AI_BLOCK_CLASS->restPath(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$pathReg
                        """.trimIndent(),
                    )
                }
            }

            // GraphQL funnel: sanitize every String param at method head so
            // persisted-query hashes / query names for Meta AI never resolve.
            GraphQLRequestFunnelFingerprint.method.apply {
                val stringParams = parameters.withIndex().filter { it.value.type == "Ljava/lang/String;" }
                stringParams.forEach { (i, _) ->
                    addInstructions(
                        0,
                        """
                        invoke-static {p$i}, $META_AI_BLOCK_CLASS->gqlName(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object p$i
                        """.trimIndent(),
                    )
                }
            }

            enableSettings("disableMetaAi")
        }
    }
