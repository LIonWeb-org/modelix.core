/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.model.server

import com.google.common.io.BaseEncoding
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.html.body
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.tr
import org.json.JSONArray
import org.json.JSONObject
import org.modelix.authorization.KeycloakScope
import org.modelix.authorization.asResource
import org.modelix.authorization.getUserName
import org.modelix.authorization.requiresPermission
import org.modelix.model.IKeyListener
import org.modelix.model.VersionMerger
import org.modelix.model.api.*
import org.modelix.model.client.IdGenerator
import org.modelix.model.lazy.CLTree
import org.modelix.model.lazy.CLVersion
import org.modelix.model.lazy.RepositoryId
import org.modelix.model.operations.*
import org.modelix.model.persistent.CPVersion
import java.util.*

class LionwebModelServer(val client: LocalModelClient) {

    fun getStore() = client.storeCache

    fun init(application: Application) {
        application.apply {
            routing {
                requiresPermission("model-json-api".asResource(), KeycloakScope.READ) {
                    route("/lionweb-json") {
                        initRouting()
                    }
                }
            }
        }
    }

    private fun getCurrentVersion(repositoryId: RepositoryId): CLVersion {
        val versionHash = client.asyncStore.get(repositoryId.getBranchReference().getKey())!!
        return CLVersion.loadFromHash(versionHash, getStore())
    }

