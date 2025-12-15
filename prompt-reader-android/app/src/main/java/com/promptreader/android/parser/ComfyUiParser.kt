package com.promptreader.android.parser

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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

    fun parseWorkflow(workflowText: String): Result {
        val normalized = normalizeWorkflowText(workflowText)
        val workflow = (runCatching { JSONTokener(normalized).nextValue() }.getOrNull() as? JSONObject) ?: JSONObject()
        val nodes = workflow.optJSONArray("nodes") ?: JSONArray()

        val extracted = extractFromWorkflowObject(workflow)
        val model = extractModelFromWorkflowNodes(nodes)
        val entries = buildWorkflowSettingEntries(extracted.settingText, model)
        val setting = buildSettingFromEntries(entries)
        val detail = buildWorkflowSettingDetail(entries, workflow)

        val rawParts = mutableListOf<String>()
        if (extracted.positive.isNotBlank()) rawParts += extracted.positive
        if (extracted.negative.isNotBlank()) rawParts += extracted.negative
        rawParts += normalized.trim()

        return Result(
            positive = extracted.positive,
            negative = extracted.negative,
            setting = setting,
            raw = rawParts.joinToString("\n"),
            settingEntries = entries,
            settingDetail = detail,
            tool = "ComfyUI",
        )
    }

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

        val workflowFallback = if (!workflowText.isNullOrBlank()) extractFromWorkflow(workflowText) else null
        if (workflowFallback != null && (positive.isBlank() || negative.isBlank())) {
            if (positive.isBlank() && workflowFallback.positive.isNotBlank()) positive = workflowFallback.positive
            if (negative.isBlank() && workflowFallback.negative.isNotBlank()) negative = workflowFallback.negative
        }
        val modelName = extractModelName(promptObj, bestVisited, ctx.flow, workflowText)
        var settingEntries = buildSettingEntries(ctx.flow, width, height, modelName)
        if (workflowFallback != null) {
            val workflowEntries = buildWorkflowSettingEntries(
                settingText = workflowFallback.settingText,
                model = workflowFallback.modelFromWorkflow ?: modelName,
            )
            settingEntries = mergeSettingEntries(settingEntries, workflowEntries)
        }
        setting = buildSettingFromEntries(settingEntries)
        val settingDetail = buildSettingDetail(ctx.flow, settingEntries)

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
        entries: List<SettingEntry>,
    ): String {
        val detail = JSONObject()
        for (e in entries) detail.put(e.key, e.value)

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

    private data class WorkflowExtract(
        val positive: String,
        val negative: String,
        val settingText: String,
        val modelFromWorkflow: String?,
    )

    private fun extractFromWorkflow(workflowText: String): WorkflowExtract {
        val normalized = normalizeWorkflowText(workflowText)
        val workflow = runCatching { JSONTokener(normalized).nextValue() }.getOrNull() as? JSONObject
            ?: return WorkflowExtract("", "", "", null)
        val extracted = extractFromWorkflowObject(workflow)
        val nodes = workflow.optJSONArray("nodes")
        val model = if (nodes != null) extractModelFromWorkflowNodes(nodes) else null
        return extracted.copy(modelFromWorkflow = model ?: extracted.modelFromWorkflow)
    }

    private fun extractFromWorkflowObject(workflow: JSONObject): WorkflowExtract {
        val nodes = workflow.optJSONArray("nodes") ?: return WorkflowExtract("", "", "", null)

        // Prefer SDPromptReader (comfyui-prompt-reader-node), which already aggregates POSITIVE/NEGATIVE/SETTINGS.
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val type = node.optString("type", "")
            if (type != "SDPromptReader") continue
            val widgets = node.optJSONArray("widgets_values") ?: continue
            val positive = widgets.optString(3, "").trim()
            val negative = widgets.optString(4, "").trim()
            val settingText = widgets.optString(5, "").trim()
            return WorkflowExtract(positive, negative, settingText, null)
        }

        // Next: try to traverse the workflow graph (nodes + links) and find CLIP text nodes connected to a sampler.
        val links = workflow.optJSONArray("links") ?: JSONArray()
        val graphExtract = extractFromWorkflowGraph(nodes, links)
        if (graphExtract.positive.isNotBlank() || graphExtract.negative.isNotBlank()) return graphExtract

        // Fallback: best-effort scan for CLIPTextEncode nodes even without links.
        val scanned = extractFromWorkflowTextNodes(nodes)
        return scanned
    }

    private data class WorkflowLink(
        val id: Int,
        val fromNodeId: String,
        val toNodeId: String,
    )

    private class WorkflowTraverseContext(
        private val nodesById: Map<String, JSONObject>,
        private val linkById: Map<Int, WorkflowLink>,
    ) {
        val visited = LinkedHashSet<String>()
        var positive: String = ""
        var negative: String = ""

        fun traverse(nodeId: String) {
            if (!visited.add(nodeId)) return
            val node = nodesById[nodeId] ?: return

            val type = node.optString("type", "")

            when {
                // Follow image chain from SaveImage-like end nodes.
                SAVE_IMAGE_TYPES.contains(type) -> {
                    findUpstreamByInputName(nodeId, "images")?.let(::traverse)
                    findUpstreamByInputName(nodeId, "image")?.let(::traverse)
                }

                // KSampler-like nodes contain the conditioning links we need.
                KSAMPLER_TYPES.contains(type) || hasInputs(node, setOf("positive", "negative")) -> {
                    findUpstreamByInputName(nodeId, "positive")?.let { upstream ->
                        val text = traverseToText(upstream)
                        if (!text.isNullOrBlank()) positive = text
                    }
                    findUpstreamByInputName(nodeId, "negative")?.let { upstream ->
                        val text = traverseToText(upstream)
                        if (!text.isNullOrBlank()) negative = text
                    }

                    // Also traverse upstream for model/settings.
                    findUpstreamByInputName(nodeId, "model")?.let(::traverse)
                    findUpstreamByInputName(nodeId, "samples")?.let(::traverse)
                    findUpstreamByInputName(nodeId, "latent_image")?.let(::traverse)
                }

                else -> {
                    // Generic pass-through: follow common upstream links first, then any linked input.
                    val preferred = listOf(
                        "conditioning",
                        "conditioning_1",
                        "conditioning_2",
                        "clip",
                        "model",
                        "samples",
                        "image",
                        "images",
                        "latent",
                        "latent_image",
                    )
                    for (name in preferred) {
                        findUpstreamByInputName(nodeId, name)?.let {
                            traverse(it)
                            return
                        }
                    }

                    val inputs = node.optJSONArray("inputs") ?: return
                    for (i in 0 until inputs.length()) {
                        val input = inputs.optJSONObject(i) ?: continue
                        val linkId = input.optInt("link", -1)
                        if (linkId < 0) continue
                        val upstream = linkById[linkId]?.fromNodeId ?: continue
                        traverse(upstream)
                        return
                    }
                }
            }
        }

        private fun hasInputs(node: JSONObject, names: Set<String>): Boolean {
            val inputs = node.optJSONArray("inputs") ?: return false
            val found = HashSet<String>()
            for (i in 0 until inputs.length()) {
                val input = inputs.optJSONObject(i) ?: continue
                val name = input.optString("name", "")
                if (name.isNotBlank()) found += name.lowercase()
            }
            return names.all { it.lowercase() in found }
        }

        private fun findUpstreamByInputName(nodeId: String, inputName: String): String? {
            val node = nodesById[nodeId] ?: return null
            val inputs = node.optJSONArray("inputs") ?: return null
            for (i in 0 until inputs.length()) {
                val input = inputs.optJSONObject(i) ?: continue
                if (!inputName.equals(input.optString("name", ""), ignoreCase = true)) continue
                val linkId = input.optInt("link", -1)
                if (linkId < 0) return null
                return linkById[linkId]?.fromNodeId
            }
            return null
        }

        private fun traverseToText(nodeId: String, depth: Int = 0): String? {
            if (depth > 50) return null
            traverse(nodeId)
            val node = nodesById[nodeId] ?: return null

            val type = node.optString("type", "")
            val widgets = node.optJSONArray("widgets_values")

            if (CLIP_TEXT_TYPES.contains(type)) {
                // In workflow JSON, CLIPTextEncode nodes typically store the prompt text in widgets_values[0].
                val s = widgets?.optString(0, "")?.trim().orEmpty()
                if (s.isNotBlank()) return s
            }

            // Heuristic: many custom nodes store prompt text directly in widgets_values; use the longest string.
            val candidate = findLongestString(widgets)
            if (!candidate.isNullOrBlank()) return candidate

            // Follow conditioning links if present.
            val upstreamCandidates = listOf("conditioning", "conditioning_1", "conditioning_2", "text", "prompt", "positive")
            for (name in upstreamCandidates) {
                findUpstreamByInputName(nodeId, name)?.let { upstream ->
                    val t = traverseToText(upstream, depth + 1)
                    if (!t.isNullOrBlank()) return t
                }
            }

            // Otherwise follow any link.
            val inputs = node.optJSONArray("inputs") ?: return null
            for (i in 0 until inputs.length()) {
                val input = inputs.optJSONObject(i) ?: continue
                val linkId = input.optInt("link", -1)
                if (linkId < 0) continue
                val upstream = linkById[linkId]?.fromNodeId ?: continue
                val t = traverseToText(upstream, depth + 1)
                if (!t.isNullOrBlank()) return t
            }

            return null
        }

        private fun findLongestString(arr: JSONArray?): String? {
            if (arr == null) return null
            var best: String? = null
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                if (v !is String) continue
                val s = v.trim()
                if (s.isBlank()) continue
                if (best == null || s.length > best!!.length) best = s
            }
            return best
        }
    }

    private fun extractFromWorkflowGraph(nodes: JSONArray, links: JSONArray): WorkflowExtract {
        val nodesById = LinkedHashMap<String, JSONObject>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val id = node.opt("id")?.toString()?.trim().orEmpty()
            if (id.isNotBlank()) nodesById[id] = node
        }

        val linkById = LinkedHashMap<Int, WorkflowLink>()
        for (i in 0 until links.length()) {
            val link = links.optJSONArray(i) ?: continue
            val id = link.optInt(0, -1)
            val from = link.opt(1)?.toString()?.trim().orEmpty()
            val to = link.opt(3)?.toString()?.trim().orEmpty()
            if (id >= 0 && from.isNotBlank() && to.isNotBlank()) {
                linkById[id] = WorkflowLink(id, fromNodeId = from, toNodeId = to)
            }
        }

        // Candidate sampler nodes: real KSampler types, or nodes with positive/negative inputs.
        val candidates = ArrayList<String>()
        for ((id, node) in nodesById) {
            val type = node.optString("type", "")
            val inputs = node.optJSONArray("inputs")
            val hasPosNeg = inputs != null && run {
                var hasP = false
                var hasN = false
                for (j in 0 until inputs.length()) {
                    val inp = inputs.optJSONObject(j) ?: continue
                    val name = inp.optString("name", "")
                    if (name.equals("positive", ignoreCase = true)) hasP = true
                    if (name.equals("negative", ignoreCase = true)) hasN = true
                }
                hasP && hasN
            }
            if (KSAMPLER_TYPES.contains(type) || hasPosNeg || type.contains("ksampler", ignoreCase = true)) {
                candidates += id
            }
        }

        var best: WorkflowTraverseContext? = null
        for (id in candidates) {
            val ctx = WorkflowTraverseContext(nodesById, linkById)
            ctx.traverse(id)
            if (best == null || ctx.visited.size > best!!.visited.size) best = ctx
        }

        val ctx = best ?: return WorkflowExtract("", "", "", null)
        return WorkflowExtract(
            positive = ctx.positive.trim(),
            negative = ctx.negative.trim(),
            settingText = "",
            modelFromWorkflow = null,
        )
    }

    private fun extractFromWorkflowTextNodes(nodes: JSONArray): WorkflowExtract {
        val positives = ArrayList<String>()
        val negatives = ArrayList<String>()

        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val type = node.optString("type", "")
            if (!CLIP_TEXT_TYPES.contains(type) && !type.contains("prompt", ignoreCase = true)) continue

            val widgets = node.optJSONArray("widgets_values") ?: continue
            val s = widgets.optString(0, "").trim()
            if (s.isBlank()) continue

            val title = node.optString("title", "")
            val lowered = (title + " " + type).lowercase()
            val isNeg = lowered.contains("negative") || lowered.contains("neg") || lowered.contains("负")
            if (isNeg) negatives += s else positives += s
        }

        val positive = positives.maxByOrNull { it.length }.orEmpty()
        val negative = negatives.maxByOrNull { it.length }.orEmpty()
        return WorkflowExtract(positive, negative, "", null)
    }

    private fun mergeSettingEntries(primary: List<SettingEntry>, secondary: List<SettingEntry>): List<SettingEntry> {
        if (primary.isEmpty()) return secondary
        if (secondary.isEmpty()) return primary
        val seen = primary.map { it.key.trim().lowercase() }.toHashSet()
        val merged = ArrayList<SettingEntry>(primary.size + secondary.size)
        merged += primary
        for (e in secondary) {
            val k = e.key.trim().lowercase()
            if (k in seen) continue
            seen += k
            merged += e
        }
        return merged
    }

    private fun extractModelFromWorkflowNodes(nodes: JSONArray): String? {
        // Prefer checkpoint loader nodes to avoid picking LoRA/embedding weights.
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val type = node.optString("type", "")
            if (!type.contains("ckpt", ignoreCase = true) && !type.contains("checkpoint", ignoreCase = true)) continue
            val widgets = node.optJSONArray("widgets_values") ?: continue
            for (j in 0 until widgets.length()) {
                val s = widgets.optString(j, "").trim()
                if (CHECKPOINT_FILE_REGEX.matches(s)) return s
            }
        }

        // Fallback: scan the whole workflow for checkpoint-like strings.
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val widgets = node.optJSONArray("widgets_values") ?: continue
            for (j in 0 until widgets.length()) {
                val s = widgets.optString(j, "").trim()
                if (CHECKPOINT_FILE_REGEX.matches(s)) return s
            }
        }
        return null
    }

    private fun buildWorkflowSettingEntries(settingText: String, model: String?): List<SettingEntry> {
        val map = LinkedHashMap<String, String>()
        if (settingText.isNotBlank()) {
            val parts = settingText.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            for (p in parts) {
                val idx = p.indexOf(':')
                if (idx <= 0 || idx >= p.length - 1) continue
                val key = p.substring(0, idx).trim().lowercase()
                val value = p.substring(idx + 1).trim()
                if (key in setOf("prompt", "negative prompt", "negativeprompt", "uc")) continue
                if (value.isBlank()) continue
                map[key] = value
            }
        }

        val width = map["width"]?.toIntOrNull()
        val height = map["height"]?.toIntOrNull()
        val steps = map["steps"]
        val sampler = map["sampler"] ?: map["sampler_name"]
        val cfg = map["cfg"] ?: map["cfg scale"] ?: map["scale"]
        val seed = map["seed"]

        val entries = ArrayList<SettingEntry>()
        normalizeString(model)?.let { entries += SettingEntry("Model", it) }
        steps?.let { entries += SettingEntry("Steps", it) }
        sampler?.let { entries += SettingEntry("Sampler", it) }
        cfg?.let { entries += SettingEntry("CFG scale", it) }
        seed?.let { entries += SettingEntry("Seed", it) }
        if (width != null && height != null) entries += SettingEntry("Size", "${width}x${height}")
        return entries
    }

    private fun buildWorkflowSettingDetail(entries: List<SettingEntry>, workflow: JSONObject): String {
        val detail = JSONObject()
        for (e in entries) detail.put(e.key, e.value)

        val meta = JSONObject()
        (workflow.opt("id") as? String)?.let { meta.put("id", it) }
        if (workflow.has("revision")) meta.put("revision", workflow.optInt("revision"))
        if (workflow.has("last_node_id")) meta.put("last_node_id", workflow.optInt("last_node_id"))
        if (workflow.has("last_link_id")) meta.put("last_link_id", workflow.optInt("last_link_id"))

        val extra = workflow.optJSONObject("extra")
        (extra?.opt("frontendVersion") as? String)?.let { meta.put("frontendVersion", it) }
        extra?.optJSONObject("node_versions")?.let { meta.put("node_versions", it) }

        if (meta.length() > 0) detail.put("workflow_meta", meta)
        return detail.toString(2)
    }

    private fun normalizeWorkflowText(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("null")) {
            val idx = trimmed.indexOf('{')
            if (idx >= 0) return trimmed.substring(idx)
        }
        return trimmed
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
                in CLIP_TEXT_TYPES -> {
                    val parts = LinkedHashSet<String>()
                    val candidates = listOf(inputs.opt("text"), inputs.opt("text_g"), inputs.opt("text_l"))
                    for (c in candidates) {
                        when (c) {
                            is String -> parts += c
                            else -> {
                                val upstream = firstLink(c)
                                if (upstream != null) traverseToText(upstream)?.let { parts += it }
                            }
                        }
                    }
                    parts.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
                }

                else -> {
                    // Many custom nodes output a string into CLIPTextEncode via a link; follow heuristically.
                    // If current node has a string-y input named positive/text/etc, use it.
                    val candidates = listOf("text", "text_g", "text_l", "positive", "prompt", "string")
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
