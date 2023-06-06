package org.evomaster.core.search.impact.impactinfocollection

import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.FitnessValue
import org.evomaster.core.search.Individual
import org.evomaster.core.search.impact.impactinfocollection.value.numeric.IntegerGeneImpact
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.evomaster.core.problem.rest.RestIndividual
import java.text.DecimalFormat

/**
 * This class is to collect impacts of structure mutation on action-based individual, e.g., remove/add action.
 * @property sizeImpact is to collect an impact regarding a size of actions contained in the individual
 * @property structures save the evaluated fitness value (value) regarding the structure (key).
 *              the structure is defined based on a sequence of action names joined with ';'.
 */
class ActionStructureImpact  (sharedImpactInfo: SharedImpactInfo, specificImpactInfo: SpecificImpactInfo,
                              val sizeImpact : IntegerGeneImpact,
                              val structures : MutableMap<String, Double>
) : GeneImpact(sharedImpactInfo, specificImpactInfo){

    constructor(
            id : String,
            sizeImpact : IntegerGeneImpact = IntegerGeneImpact("size"),
            structures : MutableMap<String, Double> = mutableMapOf()
    ) : this(
            SharedImpactInfo(id),
            SpecificImpactInfo(),
            sizeImpact, structures
    )


    companion object{
        const val ACTION_SEPARATOR = ";"
        private val logger = LoggerFactory.getLogger("test_cases")
        var newFitnessScore: Double = 0.0
    }

    /*
       a sequence of actions of an individual is used to present its structure,
           the degree of impact of the structure is evaluated as the max fitness value.
       In this case, we only identify best and worst structure.
    */
    fun updateStructure(evaluatedIndividual : EvaluatedIndividual<*>){
        val structureId = evaluatedIndividual.individual.seeActions().joinToString(ACTION_SEPARATOR){it.getName()}
        val impact = structures.getOrPut(structureId){ 0.0 }
        val fitness = evaluatedIndividual.fitness.computeFitnessScore()
        logger.info("structureId 1: ${structureId}")
        logger.info("fitnesss: ${fitness}")
        logger.info("impact: ${impact}")
        if ( fitness > impact ) structures[structureId] = fitness
    }

    fun updateStructure(individual:Individual, fitnessValue: FitnessValue){
        val structureId = individual.seeActions().joinToString(ACTION_SEPARATOR){it.getName()}
        val impact = structures.getOrPut(structureId){ 0.0 }
      //  val ff = newFitnessScore
       val fitness = fitnessValue.computeFitnessScore()
        logger.info("structureId 2: ${structureId}")
      //  logger.info("fitnesss updated: ${fitness}")
      //  structures[structureId] = ff
        if ( fitness > impact ) structures[structureId] = fitness
        logger.info("impact: ${impact} :: fitness score: $fitness , structures: ${structures[structureId]}")

    }

    fun updateFitnessScore(individual: RestIndividual, fitnessValue: FitnessValue, totalCounts: Map<String, Int>? = null): Double {
        val structureId = individual.seeActions().joinToString(ACTION_SEPARATOR){it.getName()}
       // val impact = structures.getOrPut(structureId){ 0.0 }
       // logger.info("impact @:", impact)
        logger.info("structureId updateFitnessScore: ${structureId}")
        var newFitness = 0.0
        var totalHits = 0
        if (totalCounts?.isNotEmpty() == true) {
            for ((resultType, count) in totalCounts?.entries ?: emptySet()) {
                if (resultType != "invokedRules") {
                    totalHits += count.toInt()
                }
            }
        }
        var individualSize = individual.seeActions().size
        var invokedRules = totalCounts?.get("invokedRules")
        logger.info("individual s: ${individual.seeActions().size}, fv: $fitnessValue totalCounts: $totalCounts, totalHits: $totalHits, invokedRules: $invokedRules")
        val fitness = fitnessValue.computeDefaultFitnessScore()
        logger.info("defaultFitness: ${fitness}")
        newFitness = fitnessValue.computeNewFitnessScore(fitness, individualSize, totalHits, invokedRules)
        val roundedValue = DecimalFormat("#.##").format(newFitness).toDouble()
        structures[structureId] = roundedValue
        logger.info("roundedValue @: $roundedValue")
        logger.info("structures[structureId] @: ${structures[structureId]}")
        newFitnessScore = roundedValue

        return newFitnessScore
       // if ( newFitness > impact ) structures[structureId] = newFitness
       // logger.info("fitness >:", newFitness)
    }

    fun countImpact(evaluatedIndividual : EvaluatedIndividual<*>, sizeChanged : Boolean, noImpactTargets: Set<Int>, impactTargets : Set<Int>, improvedTargets : Set<Int>, onlyManipulation : Boolean = false){
        countImpactAndPerformance(noImpactTargets = noImpactTargets, impactTargets = impactTargets, improvedTargets = improvedTargets, onlyManipulation = onlyManipulation, num = 1)
        if (sizeChanged) sizeImpact.countImpactAndPerformance(noImpactTargets = noImpactTargets, impactTargets = impactTargets, improvedTargets = improvedTargets, onlyManipulation = onlyManipulation, num = 1)
        updateStructure(evaluatedIndividual)
    }

    fun getStructures(top : Int = 1) : List<List<String>>{
        if (top > structures.size)
            throw IllegalArgumentException("$top is more than the size of existing structures")
        val sorted = structures.asSequence().sortedBy { it.value }.toList()
        return sorted.subList(0, top).map { it.key.split(ACTION_SEPARATOR)  }
    }

    fun getStructures(minimalFitness : Double) : List<List<String>>{
        return structures.filter { it.value >= minimalFitness }.keys.map { it.split(ACTION_SEPARATOR) }
    }

    override fun copy() : ActionStructureImpact{
        return ActionStructureImpact(
                shared.copy(),
                specific.copy(),
                sizeImpact = sizeImpact.copy(),
                structures = structures.map { Pair(it.key, it.value) }.toMap().toMutableMap())
    }

    override fun clone(): ActionStructureImpact {
        return ActionStructureImpact(
                shared.clone(),
                specific.clone(),
                sizeImpact = sizeImpact.clone(),
                structures = structures.map { Pair(it.key, it.value) }.toMap().toMutableMap())
    }
}