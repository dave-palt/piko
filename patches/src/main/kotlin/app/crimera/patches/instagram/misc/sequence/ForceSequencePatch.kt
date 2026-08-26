/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.sequence

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * "Is clip remixable/sequenceable" gate (X.07rQ.A0I) inside the reels overflow
 * sheet builders. This gate guards the CLIPS_MEDIA_REMIX and CLIPS_MEDIA_SEQUENCE
 * rows; when it returns false, Sequence goes missing on non-remixable clips.
 *
 * Note: X.07rQ.A0H is a DIFFERENT gate (reuse/template settings, own reels only)
 * and must not be forced.
 *
 * Method/field names and fingerprint strings verified against Instagram
 * 435.0.0.37.76 (arm64-v8a) and cross-checked on 444.0.0.46.85.
 */
private const val REMIX_GATE_METHOD_NAME = "A0I"

// Action sheet builder. "remix_prefetch" is logged only by the action-sheet
// path of ClipsOrganicMediaItemViewMoreOptionsController (unique app-wide).
internal object ClipsShowRemixingOptionsFingerprint : Fingerprint(
    strings =
        listOf(
            "remix_prefetch",
        ),
)

// Bottom sheet builder. "simplified_overflow_menu" appears only in the
// reduced-options bottom-sheet path (unique app-wide).
internal object ClipsMaybeAddRemixRowsFingerprint : Fingerprint(
    strings =
        listOf(
            "simplified_overflow_menu",
        ),
)

@Suppress("unused")
val forceSequencePatch =
    bytecodePatch(
        name = "Always show Sequence option",
        description =
            "Shows the Sequence option in the reels overflow menu even when Instagram hides it for non-remixable clips",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            var patchedGates = 0

            listOf(ClipsShowRemixingOptionsFingerprint, ClipsMaybeAddRemixRowsFingerprint).forEach { fingerprint ->
                fingerprint.method.apply {
                    val gateIndices =
                        instructions.withIndex()
                            .filter { (_, instruction) ->
                                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                                instruction.opcode == Opcode.INVOKE_STATIC &&
                                    reference != null &&
                                    reference.name == REMIX_GATE_METHOD_NAME &&
                                    reference.returnType == "Z" &&
                                    reference.parameterTypes.size == 2 &&
                                    reference.parameterTypes[1] == USER_SESSION_CLASS
                            }
                            .map { it.index }

                    if (gateIndices.isEmpty()) {
                        throw PatchException("Remix gate call not found for $fingerprint")
                    }

                    // Patch from the last call site backwards so earlier indices stay valid.
                    gateIndices.sortedDescending().forEach { index ->
                        val moveResultIndex = index + 1
                        if (instructions[moveResultIndex].opcode != Opcode.MOVE_RESULT) {
                            throw PatchException("Gate call is not followed by move-result at index $index")
                        }

                        val gateRegister = instructions[moveResultIndex].registersUsed[0]
                        val freeRegister = findFreeRegister(moveResultIndex)
                        val label = "piko_force_seq_${fingerprint.javaClass.simpleName}_$index"

                        // When the preference is enabled, force the gate result to true.
                        // Instagram still guards ineligible clips with its own toast on tap.
                        addInstructionsWithLabels(
                            index + 2,
                            """
                            $PREF_CALL_DESCRIPTOR->forceSequence()Z
                            move-result v$freeRegister
                            if-eqz v$freeRegister, :$label
                            const/4 v$gateRegister, 0x1
                            """.trimIndent(),
                            ExternalLabel(label, getInstruction(index + 2)),
                        )
                        patchedGates++
                    }
                }
            }

            if (patchedGates == 0) {
                throw PatchException("No remix gate calls were patched")
            }

            enableSettings("forceSequence")
        }
    }
