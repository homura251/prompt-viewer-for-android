package com.promptreader.android.parser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal port of sd_prompt_reader/format/comfyui.py.
 *
 * This is not a perfect clone, but it follows the same strategy:
 * - find end nodes (SaveImage / KSampler variants)
 * - traverse upstream following linked inputs
 * - extract positive/negative text via CLIPTextEncode nodes
 */
object ComfyUiParser {
    private val KSAMPLER_TYPES = setOf("KSampler", "KSamplerAdvanced", "KSampler (Efficient)")
    private val SAVE_IMAGE_TYPES = setOf("SaveImage", "Image Save", "SDPromptSaver")
    private val CLIP_TEXT_TYPES = setOf("CLIPTextEncode", "CLIPTextEncodeSDXL", "CLIPTextEncodeSDXLRefiner")
    private val CHECKPOINT_FILE_REGEX = Regex(""".*\.(safetensors|ckpt|pt)$""", RegexOption.IGNORE_CASE)

    data class Result(
        val positive: String,
        val negative: String,
        val setting: String,
        val raw: String,
        val settingEntries: List<SettingEntry> = emptyList(),
        val settingDetail: String = "",
        val tool: String = "ComfyUI",
    )

    fun parse(promptJsonText: String, workflowText: String? = null, width: Int? = null, height: Int? = null): Result {
        val promptObj = JSONObject(promptJsonText)

        val endNodeIds = promptObj.keys().asSequence()
            .filter { id ->
                val node = promptObj.optJSONObject(id) ?: return@filter false
                val classType = node.optString("class_type", "")
                SAVE_IMAGE_TYPES.contains(classType) || KSAMPLER_TYPES.contains(classType)
            }
            .toList()

        var positive = ""
        var negative = ""
        var setting = ""

        // Pick the traversal that visits the most nodes.
        var bestVisited = emptySet<String>()
        var bestCtx: TraverseContext? = null
        for (endId in endNodeIds) {
            val ctx = TraverseContext(promptObj)
            ctx.traverse(endId)
            if (ctx.visited.size > bestVisited.size) {
                bestVisited = ctx.visited
                bestCtx = ctx
            }
        }

        val ctx = bestCtx ?: TraverseContext(promptObj)
        if (bestCtx != null) {
            positive = ctx.positive
            negative = ctx.negative
        }
        val modelName = extractModelName(promptObj, bestVisited, ctx.flow, workflowText)
        val settingEntries = buildSettingEntries(ctx.flow, width, height, modelName)
        setting = buildSettingFromEntries(settingEntries)
        val settingDetail = buildSettingDetail(ctx.flow, width, height, modelName)

        val rawParts = mutableListOf<String>()
        if (positive.isNotBlank()) rawParts += positive.trim()
        if (negative.isNotBlank()) rawParts += negative.trim()
        rawParts += promptObj.toString()
        if (!workflowText.isNullOrBlank()) rawParts += workflowText

        return Result(
            positive = positive.trim(),
            negative = negative.trim(),
            setting = setting,
            raw = rawParts.joinToString("\n"),
            settingEntries = settingEntries,
            settingDetail = settingDetail,
        )
    }

    private fun buildSettingEntries(flow: Map<String, Any?>, width: Int?, height: Int?, modelName: String?): List<SettingEntry> {
        val steps = normalizeString(flow["steps"])
        val sampler = normalizeString(flow["sampler_name"])
        val cfg = normalizeString(flow["cfg"])
        val seed = normalizeString(flow["seed"] ?: flow["noise_seed"])
        val model = normalizeString(modelName ?: flow["ckpt_name"])

        val entries = ArrayList<SettingEntry>()
        if (model != null) entries += SettingEntry("Model", model)
        if (steps != null) entries += SettingEntry("Steps", steps)
        if (sampler != null) entries += SettingEntry("Sampler", sampler)
        if (cfg != null) entries += SettingEntry("CFG scale", cfg)
        if (seed != null) entries += SettingEntry("Seed", seed)
        if (width != null && height != null) entries += SettingEntry("Size", "${width}x${height}")
        return entries
    }

