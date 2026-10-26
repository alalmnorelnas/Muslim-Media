package com.MuslimMedia.ans.mahjoob;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MahjoobRequestPolicyTest {
	@Test
	public void detectsImageRequestsByAcceptHeaderOrExtension() {
		Map<String, String> headers = new HashMap<>();
		headers.put("Accept", "text/html, image/avif, image/webp");
		assertTrue(MahjoobRequestPolicy.isCandidateImageRequest("GET", headers, "https://example.test/item"));
		assertTrue(MahjoobRequestPolicy.isCandidateImageRequest("GET", Collections.emptyMap(),
				"https://example.test/photo.JPEG?size=large"));
		assertFalse(MahjoobRequestPolicy.isCandidateImageRequest("POST", headers, "https://example.test/photo.jpg"));
		assertFalse(MahjoobRequestPolicy.isCandidateImageRequest("GET", Collections.emptyMap(),
				"https://example.test/page"));
	}

	@Test
	public void separatesDecisionCacheEntriesByMode() {
		assertEquals("https://example.test/photo.jpg|women",
				MahjoobRequestPolicy.cacheKey("https://example.test/photo.jpg", "women"));
		assertFalse(MahjoobRequestPolicy.cacheKey("https://example.test/photo.jpg", "women")
				.equals(MahjoobRequestPolicy.cacheKey("https://example.test/photo.jpg", "men")));
	}

	@Test
	public void defaultsUnknownModesToEveryone() {
		assertEquals("off", MahjoobRequestPolicy.normalizeMode("off"));
		assertEquals("women", MahjoobRequestPolicy.normalizeMode("women"));
		assertEquals("men", MahjoobRequestPolicy.normalizeMode("men"));
		assertEquals("everyone", MahjoobRequestPolicy.normalizeMode("everyone"));
		assertEquals("everyone", MahjoobRequestPolicy.normalizeMode("unknown"));
		assertEquals("everyone", MahjoobRequestPolicy.normalizeMode(null));
	}

	@Test
	public void enforcesInclusiveSizeLimit() {
		assertTrue(MahjoobRequestPolicy.isWithinSizeLimit(0, 10));
		assertTrue(MahjoobRequestPolicy.isWithinSizeLimit(10, 10));
		assertFalse(MahjoobRequestPolicy.isWithinSizeLimit(11, 10));
		assertFalse(MahjoobRequestPolicy.isWithinSizeLimit(-1, 10));
	}

	@Test
	public void enforcesVideoFrameRateLimit() {
		assertTrue(MahjoobRequestPolicy.hasElapsed(-1, 1000, 450));
		assertFalse(MahjoobRequestPolicy.hasElapsed(1000, 1449, 450));
		assertTrue(MahjoobRequestPolicy.hasElapsed(1000, 1450, 450));
		assertFalse(MahjoobRequestPolicy.hasElapsed(1000, 999, 450));
	}
}