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
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

// Post header username row (barcelona, feed + reels). The invoke method
// builds the K4g header state; its A05 boolean gates whether the post
// timestamp is rendered beside the username. Stock rendering is inherited:
// relative ("5h") for recent posts, absolute date otherwise.
internal object PostHeaderUsernameFlowRowFingerprint : Fingerprint(
    strings = listOf("feed_post_header"),
    returnType = "Ljava/lang/Object;",
)

@Suppress("unused")
val postTimestampPatch =
    bytecodePatch(
        name = "Show post timestamp",
        description = "Shows the post date/time beside the username in post and reel headers.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
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
                enableSettings("showPostTimestamp")
            }
        }
    }
