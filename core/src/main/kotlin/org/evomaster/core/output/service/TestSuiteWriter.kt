package org.evomaster.core.output.service

import com.google.inject.Inject
import org.evomaster.client.java.controller.api.dto.database.operations.InsertionDto
import org.evomaster.core.EMConfig
import org.evomaster.core.output.*
import org.evomaster.core.search.Solution
import org.evomaster.core.search.service.SearchTimeController
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZonedDateTime


/**
 * Given a Solution as input, convert it to a string representation of
 * the tests that can be written to file and be compiled
 */
class TestSuiteWriter {

    @Inject
    private lateinit var config: EMConfig

    @Inject
    private lateinit var searchTimeController: SearchTimeController

    companion object {
        private const val controller = "controller"
        private const val baseUrlOfSut = "baseUrlOfSut"
        private const val activeExpectations = "activeExpectations"
    }

    fun writeTests(
            solution: Solution<*>,
            controllerName: String?
    ) {

        val name = TestSuiteFileName(config.testSuiteFileName)

        val content = convertToCompilableTestCode(solution, name, controllerName)
        saveToDisk(content, config, name)

        /*if (config.expectationsActive || config.enableBasicAssertions){
            val numberMatcher = addAdditionalNumberMatcher(name)
            if (name.hasPackage() && config.outputFormat.isJavaOrKotlin()) {
                saveToDisk(numberMatcher, config, TestSuiteFileName("${name.getPackage()}.NumberMatcher"))
            }
            else{
                saveToDisk(numberMatcher, config, TestSuiteFileName("NumberMatcher"))
            }
        }*/

    }


    private fun convertToCompilableTestCode(
            solution: Solution<*>,
            testSuiteFileName: TestSuiteFileName,
            controllerName: String?
            )
            : String {

        val lines = Lines()
        val testSuiteOrganizer = TestSuiteOrganizer()

        header(solution, testSuiteFileName, lines)

        lines.indented {

            beforeAfterMethods(controllerName, lines)

            val tests = testSuiteOrganizer.sortTests(solution, config.customNaming)

            for (test in tests) {
                lines.addEmpty(2)

                val testLines = TestCaseWriter()
                        .convertToCompilableTestCode(config, test, baseUrlOfSut)
                lines.add(testLines)
            }
        }

        footer(lines)

        return lines.toString()
    }


    private fun saveToDisk(testFileContent: String,
                           config: EMConfig,
                           testSuiteFileName: TestSuiteFileName) {

        val path = Paths.get(config.outputFolder, testSuiteFileName.getAsPath(config.outputFormat))

        Files.createDirectories(path.parent)
        Files.deleteIfExists(path)
        Files.createFile(path)

        path.toFile().appendText(testFileContent)
    }

    private fun classDescriptionComment(solution: Solution<*>, lines: Lines) {
        lines.add("/**")
        lines.add(" * This file was automatically generated by EvoMaster on ${ZonedDateTime.now()}")
        lines.add(" * <br>")
        lines.add(" * The generated test suite contains ${solution.individuals.size} tests")
        lines.add(" * <br>")
        lines.add(" * Covered targets: ${solution.overall.coveredTargets()}")
        lines.add(" * <br>")
        lines.add(" * Used time: ${searchTimeController.getElapsedTime()}")
        lines.add(" * <br>")
        lines.add(" * Needed budget for current results: ${searchTimeController.neededBudget()}")
        lines.add(" */")
    }

    private fun header(solution: Solution<*>,
                       name: TestSuiteFileName,
                       lines: Lines) {

        val format = config.outputFormat

        if (name.hasPackage() && format.isJavaOrKotlin()) {
            addStatement("package ${name.getPackage()}", lines)
        }

        lines.addEmpty(2)

        if (format.isJUnit5()) {
            addImport("org.junit.jupiter.api.AfterAll", lines)
            addImport("org.junit.jupiter.api.BeforeAll", lines)
            addImport("org.junit.jupiter.api.BeforeEach", lines)
            addImport("org.junit.jupiter.api.Test", lines)
            addImport("org.junit.jupiter.api.Assertions.*", lines, true)
        }
        if (format.isJUnit4()) {
            addImport("org.junit.AfterClass", lines)
            addImport("org.junit.BeforeClass", lines)
            addImport("org.junit.Before", lines)
            addImport("org.junit.Test", lines)
            addImport("org.junit.Assert.*", lines, true)
        }

        //TODO check if those are used
        addImport("io.restassured.RestAssured", lines)
        addImport("io.restassured.RestAssured.given", lines, true)
        addImport("org.evomaster.client.java.controller.api.EMTestUtils.*", lines, true)
        addImport("org.evomaster.client.java.controller.SutHandler", lines)
        addImport("org.evomaster.client.java.controller.db.dsl.SqlDsl.sql", lines, true)
        addImport(InsertionDto::class.qualifiedName!!, lines)
        addImport("java.util.List", lines)
        // TODO: BMR - this is temporarily added as WiP. Should we have a more targeted import (i.e. not import everything?)
        if(config.enableBasicAssertions){
            addImport("org.hamcrest.Matchers.*", lines, true)
            //addImport("org.hamcrest.core.AnyOf.anyOf", lines, true)
            addImport("io.restassured.config.JsonConfig", lines)
            addImport("io.restassured.path.json.config.JsonPathConfig", lines)
            addImport("org.evomaster.client.java.controller.contentMatchers.NumberMatcher.*", lines, true)
        }

        if(config.expectationsActive) {
            addImport("org.evomaster.client.java.controller.expect.ExpectationHandler.expectationHandler", lines, true)
        }
        //addImport("static org.hamcrest.core.Is.is", lines, format)

        lines.addEmpty(2)

        /*if(config.enableBasicAssertions && config.outputFormat.isJava()){
            addAdditionalNumberMatcher(lines)
        }*/

        lines.addEmpty(2)

        classDescriptionComment(solution, lines)

        if (format.isJavaOrKotlin()) {
            defineClass(name, lines)
            lines.addEmpty()
        }
    }

