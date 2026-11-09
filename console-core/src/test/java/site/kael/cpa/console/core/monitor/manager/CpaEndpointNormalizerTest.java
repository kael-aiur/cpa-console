package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;
import site.kael.cpa.console.core.cpa.client.CpaEndpointNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D5.1a normalization self-check: base path preserved, forbidden components rejected. */
class CpaEndpointNormalizerTest {

    @Test
    void preservesBasePathAndDropsTrailingSlash() {
        assertEquals("http://example.test:8317", CpaEndpointNormalizer.normalize("http://example.test:8317").endpoint());
        assertEquals("http://example.test:8317/base", CpaEndpointNormalizer.normalize("http://example.test:8317/base").endpoint());
        assertEquals("http://example.test:8317/base", CpaEndpointNormalizer.normalize("http://example.test:8317/base/").endpoint());
        assertEquals("http://example.test:8317/base", CpaEndpointNormalizer.normalize("http://example.test:8317//base///").endpoint());
        assertEquals("https://example.test", CpaEndpointNormalizer.normalize("https://example.test").endpoint());
        assertEquals("http://example.test:8080", CpaEndpointNormalizer.normalize("HTTP://EXAMPLE.test:8080").endpoint());
    }

    @Test
    void distinguishesDistinctBasePaths() {
        String a = CpaEndpointNormalizer.normalize("http://example.test/cpa-a").endpoint();
        String b = CpaEndpointNormalizer.normalize("http://example.test/cpa-b").endpoint();
        assertTrue(!a.equals(b));
    }

    @Test
    void rejectsUserinfoQueryFragmentAndBadScheme() {
        for (String bad : new String[]{
                "http://user:pass@example.test",
                "http://example.test/?x=1",
                "http://example.test/#frag",
                "ftp://example.test",
                "example.test",
                "",
                "   "}) {
            assertFalse(CpaEndpointNormalizer.normalize(bad).accepted(), bad);
        }
    }
}
