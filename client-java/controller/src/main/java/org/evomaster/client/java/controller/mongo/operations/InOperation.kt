package org.evomaster.client.java.controller.mongo.operations

open class InOperation<V>(open val fieldName: String, open val values: List<V>) : QueryOperation()