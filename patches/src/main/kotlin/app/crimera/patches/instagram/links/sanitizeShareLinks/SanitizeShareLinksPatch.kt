/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.links.sanitizeShareLinks

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.LINKS_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.LOCAL_SHARE_LINK_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val sanitizeShareLinksPatch =
    bytecodePatch(
        name = "Sanitize share links",
    ) {

        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {

            val EXTENSION_METHOD =
                """
                invoke-static/range { v%s .. v%s }, ${LINKS_DESCRIPTOR}->sanitizeUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v%s
                """.trimIndent()

            val jsonParserFingerprints =
                listOf(
                    PermalinkResponseJsonParserFingerprint,
                    ProfileUrlResponseJsonParserFingerprint,
                )

            jsonParserFingerprints.forEach { fingerprint ->
                val strIndex = fingerprint.stringMatches[0].index
                fingerprint.method.apply {
                    val strIPutObjectIndex = indexOfFirstInstruction(strIndex, Opcode.IPUT_OBJECT)
                    val urlRegister = instructions[strIPutObjectIndex].registersUsed[0]

                    addInstructions(strIPutObjectIndex, EXTENSION_METHOD.format(urlRegister, urlRegister, urlRegister))
                }
            }

            val responseImplFingerprint =
                listOf(
                    StoryUrlResponseImplFingerprint,
                    LiveUrlResponseImplFingerprint,
                )

            responseImplFingerprint.forEach { fingerprint ->
                fingerprint.method.apply {
                    val returnObjectInst = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                    val index = returnObjectInst.location.index
                    val urlRegister = returnObjectInst.registersUsed[0]

                    addInstructions(index, EXTENSION_METHOD.format(urlRegister, urlRegister, urlRegister))
                }
            }

            // Short-circuit the share-URL network round-trips: the server
            // response only wraps a URL derivable from on-device data
            // (shortcode / username) plus an igsh tracking token that
            // sanitizeUrl strips anyway. When the toggle is on, the
            // request-builder methods return a locally-completed X/2Hd task;
            // the extension helper returns null on any failure, which falls
            // through to the original network request.

            // Local share-link short-circuit is DORMANT pending on-device
            // debugging (fork30 regression: icon didn't swap + delayed crash).
            // Gated behind the piko_debug_kill_switch pref so the code stays
            // reachable for a debug build without rebuilding the bundle.
            //
            // X/MFy.A00(UserSession, Media, 6xB, Integer, String)LX/2Hd — media permalink.
            // Pass the raw Media (p1); the extension resolves shortcode/type
            // via MediaData. Carousel index (6xB.A07) is read with a null guard.
            MediaPermalinkRequestFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, Lapp/morphe/extension/instagram/utils/Pref;->pikoDebugKillSwitch()Z
                    move-result v2
                    if-eqz v2, :piko_mfy_keep
                    const/4 v0, 0x0
                    if-eqz p2, :piko_mfy_call
                    iget v1, p2, LX/6xB;->A07:I
                    move v0, v1
                    :piko_mfy_call
                    invoke-static {p1, v0}, $LOCAL_SHARE_LINK_CLASS->mediaPermalink(Ljava/lang/Object;I)Ljava/lang/Object;
                    move-result-object v2
                    if-eqz v2, :piko_mfy_keep
                    check-cast v2, LX/2Hd;
                    return-object v2
                    """.trimIndent(),
                    ExternalLabel("piko_mfy_keep", getInstruction(0)),
                )
            }

            // X/MFy.A03(UserSession, Integer, String username, String mediaId, String)LX/2Hd — story item.
            // Username/mediaId are the raw params; the repo method itself
            // trims mediaId at '_' afterwards (the extension mirrors that).
            StoryItemUrlRequestFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, Lapp/morphe/extension/instagram/utils/Pref;->pikoDebugKillSwitch()Z
                    move-result v0
                    if-eqz v0, :piko_mfy3_keep
                    invoke-static {p3, p4}, $LOCAL_SHARE_LINK_CLASS->storyItemUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :piko_mfy3_keep
                    check-cast v0, LX/2Hd;
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("piko_mfy3_keep", getInstruction(0)),
                )
            }

            // X/KFb.A00(UserSession, Integer, String username, String)LX/2Hd — profile.
            ProfileUrlRequestFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, Lapp/morphe/extension/instagram/utils/Pref;->pikoDebugKillSwitch()Z
                    move-result v0
                    if-eqz v0, :piko_kfb_keep
                    invoke-static {p2}, $LOCAL_SHARE_LINK_CLASS->profileUrl(Ljava/lang/String;)Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :piko_kfb_keep
                    check-cast v0, LX/2Hd;
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("piko_kfb_keep", getInstruction(0)),
                )
            }

            enableSettings("sanitizeShareLinks")
        }
    }
