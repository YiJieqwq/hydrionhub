package io.hydrion.hub.model

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONArray
import org.json.JSONObject

/** The local demo history is replaced atomically. The codec is used only on Dispatchers.IO. */
internal class HistoryFile(context: Context) {
    val path = AppRestoreStore.current(context).history(context.filesDir)
    private val file = AtomicFile(path)

    fun load(): SavedWorkspace {
        val json = try {
            file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: FileNotFoundException) {
            return SavedWorkspace()
        }
        return decodeWorkspace(json)
    }

    fun save(saved: SavedWorkspace) {
        val bytes = encodeWorkspace(saved).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}

internal fun decodeWorkspace(json: JSONObject): SavedWorkspace {
    fun readPending(item: JSONObject) = readConversation(item).also { require(!it.archived) }
    val history = json.optJSONArray("history").objects().map(::readConversation)
    val drafts = json.optJSONObject("drafts") ?: JSONObject()
    val assistants = json.optJSONArray("assistants").objects().map {
        LocalAssistant(it.getString("id"), it.getString("name"), it.optional("sidebarGroup")?.takeIf(LocalAssistant::validName),
            it.optional("systemPrompt").orEmpty().takeIf(LocalAssistant::validPrompt).orEmpty(), it.readRoute("chatModel"),
            it.optional("avatarText").orEmpty().takeIf(LocalAssistant::validAvatar).orEmpty(),
            it.optInt("contextLimit").takeIf { count -> count in LocalAssistant.ContextLimits },
            it.optJSONArray("manualPanelTools")?.let { tools ->
                val names = (0 until tools.length()).mapTo(mutableSetOf(), tools::optString)
                ManualPanelTool.entries.filterTo(mutableSetOf()) { tool -> tool.name in names }
            }.orEmpty())
    }.ifEmpty { listOf(LocalAssistant.Default) }
    val lastThreads = json.optJSONObject("lastThreads") ?: JSONObject()
    val defaults = Draft()
    return SavedWorkspace(history, drafts.keys().asSequence().associateWith { id ->
        val draft = drafts.getJSONObject(id)
        val imageTool = draft.optString("imageTool", defaults.imageTool)
        Draft(draft.optString("text"), readAttachments(draft.optJSONArray("attachments")),
            draft.optString("search", defaults.search),
            if (imageTool == "模型内置图片生成") CUSTOM_IMAGE_TOOL else imageTool)
    }, json.optJSONObject("unsent")?.let(::readPending), assistants,
        json.optString("selectedAssistant", LocalAssistant.Default.id),
        json.optJSONArray("otherUnsent").objects().map(::readPending),
        lastThreads.keys().asSequence().associateWith(lastThreads::getString))
}

internal fun encodeWorkspace(saved: SavedWorkspace): JSONObject {
    val history = JSONArray(saved.history.map(::writeConversation))
    val drafts = JSONObject()
    for ((id, draft) in saved.drafts) {
        drafts.put(id, JSONObject().put("text", draft.text).put("attachments", writeAttachments(draft.attachments))
            .put("search", draft.search).put("imageTool", draft.imageTool))
    }
    return JSONObject().put("history", history).put("drafts", drafts).put("unsent", saved.unsent?.let(::writeConversation))
        .put("assistants", JSONArray(saved.assistants.map { JSONObject().put("id", it.id).put("name", it.name).put("sidebarGroup", it.sidebarGroup)
            .put("systemPrompt", it.systemPrompt.takeIf(String::isNotEmpty)).put("chatModel", it.chatModel?.toJson())
            .put("manualPanelTools", it.manualPanelTools.takeIf { tools -> tools.isNotEmpty() }?.let { tools -> JSONArray(tools.map { tool -> tool.name }) })
            .put("avatarText", it.avatarText.takeIf(String::isNotEmpty)).put("contextLimit", it.contextLimit) }))
        .put("selectedAssistant", saved.selectedAssistant).put("lastThreads", JSONObject(saved.lastThreads))
        .put("otherUnsent", JSONArray(saved.otherUnsent.map(::writeConversation)))
}

private fun readConversation(item: JSONObject) = Conversation(
    id = item.getString("id"), title = item.getString("title"),
    mode = Mode.valueOf(item.getString("mode")), pinned = item.optBoolean("pinned"),
    updatedAt = item.getLong("updatedAt"), messages = item.optJSONArray("messages").objects().map(::readMessage),
    environmentId = item.optional("environmentId"), assistantId = item.optString("assistantId", LocalAssistant.Default.id),
    archived = item.opt("archived")?.let { value -> require(value is Boolean); value } ?: false,
    panelActionsDenied = item.opt("panelActionsDenied")?.let { value -> require(value is Boolean); value } ?: false,
    contextStart = item.optional("contextStart"),
    versions = if (item.isNull("messageVersions")) emptyMap() else item.getJSONObject("messageVersions").let { groups ->
        groups.keys().asSequence().associateWith { id ->
            val group = groups.getJSONObject(id)
            MessageVersions(group.getInt("selected"), group.getJSONArray("others").objects().map(::readMessage))
        }
    },
    modelRoutes = item.optJSONObject("modelRoutes")?.let { json ->
        Mode.entries.mapNotNull { mode -> json.readRoute(mode.name)?.let { mode to it } }.toMap()
    })

private fun writeConversation(c: Conversation) = JSONObject().put("id", c.id).put("title", c.title)
    .put("mode", c.mode.name).put("pinned", c.pinned).put("updatedAt", c.updatedAt)
    .put("messages", JSONArray(c.messages.map(::writeMessage))).put("environmentId", c.environmentId).put("assistantId", c.assistantId)
    .apply {
        if (c.archived) put("archived", true)
        if (c.panelActionsDenied) put("panelActionsDenied", true)
    }
    .put("contextStart", c.contextStart)
    .put("messageVersions", c.versions.takeIf { it.isNotEmpty() }?.let { groups ->
        JSONObject().apply { groups.forEach { (id, group) ->
            put(id, JSONObject().put("selected", group.selected).put("others", JSONArray(group.others.map(::writeMessage))))
        } }
    })
    .put("modelRoutes", c.modelRoutes?.let { routes -> JSONObject().apply { routes.forEach { (mode, r) -> put(mode.name, r.toJson()) } } })

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).map { getJSONObject(it) }