    private fun Route.initRouting() {
        get("/") {
            call.respondHtml(HttpStatusCode.OK) {
                body {
                    table {
                        tr {
                            td { +"GET /{repositoryId}/" }
                            td { + "Returns the model content of the latest version on the master branch." }
                        }
                        tr {
                            td { +"GET /{repositoryId}/{versionHash}/" }
                            td { + "Returns the model content of the specified version on the master branch." }
                        }
                        tr {
                            td { +"GET /{repositoryId}/{versionHash}/poll" }
                            td { + "" }
                        }
                        tr {
                            td { +"POST /{repositoryId}/init" }
                            td { + "Initializes a new repository." }
                        }
                        tr {
                            td { +"POST /{repositoryId}/{versionHash}/update" }
                            td {
                                + "Applies the delta to the specified version of the model and merges"
                                +" it into the master branch. Return the model content after the merge."
                            }
                        }
                        tr {
                            td { +"WEBSOCKET /{repositoryId}/ws" }
                            td {
                                + "WebSocket for exchanging model deltas."
                            }
                        }
                    }
                }
            }
        }
        get("/{repositoryId}/") {
            val repositoryId = RepositoryId(call.parameters["repositoryId"]!!)
            val versionHash = client.asyncStore.get(repositoryId.getBranchReference().getKey())!!
            // TODO 404 if it doesn't exist
            val version = CLVersion.loadFromHash(versionHash, getStore())
            respondVersion(version)
        }
        get("/{repositoryId}/{versionHash}/") {
            val versionHash = call.parameters["versionHash"]!!
            // TODO 404 if it doesn't exist
            val version = CLVersion.loadFromHash(versionHash, getStore())
            respondVersion(version)
        }
        post("/{repositoryId}/init") {
            // TODO error if it already exists
            val repositoryId = RepositoryId(call.parameters["repositoryId"]!!)
            val newTree = CLTree(repositoryId, getStore())
            val userId = call.getUserName()
            val newVersion = CLVersion.createRegularVersion(
                client.idGenerator.generate(),
                Date().toString(),
                userId,
                newTree,
                null,
                emptyArray()
            )
            client.asyncStore.put(repositoryId.getBranchReference().getKey(), newVersion.hash)
            respondVersion(newVersion)
        }
        post("/{repositoryId}/update") {
            val updateData = JSONObject(call.receiveText())
            val repositoryId = RepositoryId(call.parameters["repositoryId"]!!)

            val currentVersionHash = client.asyncStore.get(repositoryId.getBranchReference().getKey())!!

            val baseVersionHash = updateData.optString("__version", currentVersionHash)

            if (baseVersionHash != currentVersionHash) {
                call.respond(HttpStatusCode.BadRequest, "Cannot update, current version $currentVersionHash does not match base version $baseVersionHash")
                return@post
            }

            val baseVersionData = getStore().get(baseVersionHash, { CPVersion.deserialize(it) })
            if (baseVersionData == null) {
                call.respond(HttpStatusCode.NotFound, "version not found: $baseVersionHash")
                return@post
            }

            val baseVersion = CLVersion(baseVersionData, getStore())
            val mergedVersion = applyUpdate(baseVersion, updateData.getJSONArray("nodes"), repositoryId, getUserName())

            respondVersion(mergedVersion)
        }
        get("/{repositoryId}/the-form") {
            val repositoryId = RepositoryId(call.parameters["repositoryId"]!!)
            val versionHash = client.asyncStore.get(repositoryId.getBranchReference().getKey())!!
            // TODO 404 if it doesn't exist
            val version = CLVersion.loadFromHash(versionHash, getStore())

            val FORM_CONCEPT_ID = "mps:1b1e4cbb-850f-4943-a2a8-9a345d1e7e25/7344893323394389269"
            val formNode = version.tree.getDescendants(ITree.ROOT_ID, false).first {
                it.concept == FORM_CONCEPT_ID
            }

            respondJson(subtreeAsJson(PNodeAdapter(formNode.id, TreePointer(version.tree)), version.hash))
        }
        post("/{repositoryId}/generate-ids") {
            val quantity = call.request.queryParameters["quantity"]?.toInt() ?: 1000
            val ids = (client.idGenerator as IdGenerator).generate(quantity)
            val idStrings = ids.map { it.toString() }
            respondJson(buildJSONObject {
                put("serializationFormatVersion", "1")
                put("freeIds", JSONArray().putAll(idStrings))
            })
        }
        webSocket("/{repositoryId}/ws") {
            val repositoryId = RepositoryId(call.parameters["repositoryId"]!!)
            val userId = call.getUserName()

            var lastVersion: CLVersion? = null
            val deltaMutex = Mutex()
            val sendDelta: suspend (CLVersion)->Unit = { newVersion ->
                deltaMutex.withLock {
                    newVersion.operations.forEach { send(operation2json(it).toString()) }
                }
            }

            val listener = object : IKeyListener {
                override fun changed(key: String, value: String?) {
                    if (value == null) return
                    launch {
                        val newVersion = CLVersion.loadFromHash(value, client.storeCache)
                        sendDelta(newVersion)
                    }
                }
            }

            client.listen(repositoryId.getBranchKey(), listener)
            try {
                sendDelta(getCurrentVersion(repositoryId))
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val updateData = JSONArray(frame.readText())
                            val mergedVersion = applyUpdate(lastVersion!!, updateData, repositoryId, userId)
                            sendDelta(mergedVersion)
                        }
                        else -> {}
                    }
                }
            } finally {
                client.removeListener(repositoryId.getBranchKey(), listener)
            }
        }
    }

    private fun operation2json(operation: IOperation): JSONObject =
            when (operation) {
                is AddNewChildOp -> JSONObject()
                        .put("_id", operation.childId.toString())
                        .put("_type", "NodeAdded")
                        .put("_concept", operation.concept?.getUID())
                        .put("location", position2json(operation.position))

                is MoveNodeOp -> JSONObject()
                        .put("_id", operation.childId.toString())
                        .put("_type", "NodeMoved")
//                        .put("oldLocation", position2json(TODO()))
                        .put("newLocation", position2json(operation.targetPosition))

                is DeleteNodeOp -> JSONObject()
                        .put("_id", operation.childId.toString())
                        .put("_type", "NodeDeleted")
//                        .put("location", position2json(TODO()))

                is SetPropertyOp -> JSONObject()
                        .put("_id", operation.nodeId.toString())
                        .put("_type", "PropertyChanged")
                        .put("property", operation.role)
                        .put("newValue", operation.value)
//                        .put("oldValue", TODO())

                else -> throw UnsupportedOperationException("Unsupported operation class: ${operation.javaClass}")
            }

    private fun position2json(position: PositionInRole): JSONObject =
            JSONObject(mapOf<String, Any>(
                    "parent" to position.nodeId.toString(),
                    "role" to position.role!!,
                    "index" to position.index.toString()))

    private fun applyUpdate(
        baseVersion: CLVersion,
        updateData: JSONArray,
        repositoryId: RepositoryId,
        userId: String?
    ): CLVersion {
        val branch = OTBranch(PBranch(baseVersion.tree, client.idGenerator), client.idGenerator, client.storeCache)
        branch.computeWriteT { t -> update(updateData, t) }

        val operationsAndTree = branch.operationsAndTree
        val newVersion = CLVersion.createRegularVersion(
            client.idGenerator.generate(),
            Date().toString(),
            userId,
            operationsAndTree.second as CLTree,
            baseVersion,
            operationsAndTree.first.map { it.getOriginalOp() }.toTypedArray()
        )
        val mergedVersion = VersionMerger(client.storeCache, client.idGenerator)
            .mergeChange(getCurrentVersion(repositoryId), newVersion)
        client.asyncStore.put(repositoryId.getBranchReference().getKey(), mergedVersion.hash)
        // TODO handle concurrent write to the branchKey, otherwise versions might get lost. See ReplicatedRepository.
        return mergedVersion
    }

    /**
     * Updates the tree according to [nodeData]. After the update each node will have property and reference values
     * as specified in the data. Any existing children of nodes in the data that are not mentioned in the data will
     * be deleted.
     *
     * The intended use case for this operation is a "Save" button in an editor that will save an entire subtree.
     *
     * Limitations:
     * * A node can only be deleted by omitting it from a list of children of its parent.
     * * A node can only be created by adding it to a list of children of another node.
     * * This operation does not do conflict resolution.
     */
    private fun update(nodeData: JSONArray, t: IWriteTransaction) {
        val jsonObjects = nodeData.asSequence().filterIsInstance(JSONObject::class.java)

        val oldChildren = jsonObjects.asSequence().map { it.getLong("id") }
                .filter(t::containsNode)
                .flatMap(t::getAllChildren)
                .toSet()

        jsonObjects.forEach {
            val nodeId = it.getLong("id")

            if (!t.containsNode(nodeId)) {
                val conceptId = decodeBase64Url(it.getString("concept"))
                t.addNewChild(ITree.ROOT_ID, ITree.DETACHED_NODES_ROLE, -1, nodeId, ConceptReference(conceptId))
            }
        }

        val movedChildren = mutableSetOf<Long>()

        jsonObjects.forEach {
            updateProperties(it, t)
            updateReferences(it, t)
            updateChildren(it, t, movedChildren)
        }

        oldChildren.subtract(movedChildren).forEach { t.deleteNode(it) }
    }

    private fun updateProperties(nodeData: JSONObject, t: IWriteTransaction) {
        val nodeId = nodeData.getLong("id")

        val missingProperties = t.getPropertyRoles(nodeId).toMutableSet()

        nodeData.optJSONObject("properties")?.stringEntries()?.forEach { role, value ->
            t.setProperty(nodeId, role, value)
            missingProperties.remove(role)
        }

        missingProperties.forEach { t.setProperty(nodeId, it, null) }
    }

    private fun updateReferences(nodeData: JSONObject, t: IWriteTransaction) {
        val nodeId = nodeData.getLong("id")

        val missingReferences = t.getReferenceRoles(nodeId).toMutableSet()

        nodeData.optJSONObject("references")?.arrayEntries()?.forEach { (role, refs) ->
            refs.forEach {
                val ref = it as JSONObject
                val newTargetId = ref.getLong("reference")
                t.setReferenceTarget(nodeId, role, LocalPNodeReference(newTargetId))

                missingReferences.remove(role)
            }
        }

        missingReferences.forEach { t.setReferenceTarget(nodeId, it, null) }
    }

    private fun updateChildren(nodeData: JSONObject, t: IWriteTransaction, moved: MutableSet<Long>) {
        val nodeId = nodeData.getLong("id")

        nodeData.getJSONObject("children").arrayEntries().forEach { role, childIds ->
            childIds.asLongList().forEachIndexed { index, childId -> t.moveChild(nodeId, role, index, childId); moved.add(childId) }
        }
    }

    private suspend fun CallContext.respondVersion(version: CLVersion) {
        respondJson(versionAsJson(version))
    }

    private fun versionAsJson(version: CLVersion): JSONObject {
        val branch = TreePointer(version.tree)
        val rootNode = PNodeAdapter(ITree.ROOT_ID, branch)
        return subtreeAsJson(rootNode, version.hash)
    }

