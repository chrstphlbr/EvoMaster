package org.evomaster.core.problem.rest.resource

import org.evomaster.core.Lazy
import org.evomaster.core.database.DbAction
import org.evomaster.core.problem.rest.*
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.resource.dependency.*
import org.evomaster.core.problem.util.ParamUtil
import org.evomaster.core.problem.rest.util.ParserUtil
import org.evomaster.core.problem.util.RestResourceTemplateHandler
import org.evomaster.core.problem.util.BindingBuilder
import org.evomaster.core.search.Action
import org.evomaster.core.search.ActionFilter
import org.evomaster.core.search.ActionResult
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.ObjectGene
import org.evomaster.core.search.service.Randomness
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @property path resource path
 * @property actions actions under the resource, with references of tables
 * @property initMode configurable option to init resource with additional info, e.g., related tables
 */
class RestResourceNode(
        val path : RestPath,
        val actions: MutableList<RestCallAction> = mutableListOf(),
        val initMode : InitMode,
        val employNLP : Boolean
) {

    companion object {
        private const val PROB_EXTRA_PATCH = 0.8
        val log: Logger = LoggerFactory.getLogger(RestResourceNode::class.java)
    }

    /**
     * key is original text of the token
     * value is PathRToken which contains more analysis info about the original text
     */
    private val tokens : MutableMap<String, PathRToken> = mutableMapOf()

    /**
     * segments of a path
     * since a token may be a combined word, the word can be separator by processing text analysis,
     * the [segments] can be a flatten list of words for the path (at index 1) or a list of original tokens (at index 0).
     */
    private val segments : MutableList<List<String>> = mutableListOf()

    init {
        when(initMode){
            InitMode.WITH_TOKEN, InitMode.WITH_DERIVED_DEPENDENCY, InitMode.WITH_DEPENDENCY ->{
                if(path.getNonParameterTokens().isNotEmpty()){
                    tokens.clear()
                    ParserUtil.parsePathTokens(this.path, tokens, employNLP && initMode != InitMode.WITH_DEPENDENCY)
                }
                initSegments()
            }
            else ->{
                //do nothing
            }
        }
    }

    /**
     * [ancestors] is ordered, first is closest ancestor, and last is deepest one.
     */
    private val ancestors : MutableList<RestResourceNode> = mutableListOf()


    /**
     * possible solutions to prepare resources
     */
    private val creations : MutableList<CreationChain> = mutableListOf()

    /**
     * key is id of param which is [getLastTokensOfPath] + [Param.name]
     * value is detailed info [ParamInfo] including
     *          e.g., whether the param is required to be bound with existing resource (i.e., POST action or table),
     */
    val paramsInfo : MutableMap<String, ParamInfo> = mutableMapOf()


    /**
     * collect related tables
     */
    val resourceToTable : ResourceRelatedToTable = ResourceRelatedToTable(path.toString())

    /**
     * HTTP methods under the resource, including possible POST in its ancestors'
     * last means if there are post actions in its ancestors
     */
    private val verbs : Array<Boolean> = Array(RestResourceTemplateHandler.getSizeOfHandledVerb() + 1){false}

    /**
     * key is template with string format
     * value is template info
     */
    private val templates : MutableMap<String, CallsTemplate> = mutableMapOf()

    /**
     * In REST, params of the action might be modified, e.g., for WebRequest
     * In this case, we modify the [actions] with updated action with new params if there exist,
     * and backup its original form with [originalActions]
     */
    private val originalActions : MutableList<RestCallAction> = mutableListOf()

    /**
     * this init occurs after actions and ancestors are set up
     */
    fun init(){
        initVerbs()
        initCreationPoints()
        when(initMode){
            InitMode.WITH_TOKEN, InitMode.WITH_DERIVED_DEPENDENCY, InitMode.WITH_DEPENDENCY -> initParamInfo()
            else -> { }
        }
    }

    /**
     * init ancestors of [this] resource node
     */
    fun initAncestors(resources : List<RestResourceNode>){
        resources.forEach {r ->
            if(!r.path.isEquivalent(this.path) && r.path.isAncestorOf(this.path))
                ancestors.add(r)
        }
    }

    /**
     * @return resource node based on [path]
     */
    fun getResourceNode(path: RestPath) : RestResourceNode?{
        if (path.toString() == path.toString()) return this
        return ancestors.find { it.path.toString() == path.toString() }
    }


    /**
     * @return mutable genes in [dbactions] and they do not bind with rest actions.
     */
    fun getMutableSQLGenes(dbactions: MutableList<DbAction>, template: String, is2POST : Boolean) : List<out Gene>{

        val related = getPossiblyBoundParams(template, is2POST).map {
            resourceToTable.paramToTable[it.key]
        }

        return dbactions.filterNot { it.representExistingData }.flatMap { db->
            val exclude = related.flatMap { r-> r?.getRelatedColumn(db.table.name)?.toList()?:listOf() }
            db.seeGenesForInsertion(exclude)
        }.filter(Gene::isMutable)
    }

    /**
     * @return mutable genes in [actions] which perform action on current [this] resource node
     *          with [callsTemplate] template, e.g., POST-GET
     */
    private fun getMutableRestGenes(actions: List<RestCallAction>, template: String) : List<out Gene>{

        if (!RestResourceTemplateHandler.isNotSingleAction(template)) return actions.flatMap(RestCallAction::seeGenes).filter(Gene::isMutable)

        val missing = getPossiblyBoundParams(template, false)
        val params = mutableListOf<Param>()
        (actions.indices).forEach { i ->
            val a = actions[i]
            if (i != actions.size-1 && (i == 0 || a.verb == HttpVerb.POST)) {
                params.addAll(a.parameters)
            } else{
                //add the parameters which does not bind with POST if exist
                params.addAll(a.parameters.filter { p->
                    missing.none { m->
                        m.key == getParamId(a.parameters, p)
                    }
                })
            }
        }
        return params.flatMap(Param::seeGenes).filter(Gene::isMutable)
    }

    private fun initVerbs(){
        actions.forEach { a->
            RestResourceTemplateHandler.getIndexOfHttpVerb(a.verb).let {
                if(it == -1)
                    throw IllegalArgumentException("cannot handle the action with ${a.verb}")
                else
                    verbs[it] = true
            }
        }
        verbs[verbs.size - 1] = verbs[RestResourceTemplateHandler.getIndexOfHttpVerb(HttpVerb.POST)]
        if (!verbs[verbs.size - 1]){
            if(ancestors.isNotEmpty())
                verbs[verbs.size - 1] = ancestors.any { a -> a.actions.any { ia->  ia.verb == HttpVerb.POST } }
        }

        RestResourceTemplateHandler.initSampleSpaceOnlyPOST(verbs, templates)

        assert(templates.isNotEmpty())

    }

    //if only get
    fun isIndependent() : Boolean{
        return templates.all { it.value.independent } && (creations.none { c->c.isComplete() } || resourceToTable.paramToTable.isEmpty())
    }

    // if only post, the resource does not contain any independent action
    fun hasIndependentAction() : Boolean{
        return (1 until (verbs.size - 1)).find { verbs[it]} != null
    }

    /************************** creation manage*********************************/

    fun getSqlCreationPoints() : List<String>{
        if (resourceToTable.confirmedSet.isNotEmpty()) return resourceToTable.confirmedSet.keys.toList()
        return resourceToTable.derivedMap.keys.toList()
    }

    /**
     * @return whether there exist POST action (either from [this] node or its [ancestors]) to create the resource
     */
    fun hasPostCreation() = creations.any { it is PostCreationChain && it.actions.isNotEmpty() } || verbs.first()

    private fun initCreationPoints(){

        val postCreation = PostCreationChain(mutableListOf())
        val posts = actions.filter { it.verb == HttpVerb.POST}
        val post : RestCallAction? = when {
            posts.isEmpty() -> {
                chooseClosestAncestor(path, listOf(HttpVerb.POST))
            }
            posts.size == 1 -> {
                posts[0]
            }
            else -> null
        }

        if(post != null){
            postCreation.actions.add(0, post)
            if ((post).path.hasVariablePathParameters() &&
                    (!post.path.isLastElementAParameter()) ||
                    post.path.getVariableNames().size >= 2) {
                nextCreationPoints(post.path, postCreation)
            }else
                postCreation.confirmComplete()
        }else{
            if(path.hasVariablePathParameters()) {
                postCreation.confirmIncomplete(path.toString())
            }else
                postCreation.confirmComplete()
        }

        creations.add(postCreation)
    }

    private fun nextCreationPoints(path:RestPath, postCreationChain: PostCreationChain){
        val post = chooseClosestAncestor(path, listOf(HttpVerb.POST))
        if(post != null){
            postCreationChain.actions.add(0, post)
            if (post.path.hasVariablePathParameters() &&
                    (!post.path.isLastElementAParameter()) ||
                    post.path.getVariableNames().size >= 2) {
                nextCreationPoints(post.path, postCreationChain)
            }else
                postCreationChain.confirmComplete()
        }else{
            postCreationChain.confirmIncomplete(path.toString())
        }
    }

    private fun checkDifferenceOrInit(dbactions : MutableList<DbAction> = mutableListOf(), postactions: MutableList<RestCallAction> = mutableListOf()) : Pair<Boolean, CreationChain>{
        when{
            dbactions.isNotEmpty() && postactions.isNotEmpty() ->{
                creations.find { it is CompositeCreationChain }?.let {
                    return Pair(
                            (it as CompositeCreationChain).actions.map { a-> if(a is DbAction) a.table.name else if (a is RestCallAction) a.getName() else ""}.toHashSet()
                                    == dbactions.map { a-> a.table.name}.plus(postactions.map { p->p.getName() }).toHashSet(),
                            it
                    )
                }
                val composite = CompositeCreationChain(dbactions.plus(postactions).toMutableList()).also {
                    creations.add(it)
                }
                return Pair(true, composite)
            }
            dbactions.isNotEmpty() && postactions.isEmpty() ->{
                creations.find { it is DBCreationChain }?.let {
                    return Pair(
                            (it as DBCreationChain).actions.map { a-> a.table.name }.toHashSet() == dbactions.map { a-> a.table.name}.toHashSet(),
                            it
                    )
                }
                val db = DBCreationChain(dbactions).also {
                    creations.add(it)
                }
                return Pair(true, db)
            }
            dbactions.isEmpty() && postactions.isNotEmpty() ->{
                creations.find { it is PostCreationChain }?.let {
                    return Pair(
                            (it as PostCreationChain).actions.map { a-> a.getName() }.toHashSet() == postactions.map { a-> a.getName()}.toHashSet(),
                            it
                    )
                }
                val post = PostCreationChain(postactions).also {
                    creations.add(it)
                }
                return Pair(true, post)
            }
            else->{
                throw IllegalArgumentException("cannot manipulate creations with the inputs")
            }
        }
    }

    private fun getCreation(predicate: (CreationChain) -> Boolean) : CreationChain?{
        return creations.find(predicate)
    }

    fun getPostChain() : PostCreationChain?{
        return getCreation { creationChain : CreationChain -> (creationChain is PostCreationChain) }?.run {
            this as PostCreationChain
        }
    }

    /***********************************************************/


    fun updateTemplateSize(){
        getCreation { creationChain : CreationChain-> creationChain is PostCreationChain  }?.let {c->
            val dif = (c as PostCreationChain).actions.size - (if(verbs[RestResourceTemplateHandler.getIndexOfHttpVerb(HttpVerb.POST)]) 1 else 0)
            templates.values.filter { it.template.contains("POST") }.forEach { u ->
                if(!u.sizeAssured){
                    u.size += dif
                    u.sizeAssured = true
                }
            }
        }
    }

    fun generateAnother(calls : RestResourceCalls, randomness: Randomness, maxTestSize: Int) : RestResourceCalls?{
        val current = calls.template?.template?: RestResourceTemplateHandler.getStringTemplateByActions(calls.seeActions(ActionFilter.NO_SQL).filterIsInstance<RestCallAction>())
        val rest = templates.filter { it.value.template != current}
        if(rest.isEmpty()) return null
        val selected = randomness.choose(rest.keys)
        return createRestResourceCall(selected,randomness, maxTestSize)

    }

    fun numOfDepTemplate() : Int{
        return templates.values.count { !it.independent }
    }

    fun numOfTemplates() : Int{
        return templates.size
    }

    private fun randomizeActionGenes(action: Action, randomness: Randomness) {
        //action.seeGenes().forEach { it.randomize(randomness, false) }
        action.randomize(randomness, false)
        if(action is RestCallAction){
            BindingBuilder.bindParamsInRestAction(action)
        }
    }

    fun randomRestResourceCalls(randomness: Randomness, maxTestSize: Int): RestResourceCalls{
        val randomTemplates = templates.filter { e->
            e.value.size in 1..maxTestSize
        }.map { it.key }
        if(randomTemplates.isEmpty()) return sampleOneAction(null, randomness)
        return createRestResourceCall(randomness.choose(randomTemplates), randomness, maxTestSize)
    }

    fun sampleIndResourceCall(randomness: Randomness, maxTestSize: Int) : RestResourceCalls{
        selectTemplate({ call : CallsTemplate -> call.independent || (call.template == HttpVerb.POST.toString() && call.size > 1)}, randomness)?.let {
            return createRestResourceCall(it.template, randomness, maxTestSize, false, false)
        }
        return createRestResourceCall(HttpVerb.POST.toString(), randomness,maxTestSize)
    }


    fun sampleOneAction(verb : HttpVerb? = null, randomness: Randomness) : RestResourceCalls{
        val al = if(verb != null) getActionByHttpVerb(actions, verb) else randomness.choose(actions).copy() as RestCallAction
        return sampleOneAction(al!!, randomness)
    }

    fun sampleOneAction(action : RestCallAction, randomness: Randomness) : RestResourceCalls{
        val copy = action.copy()
        randomizeActionGenes(copy as RestCallAction, randomness)

        val template = templates[copy.verb.toString()]
                ?: throw IllegalArgumentException("${copy.verb} is not one of templates of ${this.path}")
        val call =  RestResourceCalls(template, RestResourceInstance(this, copy.parameters), mutableListOf(copy))

        if(action.verb == HttpVerb.POST){
            getCreation { c : CreationChain -> (c is PostCreationChain) }.let {
                if(it != null && (it as PostCreationChain).actions.size == 1 && it.isComplete()){
                    call.status = ResourceStatus.CREATED_REST
                }else{
                    call.status = ResourceStatus.NOT_FOUND_DEPENDENT
                }
            }
        }else
            call.status = ResourceStatus.NOT_EXISTING

        return call
    }

    fun sampleAnyRestResourceCalls(randomness: Randomness, maxTestSize: Int, prioriIndependent : Boolean = false, prioriDependent : Boolean = false) : RestResourceCalls{
        if (maxTestSize < 1 && prioriDependent == prioriIndependent && prioriDependent){
            throw IllegalArgumentException("unaccepted args")
        }
        val fchosen = templates.filter { it.value.size <= maxTestSize }
        if(fchosen.isEmpty())
            return sampleOneAction(null,randomness)
        val chosen =
            if (prioriDependent)  fchosen.filter { !it.value.independent }
            else if (prioriIndependent) fchosen.filter { it.value.independent }
            else fchosen
        if (chosen.isEmpty())
            return createRestResourceCall(randomness.choose(fchosen).template,randomness, maxTestSize)
        return createRestResourceCall(randomness.choose(chosen).template,randomness, maxTestSize)
    }


    fun sampleRestResourceCalls(template: String, randomness: Randomness, maxTestSize: Int) : RestResourceCalls{
        assert(maxTestSize > 0)
        return createRestResourceCall(template,randomness, maxTestSize)
    }

    fun genPostChain(randomness: Randomness, maxTestSize: Int) : RestResourceCalls?{
        val template = templates["POST"]?:
            return null

        return createRestResourceCall(template.template, randomness, maxTestSize)
    }


    private fun handleHeadLocation(actions: List<RestCallAction>){
        if (actions.size == 1) return
        (1 until actions.size).reversed().forEach { i->
            handleHeaderLocation(actions[i-1], actions[i])
        }
    }

    private fun handleHeaderLocation(post: RestCallAction, target: RestCallAction){
        /*
            Once the POST is fully initialized, need to fix
            links with target
         */
        if (!post.path.isEquivalent(target.path)) {
            /*
                eg
                POST /x
                GET  /x/{id}
             */
            post.saveLocation = true
            target.locationId = post.path.lastElement()
        } else {
            /*
                eg
                POST /x
                POST /x/{id}/y
                GET  /x/{id}/y
             */
            //not going to save the position of last POST, as same as target
            post.saveLocation = false

            // the target (eg GET) needs to use the location of first POST, or more correctly
            // the same location used for the last POST (in case there is a deeper chain)
            target.locationId = post.locationId
        }
    }


    fun createRestResourceCallBasedOnTemplate(template: String, randomness: Randomness, maxTestSize: Int): RestResourceCalls{
        if(!templates.containsKey(template))
            throw IllegalArgumentException("$template does not exist in $path")
        val ats = RestResourceTemplateHandler.parseTemplate(template)
        // POST-*, *
        val results = mutableListOf<RestCallAction>()
        val first = ats.first()
        if (first == HttpVerb.POST){
            val post = getPostChain()
            Lazy.assert { post != null }
            results.addAll(post!!.createPostChain(randomness))
        }else{
            results.add(createActionByVerb(first, randomness))
        }

        if (ats.size == 2){
            results.add(createActionByVerb(ats[1], randomness))
        }else if (ats.size > 2){
            throw IllegalStateException("the size of action with $template should be less than 2, but it is ${ats.size}")
        }

        // handle header location
        handleHeadLocation(results)

        //append extra patch
        if (ats.last() == HttpVerb.PATCH && results.size +1 <= maxTestSize && randomness.nextBoolean(PROB_EXTRA_PATCH)){
            results.add(results.last().copy() as RestCallAction)
        }


        if (results.size > maxTestSize)
            throw IllegalStateException("the size (${results.size}) of actions exceeds the max size ($maxTestSize)")

        // TODO add resource status
        return RestResourceCalls(templates[template]!!, RestResourceInstance(this, results.flatMap { it.parameters }), results)
    }

    private fun createActionByVerb(verb : HttpVerb, randomness: Randomness) : RestCallAction{
        val action = (getActionByHttpVerb(actions, verb)?:throw IllegalStateException("cannot get $verb action in the resource $path")).copy() as RestCallAction
        action.randomize(randomness, false)
        return action
    }

    /**
     * create a RestResourceCall based on the [template]
     */
    fun createRestResourceCall(
            template : String,
            randomness: Randomness,
            maxTestSize : Int = 1,
            checkSize : Boolean = true,
            createResource : Boolean = true,
            additionalPatch : Boolean = true) : RestResourceCalls{
        if(!templates.containsKey(template))
            throw IllegalArgumentException("$template does not exist in $path")
        val ats = RestResourceTemplateHandler.parseTemplate(template)
        val result : MutableList<RestCallAction> = mutableListOf()
        var resource : RestResourceInstance? = null

        val skipBind : MutableList<RestCallAction> = mutableListOf()

        var isCreated = 1
        var creation : CreationChain? = null
        if(createResource && ats[0] == HttpVerb.POST){
            val nonPostIndex = ats.indexOfFirst { it != HttpVerb.POST }
            val ac = getActionByHttpVerb(actions, if(nonPostIndex==-1) HttpVerb.POST else ats[nonPostIndex])!!.copy() as RestCallAction
            randomizeActionGenes(ac, randomness)
            result.add(ac)
            isCreated = createResourcesFor(ac, result, maxTestSize , randomness, checkSize && (!templates.getValue(template).sizeAssured))

            if(!templates.getValue(template).sizeAssured){
                getPostChain()?:throw IllegalStateException("fail to init post creation")
                val pair = checkDifferenceOrInit(postactions = (if(ac.verb == HttpVerb.POST) result else result.subList(0, result.size - 1)).map { (it as RestCallAction).copy() as RestCallAction}.toMutableList())
                if (!pair.first) {
                    log.warn("the post action are not matched with initialized post creation.")
                }
                else {
                    creation = pair.second
                    updateTemplateSize()
                }

            }

            val lastPost = result.last()
            resource = RestResourceInstance(this, lastPost.parameters)
            skipBind.addAll(result)
            if(nonPostIndex == -1){
                (1 until ats.size).forEach{
                    result.add(lastPost.copy().also {
                        skipBind.add(it as RestCallAction)
                    } as RestCallAction)
                }
            }else{
                if(nonPostIndex != ats.size -1){
                    (nonPostIndex + 1 until ats.size).forEach {
                        val action = getActionByHttpVerb(actions, ats[it])!!.copy() as RestCallAction
                        randomizeActionGenes(action, randomness)
                        result.add(action)
                    }
                }
            }

        }else{
            ats.forEach {at->
                val ac = (getActionByHttpVerb(actions, at)?:throw IllegalArgumentException("cannot find $at verb in ${actions.map {a->a.getName() }.joinToString(",")}")).copy() as RestCallAction
                randomizeActionGenes(ac, randomness)
                result.add(ac)
            }

            if(resource == null)
                resource = RestResourceInstance(this, chooseLongestPath(result, randomness).also {
                    skipBind.add(it)
                }.parameters)

            if(checkSize){
                templates.getValue(template).sizeAssured = (result.size  == templates.getValue(template).size)
            }
        }

        if(result.size > 1)
            result.filterNot { ac -> skipBind.contains(ac) }.forEach { ac ->
                if(ac.parameters.isNotEmpty()){
                    ac.bindBasedOn(ac.path, resource.params)
                }
            }

        assert(result.isNotEmpty())

        if(additionalPatch && randomness.nextBoolean(PROB_EXTRA_PATCH) &&!templates.getValue(template).independent && template.contains(HttpVerb.PATCH.toString()) && result.size + 1 <= maxTestSize){
            val index = result.indexOfFirst { it.verb == HttpVerb.PATCH }
            val copy = result.get(index).copy() as RestCallAction
            result.add(index, copy)
        }
        val calls = RestResourceCalls(templates[template]!!, resource, result)

        when(isCreated){
            1 ->{
                calls.status = ResourceStatus.NOT_EXISTING
            }
            0 ->{
                calls.status = ResourceStatus.CREATED_REST
            }
            -1 -> {
                calls.status = ResourceStatus.NOT_ENOUGH_LENGTH
            }
            -2 -> {
                calls.status = ResourceStatus.NOT_FOUND
            }
            -3 -> {
                calls.status = ResourceStatus.NOT_FOUND_DEPENDENT
            }
        }

        return calls
    }

    private fun templateSelected(callsTemplate: CallsTemplate){
        templates.getValue(callsTemplate.template).times += 1
    }
    
    private fun selectTemplate(predicate: (CallsTemplate) -> Boolean, randomness: Randomness, chosen : Map<String, CallsTemplate>?=null, chooseLessVisit : Boolean = false) : CallsTemplate?{
        val ts = if(chosen == null) templates.filter { predicate(it.value) } else chosen.filter { predicate(it.value) }
        if(ts.isEmpty())
            return null
        val template =  if(chooseLessVisit) ts.asSequence().sortedBy { it.value.times }.first().value
                    else randomness.choose(ts.values)
        templateSelected(template)
        return template
    }


    private fun getActionByHttpVerb(actions : List<RestCallAction>, verb : HttpVerb) : RestCallAction? {
        return actions.find { a -> a.verb == verb }
    }

    private fun chooseLongestPath(actions: List<RestCallAction>, randomness: Randomness? = null): RestCallAction {

        if (actions.isEmpty()) {
            throw IllegalArgumentException("Cannot choose from an empty collection")
        }

        val candidates = ParamUtil.selectLongestPathAction(actions)

        if(randomness == null){
            return candidates.first()
        }else
            return randomness.choose(candidates).copy() as RestCallAction
    }

    private fun chooseClosestAncestor(target: RestCallAction, verbs: List<HttpVerb>, randomness: Randomness): RestCallAction? {
        var others = sameOrAncestorEndpoints(target)
        others = hasWithVerbs(others, verbs).filter { t -> t.getName() != target.getName() }
        if(others.isEmpty()) return null
        return chooseLongestPath(others, randomness)
    }

    private fun chooseClosestAncestor(path: RestPath, verbs: List<HttpVerb>): RestCallAction? {
        val ar = if(path.toString() == this.path.toString()){
            this
        }else{
            ancestors.find { it.path.toString() == path.toString() }
        }
        ar?.let{
            val others = hasWithVerbs(it.ancestors.flatMap { it.actions }.filterIsInstance<RestCallAction>(), verbs)
            if(others.isEmpty()) return null
            return chooseLongestPath(others)
        }
        return null
    }

    fun hasWithVerbs(actions: List<RestCallAction>, verbs: List<HttpVerb>): List<RestCallAction> {
        return actions.filter { a ->
            verbs.contains(a.verb)
        }
    }

    fun sameOrAncestorEndpoints(target: RestCallAction): List<RestCallAction> {
        if(target.path.toString() == this.path.toString()) return ancestors.flatMap { a -> a.actions }.plus(actions).filterIsInstance<RestCallAction>()
        else {
            ancestors.find { it.path.toString() == target.path.toString() }?.let {
                return it.ancestors.flatMap { a -> a.actions }.plus(it.actions).filterIsInstance<RestCallAction>()
            }
        }
        return mutableListOf()
    }


    private fun createActionFor(template: RestCallAction, target: RestCallAction, randomness: Randomness): RestCallAction {
        val restAction = template.copy() as RestCallAction
        randomizeActionGenes(restAction, randomness)
        restAction.auth = target.auth
        restAction.bindBasedOn(restAction.path, target.parameters)
        return restAction
    }


    private fun createResourcesFor(target: RestCallAction, test: MutableList<RestCallAction>, maxTestSize: Int, randomness: Randomness, forCheckSize : Boolean)
            : Int {

        if (!forCheckSize && test.size >= maxTestSize) {
            return -1
        }

        val template = chooseClosestAncestor(target, listOf(HttpVerb.POST), randomness)?:
                    return (if(target.verb == HttpVerb.POST) 0 else -2)

        val post = createActionFor(template, target, randomness)

        test.add(0, post)

        /*
            Check if POST depends itself on the postCreation of
            some intermediate resource
         */
        if (post.path.hasVariablePathParameters() &&
                (!post.path.isLastElementAParameter()) ||
                post.path.getVariableNames().size >= 2) {
            val dependencyCreated = createResourcesFor(post, test, maxTestSize, randomness, forCheckSize)
            if (0 != dependencyCreated) {
                return -3
            }
        }

        /*
            Once the POST is fully initialized, need to fix
            links with target
         */
        if (!post.path.isEquivalent(target.path)) {
            /*
                eg
                POST /x
                GET  /x/{id}
             */
            post.saveLocation = true
            target.locationId = post.path.lastElement()
        } else {
            /*
                eg
                POST /x
                POST /x/{id}/y
                GET  /x/{id}/y
             */
            //not going to save the position of last POST, as same as target
            post.saveLocation = false

            // the target (eg GET) needs to use the location of first POST, or more correctly
            // the same location used for the last POST (in case there is a deeper chain)
            target.locationId = post.locationId
        }

        return 0
    }

    /********************** utility *************************/

    fun updateActionsWithAdditionalParams(action: RestCallAction){
        val org = actions.find { it is RestCallAction && it.verb == action.verb }
        org?:throw IllegalStateException("cannot find the action (${action.getName()}) in the node $path")
        if (action.parameters.size > (org as RestCallAction).parameters.size){
            originalActions.add(org)
            actions.remove(org)
            actions.add(action)
        }
    }

    fun isPartOfStaticTokens(text : String) : Boolean{
        return tokens.any { token ->
            token.equals(text)
        }
    }

    fun getDerivedTables() : Set<String> = resourceToTable.derivedMap.flatMap { it.value.map { m->m.targetMatched } }.toHashSet()

    fun isAnyAction() : Boolean{
        verbs.forEach {
            if (it) return true
        }
        return false
    }

    fun getName() : String = path.toString()

    fun getTokenMap() : Map<String, PathRToken> = tokens.toMap()

    fun getFlatViewOfTokens(excludeStar : Boolean = true) : List<PathRToken>
            =  tokens.values.filter { !excludeStar || !it.isStar()}.flatMap { p -> if(p.subTokens.isNotEmpty()) p.subTokens else mutableListOf(p) }.toList()


    /******************** manage param *************************/

    fun getParamId(params: List<Param>, param : Param) : String = "${param::class.java.simpleName}:${getParamName(params, param)}"

    private fun getParamName(params: List<Param>, param : Param) : String = ParamUtil.appendParam(getSegment(false, params, param), param.name)

    /*
    e.g., /A/{a}/B/c/{b} return B@c
     */
    private fun getLastSegment() : String = if(tokens.isNotEmpty()) tokens.values.last().segment else ""

    private fun getLastSegment(flatten : Boolean) : String {
        if(tokens.isEmpty()) return ""
        return getSegment(flatten, tokens.values.last())
    }

    private fun getSegment(flatten : Boolean, level: Int) : String{
        if(tokens.isEmpty()) return ""
        val target = tokens.values.find { it.level == level }?:tokens.values.last()
        return getSegment(flatten, target)
    }


    private fun getSegment(flatten : Boolean, target: PathRToken) : String{
        if (!flatten) return target.segment
        val nearLevel = target.nearestParamLevel
        val array = tokens.values.filter { it.level > nearLevel && (if(target.isParameter) it.level < target.level else it.level <= target.level)}
                .flatMap { if(it.subTokens.isNotEmpty()) it.subTokens.map { s->s.getKey() } else mutableListOf(it.getKey()) }.toTypedArray()
        return ParamUtil.generateParamId(array)
    }

    private fun getParamLevel(params: List<Param>, param: Param) : Int{
        if (param !is PathParam) return tokens.size
        tokens.values.filter { it.isParameter && it.originalText.equals(param.name, ignoreCase = true) }.let {
            if(it.isEmpty()){
                //log.warn(("cannot find the path param ${param.name} in the path of the resource ${getName()}"))
                if(params.any { p-> param.name.equals(p.name, ignoreCase = true) }) return tokens.size
            }
            if(it.size == 1)
                return it.first().level
            val index = params.filter { p->p.name == param.name }.indexOf(param)
            return it[index].level
        }
    }

    private fun getSegment(flatten: Boolean, params: List<Param>, param: Param) : String{
        val level = getParamLevel(params, param)
        return getSegment(flatten, level)
    }


    fun getAllSegments(flatten: Boolean) : List<String>{
        assert(segments.size == 2)
        return if(flatten) segments[1] else segments[0]
    }

    private fun initSegments(){
        val levels = mutableSetOf<Int>()
        tokens.values.filter { it.isParameter }.forEach { levels.add(it.level) }
        if (!path.isLastElementAParameter()) levels.add(tokens.size)
        segments.add(0, levels.toList().sorted().map { getSegment(false, it) })
        segments.add(1, levels.toList().sorted().map { getSegment(true, it) })
        assert(segments.size == 2)
    }

    fun getRefTypes() : Set<String>{
        return paramsInfo.filter {  it.value.referParam is BodyParam && it.value.referParam.gene is ObjectGene && (it.value.referParam.gene as ObjectGene).refType != null}.map {
            ((it.value.referParam as BodyParam).gene as ObjectGene).refType!!
        }.toSet()
    }


    fun anyParameterChanged(action : RestCallAction) : Boolean{
        val target = actions.find { it.getName() == action.getName() }
                ?: throw IllegalArgumentException("cannot find the action ${action.getName()} in the resource ${getName()}")
        return action.parameters.size != (target as RestCallAction).parameters.size
    }

    fun updateAdditionalParams(action: RestCallAction) : Map<String, ParamInfo>?{
        (actions.find { it is RestCallAction && it.getName() == action.getName() }
                ?: throw IllegalArgumentException("cannot find the action ${action.getName()} in the resource ${getName()}")) as RestCallAction

        val additionParams = action.parameters.filter { p-> paramsInfo[getParamId(action.parameters, p)] == null}
        if(additionParams.isEmpty()) return null
        return additionParams.map { p-> Pair(getParamId(action.parameters, p), initParamInfo(action.verb, action.parameters, p)) }.toMap()
    }

    fun updateAdditionalParam(action: RestCallAction, param: Param) : ParamInfo{
        return initParamInfo(action.verb, action.parameters, param).also { it.fromAdditionInfo = true }
    }

    private fun initParamInfo(){
        paramsInfo.clear()

        /*
         parameter that is required to bind with post action, or row of tables
         1) path parameter in the middle of the path, i.e., /A/{a}/B/{b}, {a} is required to bind
         2) GET, DELETE, PATCH, PUT(-prob), if the parameter refers to "id", it is required to bind, in most case, the parameter is either PathParam or QueryParam
         3) other parameter, it is not necessary to bind, but it helps if it is bound.
                e.g., Request to get a list of data whose value is less than "parameter", if bind with an existing data, the requests make more sentence than a random data
         */
        if (tokens.isEmpty()) return
        actions.forEach { a ->
            a.parameters.forEach{p->
                initParamInfo(a.verb, a.parameters, p)
            }
        }
    }

    private fun initParamInfo(verb: HttpVerb, params: List<Param>, param: Param) : ParamInfo{

        val key = getParamId(params,param)

        val segment = getSegment(flatten = true, params = params,param = param)
        val level = getAllSegments(true).indexOf(segment)
        val doesReferToOther = when(param){
            /*
            if has POST, ignore the last path param, otherwise all path param
             */
            is PathParam->{
                !verbs[RestResourceTemplateHandler.getIndexOfHttpVerb(HttpVerb.POST)] || getParamLevel(params, param) < tokens.size - 1
            }else->{
                false
            }
        }

        val paramInfo = paramsInfo.getOrPut(key){
            ParamInfo(param.name, key, segment, level, param, doesReferToOther)
        }

        paramInfo.involvedAction.add(verb)
        return paramInfo
    }

    /**
     * @return params in a [RestResourceCalls] that are not bounded with POST actions if there exist based on the template [actionTemplate]
     *
     */
    fun getPossiblyBoundParams(actionTemplate: String, withSql : Boolean) : List<ParamInfo>{
        val actions = RestResourceTemplateHandler.parseTemplate(actionTemplate)
        Lazy.assert {
            actions.isNotEmpty()
        }

        when(actions[0]){
            HttpVerb.POST->{
                if (withSql) return paramsInfo.values.toList()
                return paramsInfo.values.filter { it.doesReferToOther }
            }
            HttpVerb.PATCH, HttpVerb.PUT->{
                return paramsInfo.values.filter { it.involvedAction.contains(actions[0]) && (it.referParam is PathParam || it.name.toLowerCase().contains("id"))}
            }
            HttpVerb.GET, HttpVerb.DELETE->{
                return paramsInfo.values.filter { it.involvedAction.contains(actions[0]) }
            }
            else ->{
                return listOf()
            }
        }
    }

    fun getTemplate(key: String) : CallsTemplate{
        if (templates.containsKey(key)) return templates.getValue(key)
        throw IllegalArgumentException("cannot find $key template in the node $path")
    }

    fun getTemplates() : Map<String, CallsTemplate> = templates.toMap()

    fun confirmFailureCreationByPost(calls: RestResourceCalls, action: RestCallAction, result: ActionResult){
        if (result !is RestCallResult) return

        val fail = action.verb.run { this == HttpVerb.POST || this == HttpVerb.PUT} &&
                calls.status == ResourceStatus.CREATED_REST && result.getStatusCode().run { this != 201 || this != 200 }

        if (fail && creations.isNotEmpty()){
            creations.filter { it is PostCreationChain && calls.seeActions(ActionFilter.NO_SQL).map { a->a.getName() }.containsAll(it.actions.map { a-> a.getName() }) }.apply {
                if (size == 1)
                    (first() as PostCreationChain).confirmFailure()
            }
        }
    }
}


enum class InitMode{
    NONE,
    WITH_TOKEN,
    /**
     * [WITH_DERIVED_DEPENDENCY] subsume [WITH_TOKEN]
     */
    WITH_DERIVED_DEPENDENCY,
    WITH_DEPENDENCY
}

/**
 * extract info for a parm
 *
 * @property name a name of param
 * @property key is generated based on [getParamId]
 * @property preSegment refers to the segment of the param in the path
 * @property segmentLevel refers to the level of param
 * @property referParam refers to the instance of Param in the cluster
 * @property doesReferToOther indicates whether the param is required to refer to a resource,
 *              e.g., GET /foo/{id}, with GET, {id} refers to a resource which cannot be created by the current action
 * @property involvedAction indicates the actions which exists such param,
 *              e.g., GET, PATCH might have the same param named id
 * @property fromAdditionInfo indicates whether the param is added later,
 *              e.g., during the search
 */
data class ParamInfo(
    val name : String,
    val key : String,
    val preSegment : String, //by default is flatten segment
    val segmentLevel : Int,
    val referParam : Param,
    val doesReferToOther : Boolean,
    val involvedAction : MutableSet<HttpVerb> = mutableSetOf(),
    var fromAdditionInfo : Boolean = false
)