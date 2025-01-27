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
package org.modelix.metamodel

import org.modelix.model.data.PropertyType

fun PropertyType.getSerializer(optional: Boolean): IPropertyValueSerializer<*> {
    return if (optional) {
        when (this) {
            PropertyType.STRING -> OptionalStringPropertySerializer
            PropertyType.BOOLEAN -> OptionalBooleanPropertySerializer
            PropertyType.INT -> OptionalIntPropertySerializer
        }
    } else {
        when (this) {
            PropertyType.STRING -> MandatoryStringPropertySerializer
            PropertyType.BOOLEAN -> MandatoryBooleanPropertySerializer
            PropertyType.INT -> MandatoryIntPropertySerializer
        }
    }
}

interface IPropertyValueSerializer<ValueT> {
    fun serialize(value: ValueT): String?
    fun deserialize(serialized: String?): ValueT
}

object MandatoryStringPropertySerializer : IPropertyValueSerializer<String> {
    override fun serialize(value: String): String? {
        return value
    }

    override fun deserialize(serialized: String?): String {
        return serialized ?: ""
    }
}

object OptionalStringPropertySerializer : IPropertyValueSerializer<String?> {
    override fun serialize(value: String?): String? {
        return value
    }

    override fun deserialize(serialized: String?): String? {
        return serialized
    }
}

object MandatoryBooleanPropertySerializer : IPropertyValueSerializer<Boolean> {
    override fun serialize(value: Boolean): String? {
        return if (value) "true" else "false"
    }

    override fun deserialize(serialized: String?): Boolean {
        return serialized == "true"
    }
}

object OptionalBooleanPropertySerializer : IPropertyValueSerializer<Boolean?> {
    override fun serialize(value: Boolean?): String? {
        return value?.let { if (it) "true" else "false" }
    }

    override fun deserialize(serialized: String?): Boolean? {
        return serialized?.let { it == "true" }
    }
}

object MandatoryIntPropertySerializer : IPropertyValueSerializer<Int> {
    override fun serialize(value: Int): String? {
        return value.toString()
    }

    override fun deserialize(serialized: String?): Int {
        return serialized?.toInt() ?: 0
    }
}

object OptionalIntPropertySerializer : IPropertyValueSerializer<Int?> {
    override fun serialize(value: Int?): String? {
        return value?.toString()
    }

    override fun deserialize(serialized: String?): Int? {
        return serialized?.toInt()
    }
}
