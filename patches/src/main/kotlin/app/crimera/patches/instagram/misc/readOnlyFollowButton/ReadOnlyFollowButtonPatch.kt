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
                // The onClick Function0 (p4) is moved into vN in the prologue and
                // immediately null-checked by a shared kotlin-intrinsics helper
                // (435: 3l9.A0R, 439: 04l9.A0g — both `(Ljava/lang/Object;)V`).
                // Anchor on that first null-check of the moved p4 instead of the
                // helper's obfuscated name.
                val p4MoveIndex =
                    instructions.indexOfFirst {
                        it.opcode == Opcode.MOVE_OBJECT_FROM16
                    }
                if (p4MoveIndex == -1) error("p4 prologue move not found in FollowButton")

                val onClickReg = getInstruction(p4MoveIndex).registersUsed[0]

                val nullCheckIndex =
                    instructions.withIndex()
                        .drop(p4MoveIndex + 1)
                        .firstOrNull { (_, ins) ->
                            ins.opcode == Opcode.INVOKE_STATIC_RANGE &&
                                ins.getReference<MethodReference>()?.let { ref ->
                                    // null-check helper shape: single Object param, void return
                                    ref.parameterTypes.size == 1 &&
                                        ref.parameterTypes[0].toString() == "Ljava/lang/Object;" &&
                                        ref.returnType == "V"
                                } == true
                        }?.index
                        ?: error("Function0 null-check not found after p4 move in FollowButton")

                // Inserted before the null-check: when the toggle is on, the
                // onClick register is replaced by the shared no-op lambda. The
                // null-check then validates the replacement and execution
                // continues unchanged. (435: v30 > v15 so range form; 439 keeps
                // the same shape.)
                addInstructionsWithLabels(
                    nullCheckIndex,
                    """
                    $PREF_CALL_DESCRIPTOR->readOnlyFollowButton()Z
                    move-result v2
                    if-eqz v2, :piko_vgs_keep
                    invoke-static/range {v$onClickReg .. v$onClickReg}, $NOOP_FUNCTION0_CLASS->noop(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$onClickReg
                    """.trimIndent(),
                    ExternalLabel("piko_vgs_keep", getInstruction(nullCheckIndex)),
                )
            }

            // ---- Site 2: WkD.A02 (IGDS FollowButtonComponent) ----
            IgdsFollowButtonComponentFingerprint.method.apply {
                // The wide static call that consumes the onClick Function0
                // (435: D4K.A01, 439: 0CSf.A01 — both 9-arg range calls whose
                // 5th arg is the Function0). Found by shape: INVOKE_STATIC_RANGE
                // whose param list contains kotlin Function0.
                val d4kCallIndex =
                    instructions.withIndex()
                        .firstOrNull { (_, ins) ->
                            ins.opcode == Opcode.INVOKE_STATIC_RANGE &&
                                ins.getReference<MethodReference>()?.let { ref ->
                                    ref.parameterTypes.any { it.toString() == "Lkotlin/jvm/functions/Function0;" }
                                } == true
                        }?.index
                        ?: error("Function0-consuming static call not found in FollowButtonComponent")

                // onClick rides in v10 (5th arg of v6..v14) in both 435 and 439 —
                // derived from the call's own register range.
                val firstReg =
                    getInstruction(d4kCallIndex).registersUsed.first()
                val f0Index = run {
                    val ref = getInstruction(d4kCallIndex).getReference<MethodReference>()!!
                    ref.parameterTypes.indexOfFirst { it.toString() == "Lkotlin/jvm/functions/Function0;" }
                }
                val onClickReg = firstReg + f0Index

                // Scratch register dead at the call site on every path:
                // 435 used v3; 439's pre-call code recomputes v3 (0AsH.A0D args),
                // so use v2 (written once at goto_0, consumed by the 0DVb memo
                // key, dead through the call; no refs after it either).
                val scratchReg = 2

                // Inserted immediately before the consuming call so the memo
                // path's reassignment of the onClick register cannot resurrect
                // the real handler.
                addInstructionsWithLabels(
                    d4kCallIndex,
                    """
                    $PREF_CALL_DESCRIPTOR->readOnlyFollowButton()Z
                    move-result v$scratchReg
                    if-eqz v$scratchReg, :piko_wkd_keep
                    invoke-static {v$onClickReg}, $NOOP_FUNCTION0_CLASS->noop(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$onClickReg
                    """.trimIndent(),
                    ExternalLabel("piko_wkd_keep", getInstruction(d4kCallIndex)),
                )
            }

            // ---- Site 3: 07eP.A05 (classic view wiring chokepoint) ----
            // The home-feed inline_follow_button is NOT compose — its click
            // listener is attached in the follow-button controller's wiring
            // method at 003D.A00(listener, view) calls: the primary site
            // (listener v11 = custom field A00 or the default 07hW follow
            // action), an experimental-gated variant (0JnG), and a long-click
            // listener. Patch EVERY attach call, reading the listener register
            // from each instruction itself. Listener regs <= v15 so plain forms.
            ViewFollowButtonWiringFingerprint.method.apply {
                // Attach helper rotates between versions (435: 0es.A00, 439:
                // 003D.A00) — find it by shape: static invoke whose first param
                // is View$OnClickListener and last is View.
                val attachSites =
                    instructions.withIndex()
                        .filter { (_, ins) ->
                            ins.opcode == Opcode.INVOKE_STATIC &&
                                ins.getReference<MethodReference>()?.let { ref ->
                                    ref.parameterTypes.size == 2 &&
                                        ref.parameterTypes[0].toString() == "Landroid/view/View${'$'}OnClickListener;" &&
                                        ref.parameterTypes[1].toString() == "Landroid/view/View;" &&
                                        ref.returnType == "V"
                                } == true
                        }
                        .map { (i, ins) -> i to ins.registersUsed.first() }
                if (attachSites.isEmpty()) error("OnClickListener attach call not found in wiring method")

                attachSites.sortedByDescending { it.first }.forEach { (idx, reg) ->
                    addInstructions(
                        idx,
                        """
                        invoke-static {v$reg}, $NOOP_FUNCTION0_CLASS->noopListener(Landroid/view/View${'$'}OnClickListener;)Landroid/view/View${'$'}OnClickListener;
                        move-result-object v$reg
                        """.trimIndent(),
                    )
                }
            }

            enableSettings("readOnlyFollowButton")
        }
    }
