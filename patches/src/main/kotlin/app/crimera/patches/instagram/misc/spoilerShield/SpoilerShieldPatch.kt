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
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

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

            enableSettings("spoilerShield")
        }
    }
