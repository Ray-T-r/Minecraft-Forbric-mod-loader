package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class KernelCollectionSourcesTest {
	@Test void anEqualButDifferentElementStillFailsIdentityAfterTheCallback() {
		String original=new String("same"), different=new String("same");AtomicInteger calls=new AtomicInteger();
		IllegalStateException failed=assertThrows(IllegalStateException.class,()->KernelCollectionSources.replacePrefix(()->Stream.of(different),List.of(original),()->{calls.incrementAndGet();return List.of();},"identity probe"));
		assertTrue(failed.getMessage().contains("identity probe"));assertEquals(1,calls.get());
	}
	@Test void aShortPrefixFailsAndAReplacementExceptionIsNotSwallowed() {
		Object first=new Object(),second=new Object();AtomicInteger calls=new AtomicInteger();
		assertThrows(IllegalStateException.class,()->KernelCollectionSources.replacePrefix(()->Stream.of(first),List.of(first,second),()->{calls.incrementAndGet();return List.of();},"short probe"));assertEquals(1,calls.get());
		RuntimeException expected=new RuntimeException("guest callback failed");
		AtomicInteger factories=new AtomicInteger();
		assertSame(expected,assertThrows(RuntimeException.class,()->KernelCollectionSources.replacePrefix(()->{factories.incrementAndGet();return Stream.of(first);},List.of(first),()->{throw expected;},"callback probe")));assertEquals(0,factories.get());
	}
	@Test void anEmptyOriginalCanIntroduceEntriesWithoutLosingTheNativeSuffix() {
		Object added=new Object(),suffix=new Object();List<Object> result=KernelCollectionSources.replacePrefix(()->Stream.of(suffix),List.of(),()->List.of(added),"empty probe").toList();assertSame(added,result.getFirst());assertSame(suffix,result.getLast());
	}
	@Test void theOriginalIsSnapshottedBeforeTheCallbackAndTheNativeStreamClosesOnce() {
		Object first=new Object(),replacement=new Object(),tail=new Object();List<Object>original=new ArrayList<>(List.of(first));AtomicInteger callbacks=new AtomicInteger(),factories=new AtomicInteger(),closes=new AtomicInteger();
		List<Object>result=KernelCollectionSources.replacePrefix(()->{assertEquals(1,callbacks.get());factories.incrementAndGet();return Stream.of(first,tail).onClose(closes::incrementAndGet);},original,()->{callbacks.incrementAndGet();original.clear();return List.of(replacement);},"snapshot order").toList();
		assertEquals(List.of(replacement,tail),result);assertEquals(1,factories.get());assertEquals(1,callbacks.get());assertEquals(1,closes.get());
	}
}
