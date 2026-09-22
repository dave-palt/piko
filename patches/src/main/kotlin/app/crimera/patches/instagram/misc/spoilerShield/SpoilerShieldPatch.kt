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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Spoiler shield hook A: the media-overlay payload getter on com.instagram.feed.media.Media
 * — the only no-arg method returning MediaOverlayPayloadSchemaIntf (435/439: A0I; resolved
 * by shape so renames across versions don't matter). Every blurred-cover consumer on
 * feed/reels/grid funnels through this single getter.
 *
 * Stock body (439):
 *   iget A04 (LiveTreeMediaDict) / iget A00 (03hQ) / iget A1i (payload) / return-object v0
 *
 * Injected before the return: pass (stockPayload, this) to SpoilerShield in the extension,
 * which returns the stock payload untouched unless it is null AND the user's spoiler rules
 * match this media — in that case a fabricated payload drives Instagram's own blurred
 * cover with the match reason as the cover text.
 */
internal object MediaOverlayPayloadGetterFingerprint : Fingerprint(
    custom = { methodDef, classDef ->
        classDef.type == "Lcom/instagram/feed/media/Media;" &&
            methodDef.parameterTypes.isEmpty() &&
            methodDef.returnType == "Lcom/instagram/api/schemas/MediaOverlayPayloadSchemaIntf;"
    },
)

/**
 * Spoiler shield hook B + C: the cover-config builder (439: X/0740.A00). Identified by
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

/**
 * Spoiler shield hook D: the classic feed row controller's bind method (439: X/01Rd.A07).
 * The controller gates the whole cover block behind two boolean flags (its own A0B and
 * the row-state's A0d), both false for normal posts — the cover builder (and hooks B/C
 * inside it) never runs. Identified by shape: invokes the cover builder
 * (LX/0740;->A00, either invoke opcode) AND has 2-3 boolean iget+if-eqz gates in the
 * GATE_WINDOW instructions right before that invoke. The 2-instruction gap between the
 * two gates is stock (439); other builder callers (0GeQ, 09r2, 06EY...) lack these.
 */
internal object FeedRowCoverGateFingerprint : Fingerprint(
    custom = { methodDef, _ ->
        val impl = methodDef.implementation
        if (impl == null) {
            false
        } else {
            val insns = impl.instructions.toList()
            val builderInvokeIndex = insns.indexOfFirst {
                (it.opcode == Opcode.INVOKE_VIRTUAL ||
                    it.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                    (it as? ReferenceInstruction)?.reference.let { r ->
                        (r as? MethodReference)?.definingClass == "LX/0740;" && r.name == "A00"
                    } == true
            }
            if (builderInvokeIndex < 0) {
                false
            } else {
                val gateCount =
                    insns.withIndex()
                        .count { (index, insn) ->
                            index < builderInvokeIndex &&
                                builderInvokeIndex - index <= GATE_WINDOW &&
                                insn.opcode == Opcode.IGET_BOOLEAN &&
                                (insn as? ReferenceInstruction)?.reference.let { r ->
                                    (r as? FieldReference)?.type == "Z"
                                } == true &&
                                insns.getOrNull(index + 1)?.opcode == Opcode.IF_EQZ
                        }
                gateCount in 2..3
            }
        }
    },
)

private fun isMediaBooleanGate(insns: List<Instruction>, i: Int): Boolean {
    val invoke = insns[i] as? ReferenceInstruction ?: return false
    val ref = invoke.reference as? MethodReference ?: return false
    if (invoke.opcode != Opcode.INVOKE_VIRTUAL) return false
    if (ref.definingClass != "Lcom/instagram/feed/media/Media;") return false
    if (ref.parameterTypes.isNotEmpty() || ref.returnType != "Z") return false
    if (insns[i + 1].opcode != Opcode.MOVE_RESULT) return false
    if (insns[i + 2].opcode != Opcode.IF_EQZ) return false
    return true
}

/**
 * Spoiler shield hook E: the LIVE Litho feed row builder (439: X/00q4.A0o, classes15 —
 * the row that renders row_feed_profile_header). Its cover section is gated by the
 * row-state flag 01As.A0d (stock false for normal posts) and then a mobileconfig
 * selector 017x.A00 (true → server Bloks cover; false → local builder 0740.A00, where
 * hooks B/C already live). We force the gate and steer the selector to the local arm
 * for matching rows, so the fabricated payload drives the native blurred cover.
 * Identified by shape: reads 01As;->A0d:Z AND invokes both 017x;->A00 and 0740;->A00.
 */
internal object LiveRowCoverGateFingerprint : Fingerprint(
    // Litho section-builder shape (439: A0o(01iy)03Wk) — disambiguates from the
    // classic view binder 01Rd.A07 which shares the triple-gate shape.
    parameters = listOf("LX/01iy;"),
    returnType = "LX/03Wk;",
    custom = { methodDef, _ ->
        val impl = methodDef.implementation
        if (impl == null) {
            false
        } else {
            val insns = impl.instructions.toList()
            val readsRowFlag = insns.any {
                it.opcode == Opcode.IGET_BOOLEAN &&
                    (it as? ReferenceInstruction)?.reference.let { r ->
                        (r as? FieldReference)?.name == "A0d" && r.definingClass == "LX/01As;"
                    } == true
            }
            val invokesSelector = insns.any {
                (it.opcode == Opcode.INVOKE_STATIC ||
                    it.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    (it as? ReferenceInstruction)?.reference.let { r ->
                        (r as? MethodReference)?.definingClass == "LX/017x;" && r.name == "A00"
                    } == true
            }
            val invokesCoverBuilder = insns.any {
                (it.opcode == Opcode.INVOKE_VIRTUAL ||
                    it.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                    (it as? ReferenceInstruction)?.reference.let { r ->
                        (r as? MethodReference)?.definingClass == "LX/0740;" && r.name == "A00"
                    } == true
            }
            readsRowFlag && invokesSelector && invokesCoverBuilder
        }
    },
)

/**
 * Spoiler shield hook G: the feed caption model getter (439: MediaExtKt.A0I,
 * classes1). Static, takes Media, returns the caption model 02bL — unique shape
 * app-wide. The media cover hides the image but the caption row below still shows
 * the spoiler text; nulling the model for matching media renders the row caption-less
 * (every consumer null-checks: 04xV.A01 `if-eqz p1`).
 */
internal object CaptionModelGetterFingerprint : Fingerprint(
    // 439: A0I — MediaExtKt has TWO static (Media)02bL methods (A0H = preview model used
    // elsewhere, A0I = caption model); pin the name per-version.
    name = "A0I",
    parameters = listOf("Lcom/instagram/feed/media/Media;"),
    returnType = "LX/02bL;",
    custom = { _, classDef ->
        classDef.type == "Lcom/instagram/feed/media/MediaExtKt;"
    },
)

/** How many instructions before the builder invoke the row gates can sit (439: ~35). */
private const val GATE_WINDOW = 60

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
            // Hook A: payload getter — fabricate the cover payload for matching media.
            MediaOverlayPayloadGetterFingerprint.method.apply {
                val retIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
                require(retIndex >= 0) { "spoiler shield: no return-object in payload getter" }
                val loadInsn = getInstruction(retIndex - 1)
                require(
                    loadInsn.opcode == Opcode.IGET_OBJECT,
                ) { "spoiler shield: payload getter does not load a field before returning" }
                val payloadReg = loadInsn.registersUsed[0]

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

            // Hook C: at the cover-config tail, the stock blur ImageUrl at the 0DxY.A01
            // iput is null for normal posts and the binder paints nothing. Rewrite it
            // through the extension keyed by the payload title (fabrication stashed the
            // title-token -> thumbnail URL map entry).
            CoverBuilderEligibilityFingerprint.method.apply {
                val a01PutIndex: Int =
                    instructions.indexOfFirst {
                        it.opcode == Opcode.IPUT_OBJECT &&
                            it.getReference<FieldReference>()?.let { ref ->
                                ref.definingClass == "LX/0DxY;" && ref.name == "A01"
                            } == true
                    }
                require(a01PutIndex >= 0) { "spoiler shield: A01 iput not found in cover builder" }

                val urlReg: Int = getInstruction(a01PutIndex).registersUsed[0]

                var titleCallIndex: Int = -1
                for (i in a01PutIndex - 1 downTo 0) {
                    val insn = getInstruction(i)
                    if (insn.opcode == Opcode.INVOKE_INTERFACE ||
                        insn.opcode == Opcode.INVOKE_INTERFACE_RANGE
                    ) {
                        val ref = insn.getReference<MethodReference>()
                        if (ref?.name == "getTitle" && ref.returnType == "Ljava/lang/String;") {
                            titleCallIndex = i
                            break
                        }
                    }
                }
                require(titleCallIndex >= 0) { "spoiler shield: getTitle call not found in cover builder" }
                val titleReg: Int = getInstruction(titleCallIndex + 1).registersUsed[0]

                addInstructions(
                    a01PutIndex,
                    """
                    invoke-static {v$urlReg, v$titleReg}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->coverImage(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$urlReg
                    check-cast v$urlReg, Lcom/instagram/common/typedurl/ImageUrl;
                    """.trimIndent(),
                )
            }

            // Hook D: force the feed row controller's two cover-path entry flags. On a
            // normal post both are false and the cover block (builder + hooks B/C) is
            // skipped entirely. Pattern (439: 01Rd.A07):
            //   iget-boolean v5, v2, 01Rd;->A0B:Z / if-eqz v5, :skip
            //   iget-boolean v5, v9, 01As;->A0d:Z / if-eqz v5, :skip
            // The second gate's object register (v9 = row state) carries Media-typed
            // fields, so the extension can key the verdict on the row's media. We
            // rewrite each gate's boolean in place (Z register, single consumer).
            // ONLY the gates within the window right before the builder invoke — the
            // method holds many unrelated boolean gates.
            FeedRowCoverGateFingerprint.method.apply {
                val builderInvokeIndex =
                    instructions.indexOfFirst {
                        (it.opcode == Opcode.INVOKE_VIRTUAL ||
                            it.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                            it.getReference<MethodReference>()?.let { ref ->
                                ref.definingClass == "LX/0740;" && ref.name == "A00"
                            } == true
                    }
                require(builderInvokeIndex >= 0) {
                    "spoiler shield: cover builder invoke not found in feed row controller"
                }

                val gateSites =
                    instructions.withIndex()
                        .filter { (index, insn) ->
                            index < builderInvokeIndex &&
                                builderInvokeIndex - index <= GATE_WINDOW &&
                                insn.opcode == Opcode.IGET_BOOLEAN &&
                                insn.getReference<FieldReference>()?.type == "Z" &&
                                instructions.getOrNull(index + 1)?.opcode == Opcode.IF_EQZ
                        }
                        .map { (index, insn) ->
                            // iget-boolean vDst, vObj -> registersUsed = [dst, obj]
                            index to insn.registersUsed[1]
                        }
                require(gateSites.size in 2..3) {
                    "spoiler shield: expected 2-3 cover gates before builder invoke, found ${gateSites.size}"
                }

                // Both injections key on the ROW register (the last gate's object reg,
                // type 01As on 439 — the only object in the pair carrying Media fields;
                // the first gate's object is the controller, which has none). The row
                // register is a method parameter, live across both gates.
                val rowReg: Int = gateSites.last().second

                gateSites.sortedByDescending { it.first }.forEach { (igetIndex, _) ->
                    val flagReg = getInstruction(igetIndex).registersUsed[0]
                    addInstructions(
                        igetIndex + 1,
                        """
                        invoke-static {v$flagReg, v$rowReg}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->forceRowFlag(ZLjava/lang/Object;)Z
                        move-result v$flagReg
                        """.trimIndent(),
                    )
                }
            }

            // Hook E: the LIVE Litho feed row builder. Two injections:
            //   E1 — the 01As.A0d entry gate: OR our row verdict in (branch-skip idiom,
            //        gate register only read).
            //   E2 — the 017x.A00 selector: when our verdict says cover and stock selects
            //        the server-Bloks arm, force the local-builder arm (we have no Bloks
            //        tree; the local arm is where hooks B/C render the fabricated cover).
            LiveRowCoverGateFingerprint.method.apply {
                // E1: iget-boolean v0, v2, 01As;->A0d:Z / if-eqz v0, :cond_666
                val gateIndex = instructions.indexOfFirst {
                    it.opcode == Opcode.IGET_BOOLEAN &&
                        it.getReference<FieldReference>()?.let { ref ->
                            ref.name == "A0d" && ref.definingClass == "LX/01As;"
                        } == true
                }
                require(gateIndex >= 0) { "spoiler shield: 01As.A0d gate not found in live row builder" }
                val flagReg = getInstruction(gateIndex).registersUsed[0]
                // gate object register v2 = row state (01As) — carries the Media fields
                val rowReg = getInstruction(gateIndex).registersUsed[1]

                addInstructions(
                    gateIndex + 1,
                    """
                    invoke-static {v$flagReg, v$rowReg}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->forceRowFlag(ZLjava/lang/Object;)Z
                    move-result v$flagReg
                    """.trimIndent(),
                )

                // E2: invoke-static 017x.A00(session) / move-result v0 / if-eqz v0, :cond_6b3
                // (if-eqz selector==false → local builder arm). When our verdict is true
                // we need selector FALSE, i.e. force the if-eqz TAKEN path.
                val selIndex = instructions.indexOfFirst {
                    (it.opcode == Opcode.INVOKE_STATIC ||
                        it.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                        it.getReference<MethodReference>()?.let { ref ->
                            ref.definingClass == "LX/017x;" && ref.name == "A00"
                        } == true
                }
                require(selIndex >= 0) { "spoiler shield: 017x.A00 selector not found in live row builder" }
                val selResReg = getInstruction(selIndex + 1).registersUsed[0]
                addInstructions(
                    selIndex + 2,
                    """
                    invoke-static {v$selResReg, v$rowReg}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->steerSelector(ZLjava/lang/Object;)Z
                    move-result v$selResReg
                    """.trimIndent(),
                )
            }

            // Hook F: rewrite the fabricated token into the reason text at the cover
            // config's title iput (0DxY.A05 <- getTitle()).
            CoverBuilderEligibilityFingerprint.method.apply {
                val titlePutIndex = instructions.indexOfFirst {
                    it.opcode == Opcode.IPUT_OBJECT &&
                        it.getReference<FieldReference>()?.let { ref ->
                            ref.definingClass == "LX/0DxY;" && ref.name == "A05"
                        } == true
                }
                require(titlePutIndex >= 0) { "spoiler shield: A05 title iput not found in cover builder" }
                val titleReg = getInstruction(titlePutIndex).registersUsed[0]

                addInstructions(
                    titlePutIndex,
                    """
                    invoke-static {v$titleReg}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->coverTitle(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$titleReg
                    check-cast v$titleReg, Ljava/lang/String;
                    """.trimIndent(),
                )
            }

            // Hook G: suppress the caption model for matching media (the cover hides the
            // media; without this the caption row still shows the spoiler text verbatim).
            // Inject before EVERY return-object; each site reads the value straight out of
            // its own return register (439: two sites, v1 and v4).
            CaptionModelGetterFingerprint.method.apply {
                val impl = implementation
                requireNotNull(impl) { "spoiler shield: caption getter has no implementation" }
                val paramReg = impl.registerCount - 1 // p0 = the Media
                val retSites =
                    instructions.withIndex()
                        .filter { (_, insn) -> insn.opcode == Opcode.RETURN_OBJECT }
                        .map { (index, insn) -> index to insn.registersUsed.first() }
                require(retSites.isNotEmpty()) { "spoiler shield: no return-object in caption getter" }

                retSites.sortedByDescending { it.first }.forEach { (retIndex, resReg) ->
                    addInstructions(
                        retIndex,
                        """
                        invoke-static {v$resReg, v$paramReg}, $PATCHES_DESCRIPTOR/spoiler/SpoilerShield;->suppressCaption(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$resReg
                        """.trimIndent(),
                    )
                }
            }

            enableSettings("spoilerShield")
        }
    }
