/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.readOnlyFollowButton

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.NOOP_FUNCTION0_CLASS
import app.crimera.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Read-only Follow button in post/reel headers: the label stays (Follow /
// Following / Requested) but tapping it does nothing. The profile page's
// follow button is intentionally NOT touched.
//
// Two compose surfaces render header follow buttons:
//  1. Vgs.A01  (barcelona FollowButton, feed + reels) — p4/v30 onClick,
//     assigned once in the prologue and only read afterwards; swapped right
//     before the 3l9.A0R null-check (scratch v2, dead until its next write).
//  2. WkD.A02  (IGDS FollowButtonComponent) — v10 (p2 onClick) is re-read
//     from the composer memo (GDI()) before the consuming call, so the swap
//     goes immediately before the D4K.A01 invocation (scratch v0, dead after
//     the BU8.A04 iput).
//
// Never null: both consumers null-check the Function0; the extension supplies
// a cached no-op Function0 built via a dynamic proxy.
@Suppress("unused")
val readOnlyFollowButtonPatch =
    bytecodePatch(
        name = "Read-only follow button",
        description = "Makes the Follow button in post and reel headers a read-only indicator (tapping does nothing). The profile page button keeps working.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // ---- Site 1: Vgs.A01 (barcelona FollowButton, feed + reels) ----
            FollowButtonFingerprint.method.apply {
                val nullCheckIndex =
                    instructions.indexOfFirst {
                        it.opcode == Opcode.INVOKE_STATIC_RANGE &&
                            it.getReference<MethodReference>()?.let { ref ->
                                ref.definingClass == "LX/3l9;" && ref.name == "A0R"
                            } == true
                    }
                if (nullCheckIndex == -1) error("3l9.A0R null-check not found in FollowButton")

                // Inserted before the null-check: when the toggle is on, v30
                // (the onClick) is replaced by the shared no-op lambda (v30 >
                // v15, so range form). The null-check then validates the
                // replacement and execution continues unchanged.
                addInstructionsWithLabels(
                    nullCheckIndex,
                    """
                    $PREF_CALL_DESCRIPTOR->readOnlyFollowButton()Z
                    move-result v2
                    if-eqz v2, :piko_vgs_keep
                    invoke-static/range {v30 .. v30}, $NOOP_FUNCTION0_CLASS->noop(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v30
                    """.trimIndent(),
                    ExternalLabel("piko_vgs_keep", getInstruction(nullCheckIndex)),
                )
            }

            // ---- Site 2: WkD.A02 (IGDS FollowButtonComponent) ----
            IgdsFollowButtonComponentFingerprint.method.apply {
                val d4kCallIndex =
                    instructions.indexOfFirst {
                        it.opcode == Opcode.INVOKE_STATIC_RANGE &&
                            it.getReference<MethodReference>()?.let { ref ->
                                ref.definingClass == "LX/D4K;" && ref.name == "A01"
                            } == true
                    }
                if (d4kCallIndex == -1) error("D4K.A01 call not found in FollowButtonComponent")

                // Inserted immediately before the consuming call so the memo
                // path's reassignment of v10 cannot resurrect the real
                // handler. Scratch v3: outside the {v6..v14} call range and
                // dead here (next touch is the write at goto_69's
                // move-result-object v3). v10 <= v15 so plain invoke-static.
                addInstructionsWithLabels(
                    d4kCallIndex,
                    """
                    $PREF_CALL_DESCRIPTOR->readOnlyFollowButton()Z
                    move-result v3
                    if-eqz v3, :piko_wkd_keep
                    invoke-static {v10}, $NOOP_FUNCTION0_CLASS->noop(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v10
                    """.trimIndent(),
                    ExternalLabel("piko_wkd_keep", getInstruction(d4kCallIndex)),
                )
            }

            // ---- Site 3: 5b5.A05 (classic Litho/view wiring chokepoint) ----
            // The home-feed inline_follow_button is NOT compose — its click
            // listener is attached in 5b5.A05 at two 0es.A00(listener, view)
            // calls: the primary site (:cond_97, listener v11 = custom field
            // A00 or the default 5bJ follow action) and an experimental-gated
            // variant (LLj, listener v0). Patch EVERY attach call, reading the
            // listener register from each instruction itself. Both registers
            // are dead across their calls; v0/v11 <= v15 so plain forms.
            ViewFollowButtonWiringFingerprint.method.apply {
                val attachSites =
                    instructions.withIndex()
                        .filter { (_, ins) ->
                            ins.opcode == Opcode.INVOKE_STATIC &&
                                ins.getReference<MethodReference>()?.let { ref ->
                                    ref.definingClass == "LX/0es;" && ref.name == "A00"
                                } == true
                        }
                        .map { (i, ins) -> i to ins.registersUsed.first() }
                if (attachSites.isEmpty()) error("0es.A00 attach call not found in 5b5.A05")

                attachSites.sortedByDescending { it.first }.forEach { (idx, reg) ->
                    addInstructions(
                        idx,
                        """
                        invoke-static {v$reg}, $NOOP_FUNCTION0_CLASS->noopListener(Landroid/view/View$OnClickListener;)Landroid/view/View$OnClickListener;
                        move-result-object v$reg
                        """.trimIndent(),
                    )
                }
            }

            enableSettings("readOnlyFollowButton")
        }
    }