//    private fun diffAsJson(version: CLVersion, oldVersion: CLVersion): JSONObject {
//        val branch = TreePointer(version.tree)
//        val json = JSONArray()
//
//        version.tree.visitChanges(oldVersion.tree, object : ITreeChangeVisitorEx {
//            // Form
//            //   itemGroup IG1:
//            //     item i11
//            //   itemGroup IG2:
//            //     item i21
//            //     item i22
//            //
//            // scenario 1: move item i22 to itemGroup IG1
//            // * Modelix delta:
//            //   * childrenChanged(IG1)
//            //   * childrenChanged(IG2)
//            //   * containmentChanged(i22)
//            // * Lionweb delta:
//            //   * NodeMoved(i22, IG2@2 -> IG1@1)
//            //
//            // scenario 2: delete item i22
//            // * Modelix
//            //   * childrenChanged(IG2)
//            //   * nodeRemoved(i22)
//            // * Lionweb delta:
//            //   * NodeDeleted(i22)
//            //
//            // scenario 3: move item i22 before i21
//            // * Modelix
//            //   * childrenChanged(IG2)
//            // * Lionweb delta:
//            //   * NodeMoved(i22, IG2@2 -> IG2@1)
//
//
//            // 1. childrenChanged(nodeId: N, role: R)
//            //    - old children: [C1, C2], new children: [C1, C3]
//            //    => C2 was deleted or moved
//            //    => C3 was added or moved
//            // 2a. nodeDeleted(C2) => C2 was deleted
//            // 2b. containmentChanged(C2) => C2 was moved
//
//            override fun childrenChanged(nodeId: Long, role: String?) {
//
//
//                oldVersion.tree.getChildren(nodeId, role)
//            }
//
//            override fun containmentChanged(nodeId: Long) {
//
//            }
//
//            override fun propertyChanged(nodeId: Long, role: String) {
//                nodesToInclude += nodeId
//            }
//
//            override fun referenceChanged(nodeId: Long, role: String) {
//                nodesToInclude += nodeId
//            }
//
//            override fun nodeAdded(nodeId: Long) {
//                nodesToInclude += nodeId
//            }
//
//            override fun nodeRemoved(nodeId: Long) {}
//        })
//        val changedNodes = nodesToInclude.map { node2json(PNodeAdapter(it, branch), false) }.toJsonArray()
//        json.put("nodes", changedNodes)
//        version.tree
//        return json
//    }

    private fun subtreeAsJson(rootNode: PNodeAdapter, version: String): JSONObject {
        val json = JSONObject()
        json.put("serializationFormatVersion", "1")
        json.put("__version", version)
        json.put("nodes", rootNode.getDescendants(true).map(::node2json).toList())
        return json
    }

    private suspend fun CallContext.respondJson(json: JSONObject) {
        call.respondText(json.toString(2), ContentType.Application.Json)
    }
    private suspend fun CallContext.respondJson(json: JSONArray) {
        call.respondText(json.toString(2), ContentType.Application.Json)
    }

    private fun node2json(node: INode): JSONObject {
        val json = JSONObject()
        if (node is PNodeAdapter) {
            json.put("id", node.nodeId.toString())
        }

        val conceptId = node.getConceptReference()?.getUID()
        if (conceptId != null) {
            val encoded = encodeBase64Url(conceptId)
            json.put("concept", encoded)
        } else {
            json.put("concept", JSONObject.NULL)
        }

        val parent = node.parent
        if (parent is PNodeAdapter) {
            json.put("parent", parent.nodeId)
        } else if (parent != null) {
            throw IllegalStateException("Cannot serialize parent of class ${parent.javaClass}")
        } else {
            json.put("parent", JSONObject.NULL)
        }

        val jsonProperties = JSONObject()
        val jsonReferences = JSONObject()
        val jsonChildren = JSONObject()
        json.put("properties", jsonProperties)
        json.put("references", jsonReferences)
        json.put("children", jsonChildren)

        for (role in node.getPropertyRoles()) {
            jsonProperties.put(role, node.getPropertyValue(role))
        }

        for (role in node.getReferenceRoles()) {
            val ref = node.getReferenceTargetRef(role)

            if (ref is PNodeReference) {
                val refJson = JSONObject()
                refJson.put("resolveInfo", JSONObject.NULL)
                refJson.put("reference", ref.id)

                jsonReferences.put(role, listOf(refJson))
            } else if (ref != null) {
                throw IllegalStateException("Don't know how to serialize reference of class ${ref.javaClass}")
            }
        }

        for (children in node.allChildren.groupBy { it.roleInParent }) {
            jsonChildren.put(children.key ?: "null", children.value.map { (it as PNodeAdapter).nodeId.toString() })
        }
        return json
    }

    private val base64UrlEncoding = BaseEncoding.base64Url().omitPadding()

    private fun encodeBase64Url(input: String): String {
        return base64UrlEncoding.encode(input.toByteArray(Charsets.US_ASCII))
    }

    private fun decodeBase64Url(input: String): String =
            base64UrlEncoding.decode(input).toString(Charsets.US_ASCII)
}
