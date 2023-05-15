package org.evomaster.core.search.service

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.database.DatabaseExecution
import org.evomaster.core.search.Action
import org.evomaster.core.utils.ReportWriter.wrapWithQuotation
import org.evomaster.core.utils.ReportWriter.writeByChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * report executed info
 */
class ExecutionInfoReporter {

    @Inject
    private lateinit var config: EMConfig

    private val executedAction: MutableList<String> = mutableListOf()

    private val executedSqlAction: MutableList<DatabaseExecution> = mutableListOf()

    private var hasHeader: Boolean = false

    /**
     * @param actions are endpoints to be executed
     * @param sqlExecutionInfo are sql commands produced by the [actions]
     *      key - the index of the actions
     *      value - its corresponding database execution info
     */
    fun sqlExecutionInfo(actions: List<Action>, sqlExecutionInfo: Map<Int, DatabaseExecution>){

        if (config.outputExecutedSQL == EMConfig.OutputExecutedSQL.NONE) return

        if (!hasHeader && sqlExecutionInfo.values.any { it.executionInfo.isNotEmpty() }){
            writeByChannel(
                    Paths.get(config.saveExecutedSQLToFile),
                    getRowString(arrayOf("endpoint","sqlCommand","executionTime"))+System.lineSeparator())
            hasHeader = true
        }

        sqlExecutionInfo.forEach { t, u ->
            getOneRow(actions.get(t).getName(), u, config.outputExecutedSQL == EMConfig.OutputExecutedSQL.ONCE_EXECUTED)
        }
    }

    fun getSqlExecutionInfo(actions: List<Action>, sqlExecutionInfo: Map<Int, DatabaseExecution>): String {
        if (config.outputExecutedSQL == EMConfig.OutputExecutedSQL.NONE) return ""

        val stringBuilder = StringBuilder()

        if (!hasHeader && sqlExecutionInfo.values.any { it.executionInfo.isNotEmpty() }){
            stringBuilder.append(getRowString(arrayOf("endpoint","sqlCommand","executionTime"))).append(System.lineSeparator())
            hasHeader = true
        }

        sqlExecutionInfo.forEach { t, u ->
            stringBuilder.append(getOneRow(actions.get(t).getName(), u, config.outputExecutedSQL == EMConfig.OutputExecutedSQL.ONCE_EXECUTED))
        }

        return stringBuilder.toString()
    }


    /**
     * save all execution info at end of the search
     */
    fun saveAll(){
        if (config.outputExecutedSQL == EMConfig.OutputExecutedSQL.ALL_AT_END){
            executedAction.forEachIndexed { index, s ->
                getOneRow(s, executedSqlAction.get(index), true)
            }
        }
    }

    private fun getRowString(info: Array<String>) = info.joinToString(",")

    private fun getOneRow(action: String, sqlInfo: DatabaseExecution, output: Boolean){
        if (!output){
            executedAction.add(action)
            executedSqlAction.add(sqlInfo)
        }else{
            outputSqlExecution(action, sqlInfo)
        }
    }

    private fun outputSqlExecution(action: String, sqlInfo: DatabaseExecution){
        sqlInfo.executionInfo.forEach {
            writeByChannel(
                   Paths.get(config.saveExecutedSQLToFile),
                   getRowString(arrayOf(wrapWithQuotation(action), wrapWithQuotation(it.command), "${it.executionTime}"))+System.lineSeparator(),
                   true)
        }
    }
}