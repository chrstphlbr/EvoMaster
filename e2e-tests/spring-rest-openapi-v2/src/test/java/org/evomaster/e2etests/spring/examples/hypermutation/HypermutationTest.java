package org.evomaster.e2etests.spring.examples.hypermutation;

import com.foo.rest.examples.spring.hypermutation.HighWeightRestController;
import org.evomaster.core.problem.rest.RestIndividual;
import org.evomaster.core.search.Solution;
import org.evomaster.e2etests.spring.examples.SpringTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;


public class HypermutationTest extends HypermutationTestBase {

    @BeforeAll
    public static void initClass() throws Exception {

        SpringTestBase.initClass(new HighWeightRestController(Arrays.asList("/api/highweight/lowWeightHighCoverage/{x}")));
    }

    @Test
    public void testRunHypermutation() throws Throwable {

        runTestHandlingFlakyAndCompilation(
                "hypermtation/TestHyperweight",
                "org.adaptivehypermuation.HyperWeightTest",
                1000,
                true,
                (args) -> {

                    args.add("--weightBasedMutationRate");
                    args.add("true");

                    args.add("--probOfArchiveMutation");
                    args.add("0.0");
                    args.add("--adaptiveGeneSelectionMethod");
                    args.add("NONE");
                    args.add("--archiveGeneMutation");
                    args.add("NONE");
                    args.add("--enableTrackEvaluatedIndividual");
                    args.add("true");

                    args.add("--doCollectImpact");
                    args.add("true");

                    args.add("--focusedSearchActivationTime");
                    args.add("0.1");

                    Solution<RestIndividual> solution = initAndRun(args);


                    boolean ok = solution.getIndividuals().stream().allMatch(s-> check(s, "differentWeight",0));
                    assertTrue(ok);

                }, 2);
    }


}
