/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.spoilerShield

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Spoiler shield hook: the media-overlay payload getter on com.instagram.feed.media.Media
 * — the only no-arg method returning MediaOverlayPayloadSchemaIntf (435/439: A0I; resolved
 * by shape so renames across versions don't matter). Every blurred-cover consumer on
 * feed/reels/grid funnels through this single getter.
 *
 * Stock body (439):
 *   iget A04 (LiveTreeMediaDict) / iget A00 (03hQ) / iget A1i (payload) / return-object v0
 *
 * Injected before the return: pass (stockPayload, this) to SpoilerShield in the extension,
 * which returns the stock payload untouched unless it is null AND the user's spoiler rules
 * match this media — in that case a fabricated EARLY_ACCESS-style payload drives
 * Instagram's own blurred cover with the match reason as the cover text.
 */
internal object MediaOverlayPayloadGetterFingerprint : Fingerprint(
    custom = { methodDef, classDef ->
        classDef.type == "Lcom/instagram/feed/media/Media;" &&
            methodDef.parameterTypes.isEmpty() &&
            methodDef.returnType == "Lcom/instagram/api/schemas/MediaOverlayPayloadSchemaIntf;"
    },
)

/**
 * Spoiler shield hook B: the cover-config builder method (439: X/0740.A00). Identified by
 * shape — returns the cover-config type (0DxY) AND opens with two adjacent gates of the
 * form `invoke-virtual Media;-><rotating>()Z / move-result / if-eqz` guarding the media
 * path. For a normal post both gates are false, so the builder never consults the media
 * payload; we OR our verdict into both gate results so matching media take the cover path.
 * (The return-type pin matters: other methods share the double-gate shape — one false
 * positive (X/01gB, product tagging) shipped in the first attempt.)
 */
internal object CoverBuilderEligibilityFingerprint : Fingerprint(
    returnType = "LX/0DxY;",
    custom = { methodDef, _ ->
        val impl = methodDef.implementation
        if (impl == null) {
            false
        } else {
            val insns = impl.instructions.toList()
            var found = false
            var i = 0
            while (!found && i + 5 < insns.size) {
                found = isMediaBooleanGate(insns, i) && isMediaBooleanGate(insns, i + 3)
                i++
            }
            found
        }
    },
)

private fun isMediaBooleanGate(insns: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>, i: Int): Boolean {
    val invoke = insns[i] as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction ?: return false
    val ref = invoke.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference ?: return false
    if (invoke.opcode != Opcode.INVOKE_VIRTUAL) return false
    if (ref.definingClass != "Lcom/instagram/feed/media/Media;") return false
    if (ref.parameterTypes.isNotEmpty() || ref.returnType != "Z") return false
    if (insns[i + 1].opcode != Opcode.MOVE_RESULT) return false
    if (insns[i + 2].opcode != Opcode.IF_EQZ) return false
    return true
}

@Suppress("unused")
val spoilerShieldPatch =
    bytecodePatch(
        name = "Spoiler shield",
        description = "Blurs fresh posts that match username/hashtag/word rules behind Instagram's own media cover, with the spoiler reason on the cover.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            MediaOverlayPayloadGetterFingerprint.method.apply {
                // The stock getter tail: iget-object vN ...; return-object vN.
                // We rewrite the return source: keep the stock iget chain, then hand the
                // loaded payload + the Media (p0) to the extension instead of returning it.
                val retIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
                require(retIndex >= 0) { "spoiler shield: no return-object in payload getter" }
                val loadInsn = getInstruction(retIndex - 1)
                require(
                    loadInsn.opcode == Opcode.IGET_OBJECT,
                ) { "spoiler shield: payload getter does not load a field before returning" }
                val payloadReg = loadInsn.registersUsed[0]

                // Insert before the original return-object: the stock return now carries
                // the extension result. OFF path: extension is a pass-through, so the
                // stock payload flows through the same registers byte-equivalently.
                addInstructions(
                    retIndex,
                    """
                    invoke-static {v$payloadReg, p0}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->getMediaOverlayPayload(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$payloadReg
                    """.trimIndent(),
                )
            }

            // Hook B: force the two eligibility gates inside the cover builder so matching
            // media take the media-payload path (where hook A supplies the cover payload).
            // Inserted right after each gate's move-result: OR in our verdict. OFF path
            // ORs in 0 = stock behavior. The Media object is the gate invoke's base reg.
            CoverBuilderEligibilityFingerprint.method.apply {
                val gateSites =
                    instructions.withIndex()
                        .filter { (index, insn) ->
                            val isGateInvoke =
                                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                                    insn.getReference<MethodReference>()?.let { ref ->
                                        ref.definingClass == "Lcom/instagram/feed/media/Media;" &&
                                            ref.parameterTypes.isEmpty() &&
                                            ref.returnType == "Z"
                                    } == true
                            val hasNextMoveResult =
                                instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT
                            isGateInvoke && hasNextMoveResult
                        }
                        .map { (index, insn) -> index to insn.registersUsed.first() }
                require(gateSites.size >= 2) {
                    "spoiler shield: expected >=2 eligibility gates in cover builder, found ${gateSites.size}"
                }

                gateSites.sortedByDescending { it.first }.forEach { (invokeIndex, baseReg) ->
                    val moveResultIndex = invokeIndex + 1
                    val resultReg = getInstruction(moveResultIndex).registersUsed[0]
                    addInstructions(
                        moveResultIndex + 1,
                        """
                        invoke-static {v$resultReg, v$baseReg}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->forceCoverEligibility(ZLjava/lang/Object;)Z
                        move-result v$resultReg
                        """.trimIndent(),
                    )
                }
            }

            enableSettings("spoilerShield")
        }
    }