private fun writeAttachments(attachments: List<Attachment>) = JSONArray(attachments.map {
    JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri)
        .put("imageKey", it.imageKey).put("mimeType", it.mimeType).put("width", it.width).put("height", it.height)
})

private fun readAttachments(array: JSONArray?) = array.objects().map {
    Attachment(it.getString("id"), it.getString("name"), it.optional("uri"), it.optional("imageKey"), it.optional("mimeType"),
        it.optInt("width").takeIf { width -> width > 0 }, it.optInt("height").takeIf { height -> height > 0 })
}

private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)

private fun TokenUsage.toStoredJson() = JSONObject()
    .put("inputTokens", inputTokens).put("outputTokens", outputTokens).put("totalTokens", totalTokens)
    .put("cachedInputTokens", cachedInputTokens).put("reasoningTokens", reasoningTokens)

private fun JSONObject.count(key: String): Long? = opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }

private fun JSONObject.readUsage() = TokenUsage(
    inputTokens = count("inputTokens"), outputTokens = count("outputTokens"), totalTokens = count("totalTokens"),
    cachedInputTokens = count("cachedInputTokens"), reasoningTokens = count("reasoningTokens")
)

private fun RemoteModel.toJson() = JSONObject().put("providerId", providerId).put("modelId", modelId)

private fun JSONObject.readRoute(key: String): RemoteModel? =
    if (isNull(key)) null else getJSONObject(key).let { RemoteModel(it.getString("providerId"), it.getString("modelId")) }

private fun writeMessage(m: Message): JSONObject = JSONObject().put("id", m.id).put("role", m.role.name)
    .put("text", m.text).put("status", m.status.name).put("createdAt", m.createdAt).put("model", m.model)
    .put("parameters", JSONObject(m.parameters)).put("attachments", writeAttachments(m.attachments)).put("prompt", m.prompt)
    .put("thought", m.thought).put("tool", m.tool).put("imageKeys", JSONArray(m.imageKeys))
    .put("remoteModel", m.remoteModel?.toJson())
    .put("totalTokens", m.reportedUsage?.contextTokens).put("usage", m.usage?.toStoredJson())
    .put("timing", m.timing?.let { JSONObject().put("firstTokenMs", it.firstTokenMs)
        .put("firstBodyMs", it.firstBodyMs).put("elapsedMs", it.elapsedMs) })
    .put("toolCalls", m.toolCalls.takeIf { it.isNotEmpty() }?.let { calls ->
        JSONArray(calls.map { JSONObject().put("name", it.name).put("state", it.state.name).put("bodyOffset", it.bodyOffset)
            .put("slot", it.slot).put("arguments", it.arguments).put("result", it.result) })
    })
    .put("replySteps", m.replySteps.takeIf { it.isNotEmpty() }?.let { steps -> JSONArray(steps.map { step ->
        when (step) {
            is ReplyStep.Tool -> JSONObject().put("tool", step.slot)
            is ReplyStep.Thought -> JSONObject().put("thought", step.slot).put("bodyOffset", step.bodyOffset)
                .put("start", step.start).put("end", step.end)
        }
    }) })
    .put("translation", m.translation?.let { JSONObject().put("language", it.language).put("text", it.text) })
    .put("continuesAssistant", m.continuesAssistant.takeIf { it })
    .put("error", m.error).put("replyTo", m.replyTo).put("imageInvocation", m.imageInvocation?.let {
        JSONObject().put("model", it.model).put("parameters", JSONObject(it.parameters)).put("remoteModel", it.remoteModel?.toJson())
    })

