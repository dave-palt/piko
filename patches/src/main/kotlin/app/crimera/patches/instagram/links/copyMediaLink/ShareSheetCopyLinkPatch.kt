/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.links.copyMediaLink

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.SHARE_SHEET_COPY_LINK_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal object DirectShareSheetOnViewCreatedFingerprint : Fingerprint(
    definingClass = "Linstagram/features/direct/fragment/sharesheet/DirectShareSheetFragment;",
    name = "onViewCreated",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
    returnType = "V",
)

/**
 * Instagram's share funnel resolves the CURRENT carousel slide before the
 * share sheet opens: a static `(request, Media, carouselIndex)V` method calls
 * `MediaExtKt.getMediaAt(media, index)` and feeds that child into the share
 * request. Matched by shape (static, 3 params, [1]=Media, [2]=I, body calls
 * MediaExtKt with the `(Media;I)Media` shape); the shopping variant twin
 * (body touches ProductTag) is excluded.
 */
internal object ShareCarouselIndexFingerprint : Fingerprint(
    returnType = "V",
    custom = { methodDef, _ ->
        val params = methodDef.parameters
        AccessFlags.STATIC.isSet(methodDef.accessFlags)
                && params.size == 3
                && params[1].type == "Lcom/instagram/feed/media/Media;"
                && params[2].type == "I"
                && methodDef.implementation?.instructions?.any { inst ->
            val ref = (inst as? ReferenceInstruction)?.reference?.toString()
            ref != null && ref.startsWith("Lcom/instagram/feed/media/MediaExtKt;->")
                    && ref.contains("(Lcom/instagram/feed/media/Media;I)Lcom/instagram/feed/media/Media;")
        } == true
                && methodDef.implementation?.instructions?.none { inst ->
            val ref = (inst as? ReferenceInstruction)?.reference?.toString()
            ref != null && ref.startsWith("Lcom/instagram/model/shopping/ProductTag;")
        } == true
    },
)

@Suppress("unused")
val shareSheetCopyLinkPatch =
    bytecodePatch(
        name = "Copy link row on share sheet",
        description =
            "Adds copy buttons to the bottom of the direct share sheet: two side-by-side buttons (copy current / copy all) for gallery posts, a single copy-link button otherwise. \"Copy current\" resolves the slide on screen when the sheet was opened",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            DirectShareSheetOnViewCreatedFingerprint.method.apply {
                val superCallIndex = indexOfFirstInstruction(Opcode.INVOKE_SUPER)
                if (superCallIndex < 0) {
                    throw PatchException("invoke-super not found in DirectShareSheetFragment.onViewCreated")
                }

                // invoke-super {this, view, bundle}
                val superRegisters = instructions[superCallIndex].registersUsed
                val thisRegister = superRegisters[0]
                val viewRegister = superRegisters[1]

                addInstructions(
                    superCallIndex + 1,
                    """
                    invoke-static {v$thisRegister, v$viewRegister}, $SHARE_SHEET_COPY_LINK_CLASS->addCopyLinkRow(Ljava/lang/Object;Landroid/view/View;)V
                    """.trimIndent(),
                )

                enableSettings("shareSheetCopyLink")
            }

            // Record which carousel slide was on screen when the share sheet
            // was opened, so the "copy current media link" row copies THAT
            // slide. Entry hook: (request, parentMedia, carouselIndex) ->
            // ShareSheetCopyLink.onShareRequested(media, index). The injected
            // call clobbers no registers and returns void, so the stock
            // funnel runs byte-identically.
            ShareCarouselIndexFingerprint.method.apply {
                addInstructions(
                    0,
                    """
                    invoke-static {p1, p2}, $SHARE_SHEET_COPY_LINK_CLASS->onShareRequested(Ljava/lang/Object;I)V
                    """.trimIndent(),
                )
            }
        }
    }
