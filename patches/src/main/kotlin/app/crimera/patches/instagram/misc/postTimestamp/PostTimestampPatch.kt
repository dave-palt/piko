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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction11x

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
            // Home feed: force the header-state timestamp gate after the stock
            // iput so the timestamp renders beside the username. The state class
            // rotates between versions (435: K4g.A05, 439: 0J2P.A05) — resolved
            // dynamically from the single boolean-field iput whose value is the
            // 0224.A1T gate result (439) / equivalent computed gate (435).
            PostHeaderUsernameFlowRowFingerprint.method.apply {
                val iputIndex =
                    instructions.indexOfFirst {
                        it.opcode == Opcode.IPUT_BOOLEAN &&
                            it.getReference<FieldReference>()?.let { ref ->
                                ref.name == "A05"
                            } == true
                    }
                if (iputIndex == -1) error("A05 timestamp-gate iput not found in post header builder")

                val stateReg = getInstruction(iputIndex).registersUsed[1]

                // Idempotent force-true inserted after the stock iput: when
                // the toggle is on, A05 is (re)written with true so the
                // timestamp renders. OFF path leaves the stock value intact.
                addInstructionsWithLabels(
                    iputIndex + 1,
                    """
                    $PREF_CALL_DESCRIPTOR->showPostTimestamp()Z
                    move-result v2
                    if-eqz v2, :piko_ts_skip
                    iput-boolean v2, v$stateReg, ${getInstruction(iputIndex).getReference<FieldReference>()!!.definingClass}->A05:Z
                    """.trimIndent(),
                    ExternalLabel("piko_ts_skip", getInstruction(iputIndex + 1)),
                )
            }

            // Home feed layout picker (435 only): when the secondary line
            // (music attribution) was present, stock picked the inline layout
            // that drops the time row. In 439 the PostHeaderUsername composable
            // builds the flow-row node UNCONDITIONALLY (single 0fwn ctor, no
            // alternate inline node), so this site is obsolete — the A05 gate
            // (site 1) alone decides whether the time row renders (0Wkq.A00
            // reads it). Kept as a no-op for history; re-enable if IG brings
            // back a two-layout header.
            if (false) {
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
            }

            // Reels: the timestamp row lives in the caption component and is
            // gated on the caption-expanded flag (435: 6xB.A2j; 439: 00R5.A2n).
            // Force those gate branches so the row renders while the caption
            // is collapsed.
            // 439 render methods: 04LW.A02(0AsI)03Wk, 0TXO.A01(0AsI)03Wk.
            listOf(
                Triple(ReelsCaptionXu2Fingerprint, "A02", "LX/04LW;"),
                Triple(ReelsCaption2SYFingerprint, "A01", "LX/0TXO;"),
            ).forEach { (fingerprint, renderName, definingClass) ->
                // The render method itself: its caption-expanded read is
                // immediately followed by the gate branch.
                fingerprint.classDef.methods
                    .first {
                        it.name == renderName &&
                            it.parameterTypes == listOf("LX/0AsI;") &&
                            it.returnType == "LX/03Wk;"
                    }
                    .apply {
                        val gateBranches =
                            instructions.withIndex()
                                .filter { (i, insn) ->
                                    insn.opcode == Opcode.IGET_BOOLEAN &&
                                        insn.getReference<FieldReference>()?.let { ref ->
                                            // caption-expanded flag holder rotates
                                            // (435: 6xB, 439: 00R5); field name A2j/A2n.
                                            ref.name.startsWith("A2") && ref.type == "Z"
                                        } == true &&
                                        i + 1 < instructions.size &&
                                        instructions[i + 1].opcode == Opcode.IF_EQZ
                                }
                                .map { it.index + 1 }
                        if (gateBranches.isEmpty()) error("no caption-expanded gate branch in $definingClass.$renderName")

                        gateBranches.sortedDescending().forEach { forceGateAt(it) }
                    }

                // The public builder (A0o, the fingerprinted method) guards
                // its render invocations behind the same flag. For every
                // self-invoke of the render method, walk back through the
                // gate chain and force the outermost branch.
                fingerprint.method.apply {
                    val renderInvokes =
                        instructions.withIndex()
                            .filter { (_, insn) ->
                                insn.opcode == Opcode.INVOKE_DIRECT &&
                                    insn.getReference<MethodReference>()?.let { ref ->
                                        ref.definingClass == definingClass &&
                                            ref.name == renderName &&
                                            ref.parameterTypes == listOf("LX/0AsI;")
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

            // Classic (Litho) home-feed header (439): every subtitle-list path
            // converges on a common tail that consults
            // 00q2.A01(session, timeHolder)Z — true adds the timestamp entry
            // (00u5.A0B) if absent. The 435 audio-attribution skip branch no
            // longer exists; OR-forcing the A01 result at every site makes
            // the timestamp entry always join the list (music attribution
            // stays). OFF path ORs in 0 = stock byte-for-byte.
            FeedHeaderSubtitleListFingerprint.method.apply {
                val gateCalls =
                    instructions.withIndex()
                        .filter { (_, insn) ->
                            insn.opcode == Opcode.INVOKE_STATIC &&
                                insn.getReference<MethodReference>()?.let { ref ->
                                    // "should show time beside username" gate:
                                    // (UserSession, timeHolder)Z — 439: 00q2.A01,
                                    // resolved by shape (2 params, 2nd is the
                                    // 00rQ.A01() time-holder type, Z return).
                                    ref.parameterTypes.size == 2 &&
                                        ref.parameterTypes[0].toString() == USER_SESSION_CLASS &&
                                        ref.parameterTypes[1].toString() == "LX/00t1;" &&
                                        ref.returnType == "Z"
                                } == true
                        }.map { it.index }
                if (gateCalls.isEmpty()) error("time-gate call not found in feed header subtitle list builder")

                // Each call is followed by move-result vN + if-eqz vN — force
                // from the last site backwards so indices stay valid.
                gateCalls.map { it + 1 }.sortedDescending().forEach { moveResultIndex ->
                    if (instructions[moveResultIndex].opcode != Opcode.MOVE_RESULT) {
                        error("time-gate call not followed by move-result")
                    }
                    forceGateAt(moveResultIndex)
                }
            }

            enableSettings("showPostTimestamp")
        }
    }