private fun readMessage(j: JSONObject): Message {
    val keys = j.optJSONArray("imageKeys") ?: JSONArray()
    val parameters = j.optJSONObject("parameters") ?: JSONObject()
    return Message(id = j.getString("id"), role = Role.valueOf(j.getString("role")), text = j.optString("text"),
        status = Generation.valueOf(j.getString("status")), createdAt = j.getLong("createdAt"),
        model = j.optString("model"), parameters = parameters.keys().asSequence().associateWith(parameters::getString),
        attachments = readAttachments(j.optJSONArray("attachments")), prompt = j.optString("prompt"),
        thought = j.optString("thought"), tool = j.optional("tool"), imageKeys = (0 until keys.length()).map { keys.getString(it) },
        remoteModel = j.readRoute("remoteModel"),
        totalTokens = j.count("totalTokens"), usage = j.optJSONObject("usage")?.readUsage(),
        continuesAssistant = j.optBoolean("continuesAssistant", false),
        timing = j.optJSONObject("timing")?.let { ReplyTiming(it.count("firstTokenMs"), it.count("firstBodyMs"), it.count("elapsedMs")) },
        toolCalls = j.optJSONArray("toolCalls").objects().mapNotNull { call ->
            ToolCallState.entries.find { it.name == call.optString("state") }?.let { state ->
                ReplyToolCall(call.optString("name"), state, call.optInt("bodyOffset", -1).takeIf { it >= 0 },
                    call.optional("arguments"), call.optional("result"), call.optInt("slot", -1).takeIf { it >= 0 })
            }
        },
        translation = j.optJSONObject("translation")?.let { ReplyTranslation(it.optString("language"), it.optString("text")) },
        error = j.optional("error"), replyTo = j.optional("replyTo"), imageInvocation = j.optJSONObject("imageInvocation")?.let {
            val imageParameters = it.getJSONObject("parameters")
            ImageInvocation(it.getString("model"), imageParameters.keys().asSequence().associateWith(imageParameters::getString),
                it.readRoute("remoteModel"))
        }).withSavedSteps(j.optJSONArray("replySteps"))
}

// Old histories have only the aggregate thought. If optional ordering is unreadable,
// retain that text and every original call instead of showing a partial reconstruction.
private fun Message.withSavedSteps(array: JSONArray?): Message {
    if (array == null || array.length() == 0) return this
    val remainingCalls = toolCalls.mapNotNull { it.slot }.toMutableSet()
    val thoughts = mutableSetOf<Int>()
    val steps = mutableListOf<ReplyStep>()
    var lastEnd = 0
    for (i in 0 until array.length()) {
        val step = array.optJSONObject(i) ?: return this
        if (step.has("tool")) {
            val slot = step.optInt("tool", -1)
            if (!remainingCalls.remove(slot)) return this
            steps += ReplyStep.Tool(slot)
        } else {
            val slot = step.optInt("thought", -1)
            val offset = step.optInt("bodyOffset", -1)
            val start = step.optInt("start", -1)
            val end = step.optInt("end", -1)
            if (slot < 0 || !thoughts.add(slot) || offset !in 0..text.length ||
                start < lastEnd || end <= start || end > thought.length ||
                (lastEnd until start).any { !thought[it].isWhitespace() }) return this
            steps += ReplyStep.Thought(slot, offset, start, end)
            lastEnd = end
        }
    }
    if (remainingCalls.isNotEmpty() || toolCalls.size + thoughts.size != steps.size ||
        (lastEnd until thought.length).any { !thought[it].isWhitespace() }) return this
    return copy(replySteps = steps)
}
