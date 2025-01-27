package org.modelix.metamodel

import org.modelix.model.api.IChildLink
import org.modelix.model.api.IConcept
import org.modelix.model.api.INode
import org.modelix.model.api.addNewChild
import org.modelix.model.api.getChildren
import kotlin.reflect.KClass

abstract class ChildAccessor<ChildT : ITypedNode>(
    protected val parent: INode,
    protected val role: IChildLink,
    protected val childConcept: IConcept,
    protected val childType: KClass<ChildT>,
): Iterable<ChildT> {
    fun isEmpty(): Boolean = !iterator().hasNext()

    fun getSize(): Int {
        return this.count()
    }

    override fun iterator(): Iterator<ChildT> {
        return parent.getChildren(role).map {
            when (childConcept) {
                is GeneratedConcept<*, *> -> it.typed(childType)
                else -> throw RuntimeException("Unsupported concept type: ${childConcept::class} (${childConcept.getLongName()})")
            }
        }.iterator()
    }

    fun addNew(index: Int = -1): ChildT {
        return parent.addNewChild(role, index, childConcept).typed(childType)
    }

    fun <NewNodeT : ChildT> addNew(index: Int = -1, concept: INonAbstractConcept<NewNodeT>): NewNodeT {
        return parent.addNewChild(role, index, concept.untyped()).typed(concept.getInstanceClass())
    }

    fun removeUnwrapped(child: INode) {
        parent.removeChild(child)
    }

    fun remove(child: TypedNodeImpl) {
        removeUnwrapped(child.unwrap())
    }
}
