package org.modelix.metamodel

import org.modelix.model.api.*
import kotlin.reflect.KClass

abstract class GeneratedConcept<InstanceT : ITypedNode, WrapperT : ITypedConcept>(private val is_abstract: Boolean) : IConcept {
    abstract val _typed: WrapperT
    abstract val instanceClass: KClass<InstanceT>
    private val propertiesMap: MutableMap<String, GeneratedProperty> = LinkedHashMap()
    private val childLinksMap: MutableMap<String, GeneratedChildLink<*, *>> = LinkedHashMap()
    private val referenceLinksMap: MutableMap<String, GeneratedReferenceLink<*, *>> = LinkedHashMap()

    abstract fun wrap(node: INode): InstanceT

    override fun isAbstract(): Boolean {
        return is_abstract
    }

    fun newProperty(name: String): GeneratedProperty {
        return GeneratedProperty(this, name).also {
            propertiesMap[name] = it
        }
    }

    fun <ChildNodeT : ITypedNode, ChildConceptT : ITypedConcept> newChildLink(name: String, isMultiple: Boolean, isOptional: Boolean, targetConcept: IConcept): GeneratedChildLink<ChildNodeT, ChildConceptT> {
        return GeneratedChildLink<ChildNodeT, ChildConceptT>(this, name, isMultiple, isOptional, targetConcept).also {
            childLinksMap[name] = it
        }
    }

    fun <TargetNodeT : ITypedNode, TargetConceptT : ITypedConcept> newReferenceLink(name: String, isOptional: Boolean, targetConcept: IConcept): GeneratedReferenceLink<TargetNodeT, TargetConceptT> {
        return GeneratedReferenceLink<TargetNodeT, TargetConceptT>(this, name, isOptional, targetConcept).also {
            referenceLinksMap[name] = it
        }
    }

    override fun getChildLink(name: String): IChildLink {
        return childLinksMap[name]
            ?: throw IllegalArgumentException("Concept ${getLongName()} doesn't contain child link $name")
    }

    override fun getProperty(name: String): IProperty {
        return propertiesMap[name]
            ?: throw IllegalArgumentException("Concept ${getLongName()} doesn't contain property $name")
    }

    override fun getReferenceLink(name: String): IReferenceLink {
        return referenceLinksMap[name]
            ?: throw IllegalArgumentException("Concept ${getLongName()} doesn't contain reference link $name")
    }

    override fun getReference(): IConceptReference {
        return GeneratedConceptReference(getUID())
    }

    override fun getShortName(): String {
        return this::class.simpleName!!
    }

    override fun getLongName(): String {
        return language!!.getName() + "." + getShortName()
    }

    override fun getOwnChildLinks(): List<IChildLink> {
        return childLinksMap.values.toList()
    }

    override fun getOwnReferenceLinks(): List<IReferenceLink> {
        return referenceLinksMap.values.toList()
    }

    override fun getUID(): String {
        return GeneratedConceptReference.PREFIX + getLongName()
    }

    override fun isExactly(concept: IConcept?): Boolean {
        return concept == this
    }

    override fun isSubConceptOf(superConcept: IConcept?): Boolean {
        if (superConcept == null) return false
        if (isExactly(superConcept)) return true

        for (c in getDirectSuperConcepts()) {
            if (c.isSubConceptOf(superConcept)) return true
        }

        return false
    }

    override fun getOwnProperties(): List<IProperty> {
        return propertiesMap.values.toList()
    }

    override fun getAllProperties(): List<IProperty> {
        return getAllConcepts().flatMap { it.getOwnProperties() }
    }

    override fun getAllChildLinks(): List<IChildLink> {
        return getAllConcepts().flatMap { it.getOwnChildLinks() }
    }

    override fun getAllReferenceLinks(): List<IReferenceLink> {
        return getAllConcepts().flatMap { it.getOwnReferenceLinks() }
    }
}

class GeneratedProperty(private val owner: IConcept, override val name: String) : IProperty {
    override fun getConcept(): IConcept = owner
    override fun getUID(): String = getConcept().getUID() + "." + name
}

class GeneratedChildLink<ChildNodeT : ITypedNode, ChildConceptT : ITypedConcept>(
    private val owner: IConcept,
    override val name: String,
    override val isMultiple: Boolean,
    override val isOptional: Boolean,
    override val targetConcept: IConcept,
) : IChildLink {
    @Deprecated("use .targetConcept")
    override val childConcept: IConcept = targetConcept

    override fun getConcept(): IConcept = owner

    override fun getUID(): String = getConcept().getUID() + "." + name
}

class GeneratedReferenceLink<TargetNodeT : ITypedNode, TargetConceptT : ITypedConcept>(
    private val owner: IConcept,
    override val name: String,
    override val isOptional: Boolean,
    override val targetConcept: IConcept,
) : IReferenceLink {

    override fun getConcept(): IConcept = owner

    override fun getUID(): String = getConcept().getUID() + "." + name
}

