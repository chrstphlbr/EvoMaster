package org.evomaster.core.search

import org.evomaster.core.EMConfig
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.impact.*
import org.evomaster.core.search.service.mutator.MutatedGeneSpecification
import org.evomaster.core.search.tracer.TraceableElement
import org.evomaster.core.search.tracer.TraceableElementCopyFilter
import org.evomaster.core.search.tracer.TrackOperator
import org.evomaster.core.Lazy

/**
 * EvaluatedIndividual allows to tracking its evolution.
 * Note that tracking EvaluatedIndividual can be enabled by set EMConfig.enableTrackEvaluatedIndividual true.
 */
class EvaluatedIndividual<T>(val fitness: FitnessValue,
                             val individual: T,
                             /**
                              * Note: as the test execution could had been
                              * prematurely stopped, there might be less
                              * results than actions
                              */
                             val results: List<out ActionResult>,
                             trackOperator: TrackOperator? = null,
                             tracking : MutableList<EvaluatedIndividual<T>>? = null,
                             undoTracking : MutableList<EvaluatedIndividual<T>>? = null,
                             private val impactInfo : ImpactsOfIndividual ?= null
) : TraceableElement(trackOperator,  tracking, undoTracking) where T : Individual {

    companion object{
        const val ONLY_INDIVIDUAL = "ONLY_INDIVIDUAL"
        const val WITH_TRACK_WITH_CLONE_IMPACT = "WITH_TRACK_WITH_CLONE_IMPACT"
        const val WITH_TRACK_WITH_COPY_IMPACT = "WITH_TRACK_WITH_COPY_IMPACT"
    }

    /**
     * [hasImprovement] represents if [this] helps to improve Archive, e.g., reach new target.
     */
    var hasImprovement = false

    var mutatedGeneSpecification : MutatedGeneSpecification? = null

    init{
        if(individual.seeActions().size < results.size){
            throw IllegalArgumentException("Less actions than results")
        }
    }

    constructor(fitness: FitnessValue, individual: T, results: List<out ActionResult>, enableTracking: Boolean, trackOperator: TrackOperator?, enableImpact: Boolean):
            this(fitness, individual, results,
                    trackOperator = trackOperator, tracking = if (enableTracking) mutableListOf() else null, undoTracking = if (enableTracking) mutableListOf() else null,
                    impactInfo = if (enableImpact) ImpactsOfIndividual() else null
            ){
        if(enableImpact) initImpacts()
    }

    fun copy(): EvaluatedIndividual<T> {
        return EvaluatedIndividual(
                fitness.copy(),
                individual.copy() as T,
                results.map(ActionResult::copy),
                trackOperator
        )
    }

    /**
     * Note: if a test execution was prematurely stopped,
     * the number of evaluated actions would be lower than
     * the total number of actions
     */
    fun evaluatedActions() : List<EvaluatedAction>{

        val list: MutableList<EvaluatedAction> = mutableListOf()

        val actions = individual.seeActions()

        (0 until results.size).forEach { i ->
            list.add(EvaluatedAction(actions[i], results[i]))
        }

        return list
    }

    override fun copy(copyFilter: TraceableElementCopyFilter): EvaluatedIndividual<T> {
        when(copyFilter){
            TraceableElementCopyFilter.NONE -> return copy()
            TraceableElementCopyFilter.WITH_TRACK ->{
                return EvaluatedIndividual(
                        fitness.copy(),
                        individual.copy() as T,
                        results.map(ActionResult::copy),
                        trackOperator?:individual.trackOperator,
                        getTracking()?.map { it.copy() }?.toMutableList()?: mutableListOf(),
                        getUndoTracking()?.map { it.copy()}?.toMutableList()?: mutableListOf()
                ).also { current->
                    mutatedGeneSpecification?.let {
                        current.mutatedGeneSpecification = it.copyFrom(current)
                    }
                }
            }

            TraceableElementCopyFilter.DEEP_TRACK -> {
                throw IllegalArgumentException("${copyFilter.name} should be not applied for EvaluatedIndividual")
            }
            else ->{
                when {
                    copyFilter.name == ONLY_INDIVIDUAL -> return EvaluatedIndividual(
                            fitness.copy(),
                            individual.copy(TraceableElementCopyFilter.WITH_TRACK) as T,
                            results.map(ActionResult::copy),
                            trackOperator
                    )
                    copyFilter.name == WITH_TRACK_WITH_CLONE_IMPACT -> {
                        return EvaluatedIndividual(
                                fitness.copy(),
                                individual.copy(TraceableElementCopyFilter.NONE) as T,
                                results.map(ActionResult::copy),
                                trackOperator?:individual.trackOperator,
                                getTracking()?.map { it.copy() }?.toMutableList()?: mutableListOf(),
                                getUndoTracking()?.map { it.copy()}?.toMutableList()?: mutableListOf(),
                                impactInfo = impactInfo!!.clone()
                        ).also { current->
                            mutatedGeneSpecification?.let {
                                current.mutatedGeneSpecification = it.copyFrom(current)
                            }
                        }
                    }
                    copyFilter.name == WITH_TRACK_WITH_COPY_IMPACT -> {
                        return EvaluatedIndividual(
                                fitness.copy(),
                                individual.copy(TraceableElementCopyFilter.NONE) as T,
                                results.map(ActionResult::copy),
                                trackOperator?:individual.trackOperator,
                                getTracking()?.map { it.copy() }?.toMutableList()?: mutableListOf(),
                                getUndoTracking()?.map { it.copy()}?.toMutableList()?: mutableListOf(),
                                impactInfo = impactInfo!!.copy()
                        ).also { current->
                            mutatedGeneSpecification?.let {
                                current.mutatedGeneSpecification = it.copyFrom(current)
                            }
                        }
                    }
                    else -> throw IllegalStateException("${copyFilter.name} is not implemented!")
                }
            }
        }
    }

    fun getHistoryOfGene(gene: Gene, geneId : String, length : Int = -1) : List<Gene>{
        /*
        TODO if gene is not root gene
         */
        getTracking()?: throw IllegalArgumentException("tracking is not enabled")
        return getTracking()!!.flatMap { it.individual.seeGenes().find { g->ImpactUtils.generateGeneId(it.individual, g) == geneId}.run {
            if (this == null || this::class.java.simpleName  != gene::class.java.simpleName) listOf() else listOf(this)
        } }
    }

    /**
     * get latest modification with respect to the [gene]
     */
    fun getLatestGene(gene: Gene) : Gene?{
        getTracking()?: throw IllegalArgumentException("tracking is not enabled")
        if (getTracking()!!.isEmpty()) return null
        val latestInd = getTracking()!!.last().individual

        val geneId = ImpactUtils.generateGeneId(individual, gene)

        //the individual was mutated in terms of structure, the gene might be found in history
        val latest = latestInd.seeGenes().find { ImpactUtils.generateGeneId(latestInd, it) == geneId }?:return null
        return if (latest::class.java.simpleName == gene::class.java.simpleName) latest else null
    }

    fun getImpactOfGenes() : MutableMap<String, out GeneImpact>{
        if (impactInfo == null) throw IllegalStateException("this method should be invoked")
        return impactInfo.impactsOfGenes
    }

    fun getImpactOfGenes(name : String) : GeneImpact?{
        return getImpactOfGenes()[name]
    }

    private fun getImpactsOfStructure() : ActionStructureImpact{
        if (impactInfo == null) throw IllegalStateException("this method should be invoked")
        return impactInfo.impactsOfStructure
    }

    private fun getReachedTarget() : MutableMap<Int, Double>{
        if (impactInfo == null) throw IllegalStateException("this method should be invoked")
        return impactInfo.reachedTargets
    }

    fun getRelatedNotCoveredTarget() : Set<Int> = impactInfo?.reachedTargets?.filter { it.value < 1.0 && it.value > 0.0 }?.keys?: setOf()

    fun getNotRelatedNotCoveredTarget() : Set<Int> = impactInfo?.reachedTargets?.filter { it.value == 0.0 }?.keys?: setOf()

    fun updateUndoTracking(evaluatedIndividual: EvaluatedIndividual<T>, maxLength: Int){
        if (getUndoTracking()?.size?:0 == maxLength && maxLength > 0){
            getUndoTracking()?.removeAt(0)
        }
        getUndoTracking()?.add(evaluatedIndividual)
    }

    override fun next(trackOperator: TrackOperator, next: TraceableElement, copyFilter: TraceableElementCopyFilter, maxLength : Int): EvaluatedIndividual<T>? {
        if (next !is EvaluatedIndividual<*>) throw  IllegalArgumentException("the type of next is mismatched")

        when(copyFilter){
            TraceableElementCopyFilter.NONE, TraceableElementCopyFilter.DEEP_TRACK -> throw IllegalArgumentException("incorrect invocation")
            TraceableElementCopyFilter.WITH_TRACK -> {
                return EvaluatedIndividual(
                        next.fitness.copy(),
                        next.individual.copy() as T,
                        next.results.map(ActionResult::copy),
                        trackOperator,
                        tracking = (getTracking()?.plus(this)?.map { it.copy()}?.toMutableList()?: mutableListOf(this.copy())).run {
                            if (size == maxLength && maxLength > 0){
                                this.removeAt(0)
                                this
                            }else
                                this
                        },
                        undoTracking = (getUndoTracking()?.map { it.copy()}?.toMutableList()?: mutableListOf()).run {
                            if (size == maxLength && maxLength > 0){
                                this.removeAt(0)
                                this
                            }else
                                this
                        }
                ).also { current->
                    mutatedGeneSpecification?.let {
                        current.mutatedGeneSpecification = it.copyFrom(current)
                    }
                }
            }else ->{
            when {
                copyFilter.name == WITH_TRACK_WITH_CLONE_IMPACT -> return EvaluatedIndividual(
                        next.fitness.copy(),
                        next.individual.copy() as T,
                        next.results.map(ActionResult::copy),
                        trackOperator,
                        tracking = (getTracking()?.plus(this)?.map { it.copy()}?.toMutableList()?: mutableListOf(this.copy())).run {
                            if (size == maxLength && maxLength > 0){
                                this.removeAt(0)
                                this
                            }else
                                this
                        },
                        undoTracking = (getUndoTracking()?.map { it.copy()}?.toMutableList()?: mutableListOf()).run {
                            if (size == maxLength && maxLength > 0){
                                this.removeAt(0 )
                                this
                            }else
                                this
                        },
                        impactInfo = impactInfo!!.clone()
                ).also { current->
                    mutatedGeneSpecification?.let {
                        current.mutatedGeneSpecification = it.copyFrom(current)
                    }
                }
                copyFilter.name == WITH_TRACK_WITH_COPY_IMPACT -> return EvaluatedIndividual(
                        next.fitness.copy(),
                        next.individual.copy() as T,
                        next.results.map(ActionResult::copy),
                        trackOperator,
                        tracking = (getTracking()?.plus(this)?.map { it.copy()}?.toMutableList()?: mutableListOf(this.copy())).run {
                            if (size == maxLength && maxLength > 0){
                                this.removeAt(0)
                                this
                            }else
                                this
                        },
                        undoTracking = (getUndoTracking()?.map { it.copy()}?.toMutableList()?: mutableListOf()).run {
                            if (size == maxLength && maxLength > 0){
                                this.removeAt(0 )
                                this
                            }else
                                this
                        },
                        impactInfo = impactInfo!!.copy()
                ).also { current->
                    mutatedGeneSpecification?.let {
                        current.mutatedGeneSpecification = it.copyFrom(current)
                    }
                }
                copyFilter.name == ONLY_INDIVIDUAL -> IllegalArgumentException("incorrect invocation")
            }

                throw IllegalStateException("${copyFilter.name} is not implemented!")
            }
        }
    }

    override fun getUndoTracking(): MutableList<EvaluatedIndividual<T>>? {
        if(super.getUndoTracking() == null) return null
        return super.getUndoTracking() as MutableList<EvaluatedIndividual<T>>
    }

    private fun initImpacts(){
        getTracking()?.apply {
            assert(size == 0)
        }

        updateActionGenes(individual)
        updateDbActionGenes(individual, individual.seeGenes(Individual.GeneFilter.ONLY_SQL))

        impactInfo!!.impactsOfStructure.updateStructure(this)

        fitness.getViewOfData().forEach { (t, u) ->
            getReachedTarget()[t] = u.distance
        }
    }

    fun <T:Individual> updateDbActionGenes(ind: T, genes: List<Gene>){
        genes.filter { it.isMutable() }.forEach {
            val id = ImpactUtils.generateGeneId(ind, it)
            impactInfo!!.impactsOfGenes.putIfAbsent(id, ImpactUtils.createGeneImpact(it, id))
        }
    }

    private fun updateActionGenes(ind: T){
        if (ind.seeActions().isNotEmpty()){
            ind.seeActions().forEach { a->
                a.seeGenes().filter { it.isMutable() }.forEach { g->
                    val id = ImpactUtils.generateGeneId(a, g)
                    impactInfo!!.impactsOfGenes.putIfAbsent(id, ImpactUtils.createGeneImpact(g, id))
                }
            }
        }else{
            ind.seeGenes().filter { it.isMutable() }.forEach { g->
                val id = ImpactUtils.generateGeneId(ind, g)
                impactInfo!!.impactsOfGenes.putIfAbsent(id, ImpactUtils.createGeneImpact(g, id))
            }
        }
    }

    /**
     * compare current with latest
     * [inTrack] indicates how to find the latest two elements to compare.
     * For instance, if the latest modification does not improve the fitness, it will be saved in [undoTracking].
     * in this case, the latest is the last of [undoTracking], not [this]
     */
    fun updateImpactOfGenes(
            inTrack : Boolean,
            mutatedGenes : MutatedGeneSpecification,
            notCoveredTargets : Set<Int>,
            impactTargets : MutableSet<Int>,
            improvedTargets: MutableSet<Int>,
            strategy : EMConfig.SecondaryObjectiveStrategy,
            bloatControl: Boolean){
        Lazy.assert{mutatedGenes.mutatedIndividual != null}
        Lazy.assert{getTracking() != null}
        Lazy.assert{getUndoTracking() != null}

        if(inTrack) Lazy.assert{getTracking()!!.isNotEmpty()}
        else Lazy.assert{getUndoTracking()!!.isNotEmpty()}

        val previous = if(inTrack) getTracking()!!.last() else this
        val next = if(inTrack) this else getUndoTracking()!!.last()

//        val improvedTargets = mutableSetOf<Int>()
//        val impactTargets = mutableSetOf<Int>()

        updateReachedTargets(fitness)

//        next.fitness.isDifferent(previous.fitness, notCoveredTargets, improved = improvedTargets, different = impactTargets, strategy = strategy, bloatControlForSecondaryObjective = bloatControl)
        compareWithLatest(next, previous, improvedTargets, impactTargets, mutatedGenes)
    }



    private fun compareWithLatest(next : EvaluatedIndividual<T>, previous : EvaluatedIndividual<T>, improvedTargets : Set<Int>, impactTargets: Set<Int>, mutatedGenes: MutatedGeneSpecification){
        /**
         * genes of individual might be added with additionalInfoList
         */
        updateDbActionGenes(next.individual, next.individual.seeGenes(Individual.GeneFilter.ONLY_SQL))
        updateActionGenes(next.individual)

        val noImpactTargets = next.fitness.getViewOfData().keys.filterNot { impactTargets.contains(it) }.toSet()

        if (mutatedGenes.mutatedGenes.isEmpty() && mutatedGenes.mutatedDbGenes.isEmpty()){ // structure mutated
            val sizeChanged = (next.individual.seeActions().size != previous.individual.seeActions().size)
            /*
             TODO if required position/sequence sensitive analysis
             */
            getImpactsOfStructure().countImpact(next, sizeChanged, noImpactTargets= noImpactTargets, impactTargets = impactTargets, improvedTargets = improvedTargets)

            /*
             TODO MAN: shall we update impacts of genes regarding deletion of genes?
             */

            return
        }
        if (mutatedGenes.addedInitializationGenes.isNotEmpty()) return

        /*
        NOTE THAT if applying 1/n, a number of mutated genes may be more than 1 (e.g., n = 2).
        This might have side effects to impact analysis, so we only collect no impact info and ignore to collect impacts info.
        But times of manipulation should be updated.
         */
        val onlyManipulation = false//((mutatedGenes.mutatedGenes.size + mutatedGenes.mutatedDbGenes.size) > 1) && impactTargets.isNotEmpty()

        val mutatedGenesWithContext = ImpactUtils.extractMutatedGeneWithContext(mutatedGenes.mutatedGenes, mutatedGenes.mutatedIndividual!!, previousIndividual = previous.individual)

        mutatedGenesWithContext.forEach { (t, u) ->
            val impact = getImpactOfGenes().getValue(t)

            u.forEach { gc ->
                impact.countImpactWithMutatedGeneWithContext(gc, noImpactTargets = noImpactTargets, impactTargets = impactTargets, improvedTargets = improvedTargets, onlyManipulation = onlyManipulation)
            }
        }

        val mutatedDBGenesWithContext = ImpactUtils.extractMutatedDbGeneWithContext(mutatedGenes.mutatedDbGenes, mutatedGenes.mutatedIndividual!!, previousIndividual = previous.individual)
        mutatedDBGenesWithContext.forEach { (t, u) ->
            val impact = getImpactOfGenes().getValue(t)

            u.forEach { gc ->
                impact.countImpactWithMutatedGeneWithContext(gc, noImpactTargets = noImpactTargets, impactTargets = impactTargets, improvedTargets = improvedTargets, onlyManipulation = onlyManipulation)
            }
        }
    }

    fun findGeneById(id : String, index : Int = -1, isDb: Boolean=false) : Gene?{
        if (!isDb){
            if (index == -1) return individual.seeGenes().find { ImpactUtils.generateGeneId(individual, it) == id }
            if (index > individual.seeActions().size)
                throw IllegalArgumentException("index $index is out of boundary of actions ${individual.seeActions().size} of the individual")
            return individual.seeActions()[index].seeGenes().find { ImpactUtils.generateGeneId(individual, it) == id }
        }
        if (index == -1) return individual.seeInitializingActions().flatMap { it.seeGenes() }.find { ImpactUtils.generateGeneId(individual, it) == id }
        if (index >= individual.seeInitializingActions().size) return null
            //throw IllegalArgumentException("index $index is out of boundary of initializing actions ${individual.seeInitializingActions().size} of the individual")
        return individual.seeInitializingActions()[index].seeGenes().find { ImpactUtils.generateGeneId(individual, it) == id }
    }

    /**
     * update fitness archived by [this]
     * return whether [fitness] archive new targets or improve distance
     */
    private fun updateReachedTargets(fitness: FitnessValue) : List<Int>{
        val difference = mutableListOf<Int>()
        fitness.getViewOfData().forEach { (t, u) ->
            var previous = getReachedTarget()[t]
            if(previous == null){
                difference.add(t)
                previous = 0.0
                getReachedTarget()[t] = previous
            }else{
                if(u.distance > previous){
                    difference.add(t)
                    getReachedTarget()[t] = u.distance
                }
            }
        }
        return difference
    }

    override fun getTracking(): List<EvaluatedIndividual<T>>? {
        val tacking = super.getTracking()?:return null
        if(tacking.all { it is EvaluatedIndividual<*> })
            return tacking as List<EvaluatedIndividual<T>>
        else
            throw IllegalArgumentException("tracking has elements with mismatched type")
    }
}