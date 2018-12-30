package com.alvinhkh.buseta.mtr.dao

import org.simpleframework.xml.convert.Converter
import org.simpleframework.xml.stream.InputNode
import org.simpleframework.xml.stream.OutputNode

/*
 * https://stackoverflow.com/a/17220939/2411672
 */
class EmptyElementConverter : Converter<String> {
    @Throws(Exception::class)
    override fun read(node: InputNode): String {
        /* Implement if required */
        throw UnsupportedOperationException("Not supported yet.")
    }

    @Throws(Exception::class)
    override fun write(node: OutputNode, value: String) {
        /* Simple implementation: do nothing here ;-) */
    }
}