    private fun staticVariables(controllerName: String?, lines: Lines){

        if(config.outputFormat.isJava()) {
            if(! config.blackBox) {
                lines.add("private static final SutHandler $controller = new $controllerName();")
                lines.add("private static String $baseUrlOfSut;")
            } else {
                lines.add("private static String $baseUrlOfSut = \"${config.bbTargetUrl}\";")
            }

            if(config.expectationsActive){
                lines.add("private static boolean activeExpectations = false;")
            }

        } else if(config.outputFormat.isKotlin()) {
            if(! config.blackBox) {
                lines.add("private val $controller : SutHandler = $controllerName()")
                lines.add("private lateinit var $baseUrlOfSut: String")
            } else {
                lines.add("private val $baseUrlOfSut = \"${config.bbTargetUrl}\"")
            }

            if(config.expectationsActive){
                lines.add("private val $activeExpectations = false")
            }
        }
        //Note: ${config.expectationsActive} can be used to get the active setting, but the default
        // for generated code should be false.
    }

    private fun initClassMethod(lines: Lines){

        val format = config.outputFormat

        when {
            format.isJUnit4() -> lines.add("@BeforeClass")
            format.isJUnit5() -> lines.add("@BeforeAll")
        }
        if(format.isJava()) {
            lines.add("public static void initClass()")
        } else if(format.isKotlin()){
            lines.add("@JvmStatic")
            lines.add("fun initClass()")
        }

        lines.block {
            if(! config.blackBox) {
                addStatement("baseUrlOfSut = $controller.startSut()", lines)
                addStatement("assertNotNull(baseUrlOfSut)", lines)
            }

            addStatement("RestAssured.urlEncodingEnabled = false", lines)

            if (config.enableBasicAssertions){
                addStatement("RestAssured.config = RestAssured.config().jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.DOUBLE))", lines)
            }
        }
    }

    private fun tearDownMethod(lines: Lines){

        if(config.blackBox) {
           return
        }

        val format = config.outputFormat

        when {
            format.isJUnit4() -> lines.add("@AfterClass")
            format.isJUnit5() -> lines.add("@AfterAll")
        }
        if(format.isJava()) {
            lines.add("public static void tearDown()")
        } else if(format.isKotlin()){
            lines.add("@JvmStatic")
            lines.add("fun tearDown()")
        }
        lines.block {
            addStatement("$controller.stopSut()", lines)
        }
    }

    private fun initTestMethod(lines: Lines){

        if(config.blackBox) {
            return
        }

        val format = config.outputFormat

        when {
            format.isJUnit4() -> lines.add("@Before")
            format.isJUnit5() -> lines.add("@BeforeEach")
        }
        if(format.isJava()) {
            lines.add("public void initTest()")
        } else if(format.isKotlin()){
            lines.add("fun initTest()")
        }
        lines.block {
            addStatement("$controller.resetStateOfSUT()", lines)
        }
    }

    private fun beforeAfterMethods(controllerName: String?, lines: Lines) {

        lines.addEmpty()

        val staticInit = {
            staticVariables(controllerName, lines)
            lines.addEmpty(2)

            initClassMethod(lines)
            lines.addEmpty(2)

            tearDownMethod(lines)
        }

        if(config.outputFormat.isKotlin()){
            lines.add("companion object")
            lines.block(1, staticInit)
        } else {
            staticInit.invoke()
        }
        lines.addEmpty(2)

        initTestMethod(lines)
        lines.addEmpty(2)
    }


    private fun footer(lines: Lines) {
        lines.addEmpty(2)
        lines.add("}")
    }

    private fun defineClass(name: TestSuiteFileName, lines: Lines) {

        lines.addEmpty()

        val format = config.outputFormat

        when {
            format.isJava() -> lines.append("public ")
            format.isKotlin() -> lines.append("internal ")
        }

        lines.append("class ${name.getClassName()} {")
    }

    private fun addImport(klass: String, lines: Lines, static: Boolean = false) {

        //Kotlin for example does not use "static" in the imports
        val s = if(static && config.outputFormat.isJava()) "static" else ""

        addStatement("import $s $klass", lines)
    }

    private fun addStatement(statement: String, lines: Lines){
        lines.add(statement)
        appendSemicolon(lines)
    }

    private fun appendSemicolon(lines: Lines) {
        if (config.outputFormat.isJava()) {
            lines.append(";")
        }
    }
}