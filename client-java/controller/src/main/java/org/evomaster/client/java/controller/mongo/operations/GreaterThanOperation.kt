package org.evomaster.client.java.controller.mongo.operations

class GreaterThanOperation<V>(
    override val fieldName: String,
    override val value: V
): ComparisonOperation<V>(fieldName, value)