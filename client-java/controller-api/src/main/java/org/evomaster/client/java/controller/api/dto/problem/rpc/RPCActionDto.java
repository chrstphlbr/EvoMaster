package org.evomaster.client.java.controller.api.dto.problem.rpc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * a dto to collect info of endpoints to be tested
 * that is used by both core (for identifying action) and driver (for endpoint invocation) sides
 */
public class RPCActionDto {

    private static final Logger logger = LoggerFactory.getLogger("test_cases");

    /**
     * name of the RPC interface
     */
    public String interfaceId;

    /**
     * name of the client
     */
    public String clientInfo;

    /**
     * a variable referring to client instance
     *
     * this info would be used in static init declaration referring
     * to an actual client instance in the generated test
     * then later, the variable could be used to process rpc function call
     *
     * eg, the variable is foo
     * public class Test{
     *     private static Client foo;
     *     ...
     *     pubic void test(){
     *         response = foo.bar(request)
     *     }
     * }
     *
     * TODO
     * Note that the current test generation is proceeded in the driver
     * if we move it to core, this info could be removed
     */
    public String clientVariable;

    /**
     * name of the action
     */
    public String actionName;

    /**
     * request params
     */
    public List<ParamDto> requestParams;

    /**
     * response param (nullable)
     */
    public ParamDto responseParam;

    /**
     * if the action requires auth to access
     */
    public boolean isAuthorized;

    /**
     * possible candidates for auth to access the endpoint
     */
    public List<Integer> requiredAuthCandidates;

    /**
     * related candidates to customize values in request of this endpoint
     */
    public Set<String> relatedCustomization;

    /**
     * an action to setup auth in this invocation
     */
    public RPCActionDto authSetup;


    // test generation configuration, might be removed later
    /**
     * variable name of response
     */
    public String responseVariable;

    /**
     * variable name of controller
     */
    public String controllerVariable;

    /**
     * if generate assertions on driver side and send back core
     */
    public boolean doGenerateAssertions;

    /**
     * if generate test script on driver side and send back core
     */
    public boolean doGenerateTestScript;

    /**
     * the maximum number of assertions to be generated for data in collections
     * zero or negative number means that assertions would be generated for all data in collection
     */
    public int maxAssertionForDataInCollection;

    /**
     *
     * @return a copy of RPCActionDto for enabling its invocation
     * eg, exclude all possible candidates of param values and auth
     */
    public RPCActionDto copy(){
        RPCActionDto copy = new RPCActionDto();
        copy.interfaceId = interfaceId;
        copy.clientInfo = clientInfo;
        copy.clientVariable = clientVariable;
        copy.actionName = actionName;
        copy.responseParam = responseParam;
        if (requestParams != null)
            copy.requestParams = requestParams.stream().map(ParamDto::copy).collect(Collectors.toList());
        copy.responseVariable = responseVariable;
        copy.controllerVariable = controllerVariable;
        copy.doGenerateAssertions = doGenerateAssertions;
        copy.doGenerateTestScript = doGenerateTestScript;
        copy.maxAssertionForDataInCollection = maxAssertionForDataInCollection;
        copy.isAuthorized = isAuthorized;
        return copy;
    }

    /**
     *
     * @return a complete copy of RPCActionDto including its schema info,
     * eg, possible auth candidates and pe-defined values in requests
     */
    public RPCActionDto copyComplete(){
        RPCActionDto copy = copy();
        if (copy.requiredAuthCandidates != null)
            copy.requiredAuthCandidates = new ArrayList<>(requiredAuthCandidates);
        if (copy.relatedCustomization != null)
            copy.relatedCustomization = new HashSet<>(relatedCustomization);
        return copy;
    }

    public String getClientName(RPCActionDto dto) {
        String clientName = dto.clientInfo;
        if (dto != null){
            logger.info("Extracted client name: {}", clientName);
        }else {
            logger.info("NO extracted client name");
        }
            return clientName;
    }

}
