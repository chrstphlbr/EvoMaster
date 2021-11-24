package org.evomaster.core.output.service

import com.google.inject.Inject
import org.evomaster.client.java.controller.api.dto.SutInfoDto
import org.evomaster.core.output.Lines
import org.evomaster.core.problem.graphql.GraphQLAction
import org.evomaster.core.problem.graphql.GraphQLIndividual
import org.evomaster.core.problem.graphql.GraphQLUtils
import org.evomaster.core.problem.graphql.GraphQlCallResult
import org.evomaster.core.problem.httpws.service.HttpWsAction
import org.evomaster.core.problem.httpws.service.HttpWsCallResult
import org.evomaster.core.remote.SutProblemException
import org.evomaster.core.remote.service.RemoteController
import org.evomaster.core.search.Action
import org.evomaster.core.search.ActionResult
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.gene.GeneUtils
import org.slf4j.LoggerFactory
import javax.annotation.PostConstruct

class GraphQLTestCaseWriter : HttpWsTestCaseWriter() {

    companion object {
        private val log = LoggerFactory.getLogger(GraphQLTestCaseWriter::class.java)
    }

    @Inject(optional = true)
    protected lateinit var rc: RemoteController

    protected lateinit var infoDto: SutInfoDto

    @PostConstruct
    protected fun initialize() {

        log.debug("Initializing {}", GraphQLTestCaseWriter::class.simpleName)
        if (!config.blackBox || config.bbExperiments) {
            rc.checkConnection()

            val started = rc.startSUT()
            if (!started) {
                throw SutProblemException("Failed to start the system under test")
            }

            infoDto = rc.getSutInfo()
                ?: throw SutProblemException("Failed to retrieve the info about the system under test")
        }
        log.debug("Done initializing {}", GraphQLTestCaseWriter::class.simpleName)
    }


    override fun handleActionCalls(lines: Lines, baseUrlOfSut: String, ind: EvaluatedIndividual<*>){
        if (ind.individual is GraphQLIndividual) {
            ind.evaluatedActions().forEach { a ->
                handleSingleCall(a, lines, baseUrlOfSut)
            }
        }
    }

    override fun addActionLines(action: Action, lines: Lines, result: ActionResult, baseUrlOfSut: String) {
        addGraphQlCallLines(action as GraphQLAction, lines, result as GraphQlCallResult, baseUrlOfSut)
    }

    private fun addGraphQlCallLines(call: GraphQLAction, lines: Lines, result: GraphQlCallResult, baseUrlOfSut: String) {

        val responseVariableName = makeHttpCall(call, lines, result, baseUrlOfSut)
        handleResponseAfterTheCall(call, result, responseVariableName, lines)
    }

    override fun handleBody(call: HttpWsAction, lines: Lines) {

        /*
            TODO: when/if we are going to deal with GET, then we will need to update/refactor this code
         */

        when {
            format.isJavaOrKotlin() -> lines.add(".contentType(\"application/json\")")
            format.isJavaScript() -> lines.add(".set('Content-Type','application/json')")
           // format.isCsharp() -> lines.add("Client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue(\"application/json\"));")
        }


        val gql = call as GraphQLAction

        val body = GraphQLUtils.generateGQLBodyEntity(gql, format)
        printSendJsonBody(body!!.entity, lines)
    }

    override fun getAcceptHeader(call: HttpWsAction, res: HttpWsCallResult): String {

        val accept = openAcceptHeader()

        /**
         * GQL services typically respond using JSON
         */
        var result =  "$accept\"application/json\""
        result = closeAcceptHeader(result)
        return result
    }


    override fun handleLastStatementComment(res: HttpWsCallResult, lines: Lines){

        super.handleLastStatementComment(res, lines)

        val code = res.getStatusCode()

        /*
            if last line has already been added due to 500, no point in adding again
         */

        val gql = res as GraphQlCallResult

        if (code != 500 && gql.hasLastStatementWhenGQLError()) {
            lines.append(" // " + gql.getLastStatementWhenGQLErrors())
        }
    }

    override fun handleVerbEndpoint(baseUrlOfSut: String, _call: HttpWsAction, lines: Lines) {

        // TODO maybe in future might want to have GET for QUERY types
        val verb = "post"
        lines.add(".$verb(")

        if(config.blackBox){
            /*
                in BB, the baseUrl is actually the full endpoint
             */

            if (format.isKotlin()) {
                lines.append("\"\${$baseUrlOfSut}\"")
            } else {
                lines.append("$baseUrlOfSut")
            }
        } else {

            if (format.isKotlin()) {
                lines.append("\"\${$baseUrlOfSut}")
            } else {
                lines.append("$baseUrlOfSut + \"")
            }

           val path= infoDto.graphQLProblem?.endpoint?.removePrefix(infoDto.baseUrlOfSUT)

            lines.append("${path?.let { GeneUtils.applyEscapes(it, mode = GeneUtils.EscapeMode.NONE, format = format) }}\"")
        }

        lines.append(")")
    }

}