package com.bencodez.advancedcore.tests.rewards;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.core.reward.*;

class SharedRewardJava8CompatibilityTest {
    @Test void fingerprintRetainsPinnedLengthDelimitedSha256Encoding() {
        SharedRewardStep step=new SharedRewardStep("message",false,(context,path)->CompletableFuture.completedFuture(SharedRewardResult.COMPLETED));
        SharedRewardPlan plan=SharedRewardPlan.immediate("vote",Collections.singletonList(step)).withDefinitionFingerprint("config-v1");
        assertEquals("cbdbb53b1c7ed0fc02c3bbda8deba7acae19e04f3cdaf706a4a5cb9bb1c5fb2a",plan.fingerprint());
    }
    @Test void preparedListsAreCopiedImmutableAndRejectNullElements() {
        ArrayList<SharedRewardStep> steps=new ArrayList<>();
        SharedRewardStep step=new SharedRewardStep("message",false,(context,path)->CompletableFuture.completedFuture(SharedRewardResult.COMPLETED));
        steps.add(step);SharedRewardPlan plan=SharedRewardPlan.immediate("vote",steps);steps.clear();
        assertEquals(Collections.singletonList(step),plan.steps());assertThrows(UnsupportedOperationException.class,()->plan.steps().clear());
        assertThrows(NullPointerException.class,()->SharedRewardPlan.immediate("vote",Arrays.asList(step,null)));
    }
    @Test void progressCopiesNullablePlaceholdersAndRetainsDeadlineOnAdvance() {
        HashMap<String,String> placeholders=new HashMap<>();placeholders.put("optional",null);
        Instant deadline=Instant.parse("2026-01-01T00:00:00Z");
        SharedRewardProgress saved=new SharedRewardProgress("fingerprint",true,0,placeholders,deadline);placeholders.put("optional","changed");
        assertNull(saved.placeholders().get("optional"));assertTrue(saved.placeholders().containsKey("optional"));
        assertThrows(UnsupportedOperationException.class,()->saved.placeholders().put("other","value"));
        SharedRewardContext context=new SharedRewardContext(UUID.randomUUID(),"Ben",saved.placeholders());
        SharedRewardProgress advanced=saved.advance(1,context);assertEquals(deadline,advanced.notBefore());
        assertThrows(IllegalArgumentException.class,()->advanced.advance(0,context));
        assertEquals(saved,new SharedRewardProgress("fingerprint",true,0,saved.placeholders(),deadline));
        assertEquals(saved.hashCode(),new SharedRewardProgress("fingerprint",true,0,saved.placeholders(),deadline).hashCode());
    }
    @Test void unicodeBlankValidationMatchesModernContract() {
        assertThrows(IllegalArgumentException.class,()->SharedRewardPlan.immediate("\u2003",Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,()->new SharedRewardStep("\u2003",false,(context,path)->CompletableFuture.completedFuture(SharedRewardResult.COMPLETED)));
        assertThrows(IllegalArgumentException.class,()->new SharedRewardProgress("\u2003",true,0,Collections.emptyMap()));
        assertEquals("\u00a0",SharedRewardPlan.immediate("\u00a0",Collections.emptyList()).id());
    }
}
