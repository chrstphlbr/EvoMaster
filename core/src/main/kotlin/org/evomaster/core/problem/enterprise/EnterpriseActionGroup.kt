package org.evomaster.core.problem.enterprise

import org.evomaster.core.problem.external.service.ExternalServiceAction
import org.evomaster.core.search.*


/**
 * This is used to represent a single MAIN action (eg call to API), which is linked to a group
 * of dependent actions (eg setting up wiremock).
 * The dependent actions are related ONLY to the MAIN action.
 * Deleting the MAIN action would imply it is 100% safe to remove the dependent ones.
 */
class EnterpriseActionGroup(
    children: MutableList<out Action>,
    private val mainClass : Class<*>,
    groups: GroupsOfChildren<out Action> = GroupsOfChildren(
        children,
        listOf(
            ChildGroup(GroupsOfChildren.EXTERNAL_SERVICES, { e -> e is ExternalServiceAction }),
            ChildGroup(GroupsOfChildren.MAIN, { k -> mainClass.isAssignableFrom(k.javaClass) }, 0, 0, 1)
        )
    )
) : ActionDependentGroup(
    children,
    groups
) {

    override fun copyContent(): EnterpriseActionGroup {

        val k = children.map { it.copy() } as MutableList<out Action>

        return EnterpriseActionGroup(
            k,
            mainClass,
            groupsView()!!.copy(k) as GroupsOfChildren<Action>
        )
    }
}