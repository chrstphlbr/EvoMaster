package org.evomaster.core.problem.rest.service

import org.evomaster.client.java.controller.api.dto.AdditionalInfoDto
import org.evomaster.core.problem.httpws.service.HttpWsCallResult
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.RestCallResult
import org.evomaster.core.problem.rest.RestIndividual
import org.evomaster.core.search.ActionResult
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.FitnessValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.ws.rs.core.NewCookie
import java.io.BufferedReader
import java.io.InputStreamReader
import com.google.gson.Gson

class BlackBoxRestFitness : RestFitness() {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BlackBoxRestFitness::class.java)
        private val logger = LoggerFactory.getLogger("test_cases")

    }

    fun predictStatusCode(individual: RestIndividual, individualIndex: Int): RestIndividual {
        logger.info("Size: ${individual.seeActions().size}")
        logger.info("individualIndex: ${individualIndex}")
        for (i in individual.seeActions().size - 1 downTo 0) {
            val action = individual.seeActions()[i]
            logger.info("index: , ${i}, action: ${action}")
            val actionObject = action.getFormattedParameters().map { it.toString() }
            val actionObjectJson = "'${actionObject.joinToString(",")}'"
            val scriptDirectory = "~/Project/GURI-GitHub/ait4cr-rest-test-experiments/"
            val processBuilder = ProcessBuilder(
                "/bin/bash",
                "-c",
                "source ~/.bash_profile && python " + scriptDirectory + "single-prediction.py " + actionObjectJson + " '" + individualIndex + "'" + " '" + i + "'" + " '" + action + "'"
            )
            processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
            processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            val process = processBuilder.start()

            val exitCode = process.waitFor()

            if (exitCode == 0) {

                // Process the output returned by the Python script
                val output1 = process.inputStream.bufferedReader().use(BufferedReader::readText)
            //    logger.info("output1: $output1")
                val output2 = process.errorStream.bufferedReader().use(BufferedReader::readText)
            //    logger.info("output2: $output2")
                val predictionValue = output1.substringAfterLast("[")
                    .substringBeforeLast("]")
                    .trim()
                    .toInt()

                logger.info("Prediction: $predictionValue")

                if (predictionValue == 0) {
                    individual.removeResourceCall(i)
                }
            }  else {
                // Handle the case when the process exits with a non-zero exit code
                logger.error("Error executing Python script. Exit code: $exitCode")
                val errorMessage = process.errorStream.bufferedReader().use(BufferedReader::readText)
                logger.error("Error Message: $errorMessage")
            }

        }

        return individual
    }

    override fun doCalculateCoverage(individual: RestIndividual, targets: Set<Int>): EvaluatedIndividual<RestIndividual>? {

        var individual = individual
        var individualIndex = time.evaluatedIndividuals // Start with the current evaluatedIndividuals value

        individual = predictStatusCode(individual, individualIndex)
        logger.info("Size: ${individual.seeActions().size}")
        val cookies = mutableMapOf<String, List<NewCookie>>()
        val tokens = mutableMapOf<String, String>()
        if(config.bbExperiments){
            /*
                If we have a controller, we MUST reset the SUT at each test execution.
                This is to avoid memory leaks in the dat structures used by EM.
                Eg, this was a huge problem for features-service with AdditionalInfo having a
                memory leak
             */
            rc.resetSUT()

            /*
                currently, for bb, the auth can be only configured with the driver,
                ie, bbExperiments is enabled.
                TODO might support other manner to configure auth for bb
             */
            cookies.putAll(getCookies(individual))
            tokens.putAll(getTokens(individual))
        }

        val fv = FitnessValue(individual.size().toDouble())

        val actionResults: MutableList<ActionResult> = mutableListOf()

        //used for things like chaining "location" paths
        val chainState = mutableMapOf<String, String>()
        val scriptDirectory = "~/Project/GURI-GitHub/ait4cr-rest-test-experiments/"

        //run the test, one action at a time
        for (i in 0 until individual.seeActions().size) {

            val a = individual.seeActions()[i]

            var ok = false

            if (a is RestCallAction) {

                    //    if (output1.trim() == "0") {
                    //        individual.removeResourceCall(i)
                    //    }

                        ok = handleRestCall(a, actionResults, chainState, cookies, tokens)
                        actionResults[i].stopping = !ok

                } else {
                    throw IllegalStateException("Cannot handle: ${a.javaClass}")
                }


        //    }

            if (!ok) {
                break
            }
        }

       // val updatedActions = individual.seeActions().filterIndexed { index, _ -> !skippedActionIndexes.contains(index) }

        handleResponseTargets(fv, individual.seeActions(), actionResults, listOf())

        return EvaluatedIndividual(fv, individual as RestIndividual, actionResults, trackOperator = individual.trackOperator, index = time.evaluatedIndividuals, config = config)
    }

    override fun getlocation5xx(status: Int, additionalInfoList: List<AdditionalInfoDto>, indexOfAction: Int, result: HttpWsCallResult, name: String): String? {
        /*
            In Black-Box testing, there is no info from the source/bytecode
         */
        return null
    }
}