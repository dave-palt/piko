/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.postTimestamp

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.MutableMethod
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Post header username row (barcelona, feed + reels). The invoke method
// builds the K4g header state; its A05 boolean gates whether the post
// timestamp is rendered beside the username. Stock rendering is inherited:
// relative ("5h") for recent posts, absolute date otherwise.
internal object PostHeaderUsernameFlowRowFingerprint : Fingerprint(
    strings = listOf("feed_post_header"),
    returnType = "Ljava/lang/Object;",
)

/**
 * ORs the gate register of the conditional branch at [branchIndex] with the
 * showPostTimestamp preference. With the toggle off the inserted code ORs in
 * 0, leaving stock behavior byte-for-byte equivalent; with it on the branch
 * always takes the "timestamp visible" path.
 */
private fun MutableMethod.forceGateAt(branchIndex: Int) {
    val gateReg = getInstruction(branchIndex).registersUsed[0]
    val scratch = findFreeRegister(branchIndex)
    if (scratch < 0) error("no free register for gate forcing at index $branchIndex")
    addInstructions(
        branchIndex,
        """
        $PREF_CALL_DESCRIPTOR->showPostTimestamp()Z
        move-result v$scratch
        or-int v$gateReg, v$gateReg, v$scratch
        """.trimIndent(),
    )
}

@Suppress("unused")
val postTimestampPatch =
    bytecodePatch(
        name = "Show post timestamp",
        description = "Shows the post date/time beside the username in post and reel headers.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // Home feed: force K4g.A05=true after the stock iput so the
            // timestamp renders beside the username.
            PostHeaderUsernameFlowRowFingerprint.method.apply {
                val iputIndex =
                    instructions.indexOfFirst {
                        it.opcode == Opcode.IPUT_BOOLEAN &&
                            it.getReference<FieldReference>()?.let { ref ->
                                ref.definingClass == "LX/K4g;" && ref.name == "A05"
                            } == true
                    }
                if (iputIndex == -1) error("K4g.A05 iput not found in post header builder")

                // Idempotent force-true inserted after the stock iput: when
                // the toggle is on, A05 is (re)written with true so the
                // timestamp renders. OFF path leaves the stock value intact.
                addInstructionsWithLabels(
                    iputIndex + 1,
                    """
                    $PREF_CALL_DESCRIPTOR->showPostTimestamp()Z
                    move-result v2
                    if-eqz v2, :piko_ts_skip
                    iput-boolean v2, v3, LX/K4g;->A05:Z
                    """.trimIndent(),
                    ExternalLabel("piko_ts_skip", getInstruction(iputIndex + 1)),
                )
            }

            // Home feed: when the secondary line (music attribution, K4g.A04)
            // is present, stock picks the inline layout that drops the time
            // row (v17 == 0 at the branch after the 135.A0M call). Force the
            // flow-row path; the music line renders via the common tail in
            // both branches.
            PostHeaderUsernameFingerprint.method.apply {
                val a0mIndex =
                    instructions.indexOfFirst {
                        it.opcode == Opcode.INVOKE_STATIC &&
                            it.getReference<MethodReference>()?.let { ref ->
                                ref.definingClass == "LX/135;" && ref.name == "A0M"
                            } == true
                    }
                if (a0mIndex == -1) error("135.A0M call not found in PostHeaderUsername")

                var branchIndex = -1
                for (i in a0mIndex + 1 until minOf(a0mIndex + 7, instructions.size)) {
                    if (instructions[i].opcode == Opcode.IF_NEZ) {
                        branchIndex = i
                        break
                    }
                }
                if (branchIndex == -1) error("layout branch after 135.A0M not found")
                forceGateAt(branchIndex)
            }

            // Reels: the timestamp row lives in the caption component and is
            // gated on the caption-expanded flag (6xB.A2j). Force those gate
            // branches so the row renders while the caption is collapsed.
            listOf(
                Triple(ReelsCaptionXu2Fingerprint, "A01", "LX/Xu2;"),
                Triple(ReelsCaption2SYFingerprint, "A02", "LX/2SY;"),
            ).forEach { (fingerprint, renderName, definingClass) ->
                // The render method itself: its A2j read is immediately
                // followed by the gate branch.
                fingerprint.classDef.methods
                    .first {
                        it.name == renderName &&
                            it.parameterTypes == listOf("LX/J3H;") &&
                            it.returnType == "LX/2Yc;"
                    }
                    .apply {
                        val gateBranches =
                            instructions.withIndex()
                                .filter { (i, insn) ->
                                    insn.opcode == Opcode.IGET_BOOLEAN &&
                                        insn.getReference<FieldReference>()?.let { ref ->
                                            ref.definingClass == "LX/6xB;" && ref.name == "A2j"
                                        } == true &&
                                        i + 1 < instructions.size &&
                                        instructions[i + 1].opcode == Opcode.IF_EQZ
                                }
                                .map { it.index + 1 }
                        if (gateBranches.isEmpty()) error("no A2j gate branch in $definingClass.$renderName")

                        gateBranches.sortedDescending().forEach { forceGateAt(it) }
                    }

                // The public builder (A0i, the fingerprinted method) guards
                // its render invocations behind the same flag. For every
                // self-invoke of the render method, walk back through the
                // gate chain and force the outermost (A2j) branch.
                fingerprint.method.apply {
                    val renderInvokes =
                        instructions.withIndex()
                            .filter { (_, insn) ->
                                insn.opcode == Opcode.INVOKE_DIRECT &&
                                    insn.getReference<MethodReference>()?.let { ref ->
                                        ref.definingClass == definingClass &&
                                            ref.name == renderName &&
                                            ref.parameterTypes == listOf("LX/J3H;")
                                    } == true
                            }
                            .map { it.index }
                    if (renderInvokes.isEmpty()) error("no $renderName invocations in $definingClass builder")

                    val gateBranches =
                        renderInvokes.map { invokeIndex ->
                            var gateIdx = -1
                            for (j in invokeIndex - 1 downTo invokeIndex - 6) {
                                if (j >= 0 && instructions[j].opcode == Opcode.IF_EQZ) gateIdx = j
                            }
                            if (gateIdx == -1) error("no gate branch before $renderName invoke at $invokeIndex")
                            gateIdx
                        }.distinct()

                    gateBranches.sortedDescending().forEach { forceGateAt(it) }
                }
            }

            enableSettings("showPostTimestamp")
        }
    }
