package ru.weakpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HitRulesTest {
	@Test
	void betterToolsNeedFewerBlows() {
		assertEquals(5, HitRules.gatherHits(2));
		assertEquals(5, HitRules.gatherHits(4));
		assertEquals(4, HitRules.gatherHits(6));
		assertEquals(3, HitRules.gatherHits(8));
		assertEquals(2, HitRules.gatherHits(9));
		assertEquals(2, HitRules.gatherHits(12));
		assertEquals(12, HitRules.HAND_TREE_HITS);
	}

	@Test
	void swingFollowsAttackSpeed() {
		assertEquals(17, HitRules.swingTicks(1.2));
		assertEquals(25, HitRules.swingTicks(0.8));
		assertEquals(6, HitRules.swingTicks(4));
	}
}
