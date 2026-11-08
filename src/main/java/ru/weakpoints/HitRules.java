package ru.weakpoints;

/** Numbers of the weak-point game, kept apart so the client and the tests use exactly what the server uses. */
public final class HitRules {
	/** A tree felled bare-handed on its weak points. */
	public static final int HAND_TREE_HITS = 12;
	/** Somewhat wider than the drawn marker, so lag and a shaky hand still count. */
	public static final double TOLERANCE = 0.2;

	private HitRules() {
	}

	/** Hits to take a whole vein or tree by base tool speed: 5 with wood or stone, 4 iron, 3 diamond, 2 netherite and gold. */
	public static int gatherHits(float toolSpeed) {
		if (toolSpeed <= 4) return 5;
		if (toolSpeed <= 6) return 4;
		if (toolSpeed <= 8) return 3;
		return 2;
	}

	/** One blow per swing of the tool, as its vanilla attack speed sets it; clicking a block never resets that timer itself. */
	public static int swingTicks(double attackSpeed) {
		return Math.max(6, (int) Math.round(20 / Math.max(0.1, attackSpeed)));
	}
}
