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
 * "Is clip remixable" gate (X.07rQ.A0H) inside the reels overflow sheet builders.
 * When it returns false, the Remix/Sequence rows are only reachable through a long
 * original-media metadata chain, which is why Sequence goes missing on some reels.
 *
 * Method/field names verified against Instagram 444.0.0.46.85.
 */
private const val REMIX_GATE_METHOD_NAME = "A0H"

// Action sheet builder (3 gate call sites).
internal object ClipsShowRemixingOptionsFingerprint : Fingerprint(
    strings =
        listOf(
            "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_shouldShowRemixingOptions",
        ),
)

// Bottom sheet builder (1 gate call site).
internal object ClipsMaybeAddRemixRowsFingerprint : Fingerprint(
    strings =
        listOf(
            "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_maybeAddRemixRows",
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