    private fun buildSettingFromEntries(entries: List<SettingEntry>): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }

    private fun buildSettingDetail(
        flow: Map<String, Any?>,
        width: Int?,
        height: Int?,
        modelName: String?,
    ): String {
        val detail = JSONObject()

        val model = normalizeString(modelName ?: flow["ckpt_name"])
        val steps = normalizeString(flow["steps"])
        val sampler = normalizeString(flow["sampler_name"])
        val cfg = normalizeString(flow["cfg"])
        val seed = normalizeString(flow["seed"] ?: flow["noise_seed"])

        if (model != null) detail.put("Model", model)
        if (steps != null) detail.put("Steps", steps)
        if (sampler != null) detail.put("Sampler", sampler)
        if (cfg != null) detail.put("CFG scale", cfg)
        if (seed != null) detail.put("Seed", seed)
        if (width != null && height != null) detail.put("Size", "${width}x${height}")

        val flowJson = JSONObject()
        for ((k, v) in flow) {
            if (v == null) continue
            when (k) {
                "steps", "sampler_name", "cfg", "seed", "noise_seed", "ckpt_name", "positive", "negative" -> Unit
                else -> flowJson.put(k, v)
            }
        }
        if (flowJson.length() > 0) detail.put("flow", flowJson)

        return detail.toString(2)
    }

    private fun normalizeString(v: Any?): String? {
        if (v == null) return null
        val s = v.toString().trim().trim('"', '\'')
        return s.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun extractModelName(
        prompt: JSONObject,
        visited: Set<String>,
        flow: Map<String, Any?>,
        workflowText: String?,
    ): String? {
        normalizeString(flow["ckpt_name"])?.let { return it }

        fun extractFromNode(nodeId: String): String? {
            val node = prompt.optJSONObject(nodeId) ?: return null
            val inputs = node.optJSONObject("inputs") ?: return null
            val direct = normalizeString(inputs.opt("ckpt_name"))
                ?: normalizeString(inputs.opt("checkpoint_name"))
                ?: normalizeString(inputs.opt("checkpoint"))
                ?: normalizeString(inputs.opt("model_name"))
            return direct
        }

        // Prefer checkpoint-related nodes within the chosen traversal.
        val checkpointVisited = visited.filter { id ->
            val node = prompt.optJSONObject(id) ?: return@filter false
            node.optString("class_type", "").contains("checkpoint", ignoreCase = true)
        }
        for (id in checkpointVisited) extractFromNode(id)?.let { return it }
        for (id in visited) extractFromNode(id)?.let { return it }

        // Fallback: scan the whole prompt graph.
        val it = prompt.keys()
        while (it.hasNext()) {
            val id = it.next()
            extractFromNode(id)?.let { model -> return model }
        }

        // Last resort: try to find a checkpoint-like filename in the workflow JSON.
        workflowText ?: return null
        val workflowRoot: Any = runCatching { JSONObject(workflowText) }.getOrNull()
            ?: runCatching { JSONArray(workflowText) }.getOrNull()
            ?: return null
        return findCheckpointLikeString(workflowRoot)?.let { normalizeString(it) }
    }

    private fun findCheckpointLikeString(any: Any?): String? {
        return when (any) {
            is JSONObject -> {
                val it = any.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val found = findCheckpointLikeString(any.opt(k))
                    if (found != null) return found
                }
                null
            }

            is JSONArray -> {
                for (i in 0 until any.length()) {
                    val found = findCheckpointLikeString(any.opt(i))
                    if (found != null) return found
                }
                null
            }

            is String -> any.takeIf { CHECKPOINT_FILE_REGEX.matches(it.trim()) }
            else -> null
        }
    }

    private class TraverseContext(private val prompt: JSONObject) {
        val visited = LinkedHashSet<String>()
        val flow = LinkedHashMap<String, Any?>()

        var positive: String = ""
        var negative: String = ""

        fun traverse(nodeId: String) {
            if (visited.contains(nodeId)) return
            visited.add(nodeId)

            val node = prompt.optJSONObject(nodeId) ?: return
            val classType = node.optString("class_type", "")
            val inputs = node.optJSONObject("inputs") ?: JSONObject()

            val ckpt = inputs.optString("ckpt_name", "").takeIf { it.isNotBlank() }
            if (ckpt != null) flow["ckpt_name"] = ckpt

            when {
                SAVE_IMAGE_TYPES.contains(classType) -> {
                    // images: [<nodeId>, index]
                    val images = inputs.optJSONArray("images")
                    val upstream = firstLink(images)
                    if (upstream != null) traverse(upstream)
                }

                KSAMPLER_TYPES.contains(classType) -> {
                    // Collect scalar params + follow upstream links.
                    for (key in inputs.keys()) {
                        val v = inputs.opt(key)
                        when (key) {
                            "positive" -> {
                                val upstream = firstLink(v)
                                if (upstream != null) {
                                    val text = traverseToText(upstream)
                                    if (text != null) positive = text
                                }
                            }

                            "negative" -> {
                                val upstream = firstLink(v)
                                if (upstream != null) {
                                    val text = traverseToText(upstream)
                                    if (text != null) negative = text
                                }
                            }

                            else -> {
                                val upstream = firstLink(v)
                                if (upstream != null) {
                                    // Follow for dict params like model loaders.
                                    traverse(upstream)
                                } else {
                                    flow[key] = v
                                }
                            }
                        }
                    }

                    // Some params are nested upstream outputs (e.g. model loader returns ckpt_name).
                    // Additionally, try to pull ckpt_name through model chains (CheckpointLoader -> LoRA -> ...).
                    val modelUpstream = firstLink(inputs.opt("model"))
                    if (modelUpstream != null) {
                        val ckptName = findCkptNameInModelChain(modelUpstream)
                        if (!ckptName.isNullOrBlank()) flow["ckpt_name"] = ckptName
                    }
                }

                CLIP_TEXT_TYPES.contains(classType) -> {
                    // CLIPTextEncode: text can be string or link
                    val textVal = inputs.opt("text") ?: inputs.opt("text_g") ?: inputs.opt("text_l")
                    val upstream = firstLink(textVal)
                    if (upstream != null) {
                        val text = traverseToText(upstream)
                        if (text != null) {
                            // Caller decides if it's positive/negative.
                        }
                    }
                }

                else -> {
                    // Generic pass-through: try to follow the first link-like input.
                    for (key in inputs.keys()) {
                        val upstream = firstLink(inputs.opt(key))
                        if (upstream != null) {
                            traverse(upstream)
                            break
                        }
                    }
                }
            }
        }

        private fun traverseToText(nodeId: String): String? {
            traverse(nodeId)
            val node = prompt.optJSONObject(nodeId) ?: return null
            val classType = node.optString("class_type", "")
            val inputs = node.optJSONObject("inputs") ?: JSONObject()

            return when (classType) {
                "CLIPTextEncode" -> {
                    val text = inputs.opt("text")
                    when (text) {
                        is String -> text
                        else -> {
                            val upstream = firstLink(text)
                            if (upstream != null) traverseToText(upstream) else null
                        }
                    }
                }

                else -> {
                    // Many custom nodes output a string into CLIPTextEncode via a link; follow heuristically.
                    // If current node has a string-y input named positive/text/etc, use it.
                    val candidates = listOf("text", "positive", "prompt", "string")
                    for (k in candidates) {
                        val v = inputs.opt(k)
                        if (v is String) return v
                    }
                    // Otherwise follow any link.
                    for (k in inputs.keys()) {
                        val upstream = firstLink(inputs.opt(k))
                        if (upstream != null) return traverseToText(upstream)
                    }
                    null
                }
            }
        }

        private fun firstLink(value: Any?): String? {
            return when (value) {
                is JSONArray -> {
                    // ComfyUI link: ["nodeId", outputIndex]
                    if (value.length() > 0) value.optString(0).takeIf { it.isNotBlank() } else null
                }
                is List<*> -> {
                    value.firstOrNull()?.toString()
                }
                else -> null
            }
        }

        private fun findCkptNameInModelChain(nodeId: String, depth: Int = 0): String? {
            if (depth > 30) return null
            val node = prompt.optJSONObject(nodeId) ?: return null
            val inputs = node.optJSONObject("inputs") ?: JSONObject()

            val ckpt = inputs.optString("ckpt_name", "").takeIf { it.isNotBlank() }
            if (ckpt != null) return ckpt

            val next = firstLink(inputs.opt("model"))
                ?: firstLink(inputs.opt("checkpoint"))
                ?: firstLink(inputs.opt("base_model"))
            return if (next != null) findCkptNameInModelChain(next, depth + 1) else null
        }
    }
}
